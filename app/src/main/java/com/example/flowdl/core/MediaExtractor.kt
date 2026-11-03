package com.example.flowdl.core

import android.net.Uri
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import java.io.IOException
import java.net.URL
import java.util.Locale

/** One selectable rendition returned by either yt-dlp or the conservative HTML/HLS fallback. */
data class MediaFormat(
    val url: String,
    val label: String,
    val mimeType: String? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val isManifest: Boolean = false,
    /** Non-null means the original page URL must be passed to yt-dlp with this format selector. */
    val engineFormatId: String? = null,
    val extension: String? = null,
    val fileSizeBytes: Long? = null,
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = true
)

data class MediaExtractionResult(
    val pageUrl: String,
    val title: String,
    val formats: List<MediaFormat>,
    val source: String,
    val note: String? = null
)

/**
 * Extraction pipeline:
 * 1) yt-dlp's embedded extractor registry for supported platforms (1000+ sites in the library build),
 * 2) conservative direct-file / HLS / DASH / HTML metadata fallback.
 *
 * This does not defeat DRM, paywalls, private content, or platform access controls.
 */
object MediaExtractor {
    @Volatile private var appContext: android.content.Context? = null

    fun initialize(context: android.content.Context) { appContext = context.applicationContext }

    private const val MAX_PAGE_BYTES = 2 * 1024 * 1024
    private const val MAX_PLAYLIST_BYTES = 1024 * 1024

