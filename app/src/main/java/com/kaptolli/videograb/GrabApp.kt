package com.kaptolli.videograb

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * yt-dlp + python + ffmpeg распаковываются при первом запуске (несколько секунд),
 * всё остальное в приложении ждёт [ready].
 *
 * yt-dlp в библиотеке — сборка с её релиза, поэтому раз в сутки тянем свежий бинарь
 * с GitHub: площадки ломают старые версии постоянно (и yt-dlp сам ворчит, что старше 90 дней).
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
        private const val DAY_MS = 24L * 60 * 60 * 1000

        @Volatile
        var ready: Boolean = false
            private set

        @Volatile
        var error: String? = null
            private set

        /** версия yt-dlp (тег релиза), null пока ни разу не обновлялись */
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
            ytdlpVersion = try {
                YoutubeDL.version(context)
            } catch (t: Throwable) {
                null
            }
        }

        /** раз в сутки, тихо, без права на ошибку: нет сети — попробуем в следующий запуск */
        private fun maybeAutoUpdate(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_UPDATE, 0L) < DAY_MS) return
            try {
                YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                refreshVersion(context)
                prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            } catch (t: Throwable) {
                // молча: пригодится позже, кнопкой «Обновить»
            }
        }

        /** ручное обновление, возвращает текст для тоста */
        fun updateNow(context: Context): String {
            return try {
                val status = YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                refreshVersion(context)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
                val v = ytdlpVersion ?: "?"
                when (status) {
                    YoutubeDL.UpdateStatus.DONE -> "yt-dlp обновлён: $v"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp уже свежий: $v"
                    else -> "yt-dlp: $v"
                }
            } catch (t: Throwable) {
                "не вышло обновить: " + (t.message ?: t.toString())
            }
        }
    }
}
