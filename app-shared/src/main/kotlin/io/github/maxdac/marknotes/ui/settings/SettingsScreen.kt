package io.github.maxdac.marknotes.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.maxdac.marknotes.AppContainer
import io.github.maxdac.marknotes.about.MarkNotesPeer
import io.github.maxdac.marknotes.appContainer
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.notes.Attachments
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.LockTimeout
import io.github.maxdac.marknotes.data.ThemeColor
import io.github.maxdac.marknotes.data.ThemeMode
import io.github.maxdac.marknotes.security.DeviceAuth
import io.github.maxdac.marknotes.shared.R
import io.github.maxdac.marknotes.sync.DriveStatus
import io.github.maxdac.marknotes.sync.SyncManager
import io.github.maxdac.marknotes.ui.MarkNotesLogo
import io.github.maxdac.marknotes.ui.swatch
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val NO_DRIVES_TEXT = "No drives connected. Notes are only stored on this device."
const val CHORD_SEQUENCES_SWITCH_TAG = "chord-sequences-switch"
const val DELETE_ALL_LOCAL_DATA_TEXT = "Delete all local data"
const val DELETE_LOCAL_NOTES_TEXT = "Also delete the notes stored on this device"
const val TURN_ON_APP_LOCK_TEXT = "Turn on app lock"
const val TURN_OFF_APP_LOCK_TEXT = "Turn off app lock"
const val LOCK_NOW_TEXT = "Lock now"
const val LOCK_AFTER_INACTIVITY_TEXT = "Lock after inactivity"
const val SECURE_SCREEN_TEXT = "Hide notes in Recents and block screenshots"
const val NO_SCREEN_LOCK_TEXT =
    "Set a screen lock (PIN, pattern or password) in the device settings to use the app lock."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onAddDrive: () -> Unit,
    onOpenConflicts: () -> Unit,
    onBack: () -> Unit,
    onLocalDataDeleted: () -> Unit = onBack
) {
    val container = LocalContext.current.appContainer
    val syncManager = container.syncManager
    val state by syncManager.state.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<DriveStatus?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppearanceSection(container.settings)

            HorizontalDivider()
            ChordSequencesSection(container.settings)

            HorizontalDivider()
            Text("Drives", style = MaterialTheme.typography.titleMedium)
            if (state.drives.isEmpty()) Text(NO_DRIVES_TEXT)
            state.drives.forEach { drive ->
                DriveCard(drive, enabled = !state.running, onRemove = { removing = drive })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAddDrive, enabled = !state.running) { Text("Add drive") }
                Button(onClick = syncManager::syncNow, enabled = state.hasDrives && !state.running) {
                    Text("Sync now")
                }
            }
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (state.conflicts.isNotEmpty()) {
                Button(onClick = onOpenConflicts) { Text("Resolve ${state.conflicts.size} conflict(s)") }
            }

            HorizontalDivider()
            ImagesSection(container, enabled = !state.running)

            HorizontalDivider()
            PrivacySection(syncManager, enabled = !state.running, onDeleted = onLocalDataDeleted)
            AppLockSection(container)

            HorizontalDivider()
            DiagnosticsSection(container.diagnostics)

            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MarkNotesLogo(size = 48.dp)
                Text("MarkNotes", style = MaterialTheme.typography.titleLarge)
            }
            container.connectors.values.mapNotNull { it.notice }.forEach { notice ->
                Text(notice, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "MarkNotes is free software (GPL-3.0-or-later). Sync happens on app launch and when you tap " +
                    "\"Sync now\"; there is no background polling.",
                style = MaterialTheme.typography.bodySmall
            )
            TipSection()
            OtherAppsSection(MarkNotesPeer.others(container.edition))
        }
    }

    removing?.let { drive ->
        RemoveDriveDialog(
            drive,
            lastDrive = state.drives.size == 1,
            syncManager = syncManager,
            onDismiss = { removing = null },
            onLocalDataDeleted = onLocalDataDeleted
        )
    }
}

