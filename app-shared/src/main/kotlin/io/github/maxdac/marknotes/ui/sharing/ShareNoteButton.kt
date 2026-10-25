package io.github.maxdac.marknotes.ui.sharing

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun ShareNoteButton(title: String, body: String, onError: (String) -> Unit) {
    val context = LocalContext.current
    IconButton(onClick = {
        try {
            context.startActivity(noteShareIntent(title, body))
        } catch (_: ActivityNotFoundException) {
            onError("No app is available to share this note. Install an app that accepts text and try again.")
        } catch (_: SecurityException) {
            onError("Sharing is not allowed on this device.")
        }
    }) {
        Icon(Icons.Filled.Share, contentDescription = "Share note")
    }
}

internal fun noteShareIntent(title: String, body: String): Intent = Intent.createChooser(
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, body)
    },
    "Share note"
)