    @Throws(IOException::class)
    fun extract(input: String): MediaExtractionResult {
        val page = input.trim()
        if (!Http.isSupported(page)) throw IOException("Enter a valid HTTP or HTTPS URL")
        val uri = Uri.parse(page)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (host.isBlank()) throw IOException("The URL has no valid host")

        // Plain media files can be downloaded directly. Manifests go through yt-dlp first so it
        // can resolve adaptive variants and platform-specific headers when the source supports it.
        if (isDirectMedia(page)) {
            return MediaExtractionResult(page, titleFromUrl(page), listOf(
                MediaFormat(page, "Original quality", mimeFromUrl(page), extension = extensionFromUrl(page))
            ), host)
        }

        val engineResult = runCatching { extractWithYtDlp(page, host) }
        engineResult.getOrNull()?.let { return it }
        val engineFailure = engineResult.exceptionOrNull()

        // Conservative manifest / HTML fallback for public sources not covered by the bundled registry.
        val fallback = runCatching {
            when {
                page.substringBefore('?').endsWith(".m3u8", true) -> {
                    val formats = parseHls(page, readText(page, MAX_PLAYLIST_BYTES))
                    MediaExtractionResult(page, titleFromUrl(page), formats, host,
                        "HLS playlist found, but yt-dlp could not resolve it. These entries are manifests, not direct files.")
                }
                page.substringBefore('?').endsWith(".mpd", true) -> MediaExtractionResult(
                    page, titleFromUrl(page), listOf(MediaFormat(page, "MPEG-DASH adaptive stream", "application/dash+xml", isManifest = true)), host,
                    "DASH manifest found, but no platform format metadata was available."
                )
                else -> extractHtml(page, host)
            }
        }
        return fallback.getOrElse { htmlFailure ->
            val reason = engineFailure?.message?.lineSequence()?.firstOrNull()?.take(180)
            throw IOException("No formats could be extracted. ${reason ?: "The platform may require sign-in or an updated extractor."}", htmlFailure)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractWithYtDlp(page: String, host: String): MediaExtractionResult {
        try {
            // YtDlp.init is also called at Application startup; this makes the extractor safe for other entry points.
            YtDlp.init(appContext ?: throw IOException("Media engine is not initialized"))
        } catch (e: Exception) {
            throw IOException("yt-dlp engine initialization failed: ${e.message ?: "unknown error"}", e)
        }

        try {
            val python = Python.getInstance()
            val ytDlp: PyObject = python.getModule("yt_dlp")
            val options = python.getBuiltins().callAttr("dict")
            options.put("quiet", true)
            options.put("no_warnings", true)
            options.put("skip_download", true)
            options.put("noplaylist", true)
            options.put("ignoreerrors", false)
            val downloader = ytDlp.callAttr("YoutubeDL", options)
            val info = downloader.callAttr("extract_info", page, false)
            val data = info.toJava(Map::class.java) as Map<String, Any?>
            val title = data.string("title")?.takeIf { it.isNotBlank() } ?: host
            val entries = data["formats"] as? List<*> ?: emptyList<Any>()
            val formats = entries.mapNotNull { item ->
                val f = item as? Map<*, *> ?: return@mapNotNull null
                val formatId = f["format_id"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val ext = f["ext"]?.toString()
                val height = (f["height"] as? Number)?.toInt()?.takeIf { it > 0 }
                val width = (f["width"] as? Number)?.toInt()?.takeIf { it > 0 }
                val bitrate = ((f["tbr"] as? Number)?.toDouble()?.times(1000))?.toLong()
                val size = (f["filesize"] as? Number)?.toLong()
                    ?: (f["filesize_approx"] as? Number)?.toLong()
                val videoCodec = f["vcodec"]?.toString()?.takeUnless { it == "none" }
                val audioCodec = f["acodec"]?.toString()?.takeUnless { it == "none" }
                val hasVideo = videoCodec != null
                val hasAudio = audioCodec != null
                if (!hasVideo && !hasAudio) return@mapNotNull null
                val protocol = f["protocol"]?.toString().orEmpty()
                val quality = when {
                    height != null -> "${height}p"
                    f["format_note"]?.toString()?.isNotBlank() == true -> f["format_note"].toString()
                    else -> "Format $formatId"
                }
                val dimensions = if (width != null && height != null) " · ${width}×${height}" else ""
                val tracks = when {
                    hasVideo && hasAudio -> "video + audio"
                    hasVideo -> "video only"
                    hasAudio -> "audio only"
                    else -> protocol.ifBlank { "media" }
                }
                val rate = bitrate?.takeIf { it > 0 }?.let { " · ${it / 1000} kbps" }.orEmpty()
                val sizeLabel = size?.takeIf { it > 0 }?.let { " · ${formatBytes(it)}" }.orEmpty()
                MediaFormat(
                    url = page,
                    label = "$quality$dimensions · ${ext ?: "media"} · $tracks$rate$sizeLabel",
                    mimeType = ext?.let(::mimeFromExtension),
                    height = height,
                    bitrate = bitrate,
                    isManifest = false,
                    engineFormatId = formatId,
                    extension = ext,
                    fileSizeBytes = size,
                    hasVideo = hasVideo,
                    hasAudio = hasAudio
                )
            }.distinctBy { it.engineFormatId }
                .sortedWith(compareBy<MediaFormat> { it.height ?: 0 }.thenBy { it.bitrate ?: 0L })

            if (formats.isEmpty()) throw IOException("The extractor found no downloadable formats for this page")
            return MediaExtractionResult(
                pageUrl = page,
                title = title,
                formats = formats,
                source = host,
                note = "Formats are reported by yt-dlp for this link. Some sites may require sign-in, cookies, or a newer extractor."
            )
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("yt-dlp could not extract this page: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun extractHtml(page: String, host: String): MediaExtractionResult {
        val html = readText(page, MAX_PAGE_BYTES)
        val title = firstMatch(html, listOf(
            "(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)",
            "(?is)<title[^>]*>(.*?)</title>"
        ))?.let(::decodeEntities)?.trim()?.takeIf { it.isNotBlank() } ?: host
        val found = linkedMapOf<String, MediaFormat>()
        val candidates = listOf(
            "(?is)<meta[^>]+property=[\"']og:video(?::url)?[\"'][^>]+content=[\"']([^\"']+)",
            "(?is)<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:video(?::url)?[\"']",
            "(?is)<meta[^>]+property=[\"']og:audio(?::url)?[\"'][^>]+content=[\"']([^\"']+)",
            "(?is)<video[^>]+src=[\"']([^\"']+)",
            "(?is)<source[^>]+src=[\"']([^\"']+)",
            "(?is)<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:name|property)=[\"'](?:twitter:player:stream|og:video:secure_url)[\"']"
        )
        candidates.forEach { pattern ->
            Regex(pattern).findAll(html).forEach { match ->
                val raw = decodeEntities(match.groupValues[1]).trim()
                val absolute = runCatching { URL(URL(page), raw).toString() }.getOrNull() ?: return@forEach
                if (Http.isSupported(absolute) && isDirectMedia(absolute)) {
                    found.putIfAbsent(absolute, MediaFormat(absolute, labelFromUrl(absolute), mimeFromUrl(absolute), extension = extensionFromUrl(absolute)))
                } else if (Http.isSupported(absolute) && absolute.substringBefore('?').endsWith(".m3u8", true)) {
                    found.putIfAbsent(absolute, MediaFormat(absolute, "HLS adaptive stream", "application/vnd.apple.mpegurl", isManifest = true))
                } else if (Http.isSupported(absolute) && absolute.substringBefore('?').endsWith(".mpd", true)) {
                    found.putIfAbsent(absolute, MediaFormat(absolute, "MPEG-DASH adaptive stream", "application/dash+xml", isManifest = true))
                }
            }
        }
        if (found.isEmpty()) throw IOException("No public media URL was found in the page")
        return MediaExtractionResult(page, title, found.values.toList(), host,
            "HTML fallback found public media references. Platform-specific extraction may provide more qualities.")
    }

    private fun parseHls(masterUrl: String, body: String): List<MediaFormat> {
        val lines = body.lineSequence().map { it.trim() }.toList()
        val out = mutableListOf<MediaFormat>()
        var pending: String? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF", true)) pending = line
            else if (pending != null && line.isNotBlank() && !line.startsWith('#')) {
                val absolute = URL(URL(masterUrl), line).toString()
                val height = Regex("RESOLUTION=\\d+x(\\d+)", RegexOption.IGNORE_CASE).find(pending!!)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val bandwidth = Regex("(?:AVERAGE-BANDWIDTH|BANDWIDTH)=(\\d+)", RegexOption.IGNORE_CASE).find(pending!!)?.groupValues?.getOrNull(1)?.toLongOrNull()
                val label = buildString {
                    append(height?.let { "${it}p" } ?: "HLS variant")
                    if (bandwidth != null) append(" · ${bandwidth / 1000} kbps")
                }
                out += MediaFormat(absolute, label, "application/vnd.apple.mpegurl", height, bandwidth, isManifest = true)
                pending = null
            }
        }
        return out.ifEmpty { listOf(MediaFormat(masterUrl, "HLS media playlist", "application/vnd.apple.mpegurl", isManifest = true)) }
            .sortedWith(compareBy<MediaFormat> { it.height ?: 0 }.thenBy { it.bitrate ?: 0L })
    }

    private fun readText(url: String, maxBytes: Int): String {
        val connection = Http.open(url, mapOf("Accept" to "text/html,application/vnd.apple.mpegurl,application/dash+xml,*/*"))
        try {
            if (connection.responseCode !in 200..299) throw IOException("Server returned HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IOException("The page or manifest is too large to inspect")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val charsetName = Regex("charset=([^; ]+)", RegexOption.IGNORE_CASE).find(connection.contentType.orEmpty())?.groupValues?.getOrNull(1)?.trim('"', '\'') ?: "UTF-8"
            return String(bytes, charset(charsetName))
        } finally { connection.disconnect() }
    }

    private fun isDirectMedia(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT).let { path ->
            listOf(".mp4", ".m4v", ".webm", ".mkv", ".mov", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac").any(path::endsWith)
        }

    private fun mimeFromUrl(url: String): String? = mimeFromExtension(extensionFromUrl(url))
    private fun extensionFromUrl(url: String) = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.length in 2..5 }
    private fun mimeFromExtension(ext: String?): String? = when (ext?.lowercase(Locale.ROOT)) {
        "mp4", "m4v", "m4a" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"; "aac" -> "audio/aac"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"
        else -> null
    }
    private fun labelFromUrl(url: String) = mimeFromUrl(url)?.substringAfter('/')?.uppercase(Locale.ROOT) ?: "Media file"
    private fun titleFromUrl(url: String) = URL(url).path.substringAfterLast('/').ifBlank { "Media download" }.substringBeforeLast('.', URL(url).host)
    private fun firstMatch(text: String, patterns: List<String>): String? = patterns.firstNotNullOfOrNull { Regex(it).find(text)?.groupValues?.getOrNull(1) }
    private fun decodeEntities(value: String) = value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(Locale.ROOT, bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(Locale.ROOT, bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(Locale.ROOT, bytes / 1_000.0)
        else -> "$bytes B"
    }

    private fun Map<String, Any?>.string(key: String): String? = this[key]?.toString()
}
