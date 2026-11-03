package com.example.flowdl.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private class RangeNotSupported : IOException("Server ignored the Range header")

/**
 * Owns one running download: the coordinator thread, the worker threads and the
 * byte-range chunks. Mirrors the scheduling model of SnapTube's mtdownload engine:
 * a chunk is claimed by a free worker, and when no free chunk exists a worker
 * steals the second half of the largest remaining chunk.
 */
class TaskRuntime(
    initial: DownloadTask,
    private val onFinished: (TaskRuntime) -> Unit
) {
    private class Chunk(
        val id: Int,
        val start: Long,
        @Volatile var end: Long,
        @Volatile var current: Long
    ) {
        @Volatile var owner: Int = -1
        @Volatile var active: Boolean = false
        fun remaining(): Long = (end - current).coerceAtLeast(0L)
    }

    val id: String = initial.id
    private val url: String = initial.url
    private val targetDir: String = initial.targetDir
    private val createdAt: Long = initial.createdAt

    @Volatile var fileName: String = initial.fileName
        private set
    @Volatile var status: TaskStatus = initial.status
        private set
    @Volatile var totalBytes: Long = initial.totalBytes
        private set
    @Volatile var supportsRanges: Boolean = initial.supportsRanges
        private set
    @Volatile var maxSpeedBps: Long = initial.maxSpeedBps
        private set
    @Volatile var threadCount: Int = initial.threadCount.coerceIn(1, Http.MAX_THREADS)
        private set
    @Volatile var error: String? = initial.error
        private set

    private val chunks = ArrayList<Chunk>()
    private val connLock = Any()
    private val fileLock = Any()
    private val fallbackLock = Any()
    private val limiter = RateLimiter()
    private val written = AtomicLong(0L)
    private val activeWorkers = AtomicInteger(0)
    private val nextWorkerId = AtomicInteger(0)
    private val activeConnections = Collections.synchronizedSet(HashSet<HttpURLConnection>())
    private val nextChunkId = AtomicInteger(1)
    private val finished = AtomicInteger(0)

    @Volatile private var paused = false
    @Volatile private var cancelled = false
    @Volatile private var started = false
    @Volatile private var fatalError: String? = null
    @Volatile private var speedBps = 0L
    @Volatile private var lastSampleBytes = 0L
    @Volatile private var lastSampleTime = 0L
    @Volatile private var epoch = 0

    private var raf: RandomAccessFile? = null
    private var partFile: File? = null
    private var finalFile: File? = null

    init {
        limiter.setLimit(initial.maxSpeedBps)
        synchronized(connLock) {
            initial.connections.forEach {
                chunks.add(Chunk(nextChunkId.getAndIncrement(), it.start, it.end, it.current))
            }
        }
        written.set(initial.downloadedBytes)
    }

    val isRunning: Boolean
        get() = started && !paused && !cancelled && finished.get() == 0

    fun start() {
        if (started) return
        started = true
        paused = false
        cancelled = false
        status = TaskStatus.CONNECTING
        Thread({ supervise() }, "flowdl-coord-${id.take(6)}").apply {
            isDaemon = true
            start()
        }
    }

    fun pause() {
        if (finished.get() == 1) return
        paused = true
        epoch++
        status = TaskStatus.PAUSED
        speedBps = 0
        disconnectAll()
    }

    fun resume() {
        if (finished.get() == 1 || cancelled) return
        paused = false
        status = TaskStatus.CONNECTING
    }

    fun cancel() {
        cancelled = true
        paused = false
        disconnectAll()
        if (!started) finishCanceled()
    }

    fun setThreadCount(value: Int) {
        threadCount = value.coerceIn(1, Http.MAX_THREADS)
    }

    fun setSpeedLimit(bytesPerSecond: Long) {
        maxSpeedBps = bytesPerSecond.coerceAtLeast(0L)
        limiter.setLimit(maxSpeedBps)
    }

    fun snapshot(): DownloadTask {
        val conns = synchronized(connLock) {
            chunks.map {
                Connection(
                    id = it.id,
                    start = it.start,
                    end = it.end,
                    current = it.current,
                    active = it.active,
                    failed = false
                )
            }
        }
        return DownloadTask(
            id = id,
            url = url,
            fileName = fileName,
            targetDir = targetDir,
            status = status,
            totalBytes = totalBytes,
            downloadedBytes = written.get(),
            speedBps = speedBps,
            maxSpeedBps = maxSpeedBps,
            threadCount = threadCount,
            connections = conns,
            error = error,
            createdAt = createdAt,
            supportsRanges = supportsRanges
        )
    }

    // ---------------------------------------------------------------- lifecycle

    private fun supervise() {
        try {
            prepare()
        } catch (t: Throwable) {
            fail(t)
            return
        }
        while (!cancelled) {
            if (paused) {
                status = TaskStatus.PAUSED
                speedBps = 0
                sleepQuiet(200)
                continue
            }
            if (status == TaskStatus.CONNECTING || status == TaskStatus.PAUSED) {
                status = TaskStatus.RUNNING
            }
            spawnWorkers()
            sampleSpeed()
            if (fatalError != null) {
                fail(IOException(fatalError))
                return
            }
            if (isComplete()) {
                complete()
                return
            }
            sleepQuiet(250)
        }
        finishCanceled()
    }

    private fun prepare() {
        val dir = File(targetDir)
        if (!dir.exists()) dir.mkdirs()

        if (totalBytes <= 0L || chunks.isEmpty()) {
            val probe = Http.probe(url)
            totalBytes = probe.totalBytes
            supportsRanges = probe.supportsRanges && probe.totalBytes > 0L
            if (fileName.startsWith("flowdl-")) {
                probe.suggestedName?.let { fileName = Http.sanitizeFileName(it) }
            }
            if (!fileName.contains('.')) {
                Http.extensionFor(probe.mimeType)?.let { fileName += it }
            }
            fileName = Http.sanitizeFileName(fileName)
        } else if (supportsRanges && totalBytes <= 0L) {
            supportsRanges = false
        }

        if (!supportsRanges) {
            val progressed = synchronized(connLock) { chunks.any { it.current > it.start } }
            if (progressed || chunks.isEmpty()) {
                resetToSingleStream()
            }
        }

        finalFile = uniqueTarget(dir, fileName)
        partFile = File(dir, finalFile!!.name + ".flowpart")
        raf = RandomAccessFile(partFile, "rw")

        synchronized(connLock) {
            if (chunks.isEmpty()) {
                if (supportsRanges && totalBytes > 0L) {
                    val count = Http.splitCount(totalBytes, threadCount)
                    var start = 0L
                    for (i in 0 until count) {
                        val end = if (i == count - 1) totalBytes else start + totalBytes / count
                        chunks.add(Chunk(nextChunkId.getAndIncrement(), start, end, start))
                        start = end
                    }
                } else {
                    chunks.add(
                        Chunk(
                            nextChunkId.getAndIncrement(),
                            0L,
                            if (totalBytes > 0L) totalBytes else Long.MAX_VALUE,
                            0L
                        )
                    )
                }
            } else {
                val maxCurrent = chunks.maxOfOrNull { it.current } ?: 0L
                if ((partFile?.length() ?: 0L) < maxCurrent) {
                    chunks.forEach { it.current = it.start }
                }
            }
            written.set(chunks.sumOf { (it.current - it.start).coerceAtLeast(0L) })
        }
        lastSampleBytes = written.get()
        lastSampleTime = System.currentTimeMillis()
    }

    private fun resetToSingleStream() {
        synchronized(connLock) {
            chunks.clear()
            chunks.add(
                Chunk(
                    nextChunkId.getAndIncrement(),
                    0L,
                    if (totalBytes > 0L) totalBytes else Long.MAX_VALUE,
                    0L
                )
            )
        }
        written.set(0L)
        runCatching { raf?.setLength(0L) }
    }

    // ------------------------------------------------------------------- workers

    private fun spawnWorkers() {
        val target = threadCount
        if (activeWorkers.get() >= target) return
        val workerId = nextWorkerId.getAndIncrement()
        activeWorkers.incrementAndGet()
        Thread({ workerLoop(workerId) }, "flowdl-w${workerId}-${id.take(4)}").apply {
            isDaemon = true
            start()
        }
    }

    private fun workerLoop(workerId: Int) {
        val myEpoch = epoch
        try {
            while (!paused && !cancelled) {
                if (myEpoch != epoch) return
                if (activeWorkers.get() > threadCount) return
                val chunk = claim(workerId)
                if (chunk == null) {
                    if (isComplete()) return
                    sleepQuiet(150)
                    continue
                }
                downloadChunk(chunk, myEpoch)
                release(chunk)
            }
        } finally {
            releaseOwned(workerId)
            activeWorkers.decrementAndGet()
        }
    }

    private fun claim(workerId: Int): Chunk? {
        synchronized(connLock) {
            var best: Chunk? = null
            for (c in chunks) {
                if (c.owner == -1 && c.current < c.end) {
                    if (best == null || c.remaining() > best.remaining()) best = c
                }
            }
            if (best != null) {
                best.owner = workerId
                best.active = true
                return best
            }
            if (!supportsRanges) return null
            var donor: Chunk? = null
            var donorRemaining = 0L
            for (c in chunks) {
                if (c.owner == workerId) continue
                val base = maxOf(c.start, c.current)
                val remaining = c.end - base
                if (remaining >= Http.MIN_SPLIT_BYTES && remaining > donorRemaining) {
                    donor = c
                    donorRemaining = remaining
                }
            }
            val source = donor ?: return null
            val base = maxOf(source.start, source.current)
            val middle = base + (source.end - base) / 2
            val stolen = Chunk(nextChunkId.getAndIncrement(), middle, source.end, middle)
            stolen.owner = workerId
            stolen.active = true
            synchronized(source) { source.end = middle }
            chunks.add(stolen)
            return stolen
        }
    }

    private fun release(chunk: Chunk) {
        synchronized(connLock) {
            chunk.active = false
            if (chunk.current >= chunk.end) chunk.owner = -1
        }
    }

    private fun releaseOwned(workerId: Int) {
        synchronized(connLock) {
            chunks.forEach {
                if (it.owner == workerId) {
                    it.owner = -1
                    it.active = false
                }
            }
        }
    }

    private fun downloadChunk(chunk: Chunk, myEpoch: Int) {
        var attempt = 0
        if (!supportsRanges && chunk.current > 0L) {
            synchronized(chunk) { chunk.current = chunk.start }
            synchronized(fileLock) { runCatching { raf?.setLength(0L) } }
            written.set(0L)
        }
        while (!paused && !cancelled && myEpoch == epoch) {
            if (chunk.current >= chunk.end) return
            val windowEnd = if (chunk.end == Long.MAX_VALUE) Long.MAX_VALUE
            else minOf(chunk.end, chunk.current + MAX_REQUEST_BYTES)
            var conn: HttpURLConnection? = null
            try {
                val opened = openChunk(chunk, windowEnd)
                conn = opened
                activeConnections.add(opened)
                val code = opened.responseCode
                when {
                    code == 206 -> validateRange(opened, chunk)
                    code == 200 -> {
                        if (supportsRanges) throw RangeNotSupported()
                        if (chunk.current > 0L) {
                            throw IOException("Server stopped honouring Range; cannot resume")
                        }
                    }
                    code == 416 -> {
                        if (totalBytes > 0L && chunk.current >= totalBytes) {
                            synchronized(chunk) { chunk.current = chunk.end }
                            return
                        }
                        throw IOException("HTTP 416 for bytes=${chunk.current}-${chunk.end - 1}")
                    }
                    else -> throw IOException("HTTP $code")
                }
                val input = opened.inputStream ?: throw IOException("Empty response body")
                input.use { copyToFile(chunk, it, myEpoch) }
                if (chunk.current >= chunk.end) return
                if (!supportsRanges) return
                if (chunk.current >= windowEnd) {
                    attempt = 0
                    continue
                }
                throw IOException("Stream ended early at ${chunk.current}/${chunk.end}")
            } catch (e: RangeNotSupported) {
                switchToSingleStream()
                return
            } catch (e: IOException) {
                if (paused || cancelled || myEpoch != epoch) return
                attempt++
                if (attempt > MAX_RETRIES) {
                    fatalError = e.message ?: "Network error"
                    return
                }
                sleepQuiet(300L * attempt)
            } finally {
                conn?.let {
                    activeConnections.remove(it)
                    runCatching { it.disconnect() }
                }
            }
        }
    }

    private fun copyToFile(chunk: Chunk, input: InputStream, myEpoch: Int) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (!paused && !cancelled && myEpoch == epoch) {
            val read = try {
                input.read(buffer)
            } catch (e: IOException) {
                throw e
            }
            if (read < 0) {
                if (!supportsRanges) synchronized(chunk) { chunk.end = chunk.current }
                return
            }
            if (read == 0) continue
            limiter.throttle(read)
            var wrote = 0
            synchronized(chunk) {
                if (myEpoch != epoch) return
                val room = chunk.end - chunk.current
                val take = minOf(read.toLong(), room).toInt()
                if (take > 0) {
                    synchronized(fileLock) {
                        raf?.let { file ->
                            file.seek(chunk.current)
                            file.write(buffer, 0, take)
                        }
                    }
                    chunk.current += take
                    wrote = take
                }
            }
            if (wrote > 0) {
                written.addAndGet(wrote.toLong())
            }
            if (wrote < read) return
        }
    }

    private fun openChunk(chunk: Chunk, windowEnd: Long): HttpURLConnection {
        val headers = HashMap<String, String>()
        if (supportsRanges) {
            val last = if (windowEnd == Long.MAX_VALUE) "" else (windowEnd - 1).toString()
            headers["Range"] = "bytes=${chunk.current}-$last"
        }
        return Http.open(url, headers).apply { readTimeout = 45_000 }
    }

    private fun validateRange(conn: HttpURLConnection, chunk: Chunk) {
        val header = conn.getHeaderField("Content-Range") ?: return
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)").find(header)
            ?: throw IOException("Bad Content-Range: $header")
        val start = match.groupValues[1].toLong()
        if (start != chunk.current) {
            throw IOException("Content-Range start $start != ${chunk.current}")
        }
        val totalPart = match.groupValues[3]
        if (totalPart != "*") {
            val total = totalPart.toLong()
            if (totalBytes > 0L && total != totalBytes) {
                throw IOException("Remote file size changed ($total != $totalBytes)")
            }
            if (totalBytes <= 0L) totalBytes = total
        }
    }

    private fun switchToSingleStream() {
        synchronized(fallbackLock) {
            if (!supportsRanges) return
            supportsRanges = false
            epoch++
            disconnectAll()
            synchronized(connLock) {
                chunks.clear()
                chunks.add(
                    Chunk(
                        nextChunkId.getAndIncrement(),
                        0L,
                        if (totalBytes > 0L) totalBytes else Long.MAX_VALUE,
                        0L
                    )
                )
            }
            written.set(0L)
            synchronized(fileLock) { runCatching { raf?.setLength(0L) } }
        }
    }

    // ------------------------------------------------------------------ sampling

    private fun sampleSpeed() {
        val now = System.currentTimeMillis()
        val bytes = written.get()
        if (lastSampleTime == 0L) {
            lastSampleTime = now
            lastSampleBytes = bytes
            return
        }
        val elapsed = now - lastSampleTime
        if (elapsed >= 300L) {
            val instant = ((bytes - lastSampleBytes) * 1000L / elapsed).coerceAtLeast(0L)
            speedBps = if (speedBps == 0L) instant else (speedBps * 3 + instant) / 4
            lastSampleBytes = bytes
            lastSampleTime = now
        }
    }

    private fun isComplete(): Boolean = synchronized(connLock) {
        chunks.isNotEmpty() && chunks.all { it.current >= it.end }
    }

    // ---------------------------------------------------------------- finishing

    private fun complete() {
        val part = partFile
        val dest = finalFile
        if (totalBytes > 0L) {
            synchronized(fileLock) { runCatching { raf?.setLength(totalBytes) } }
        }
        closeFile()
        if (totalBytes <= 0L) totalBytes = written.get()
        if (part != null && dest != null && part.exists()) {
            runCatching {
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
            }.onFailure {
                error = it.message
                status = TaskStatus.FAILED
                finish()
                return
            }
        }
        status = TaskStatus.COMPLETED
        speedBps = 0
        finish()
    }

    private fun fail(t: Throwable) {
        error = t.message ?: t.javaClass.simpleName
        status = TaskStatus.FAILED
        speedBps = 0
        closeFile()
        disconnectAll()
        finish()
    }

    private fun finishCanceled() {
        closeFile()
        partFile?.delete()
        status = TaskStatus.CANCELED
        speedBps = 0
        finish()
    }

    private fun finish() {
        if (finished.getAndSet(1) == 1) return
        onFinished(this)
    }

    private fun closeFile() {
        synchronized(fileLock) {
            runCatching { raf?.close() }
            raf = null
        }
    }

    private fun disconnectAll() {
        synchronized(activeConnections) {
            activeConnections.forEach { runCatching { it.disconnect() } }
            activeConnections.clear()
        }
    }

    private fun uniqueTarget(dir: File, name: String): File {
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) name.substring(name.lastIndexOf('.')) else ""
        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val MAX_RETRIES = 4
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_REQUEST_BYTES = 8L * 1024L * 1024L
    }
}
