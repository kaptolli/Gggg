package com.kaptolli.videograb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class DlState(val running: Boolean = false, val percent: Int = 0, val line: String = "")

/**
 * One download at a time. yt-dlp writes into the app's private dir (no storage permission
 * needed at all), then the finished file is copied into Download/VideoGrab via MediaStore.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        if (i.action == ACTION_CANCEL) {
            YoutubeDL.destroyProcessById(PROCESS_ID)
            if (!state.value.running) {
                state.value = DlState()
                stopSelf()
            }
        }

        val url = i.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val selector = i.getStringExtra(EXTRA_SELECTOR) ?: "b"
        val label = i.getStringExtra(EXTRA_LABEL) ?: "видео"
        val audioOnly = i.getBooleanExtra(EXTRA_AUDIO_ONLY, false)
        val audioFormat = i.getStringExtra(EXTRA_AUDIO_FORMAT)

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(label + " · 0%", "Готовлю yt-dlp…", 0, true, null), SERVICE_TYPE
        )
        state.value = DlState(true, 0, "Готовлю yt-dlp…")

        scope.launch { runDownload(url, selector, label, audioOnly, audioFormat) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun runDownload(
        url: String,
        selector: String,
        label: String,
        audioOnly: Boolean,
        audioFormat: String?
    ) {
        val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val tmpDir = File(base, "tmp").apply { mkdirs() }
        val before = tmpDir.list()?.toHashSet() ?: HashSet<String>()

        val request = YoutubeDLRequest(url)
        request.addOption("--no-mtime")
        request.addOption("--no-playlist")
        request.addOption("--newline")
        request.addOption("--retries", "5")
        request.addOption("-o", tmpDir.absolutePath + "/%(title).120s.%(ext)s")
        request.addOption("-f", selector)
        if (audioOnly) {
            request.addOption("-x")
            request.addOption("--audio-format", audioFormat ?: "mp3")
            request.addOption("--audio-quality", "0")
        } else {
            request.addOption("--merge-output-format", "mp4")
        }

        val callback: (Float, Long, String) -> Unit = { p, _eta, line ->
            val pct = p.toInt().coerceIn(0, 100)
            val text = line.trim().take(140).ifBlank { "качаю…" }
            state.value = DlState(true, pct, text)
            notifyProgress(label + " · " + pct + "%", text, pct)
        }

        try {
            YoutubeDL.execute(request, PROCESS_ID, callback)

            val fresh = tmpDir.listFiles()?.filter {
                it.isFile && !before.contains(it.name) &&
                    !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
            } ?: emptyList()

            if (fresh.isEmpty()) {
                notifyDone("Файл не появился — сайт мог не отдать поток", null)
                return
            }

            var lastUri: Uri? = null
            val names = ArrayList<String>()
            for (f in fresh) {
                names.add(f.name)
                publish(f)?.let { lastUri = it }
                f.delete()
            }
            notifyDone("Скачано: " + names.joinToString(", "), lastUri)
        } catch (t: Throwable) {
            val lines = (t.message ?: t.toString())
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("WARNING") && !it.startsWith("DEPRECATION") }
            val reason = if (lines.isEmpty()) "неизвестная ошибка" else lines.takeLast(3).joinToString(" ")
            notifyDone("Ошибка: " + reason.take(300), null)
        }
    }

    /** копия готового файла в общий Download/VideoGrab, откуда его видят галерея и файлменеджер */
    private fun publish(file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(file.name))
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/VideoGrab")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(collection, values) ?: return null

        val out = try {
            contentResolver.openOutputStream(uri)
        } catch (t: Throwable) {
            null
        }
        if (out == null) {
            contentResolver.delete(uri, null, null)
            return null
        }
        try {
            out.use { o -> file.inputStream().use { input -> input.copyTo(o) } }
        } catch (t: Throwable) {
            contentResolver.delete(uri, null, null)
            return null
        }

        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        contentResolver.update(uri, done, null, null)
        return uri
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus", "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Загрузки", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notifyProgress(title: String, text: String, percent: Int) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(title, text, percent, true, null))
        } catch (t: Throwable) {
            // нет разрешения на уведомления — прогресс всё равно виден в активити
        }
    }

    private fun notifyDone(text: String, uri: Uri?) {
        state.value = DlState(false, 100, text)
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification("VideoGrab", text, 100, false, uri))
        } catch (t: Throwable) {
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun buildNotification(
        title: String,
        text: String,
        percent: Int,
        running: Boolean,
        uri: Uri?
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .setProgress(100, percent, running && percent <= 0)

        if (running) {
            val stop = PendingIntent.getService(
                this, 1,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "Стоп", stop)
        }

        if (uri != null) {
            val open = PendingIntent.getActivity(
                this, 2,
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(open)
            builder.setAutoCancel(true)
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "videograb_downloads"
        const val NOTIF_ID = 1001
        const val PROCESS_ID = "videograb-download"
        const val ACTION_CANCEL = "com.kaptolli.videograb.action.CANCEL"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_SELECTOR = "extra_selector"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_AUDIO_ONLY = "extra_audio_only"
        const val EXTRA_AUDIO_FORMAT = "extra_audio_format"

        private val SERVICE_TYPE =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

        val state = MutableStateFlow(DlState())
    }
}
