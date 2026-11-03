package com.example.flowdl

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.flowdl.core.DownloadManager
import com.example.flowdl.core.MediaExtractor
import android.util.Log
import dev.ffmpegkit_maintained.ytdlp.YtDlp

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        DownloadManager.init(this)
        MediaExtractor.initialize(this)
        runCatching { YtDlp.init(this) }
            .onFailure { Log.e("FlowDL", "yt-dlp engine initialization failed", it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "downloads",
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
