package com.kaptolli.videograb

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * yt-dlp + python + ffmpeg unpack themselves on first launch, that takes a few seconds.
 * Everything else in the app waits for [ready].
 */
class GrabApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread {
            try {
                YoutubeDL.init(this)
                FFmpeg.init(this)
                markReady()
            } catch (t: Throwable) {
                markFailed(t.message ?: t.toString())
            }
        }.apply { name = "ytdlp-init" }.start()
    }

    companion object {
        @Volatile
        var ready: Boolean = false
            private set

        @Volatile
        var error: String? = null
            private set

        private fun markReady() {
            ready = true
        }

        private fun markFailed(msg: String) {
            error = msg
        }
    }
}
