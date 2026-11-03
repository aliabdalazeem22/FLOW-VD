package com.example.flowdl.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.flowdl.core.Http
import com.example.flowdl.core.MediaExtractionResult
import com.example.flowdl.core.MediaExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDownloadSheet(
    onDismiss: () -> Unit,
    onAdd: (url: String, name: String, threads: Int, limitBps: Long, engineFormatId: String?) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var threads by rememberSaveable { mutableStateOf(4) }
    var fraction by rememberSaveable { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var extraction by remember { mutableStateOf<MediaExtractionResult?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var selectedIsManifest by remember { mutableStateOf(false) }
    var selectedFormatId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 24.dp)
        ) {
            Text("New download", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Direct HTTP(S) links that advertise Range support are split into parallel chunks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    error = null
                    extraction = null
                    selectedIsManifest = false
                    selectedFormatId = null
                },
                label = { Text("File URL") },
                placeholder = { Text("https://example.com/file.zip") },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val trimmed = url.trim()
                    if (!Http.isSupported(trimmed)) {
                        error = "Enter a valid http:// or https:// link"
                    } else {
                        analyzing = true
                        error = null
                        extraction = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { MediaExtractor.extract(trimmed) } }
                                .onSuccess { extraction = it }
                                .onFailure { error = it.message ?: "Could not inspect this link" }
                            analyzing = false
                        }
                    }
                },
                enabled = !analyzing,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (analyzing) "Analyzing link…" else "Analyze media / find qualities") }

            extraction?.let { result ->
                Spacer(Modifier.height(12.dp))
                Text(result.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Source: ${result.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                result.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                result.formats.forEach { format ->
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            url = format.url
                            name = result.title
                            selectedIsManifest = format.isManifest
                            selectedFormatId = format.engineFormatId
                            error = if (format.isManifest) "This is an adaptive-stream manifest. Choose a yt-dlp format when available; direct HLS/DASH merging is not handled by the basic HTTP downloader." else null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(format.label + if (format.isManifest) " · manifest" else "")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("File name (optional)") },
                placeholder = { Text("auto-detected") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))

            Row {
                Text("Connections", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text("$threads", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = threads.toFloat(),
                onValueChange = { threads = it.roundToInt() },
                valueRange = 1f..8f,
                steps = 6
            )

            Row {
                Text("Speed limit", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                val limit = fractionToBps(fraction)
                Text(
                    if (limit == 0L) "Unlimited" else formatSpeed(limit),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = fraction,
                onValueChange = { fraction = it }
            )

            if (error != null) {
                Text(
                    error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    val trimmed = url.trim()
                    if (!Http.isSupported(trimmed)) {
                        error = "Enter a valid http:// or https:// link"
                    } else if (selectedIsManifest) {
                        error = "Choose a downloadable format rather than a raw HLS/DASH manifest."
                    } else {
                        onAdd(trimmed, name.trim(), threads, fractionToBps(fraction), selectedFormatId)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start download")
            }
        }
    }
}
