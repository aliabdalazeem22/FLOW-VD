package com.example.flowdl.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Global download scheduler. At most [MAX_ACTIVE] tasks download at once;
 * the rest wait in a FIFO queue. Every task owns a [TaskRuntime] that drives
 * its own worker pool.
 */
object DownloadManager {
    private const val MAX_ACTIVE = 3
    private const val TICK_MS = 400L
    private const val SAVE_INTERVAL_MS = 2_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val baseTasks = LinkedHashMap<String, DownloadTask>()
    private val runtimes = LinkedHashMap<String, TaskRuntime>()
    private val queue = ArrayDeque<String>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private var store: TaskStore? = null
    private var downloadDir: String = ""
    private var dirty = true
    private var lastSave = 0L
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        val dir = app.getExternalFilesDir("Download") ?: File(app.filesDir, "Download")
        dir.mkdirs()
        downloadDir = dir.absolutePath
        store = TaskStore(File(app.filesDir, "tasks.json"))

        store?.load()?.forEach { saved ->
            val restored = when (saved.status) {
                TaskStatus.COMPLETED, TaskStatus.FAILED -> saved.copy(speedBps = 0L)
                else -> saved.copy(status = TaskStatus.PAUSED, speedBps = 0L)
            }
            baseTasks[restored.id] = restored
        }
        publish()

        scope.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    val downloadDirectory: String get() = downloadDir

    fun add(
        url: String,
        fileName: String?,
        threadCount: Int,
        maxSpeedBps: Long
    ): String? {
        if (!Http.isSupported(url)) return null
        val trimmed = url.trim()
        val rawName = fileName?.takeIf { it.isNotBlank() }?.let { Http.sanitizeFileName(it) }
            ?: Http.fileNameFromUrl(trimmed)
            ?: "flowdl-${UUID.randomUUID().toString().take(8)}.bin"
        val task = DownloadTask(
            url = trimmed,
            fileName = uniqueName(rawName),
            targetDir = downloadDir,
            threadCount = threadCount.coerceIn(1, Http.MAX_THREADS),
            maxSpeedBps = maxSpeedBps.coerceAtLeast(0L)
        )
        synchronized(lock) {
            baseTasks[task.id] = task
            queue.addLast(task.id)
            startNextLocked()
        }
        publish()
        markDirty()
        return task.id
    }

    fun pause(id: String) {
        synchronized(lock) {
            val runtime = runtimes[id]
            if (runtime != null) {
                runtime.pause()
                baseTasks[id] = runtime.snapshot()
            } else {
                baseTasks[id]?.let {
                    baseTasks[id] = it.copy(status = TaskStatus.PAUSED, speedBps = 0L)
                }
            }
        }
        publish()
        markDirty()
    }

    fun resume(id: String) {
        var changed = false
        synchronized(lock) {
            val task = baseTasks[id]
            if (task != null && task.status != TaskStatus.COMPLETED) {
                val runtime = runtimes[id]
                if (runtime != null && runtime.isRunning) {
                    // already downloading
                } else {
                    baseTasks[id] = task.copy(status = TaskStatus.QUEUED, error = null, speedBps = 0L)
                    queue.addLast(id)
                    startNextLocked()
                    changed = true
                }
            }        }
        if (changed) {
            publish()
            markDirty()
        }
    }

    fun cancel(id: String) {
        synchronized(lock) {
            queue.remove(id)
            val runtime = runtimes[id]
            if (runtime != null) {
                runtime.cancel()
            } else {
                val task = baseTasks.remove(id)
                if (task != null) deletePartial(task)
            }
        }
        publish()
        markDirty()
    }

    fun remove(id: String) {
        synchronized(lock) {
            val runtime = runtimes[id]
            if (runtime != null) return
            baseTasks.remove(id)
        }
        publish()
        markDirty()
    }

    fun retry(id: String) = resume(id)

