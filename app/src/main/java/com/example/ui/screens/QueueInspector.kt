package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.queue.QueueSource
import com.example.ui.viewmodel.MusicPlayerViewModel

/** Debug-only diagnostics; callers must gate this screen with BuildConfig.DEBUG. */
@Composable
fun QueueInspector(viewModel: MusicPlayerViewModel, onClose: () -> Unit) {
    val state by viewModel.queueState.collectAsState()
    val logs by viewModel.queueDebugLogs.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Queue Inspector") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Session ${state.sessionId.take(8)} • Queue ${state.queueId.take(8)}\n${state.source} • ${state.lastValidation}") }
                item { Text("Current: ${state.current?.title ?: "Idle"} | ${state.position + 1}/${state.visible.size}\nShuffle: ${state.shuffle} • Repeat: ${state.repeat}") }
                item { QueueBucketText("History", state.history.map { it.title }) }
                item { QueueBucketText("Manual (priority)", state.manual.map { it.title }) }
                item { QueueBucketText("Context", state.context.map { it.title }) }
                item { QueueBucketText("Autoplay", state.autoplay.map { it.title }) }
                item {
                    Column {
                        TextButton(onClick = { viewModel.validateQueueForDebug() }) { Text("Validate") }
                        TextButton(onClick = { viewModel.clearQueueForDebug() }) { Text("Clear queue") }
                        TextButton(onClick = { viewModel.clearQueueLogsForDebug() }) { Text("Clear logs") }
                        TextButton(onClick = { viewModel.forceQueueRegenerationForDebug() }) { Text("Regenerate") }
                        TextButton(onClick = { viewModel.restoreQueueSessionForDebug() }) { Text("Restore") }
                        QueueSource.entries.forEach { source -> TextButton(onClick = { viewModel.simulateQueueForDebug(source) }) { Text("Sim ${source.name.take(6)}") } }
                    }
                }
                item { Text("Last ${logs.size} events") }
                items(logs.reversed()) { log -> Text("${log.event} • ${log.trigger} • ${log.validation}", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable private fun QueueBucketText(name: String, songs: List<String>) = Text("$name: ${if (songs.isEmpty()) "Empty" else songs.joinToString()}")
