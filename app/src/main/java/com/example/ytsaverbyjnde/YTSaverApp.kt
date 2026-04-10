package com.example.ytsaverbyjnde

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YTSaverApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        DownloadNotificationHelper.ensureChannel(applicationContext)

        // Warm up yt-dlp/FFmpeg as soon as the app launches so the first download starts faster.
        appScope.launch {
            runCatching {
                YoutubeDlRuntime.ensureReady(applicationContext)
            }
        }
    }
}
