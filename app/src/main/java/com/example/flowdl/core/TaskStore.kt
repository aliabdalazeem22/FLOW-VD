package com.example.flowdl.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Atomic JSON persistence: survives process death, keeps every chunk cursor. */
class TaskStore(private val file: File) {

    fun load(): List<DownloadTask> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        DownloadTask(
                            id = o.getString("id"),
                            url = o.getString("url"),
                            fileName = o.getString("fileName"),
                            targetDir = o.getString("targetDir"),
                            status = runCatching {
                                TaskStatus.valueOf(o.getString("status"))
                            }.getOrDefault(TaskStatus.PAUSED),
                            totalBytes = o.optLong("totalBytes", -1L),
                            downloadedBytes = o.optLong("downloadedBytes", 0L),
                            maxSpeedBps = o.optLong("maxSpeedBps", 0L),
                            threadCount = o.optInt("threadCount", 4),
                            connections = parseConnections(o.optJSONArray("connections")),
                            error = if (o.isNull("error")) null else o.optString("error"),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                            supportsRanges = o.optBoolean("supportsRanges", true)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseConnections(array: JSONArray?): List<Connection> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val c = array.getJSONArray(i)
                add(
                    Connection(
                        id = i + 1,
                        start = c.getLong(0),
                        end = c.getLong(1),
                        current = c.getLong(2),
                        active = false
                    )
                )
            }
        }
    }

    fun save(tasks: List<DownloadTask>) {
        val array = JSONArray()
        tasks.forEach { t ->
            val o = JSONObject()
            o.put("id", t.id)
            o.put("url", t.url)
            o.put("fileName", t.fileName)
            o.put("targetDir", t.targetDir)
            o.put("status", t.status.name)
            o.put("totalBytes", t.totalBytes)
            o.put("downloadedBytes", t.downloadedBytes)
            o.put("maxSpeedBps", t.maxSpeedBps)
            o.put("threadCount", t.threadCount)
            o.put("createdAt", t.createdAt)
            o.put("supportsRanges", t.supportsRanges)
            if (t.error != null) o.put("error", t.error) else o.put("error", JSONObject.NULL)
            val conns = JSONArray()
            t.connections.forEach { c ->
                conns.put(JSONArray().put(c.start).put(c.end).put(c.current))
            }
            o.put("connections", conns)
            array.put(o)
        }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(array.toString())
                tmp.delete()
            }
        }
    }
}
