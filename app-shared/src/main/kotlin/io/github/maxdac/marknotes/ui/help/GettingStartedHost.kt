package io.github.maxdac.marknotes.ui.help

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.shared.R

@Composable
internal fun GettingStartedHost(
    settings: AppSettings,
    replay: Boolean,
    onReplayFinished: () -> Unit,
    unlocked: Boolean,
    safeDestination: Boolean,
    pendingShare: Boolean,
    providers: String,
    onMarkdown: () -> Unit
) {
    val consumed by settings.gettingStartedConsumed.collectAsStateWithLifecycle()
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    var step by rememberSaveable { mutableIntStateOf(0) }
    val seed = stringResource(R.string.guide_demo_source)
    val practice = viewModel { PracticeEditorModel(seed) }
    var saveError by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(unlocked) {
        if (!unlocked) practice.reset()
    }
    val dismiss = {
        if (practice.finish { settings.setGettingStartedConsumed(true) }) {
            onReplayFinished()
            step = 0
            saveError = false
        } else {
            saveError = true
        }
    }
    if (GettingStartedPolicy.shouldShow(consumed, replay, unlocked, resumed, safeDestination, pendingShare)) {
        GettingStartedGuide(
            step = step,
            onStep = { step = it.coerceIn(0, GettingStartedPolicy.LAST_STEP) },
            demo = practice.value,
            onDemo = { practice.value = it },
            preview = practice.preview,
            onPreview = { practice.preview = it },
            providers = providers,
            saveError = saveError,
            onDismiss = dismiss,
            onMarkdown = {
                dismiss()
                if (!saveError) onMarkdown()
            }
        )
    }
}

/** Activity-scoped memory only: survives rotation, never enters saved state or the note pipeline. */
internal class PracticeEditorModel(private val seed: String) : ViewModel() {
    var value by mutableStateOf(TextFieldValue(seed))
    var preview by mutableStateOf(false)

    fun reset() {
        value = TextFieldValue(seed)
        preview = false
    }

    fun finish(persist: () -> Boolean): Boolean {
        if (!persist()) return false
        reset()
        return true
    }
}