@Composable
private fun TipSection() {
    val context = LocalContext.current
    var error by remember { mutableStateOf<Int?>(null) }
    Text(stringResource(R.string.tip_description), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        error = null
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/maxdac")))
        } catch (_: ActivityNotFoundException) {
            error = R.string.tip_no_browser
        } catch (_: SecurityException) {
            error = R.string.tip_browser_blocked
        }
    }) {
        Text(stringResource(R.string.leave_a_tip))
    }
    error?.let {
        Text(
            stringResource(it),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun OtherAppsSection(peers: List<MarkNotesPeer>) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<Int?>(null) }
    Text(stringResource(R.string.other_apps_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.other_apps_description), style = MaterialTheme.typography.bodySmall)
    peers.forEach { peer ->
        TextButton(onClick = {
            error = null
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(peer.url)))
            } catch (_: ActivityNotFoundException) {
                error = R.string.other_apps_no_browser
            } catch (_: SecurityException) {
                error = R.string.other_apps_browser_blocked
            }
        }) {
            Text(peer.label)
        }
    }
    error?.let {
        Text(
            stringResource(it),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSection(settings: AppSettings) {
    val mode by settings.themeMode.collectAsStateWithLifecycle()
    val color by settings.themeColor.collectAsStateWithLifecycle()
    Text("Appearance", style = MaterialTheme.typography.titleMedium)
    Text("Theme", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { settings.setThemeMode(option) },
                label = { Text(option.label) }
            )
        }
    }
    Text("Colour: ${color.label}", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        settings.availableColors.forEach { option ->
            ColorSwatch(option, selected = option == color) { settings.setThemeColor(option) }
        }
    }
}

