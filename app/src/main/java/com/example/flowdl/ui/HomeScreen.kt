package com.example.flowdl.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.flowdl.core.DownloadManager
import com.example.flowdl.core.YtDlpDownloadEngine
import com.example.flowdl.core.DownloadTask
import com.example.flowdl.core.TaskStatus
import com.example.flowdl.service.DownloadService
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val tasks by DownloadManager.tasks.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf("All") }

    val activeCount = tasks.count { it.status.isActive }
    val totalSpeed = tasks.filter { it.status == TaskStatus.RUNNING }.sumOf { it.speedBps }
    val hasPaused = tasks.any { it.status == TaskStatus.PAUSED || it.status == TaskStatus.FAILED }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box(
                                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(13.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFF7167FF), Color(0xFF20D9C2)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(23.dp))
                            }
                            Column {
                                Text("FlowDL", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                                Text("YOUR MEDIA. YOUR WAY.", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                },
                actions = {
                    if (activeCount > 0) {
                        IconButton(onClick = { DownloadManager.pauseAll() }) {
                            Icon(Icons.Rounded.Pause, contentDescription = "Pause all")
                        }
                    }
                    if (hasPaused) {
                        IconButton(onClick = {
                            DownloadManager.resumeAll()
                            DownloadService.start(context)
                        }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume all")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New download") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { HeroCard(totalSpeed, activeCount, tasks.size, onAdd = { showAdd = true }) }
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search downloads…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Active", "Completed", "Paused").forEach { filter ->
                        FilterChip(selected = selectedFilter == filter, onClick = { selectedFilter = filter }, label = { Text(filter) })
                    }
                }
            }
            val visibleTasks = tasks.filter { task ->
                val matchesSearch = task.fileName.contains(searchQuery, ignoreCase = true) || task.url.contains(searchQuery, ignoreCase = true)
                val matchesFilter = when (selectedFilter) {
                    "Active" -> task.status.isActive
                    "Completed" -> task.status == TaskStatus.COMPLETED
                    "Paused" -> task.status == TaskStatus.PAUSED || task.status == TaskStatus.FAILED
                    else -> true
                }
                matchesSearch && matchesFilter
            }
            if (visibleTasks.isEmpty()) {
                item { if (tasks.isEmpty()) EmptyState() else FilterEmptyState() }
            }
            items(visibleTasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onToggle = {
                        when (task.status) {
                            TaskStatus.COMPLETED -> openTask(context, task)
                            TaskStatus.RUNNING, TaskStatus.CONNECTING -> DownloadManager.pause(task.id)
                            TaskStatus.QUEUED -> DownloadManager.cancel(task.id)
                            else -> {
                                DownloadManager.resume(task.id)
                                DownloadService.start(context)
                            }
                        }
                    },
                    onCancel = {
                        if (task.status == TaskStatus.COMPLETED) {
                            DownloadManager.remove(task.id)
                        } else {
                            DownloadManager.cancel(task.id)
                        }
                    },
                    onSpeedChange = { DownloadManager.setSpeedLimit(task.id, it) },
                    onThreadsChange = { DownloadManager.setThreadCount(task.id, it) }
                )
            }
        }
    }

    if (showAdd) {
        AddDownloadSheet(
            onDismiss = { showAdd = false },
            onAdd = { url, name, threads, limit, engineFormatId ->
                if (engineFormatId != null) {
                    Toast.makeText(context, "yt-dlp download started", Toast.LENGTH_SHORT).show()
                    YtDlpDownloadEngine.download(
                        context = context,
                        pageUrl = url,
                        formatId = engineFormatId,
                        outputName = name,
                        onProgress = { _, _, _ -> },
                        onFinished = { result ->
                            Handler(Looper.getMainLooper()).post {
                                val message = result.fold(
                                    { "Download completed in FlowDL storage" },
                                    { "yt-dlp download failed: ${it.message ?: "unknown error"}" }
                                )
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                } else {
                    val id = DownloadManager.add(url, name.ifBlank { null }, threads, limit)
                    if (id != null) DownloadService.start(context)
                }
                showAdd = false
            }
        )
    }
}

@Composable
private fun HeroCard(speed: Long, active: Int, total: Int, onAdd: () -> Unit) {
    val gradient = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary
        )
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(gradient)
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = Color(0xFFBFFCF3), modifier = Modifier.size(17.dp))
                Text("DOWNLOAD STUDIO", style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.82f), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text("Everything you save,\nin one place.", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold, color = Color.White, lineHeight = MaterialTheme.typography.headlineSmall.lineHeight)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatSpeed(speed),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "live",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("$active active")
                Pill("$total saved")
                Spacer(Modifier.weight(1f))
                Surface(onClick = onAdd, shape = RoundedCornerShape(15.dp), color = Color.White) {
                    Row(modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = Color(0xFF29275F), modifier = Modifier.size(18.dp))
                        Text("Add link", color = Color(0xFF29275F), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Composable
private fun FilterEmptyState() {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(10.dp))
        Text("Nothing matches", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Try another search or filter.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("No downloads yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Paste a direct link. FlowDL splits it into parallel ranges,\nlimits each file independently and resumes after a pause.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun openTask(context: Context, task: DownloadTask) {
    val file = File(task.targetDir, task.fileName)
    if (!file.exists()) {
        Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
        return
    }
    val extension = task.fileName.substringAfterLast('.', "").lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Open with"))
    }.onFailure {
        Toast.makeText(context, it.message ?: "Cannot open file", Toast.LENGTH_SHORT).show()
    }
}
