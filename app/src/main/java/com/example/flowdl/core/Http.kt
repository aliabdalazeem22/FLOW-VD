package com.example.flowdl.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.max

data class ProbeResult(
    val totalBytes: Long,
    val supportsRanges: Boolean,
    val etag: String?,
    val mimeType: String?,
    val suggestedName: String?
)

/** Thin HTTP layer: manual redirects, Range probes and byte-range GETs. */
object Http {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val MAX_REDIRECTS = 6
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000

    fun isSupported(url: String): Boolean {
        val u = url.trim().lowercase(Locale.US)
        return u.startsWith("http://") || u.startsWith("https://")
    }

    /**
     * Opens a connection and follows up to [MAX_REDIRECTS] redirects manually,
     * so that Range/If-Range headers survive cross-host redirects.
     */
    fun open(
        url: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET"
    ): HttpURLConnection {
        var current = url
        var currentMethod = method
        var hops = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                requestMethod = currentMethod
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect $code without Location")
                if (++hops > MAX_REDIRECTS) throw IOException("Too many redirects")
                current = URL(URL(current), location).toString()
                if (code == 303) currentMethod = "GET"
                continue
            }
            return conn
        }
    }

    /** Detects size, range support, ETag and a suggested file name. */
    fun probe(url: String): ProbeResult {
        val ranged = runCatching { probeWithRange(url) }.getOrNull()
        if (ranged != null && (ranged.totalBytes > 0L || ranged.supportsRanges)) return ranged
        val head = runCatching { probeWithHead(url) }.getOrNull()
        if (head != null && (head.totalBytes > 0L || head.supportsRanges)) return head
        return ranged ?: head ?: throw IOException("Could not inspect the remote file")
    }

    private fun probeWithRange(url: String): ProbeResult {
        val conn = open(url, mapOf("Range" to "bytes=0-0"))
        try {
            val code = conn.responseCode
            val etag = conn.getHeaderField("ETag")
            val name = dispositionName(conn.getHeaderField("Content-Disposition"))
                ?: fileNameFromUrl(conn.url.toString())
            return when (code) {
                206 -> {
                    val total = parseTotal(conn.getHeaderField("Content-Range")) ?: -1L
                    ProbeResult(total, total > 0L, etag, conn.contentType, name)
                }
                200 -> ProbeResult(conn.contentLengthLong, false, etag, conn.contentType, name)
                416 -> {
                    val total = parseUnsatisfiedTotal(conn.getHeaderField("Content-Range")) ?: -1L
                    ProbeResult(total, total > 0L, etag, conn.contentType, name)
                }
                else -> throw IOException("Probe returned HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun probeWithHead(url: String): ProbeResult? {
        val conn = open(url, emptyMap(), "HEAD")
        try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            val length = conn.contentLengthLong
            val acceptRanges = conn.getHeaderField("Accept-Ranges")
                ?.contains("bytes", ignoreCase = true) == true
            return ProbeResult(
                totalBytes = length,
                supportsRanges = acceptRanges && length > 0L,
                etag = conn.getHeaderField("ETag"),
                mimeType = conn.contentType,
                suggestedName = dispositionName(conn.getHeaderField("Content-Disposition"))
                    ?: fileNameFromUrl(conn.url.toString())
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTotal(header: String?): Long? {
        val m = Regex("bytes\\s+\\d+-\\d+/(\\d+)").find(header ?: return null) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    private fun parseUnsatisfiedTotal(header: String?): Long? {
        val m = Regex("bytes\\s+\\*/(\\d+)").find(header ?: return null) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    private fun dispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val star = Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(header)
        if (star != null) {
            return runCatching { URLDecoder.decode(star.groupValues[1].trim(), "UTF-8") }.getOrNull()
        }
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
        return plain?.groupValues?.get(1)?.trim()?.let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }
    }

    fun fileNameFromUrl(url: String): String? {
        val path = runCatching { URL(url).path }.getOrNull() ?: return null
        val raw = path.substringAfterLast('/', "")
        if (raw.isBlank()) return null
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return sanitizeFileName(decoded)
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        return cleaned.take(120).ifBlank { "download" }
    }

    fun extensionFor(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        val base = mime.substringBefore(';').trim().lowercase(Locale.US)
        return when {
            base == "video/mp4" -> ".mp4"
            base == "video/webm" -> ".webm"
            base == "audio/mpeg" -> ".mp3"
            base == "audio/mp4" -> ".m4a"
            base == "application/pdf" -> ".pdf"
            base == "application/zip" -> ".zip"
            base == "image/jpeg" -> ".jpg"
            base == "image/png" -> ".png"
            base == "application/vnd.android.package-archive" -> ".apk"
            base.startsWith("text/") -> ".txt"
            else -> null
        }
    }

    fun splitCount(total: Long, threads: Int): Int {
        val byChunk = max(1L, (total + MIN_SPLIT_BYTES - 1) / MIN_SPLIT_BYTES).toInt()
        return max(1, minOf(threads.coerceIn(1, MAX_THREADS), byChunk))
    }

    const val MIN_SPLIT_BYTES = 256L * 1024L
    const val MAX_THREADS = 8
}