@Composable
private fun ChordSequencesSection(settings: AppSettings) {
    val enabled by settings.chordSequencesEnabled.collectAsStateWithLifecycle()
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch, onValueChange = settings::setChordSequencesEnabled)
            .testTag(CHORD_SEQUENCES_SWITCH_TAG),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Chord sequences", style = MaterialTheme.typography.titleMedium)
            Text(
                "Create and edit guitar chord sequences. This setting is kept on this device only.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(checked = enabled, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ColorSwatch(color: ThemeColor, selected: Boolean, onClick: () -> Unit) {
    val fill = if (color == ThemeColor.DYNAMIC) {
        Modifier.background(
            Brush.sweepGradient(ThemeColor.entries.drop(1).map { it.swatch() } + ThemeColor.BLUE.swatch()),
            CircleShape
        )
    } else {
        Modifier.background(color.swatch(), CircleShape)
    }
    Box(
        Modifier
            .size(44.dp)
            .then(fill)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = color.label
                this.selected = selected
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            selected -> Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
            color == ThemeColor.DYNAMIC -> Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
private fun DriveCard(drive: DriveStatus, enabled: Boolean, onRemove: () -> Unit) {
    val account = drive.account
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(account.provider.displayName, style = MaterialTheme.typography.titleSmall)
            Text(account.label, style = MaterialTheme.typography.bodyMedium)
            Text("Folder: /${account.syncFolder}", style = MaterialTheme.typography.bodySmall)
            if (drive.lastSyncAt > 0) {
                Text(
                    "Last sync: " + DateUtils.getRelativeTimeSpanString(drive.lastSyncAt),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            drive.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            drive.unavailableReason?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun ImagesSection(container: AppContainer, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Text("Images", style = MaterialTheme.typography.titleMedium)
    Text(
        "Images added to notes are kept in the attachments folder and synced to every drive.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedButton(onClick = { confirming = true }, enabled = enabled && !cleaning) { Text("Clean unused images") }
    if (cleaning) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Clean unused images?") },
            text = {
                Text(
                    "Images not referenced by any note will be deleted from this device, and from your drives " +
                        "on the next sync."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    cleaning = true
                    scope.launch {
                        message = try {
                            val deleted = withContext(Dispatchers.IO) { Attachments.deleteUnused(container.notes) }
                            if (deleted.isNotEmpty()) container.notifyLocalChanged()
                            when (deleted.size) {
                                0 -> "No unused images found."
                                1 -> "Deleted 1 unused image."
                                else -> "Deleted ${deleted.size} unused images."
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            container.diagnostics.report(DiagnosticCode.IMAGE_IMPORT, e)
                            "Could not clean images: ${e.message}"
                        }
                        cleaning = false
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RemoveDriveDialog(
    drive: DriveStatus,
    lastDrive: Boolean,
    syncManager: SyncManager,
    onDismiss: () -> Unit,
    onLocalDataDeleted: () -> Unit
) {
    var deleteLocal by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect ${drive.account.provider.displayName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val keep = if (lastDrive) {
                    "Nothing is deleted from the drive."
                } else {
                    "Notes stay on this device and on the drive."
                }
                Text("${drive.account.label} will no longer be synced and you will be signed out. $keep")
                if (lastDrive) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = deleteLocal, role = Role.Checkbox) { deleteLocal = it },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = deleteLocal, onCheckedChange = null)
                        Text(DELETE_LOCAL_NOTES_TEXT, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val wipe = lastDrive && deleteLocal
                onDismiss()
                syncManager.removeDrive(drive.account, deleteLocalData = wipe)
                if (wipe) onLocalDataDeleted()
            }) { Text("Disconnect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PrivacySection(syncManager: SyncManager, enabled: Boolean, onDeleted: () -> Unit) {
    val diagnostics = LocalContext.current.appContainer.diagnostics
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Privacy", style = MaterialTheme.typography.titleMedium)
    Text(
        "Notes are stored in this app's private storage, which Android encrypts with your device's screen lock.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedButton(
        onClick = { confirming = true },
        enabled = enabled && !deleting,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) { Text(DELETE_ALL_LOCAL_DATA_TEXT) }
    if (deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete all local data?") },
            text = {
                Text(
                    "Every drive is disconnected, then the notes, images and sync data stored on this device are " +
                        "deleted. Nothing is deleted from the drives: connect a drive again to download your notes. " +
                        "Notes that were never synced are lost."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        deleting = true
                        error = null
                        scope.launch {
                            val deleted = try {
                                syncManager.deleteAllLocalData()
                                true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                diagnostics.report(DiagnosticCode.LOCAL_DATA_DELETE, e)
                                error = "Could not delete the local data: ${e.message ?: e.javaClass.simpleName}"
                                false
                            } finally {
                                deleting = false
                            }
                            // Navigation must run on the main thread, whatever thread the wipe resumed on.
                            if (deleted) withContext(Dispatchers.Main) { onDeleted() }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppLockSection(container: AppContainer) {
    val context = LocalContext.current
    val settings = container.settings
    val appLock = container.appLock
    val lockEnabled by settings.appLockEnabled.collectAsStateWithLifecycle()
    val timeout by settings.lockTimeout.collectAsStateWithLifecycle()
    val secureScreen by settings.secureScreen.collectAsStateWithLifecycle()
    var deviceSecure by remember { mutableStateOf(DeviceAuth.isDeviceSecure(context)) }
    // A screen lock set up in the device settings meanwhile enables the button when coming back.
    LifecycleResumeEffect(Unit) {
        deviceSecure = DeviceAuth.isDeviceSecure(context)
        onPauseOrDispose {}
    }
    var error by remember { mutableStateOf<CharSequence?>(null) }

    // Turning the lock on or off needs the same biometrics or screen lock that unlock the app.
    fun authenticateThen(title: String, action: () -> Unit) {
        if (!appLock.beginAuthentication()) return
        error = null
        DeviceAuth.authenticate(context, title) { success, message ->
            appLock.endAuthentication(success)
            if (success) action() else error = message
        }
    }

    Text("App lock", style = MaterialTheme.typography.labelLarge)
    Text(
        "Ask for your fingerprint, face or screen lock to open MarkNotes, and lock it after a time without use.",
        style = MaterialTheme.typography.bodySmall
    )
    if (lockEnabled) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                authenticateThen(TURN_OFF_APP_LOCK_TEXT) {
                    settings.setAppLockEnabled(false)
                    appLock.onEnabledChanged()
                }
            }) { Text(TURN_OFF_APP_LOCK_TEXT) }
            OutlinedButton(onClick = appLock::lock) { Text(LOCK_NOW_TEXT) }
        }
        Text(LOCK_AFTER_INACTIVITY_TEXT, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LockTimeout.entries.forEach { option ->
                FilterChip(
                    selected = timeout == option,
                    onClick = { settings.setLockTimeout(option) },
                    label = { Text(option.label) }
                )
            }
        }
    } else {
        Button(
            onClick = {
                authenticateThen(TURN_ON_APP_LOCK_TEXT) {
                    settings.setAppLockEnabled(true)
                    settings.setSecureScreen(true)
                    appLock.onEnabledChanged()
                }
            },
            enabled = deviceSecure
        ) { Text(TURN_ON_APP_LOCK_TEXT) }
        if (!deviceSecure) Text(NO_SCREEN_LOCK_TEXT, style = MaterialTheme.typography.bodySmall)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = secureScreen, role = Role.Switch) { settings.setSecureScreen(it) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(SECURE_SCREEN_TEXT, Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = secureScreen, onCheckedChange = null)
    }
    error?.let {
        Text(it.toString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
