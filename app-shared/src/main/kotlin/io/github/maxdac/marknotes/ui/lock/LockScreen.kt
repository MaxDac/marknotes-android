package io.github.maxdac.marknotes.ui.lock

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.maxdac.marknotes.shared.R

const val UNLOCK_TEXT = "Unlock"
const val LOCK_SCREEN_TAG = "lock_screen"

/** The lock screen's title, "<app name> is locked", naming the running app. */
fun lockedTitle(context: Context): String =
    context.getString(R.string.locked_title, context.getString(R.string.app_name))

/**
 * Opaque screen shown above the app while the app lock is locked. The Surface takes every touch, so nothing below it
 * can be used; the caller also clears the semantics of what is below.
 */
@Composable
fun LockScreen(error: CharSequence?, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize().testTag(LOCK_SCREEN_TAG), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
            Text(
                stringResource(R.string.locked_title, stringResource(R.string.app_name)),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                "Unlock with your fingerprint, face or screen lock.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Button(onClick = onUnlock) { Text(UNLOCK_TEXT) }
            error?.let {
                Text(
                    it.toString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
