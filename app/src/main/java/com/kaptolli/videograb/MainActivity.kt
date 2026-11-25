package com.kaptolli.videograb

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.chip.Chip
import com.kaptolli.videograb.databinding.ActivityMainBinding
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** чем качаем видео: только реальные видео-контейнеры, никаких webp/mhtml/превью-мусора */
private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "3gp", "ts", "avi", "ogv")

/** что показываем из звука */
private val AUDIO_EXT = setOf("m4a", "mp3", "opus", "ogg", "aac", "wav")

/** выше этого не показываем — 1080p потолок, как просил */
private const val MAX_HEIGHT = 1080

private const val SEL_MP4_1080 =
    "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[height<=1080][ext=mp4]/bv*[height<=1080]+ba/b[height<=1080]/b"
private const val SEL_MP4_1080_MUTE =
    "bv*[height<=1080][ext=mp4]/bv*[height<=1080]/b[height<=1080]"
private const val SEL_MP4_720 =
    "bv*[height<=720][ext=mp4]+ba[ext=m4a]/b[height<=720][ext=mp4]/bv*[height<=720]+ba/b[height<=720]/b"
private const val SEL_MP4_480 =
    "bv*[height<=480][ext=mp4]+ba[ext=m4a]/b[height<=480][ext=mp4]/bv*[height<=480]+ba/b[height<=480]/b"
