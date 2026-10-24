package io.github.maxdac.marknotes.ui.chords

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

const val ENABLE_CHORDS_DIALOG_TAG = "enable-chords-dialog"

/**
 * Asks whether to turn on the Chord sequences setting before opening a chord sequence while it is off. [onEnable]
 * should turn the setting on and open the note; [onDismiss] leaves the note closed.
 */
@Composable
fun EnableChordSequencesDialog(onEnable: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ENABLE_CHORDS_DIALOG_TAG),
        title = { Text("Enable chord sequences?") },
        text = {
            Text(
                "This note is a chord sequence. Chord sequences are turned off in Settings. Turn them on to open " +
                    "it in the chord editor?"
            )
        },
        confirmButton = { TextButton(onClick = onEnable) { Text("Yes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("No") } }
    )
}
