package io.github.maxdac.marknotes.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.diagnostics.IssueDraft
import io.github.maxdac.marknotes.diagnostics.AndroidDiagnostics
import java.io.IOException
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsSection(diagnostics: AndroidDiagnostics) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appLock = context.appContainer.appLock
    val locked by appLock.locked.collectAsStateWithLifecycle()
    val storageFailed by diagnostics.storageFailed.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf<IssueDraft?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(locked) {
        if (locked) {
            draft = null
            exporting = false
            clearing = false
        }
    }

    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
    Text(
        "Error logging is on. Only fixed operation and failure categories are recorded, never notes, " +
            "paths, accounts, credentials or exception messages. Logs and exports use at most 5 MiB in total " +
            "and are pruned after 7 days on startup, recording or export. Older events may be removed sooner.",
        style = MaterialTheme.typography.bodySmall
    )
    if (storageFailed) {
        Text(
            "Some errors could not be recorded. Diagnostic storage may be unavailable.",
            color = MaterialTheme.colorScheme.error
        )
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    OutlinedButton(
        enabled = !busy && !locked,
        onClick = {
            busy = true
            message = null
            scope.launch {
                try {
                    val prepared = diagnostics.draft()
                    if (!appLock.locked.value) draft = prepared
                } catch (_: IOException) {
                    message = "Could not read the logs. You can clear logs and try again."
                } catch (_: SecurityException) {
                    message = "Diagnostic storage is not accessible."
                } finally {
                    busy = false
                }
            }
        }
    ) { Text("Report a problem") }
    OutlinedButton(enabled = !busy && !locked, onClick = { exporting = true }) { Text("Export full sanitized logs") }
    OutlinedButton(enabled = !busy && !locked, onClick = { clearing = true }) { Text("Clear logs") }

    draft?.let { review ->
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text("Review problem report") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(review.body, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Opening the draft sends this excerpt to GitHub in the URL BEFORE you submit. " +
                            "It can be retained in browser history, browser sync and server logs. " +
                            "The issue is public after you submit it. Review and edit it in your browser; " +
                            "the app does not submit it for you."
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    draft = null
                    try {
                        if (!appLock.locked.value) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(review.url)))
                            message = "Draft opened in browser; no issue was submitted by the app."
                        }
                    } catch (_: ActivityNotFoundException) {
                        message = "No browser is available. Export the logs instead."
                    } catch (_: SecurityException) {
                        message = "Could not open the browser. Export the logs instead."
                    }
                }) { Text("Open draft in browser") }
            },
            dismissButton = { TextButton(onClick = { draft = null }) { Text("Cancel") } }
        )
    }
    if (exporting) {
        AlertDialog(
            onDismissRequest = { exporting = false },
            title = { Text("Export sanitized logs?") },
            text = {
                Text(
                    "Share the full sanitized text with an app you choose. That app can keep or upload a copy. " +
                        "No files are attached to the GitHub draft automatically. Clear logs or another export " +
                        "invalidates the local shared file, not copies already received by another app."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    exporting = false
                    busy = true
                    scope.launch {
                        try {
                            val intent = diagnostics.shareIntent()
                            if (!appLock.locked.value) {
                                context.startActivity(Intent.createChooser(intent, "Share sanitized logs"))
                                message = "Choose a receiving app. Delivery is not confirmed."
                            }
                        } catch (_: IOException) {
                            message = "Could not export the logs."
                        } catch (_: IllegalArgumentException) {
                            message = "The diagnostic file could not be shared."
                        } catch (_: ActivityNotFoundException) {
                            message = "No app is available to share logs."
                        } catch (_: SecurityException) {
                            message = "The diagnostic file is not accessible."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Choose app") }
            },
            dismissButton = { TextButton(onClick = { exporting = false }) { Text("Cancel") } }
        )
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text("Clear logs?") },
            text = { Text("Delete local error logs and shared snapshots. Future error logging stays on.") },
            confirmButton = {
                TextButton(onClick = {
                    clearing = false
                    busy = true
                    scope.launch {
                        try {
                            diagnostics.clear()
                            draft = null
                            message = "Logs cleared. Future error logging stays on."
                        } catch (_: IOException) {
                            message = "Could not clear all logs."
                        } catch (_: SecurityException) {
                            message = "Diagnostic storage is not accessible."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Clear logs now") }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } }
        )
    }
}
