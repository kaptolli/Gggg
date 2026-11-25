package com.kaptolli.videograb

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * yt-dlp + python + ffmpeg распаковываются при первом запуске (несколько секунд),
 * остальное в приложении ждёт [ready].
 *
 * yt-dlp в библиотеке — сборка с её релиза, а площадки ломают старые версии за пару месяцев.
 * Поэтому сами тянем свежий бинарь НАПРЯМУЮ из релиза на GitHub (без api.github.com —
 * он лимитит по IP) и подменяем файл в каталоге библиотеки.
 */
class GrabApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread {
            try {
                YoutubeDL.init(this)
                FFmpeg.init(this)
                refreshVersion(this)
                markReady()
                maybeAutoUpdate(this)
            } catch (t: Throwable) {
                markFailed(t.message ?: t.toString())
            }
        }.apply { name = "ytdlp-init" }.start()
    }

    companion object {
        private const val PREFS = "grab"
        private const val KEY_LAST_UPDATE = "last_ytdlp_update"
        private const val KEY_VERSION = "ytdlp_version"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** прямая ссылка на последний релиз — редирект даёт тег версии, никакого API */
        private const val DLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"

        @Volatile
        var ready: Boolean = false
            private set

        @Volatile
        var error: String? = null
            private set

        /** версия подменённого yt-dlp, null — ещё ни разу не обновляли */
        @Volatile
        var ytdlpVersion: String? = null
            private set

        private fun markReady() {
            ready = true
        }

        private fun markFailed(msg: String) {
            error = msg
        }

        fun refreshVersion(context: Context) {
            ytdlpVersion = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VERSION, null)
        }

        /** раз в сутки, тихо, без права на ошибку */
        private fun maybeAutoUpdate(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_UPDATE, 0L) < DAY_MS) return
            prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            updateNow(context)
        }

        /** @return текст для тоста */
        fun updateNow(context: Context): String {
            return try {
                val dir = File(context.noBackupFilesDir, YoutubeDL.baseName + "/" + YoutubeDL.ytdlpDirName)
                dir.mkdirs()
                val target = File(dir, YoutubeDL.ytdlpBin)
                val tmp = File(dir, "yt-dlp.new")

                val conn = (URL(DLP_URL).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20_000
                    readTimeout = 180_000
                    setRequestProperty("User-Agent", "VideoGrab")
                }
                conn.connect()
                val finalUrl = conn.url.toString()
                conn.inputStream.use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
                conn.disconnect()

                if (tmp.length() < 500_000L) {
                    tmp.delete()
                    return "не вышло: скачался мусор (" + tmp.length() + " байт)"
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                target.setReadable(true)
                target.setExecutable(true)

                val tag = Regex("/download/([^/]+)/yt-dlp").find(finalUrl)?.groupValues?.get(1) ?: "?"
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                    .putString(KEY_VERSION, tag)
                    .apply()
                ytdlpVersion = tag
                "yt-dlp обновлён: $tag"
            } catch (t: Throwable) {
                "не вышло обновить: " + (t.message ?: t.toString())
            }
        }
    }
}