    fun setSpeedLimit(id: String, bytesPerSecond: Long) {
        synchronized(lock) {
            runtimes[id]?.setSpeedLimit(bytesPerSecond)
            baseTasks[id]?.let { baseTasks[id] = it.copy(maxSpeedBps = bytesPerSecond) }
        }
        publish()
        markDirty()
    }

    fun setThreadCount(id: String, count: Int) {
        synchronized(lock) {
            val clamped = count.coerceIn(1, Http.MAX_THREADS)
            runtimes[id]?.setThreadCount(clamped)
            baseTasks[id]?.let { baseTasks[id] = it.copy(threadCount = clamped) }
        }
        publish()
        markDirty()
    }

    fun pauseAll() {
        synchronized(lock) {
            queue.clear()
            runtimes.values.forEach { it.pause() }
            runtimes.forEach { (id, runtime) -> baseTasks[id] = runtime.snapshot() }
            baseTasks.keys.toList().forEach { id ->
                val task = baseTasks[id] ?: return@forEach
                if (task.status.isActive) baseTasks[id] = task.copy(status = TaskStatus.PAUSED)
            }
        }
        publish()
        markDirty()
    }

    fun resumeAll() {
        val ids = synchronized(lock) {
            baseTasks.values
                .filter { it.status == TaskStatus.PAUSED || it.status == TaskStatus.FAILED }
                .map { it.id }
        }
        ids.forEach { resume(it) }
    }

    fun hasActiveTasks(): Boolean = _tasks.value.any { it.status.isActive }

    fun fileFor(task: DownloadTask): File = File(task.targetDir, task.fileName)

    // -------------------------------------------------------------------- internals

    private fun tick() {
        synchronized(lock) {
            runtimes.forEach { (id, runtime) -> baseTasks[id] = runtime.snapshot() }
        }
        publish()
        val now = System.currentTimeMillis()
        if (dirty && now - lastSave >= SAVE_INTERVAL_MS) save()
    }

    private fun startNextLocked() {
        while (queue.isNotEmpty() && activeCountLocked() < MAX_ACTIVE) {
            val id = queue.removeFirst()
            val task = baseTasks[id] ?: continue
            if (task.status == TaskStatus.COMPLETED ||
                task.status == TaskStatus.CANCELED ||
                task.status == TaskStatus.PAUSED
            ) continue
            val existing = runtimes[id]
            if (existing != null) {
                existing.resume()
                continue
            }
            val runtime = TaskRuntime(task) { finished -> onRuntimeFinished(finished) }
            runtimes[id] = runtime
            runtime.start()
        }
    }

    private fun activeCountLocked(): Int = runtimes.values.count { it.isRunning }

    private fun onRuntimeFinished(runtime: TaskRuntime) {
        synchronized(lock) {
            val result = runtime.snapshot()
            runtimes.remove(runtime.id)
            when (result.status) {
                TaskStatus.CANCELED -> baseTasks.remove(runtime.id)
                TaskStatus.COMPLETED -> baseTasks[runtime.id] = result.copy(speedBps = 0L)
                else -> baseTasks[runtime.id] = result
            }
            startNextLocked()
        }
        publish()
        markDirty()
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            baseTasks.values.sortedByDescending { it.createdAt }
        }
        _tasks.value = snapshot
    }

    private fun markDirty() {
        dirty = true
    }

    private fun save() {
        val snapshot = synchronized(lock) { baseTasks.values.toList() }
        store?.save(snapshot)
        dirty = false
        lastSave = System.currentTimeMillis()
    }

    private fun uniqueName(name: String): String {
        val dir = File(downloadDir)
        if (!File(dir, name).exists()) return name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) name.substring(name.lastIndexOf('.')) else ""
        var index = 1
        while (File(dir, "$base ($index)$ext").exists()) index++
        return "$base ($index)$ext"
    }

    private fun deletePartial(task: DownloadTask) {
        runCatching {
            File(task.targetDir, task.fileName + ".flowpart").delete()
        }
    }
}
