package com.example.flowdl.core

import android.content.Context
import dev.ffmpegkit_maintained.ytdlp.DownloadProgressCallback
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import java.io.File
import java.util.concurrent.Future

/** Executes a selected yt-dlp format against the original page URL (not its expiring CDN URL). */
object YtDlpDownloadEngine {
    fun download(
        context: Context,
        pageUrl: String,
        formatId: String,
        outputName: String?,
        onProgress: (Float, Long, String) -> Unit,
        onFinished: (Result<Unit>) -> Unit
    ): Future<*> {
        val app = context.applicationContext
        val directory = app.getExternalFilesDir("Download") ?: File(app.filesDir, "Download")
        directory.mkdirs()
        val safeBase = outputName?.trim()?.takeIf { it.isNotBlank() }
            ?.let(Http::sanitizeFileName)
            ?.substringBeforeLast('.', outputName.trim())
            ?.take(100)
        val template = if (safeBase != null) {
            File(directory, "$safeBase.%(ext)s").absolutePath
        } else {
            File(directory, "%(title).150B.%(ext)s").absolutePath
        }

        val request = YtDlpRequest(pageUrl)
            .setOutputTemplate(template)
            .addOption("-f", formatId)
            .addOption("--no-playlist")
            .addOption("--newline")
            .addOption("--restrict-filenames")

        return YtDlp.executeAsync(request, object : DownloadProgressCallback {
            override fun onProgressUpdate(progress: Float, etaInSeconds: Long, line: String) {
                onProgress(progress, etaInSeconds, line)
            }
        }).also { future ->
            Thread {
                try {
                    val response = future.get()
                    if (response.exitCode == 0) onFinished(Result.success(Unit))
                    else onFinished(Result.failure(IllegalStateException("yt-dlp exited with code ${response.exitCode}")))
                } catch (t: Throwable) {
                    onFinished(Result.failure(t.cause ?: t))
                }
            }.apply { name = "FlowDL-yt-dlp-result"; isDaemon = true; start() }
        }
    }
}
