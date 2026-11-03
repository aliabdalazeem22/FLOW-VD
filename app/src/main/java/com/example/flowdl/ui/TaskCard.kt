package com.example.flowdl.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.flowdl.core.DownloadTask
import com.example.flowdl.core.TaskStatus
import java.io.File
import kotlin.math.roundToInt

@Composable
fun TaskCard(
    task: DownloadTask,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
    onSpeedChange: (Long) -> Unit,
    onThreadsChange: (Int) -> Unit
) {
    var expanded by rememberSaveable(task.id) { mutableStateOf(false) }
    var fraction by remember(task.id) { mutableFloatStateOf(limitToFraction(task.maxSpeedBps)) }
    val accent = statusColor(task.status)

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = hostOf(task.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(task.status, accent)
            }

            Spacer(Modifier.height(14.dp))

            val indeterminate = task.totalBytes <= 0L && task.status.isActive
            if (indeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.15f)
                )
            } else {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.15f)
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${formatBytes(task.downloadedBytes)} / " +
                        if (task.totalBytes > 0L) formatBytes(task.totalBytes) else "unknown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (task.status == TaskStatus.RUNNING) {
                    Text(
                        text = formatSpeed(task.speedBps),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accent
                    )
                } else if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.QUEUED) {
                    Text("queued", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (task.status == TaskStatus.FAILED && !task.error.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = task.error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.status == TaskStatus.COMPLETED) {
                    FilledTonalIconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.OpenInNew, contentDescription = "Open file")
                    }
                } else {
                    FilledTonalIconButton(onClick = onToggle, modifier = Modifier.size(44.dp)) {
                        when (task.status) {
                            TaskStatus.RUNNING, TaskStatus.CONNECTING ->
                                Icon(Icons.Rounded.Pause, contentDescription = "Pause")
                            TaskStatus.QUEUED ->
                                Icon(Icons.Rounded.Close, contentDescription = "Cancel")
                            else ->
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume")
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedIconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = if (task.status == TaskStatus.COMPLETED) "Remove" else "Cancel"
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${(task.progress * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Less" else "More"
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Speed limit", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        val shown = fractionToBps(fraction)
                        Text(
                            text = if (shown == 0L) "Unlimited" else formatSpeed(shown),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = fraction,
                        onValueChange = { fraction = it },
                        onValueChangeFinished = { onSpeedChange(fractionToBps(fraction)) }
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Connections", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { onThreadsChange(task.threadCount - 1) },
                            enabled = task.threadCount > 1,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = "Fewer connections")
                        }
                        Text(
                            text = "${task.threadCount}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                        IconButton(
                            onClick = { onThreadsChange(task.threadCount + 1) },
                            enabled = task.threadCount < 8,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "More connections")
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        InfoItem("Mode", if (task.supportsRanges) "Multi-part" else "Single stream")
                        InfoItem("Chunks", "${task.connections.size}")
                        if (task.etaSeconds >= 0L) {
                            InfoItem("ETA", formatEta(task.etaSeconds))
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = File(task.targetDir, task.fileName).absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatusPill(status: TaskStatus, color: Color) {
    val label = when (status) {
        TaskStatus.QUEUED -> "Queued"
        TaskStatus.CONNECTING -> "Linking"
        TaskStatus.RUNNING -> "Active"
        TaskStatus.PAUSED -> "Paused"
        TaskStatus.COMPLETED -> "Done"
        TaskStatus.FAILED -> "Failed"
        TaskStatus.CANCELED -> "Canceled"
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun statusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.RUNNING, TaskStatus.CONNECTING -> MaterialTheme.colorScheme.primary
    TaskStatus.QUEUED -> MaterialTheme.colorScheme.tertiary
    TaskStatus.PAUSED -> Color(0xFFB26A00)
    TaskStatus.COMPLETED -> Color(0xFF1B8A4B)
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
    TaskStatus.CANCELED -> MaterialTheme.colorScheme.outline
}

private fun hostOf(url: String): String = runCatching {
    java.net.URI(url).host ?: url
}.getOrDefault(url)