private const val SEL_MP4_360 =
    "bv*[height<=360][ext=mp4]+ba[ext=m4a]/b[height<=360][ext=mp4]/bv*[height<=360]+ba/b[height<=360]/b"

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = FormatAdapter { row -> startDownload(row) }
    private var currentUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.rvFormats.layoutManager = LinearLayoutManager(this)
        b.rvFormats.adapter = adapter

        buildPresets()
        b.btnFetch.setOnClickListener { fetch(b.etUrl.text.toString()) }
        b.btnCancel.setOnClickListener { cancelDownload() }
        b.btnUpdate.setOnClickListener { updateYtdlp() }
        updateVersionLabel()
        askNotificationPermission()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadService.state.collect { s ->
                    b.downloadPanel.visibility =
                        if (s.running || s.line.isNotEmpty()) View.VISIBLE else View.GONE
                    b.pbDownload.progress = s.percent
                    b.tvDownloadStatus.text = s.line
                }
            }
        }

        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    // ---------------------------------------------------------------- поиск

    private fun fetch(raw: String, auto: Boolean = false) {
        val url = firstUrl(raw) ?: raw.trim().takeIf { it.startsWith("http") }
        if (url.isNullOrBlank()) {
            toast("Нужна ссылка http(s)://...")
            return
        }

        currentUrl = url
        b.pbFetch.visibility = View.VISIBLE
        b.btnFetch.isEnabled = false
        b.tvTitle.text = ""
        b.tvMeta.text = ""
        b.ivThumb.setImageDrawable(null)
        adapter.submit(emptyList())

        lifecycleScope.launch {
            try {
                val info = fetchInfo(url)
                render(info)
                if (auto) autoDownload(info)
            } catch (t: Throwable) {
                toast("Не вышло: " + (t.message ?: t.toString()))
            } finally {
                b.pbFetch.visibility = View.GONE
                b.btnFetch.isEnabled = true
            }
        }
    }

    private suspend fun fetchInfo(url: String): VideoInfo {
        val deadline = System.currentTimeMillis() + 90_000
        while (!GrabApp.ready) {
            GrabApp.error?.let { throw IllegalStateException("yt-dlp не поднялся: $it") }
            if (System.currentTimeMillis() > deadline) throw IllegalStateException("таймаут инициализации yt-dlp")
            delay(250)
        }
        return withContext(Dispatchers.IO) { YoutubeDL.getInfo(url) }
    }

    private fun render(info: VideoInfo) {
        b.tvTitle.text = info.title ?: info.fulltitle ?: "(без названия)"
        val dur = if (info.duration > 0)
            String.format(Locale.US, "%d:%02d", info.duration / 60, info.duration % 60) else null
        b.tvMeta.text = listOfNotNull(info.uploader, info.extractor, dur).joinToString(" · ")

        val thumb = info.thumbnail ?: info.thumbnails?.lastOrNull()?.url
        if (thumb != null) b.ivThumb.load(thumb)

        val rows = buildRows(info)
        adapter.submit(rows)
        b.tvFormatsHeader.text = when {
            rows.isNotEmpty() -> "Форматы до 1080p (" + rows.size + ")"
            hasNoVideo(info) -> "Тут нет видео — сайт отдал картинку/пост"
            else -> "Сайт не отдал форматы — жми пресеты вверху"
        }
    }

    /** список форматов: только mp4 (по убыванию качества) до 1080p + пара звуковых дорожек */
    private fun buildRows(info: VideoInfo): List<FormatRow> {
        val rows = ArrayList<FormatRow>()
        val formats = info.formats ?: ArrayList()

        val videos = formats.filter {
            it.vcodec != null && it.vcodec != "none" && it.height > 0 && VIDEO_EXT.contains(extOf(it))
        }

        // mp4 в приоритете; если сайт отдаёт только webm — покажем webm, но с пометкой
        val mp4Only = videos.filter { isMp4(it) }
        val pool = if (mp4Only.isNotEmpty()) mp4Only else videos
        val capped = pool.filter { it.height <= MAX_HEIGHT }
        val chosen = if (capped.isNotEmpty()) capped else pool

        val seen = HashSet<String>()
        for (f in chosen.sortedWith(compareByDescending<VideoFormat> { it.height }.thenByDescending { it.tbr })) {
            val id = f.formatId ?: continue
            if (!seen.add(f.height.toString() + extOf(f))) continue
            rows.add(
                FormatRow(
                    title = resolutionText(f),
                    subtitle = listOfNotNull(
                        if (isMp4(f)) null else extOf(f).uppercase(Locale.US),
                        f.vcodec?.substringBefore('.'),
                        sizeText(f.fileSize, f.fileSizeApproximate)
                    ).joinToString(" · ") + "    со звуком, id:" + id,
                    selector = id + "+ba[ext=m4a]/" + id + "+ba/" + id,
                    audioOnly = false,
                    audioFormat = null
                )
            )
            if (rows.size >= 30) break
        }

        val audios = formats
            .filter {
                (it.vcodec == null || it.vcodec == "none") &&
                    it.acodec != null && it.acodec != "none" &&
                    AUDIO_EXT.contains(extOf(it))
            }
            .sortedByDescending { it.tbr }

        var addedAudio = 0
        for (f in audios) {
            val id = f.formatId ?: continue
            val ext = extOf(f)
            if (!seen.add("audio" + ext)) continue
            rows.add(
                FormatRow(
                    title = "аудио " + ext.uppercase(Locale.US),
                    subtitle = listOfNotNull(
                        f.acodec?.substringBefore('.'),
                        if (f.abr > 0) f.abr.toString() + " kbps" else null,
                        sizeText(f.fileSize, f.fileSizeApproximate)
                    ).joinToString(" · ") + "    id:" + id,
                    selector = id,
                    audioOnly = true,
                    audioFormat = ext
                )
            )
            addedAudio++
            if (addedAudio >= 3) break
        }

        return rows
    }

    /** пришло из «Поделиться» — сразу тянем 1080p mp4, без лишних тапов */
    private fun autoDownload(info: VideoInfo) {
        if (hasNoVideo(info)) {
            toast("Тут нет видео — качать нечего")
            return
        }
        startDownload(FormatRow("1080p MP4 · авто", "поделились ссылкой", SEL_MP4_1080, false, null))
    }

    private fun hasNoVideo(info: VideoInfo): Boolean {
        val f = info.formats ?: return false
        return f.isEmpty() || f.none { it.ext != null && VIDEO_EXT.contains(extOf(it)) }
    }

    private fun extOf(f: VideoFormat): String = f.ext?.lowercase(Locale.US) ?: ""

    private fun isMp4(f: VideoFormat): Boolean {
        val e = extOf(f)
        return e == "mp4" || e == "m4v"
    }

    private fun resolutionText(f: VideoFormat): String {
        val h = if (f.height > 0) f.height.toString() + "p" else (f.formatNote ?: "?")
        val fps = if (f.fps > 30) " " + f.fps + "fps" else ""
        val container = if (isMp4(f)) "" else " " + extOf(f).uppercase(Locale.US)
        return h + fps + container
    }

    private fun sizeText(a: Long, b: Long): String? {
        val v = maxOf(a, b)
        if (v <= 0) return null
        return String.format(Locale.US, "%.1f МБ", v / 1048576.0)
    }

    // ------------------------------------------------------------- пресеты

    private fun buildPresets() {
        val presets = listOf(
            Preset("1080p MP4", SEL_MP4_1080, false, null),
            Preset("1080p без звука", SEL_MP4_1080_MUTE, false, null),
            Preset("720p MP4", SEL_MP4_720, false, null),
            Preset("480p MP4", SEL_MP4_480, false, null),
            Preset("360p MP4", SEL_MP4_360, false, null),
            Preset("MP3", "ba/b", true, "mp3"),
            Preset("M4A", "ba[ext=m4a]/ba", true, "m4a")
        )

        for (p in presets) {
            val chip = Chip(this)
            chip.text = p.label
            chip.isCheckable = false
            chip.setOnClickListener {
                startDownload(FormatRow(p.label, "пресет", p.selector, p.audioOnly, p.audioFormat))
            }
            b.chipRow.addView(chip)
        }
    }

    // ----------------------------------------------------------- загрузка

    private fun startDownload(row: FormatRow) {
        val url = currentUrl
        if (url == null) {
            toast("Сначала вставь ссылку и нажми «Найти»")
            return
        }
        if (DownloadService.state.value.running) {
            toast("Уже качается — останови текущую загрузку")
            return
        }
        val intent = Intent(this, DownloadService::class.java)
            .putExtra(DownloadService.EXTRA_URL, url)
            .putExtra(DownloadService.EXTRA_SELECTOR, row.selector)
            .putExtra(DownloadService.EXTRA_LABEL, row.title)
            .putExtra(DownloadService.EXTRA_AUDIO_ONLY, row.audioOnly)
            .putExtra(DownloadService.EXTRA_AUDIO_FORMAT, row.audioFormat)
        ContextCompat.startForegroundService(this, intent)
        b.downloadPanel.visibility = View.VISIBLE
        toast("Погнали: " + row.title)
    }

    private fun cancelDownload() {
        startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL))
    }

    // --------------------------------------------------------------- утиль

    /** ACTION_SEND / ACTION_VIEW: вытащить ссылку и стартовать загрузку сразу */
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return
        val raw = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: intent.dataString
            ?: return
        val url = firstUrl(raw) ?: return
        // снять интент, чтобы поворот экрана не запускал скачивание второй раз
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_SUBJECT)
        intent.setData(null)
        b.etUrl.setText(url)
        toast("Шарю ссылку — качаю 1080p MP4")
        fetch(url, auto = true)
    }

    private fun firstUrl(text: String): String? {
        val m = Regex("""https?://[^\s"'<>]+""").find(text) ?: return null
        return m.value.trimEnd('.', ',', ')', ';', '!', '?')
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71)
        }
    }

    private fun updateYtdlp() {
        toast("Проверяю yt-dlp...")
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) { GrabApp.updateNow(this@MainActivity) }
            updateVersionLabel()
            toast(msg)
        }
    }

    private fun updateVersionLabel() {
        val v = GrabApp.ytdlpVersion
        b.tvYtdlp.text = if (v.isNullOrBlank()) "yt-dlp: из сборки" else "yt-dlp " + v
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
