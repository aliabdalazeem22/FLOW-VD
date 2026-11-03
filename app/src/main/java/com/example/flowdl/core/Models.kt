package com.example.flowdl.core

import java.util.UUID

enum class TaskStatus {
    QUEUED, CONNECTING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED;

    val isActive: Boolean get() = this == QUEUED || this == CONNECTING || this == RUNNING
}

/** One byte range of the remote file, owned by a single worker thread. */
data class Connection(
    val id: Int,
    val start: Long,
    val end: Long,          // exclusive
    val current: Long,      // next byte to write
    val active: Boolean = true,
    val failed: Boolean = false
) {
    val remaining: Long get() = (end - current).coerceAtLeast(0)
}

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val fileName: String,
    val targetDir: String,
    val status: TaskStatus = TaskStatus.QUEUED,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val speedBps: Long = 0L,
    /** 0 means unlimited. */
    val maxSpeedBps: Long = 0L,
    val threadCount: Int = 4,
    val connections: List<Connection> = emptyList(),
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val supportsRanges: Boolean = true
) {
    val progress: Float
        get() = when {
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            status == TaskStatus.COMPLETED -> 1f
            else -> 0f
        }

    val etaSeconds: Long
        get() {
            if (totalBytes <= 0L || speedBps <= 0L) return -1L
            return ((totalBytes - downloadedBytes).coerceAtLeast(0L)) / speedBps
        }
}
