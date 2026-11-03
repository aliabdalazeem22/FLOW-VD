package com.example.flowdl.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.flowdl.MainActivity
import com.example.flowdl.R
import com.example.flowdl.core.DownloadManager
import com.example.flowdl.core.DownloadTask
import com.example.flowdl.ui.formatBytes
import com.example.flowdl.ui.formatSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while tasks are running and mirrors progress into a
 * low-importance foreground notification. No ads, no analytics, no trackers.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    private val pauseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PAUSE_ALL) DownloadManager.pauseAll()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            pauseReceiver,
            IntentFilter(ACTION_PAUSE_ALL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote(DownloadManager.tasks.value)
        if (!observing) {
            observing = true
            scope.launch {
                DownloadManager.tasks.collect { list ->
                    if (list.none { it.status.isActive }) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        promote(list)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promote(list: List<DownloadTask>) {
        val active = list.filter { it.status.isActive }
        val notification = buildNotification(active)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(active: List<DownloadTask>): Notification {
        val done = active.sumOf { it.downloadedBytes }
        val total = active.sumOf { it.totalBytes.coerceAtLeast(0L) }
        val speed = active.sumOf { it.speedBps }
        val unknown = active.any { it.totalBytes <= 0L }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(ACTION_PAUSE_ALL).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title: String
        val summary: String
        if (active.isEmpty()) {
            title = "FlowDL"
            summary = "Idle"
        } else if (active.size == 1) {
            title = active.first().fileName
            summary = if (unknown && total <= 0L) {
                formatBytes(done) + " • " + formatSpeed(speed)
            } else {
                formatBytes(done) + " / " + formatBytes(total) + " • " + formatSpeed(speed)
            }
        } else {
            title = "${active.size} downloads running"
            summary = if (unknown && total <= 0L) {
                formatBytes(done) + " • " + formatSpeed(speed)
            } else {
                formatBytes(done) + " / " + formatBytes(total) + " • " + formatSpeed(speed)
            }
        }

        val builder = NotificationCompat.Builder(this, "downloads")
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(summary)
            .setContentIntent(openIntent)
            .setOngoing(active.isNotEmpty())
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (active.isNotEmpty()) {
            builder.addAction(R.drawable.ic_stat_pause, "Pause all", pauseIntent)
        }

        if (active.isNotEmpty() && total > 0L && !unknown) {
            builder.setProgress(1000, ((done * 1000L) / total).toInt().coerceIn(0, 1000), false)
        } else {
            builder.setProgress(0, 0, active.isNotEmpty())
        }
        return builder.build()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pauseReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_PAUSE_ALL = "com.example.flowdl.PAUSE_ALL"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
