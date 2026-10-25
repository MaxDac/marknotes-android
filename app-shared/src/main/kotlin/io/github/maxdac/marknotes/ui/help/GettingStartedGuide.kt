package io.github.maxdac.marknotes.ui.help

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.maxdac.marknotes.shared.R
import io.github.maxdac.marknotes.ui.editor.EditorText
import io.github.maxdac.marknotes.ui.editor.FormattingToolbar
import io.github.maxdac.marknotes.ui.editor.MarkdownEditing
import io.github.maxdac.marknotes.ui.editor.MarkdownPreview

const val GETTING_STARTED_TAG = "getting-started-guide"
const val GETTING_STARTED_SKIP_TAG = "getting-started-skip"
const val DEMO_EDITOR_TAG = "getting-started-demo-editor"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GettingStartedGuide(
    step: Int,
    onStep: (Int) -> Unit,
    demo: TextFieldValue,
    onDemo: (TextFieldValue) -> Unit,
    preview: Boolean,
    onPreview: (Boolean) -> Unit,
    providers: String,
    saveError: Boolean,
    onDismiss: () -> Unit,
    onMarkdown: () -> Unit
) {
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    LaunchedEffect(step, saveError) {
        focus.clearFocus()
        scroll.scrollTo(0)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        BackHandler(onBack = onDismiss)
        Surface(Modifier.fillMaxSize().testTag(GETTING_STARTED_TAG)) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.getting_started_title)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Filled.Close, stringResource(R.string.guide_close))
                            }
                        },
                        actions = {
                            TextButton(onClick = onDismiss, modifier = Modifier.testTag(GETTING_STARTED_SKIP_TAG)) {
                                Text(stringResource(R.string.guide_skip))
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        stringResource(R.string.guide_progress, step + 1, GettingStartedPolicy.LAST_STEP + 1),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    if (saveError) {
                        Text(
                            stringResource(R.string.guide_save_error),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                        )
                    }
                    GuideStage(step, demo, onDemo, preview, onPreview, providers)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onStep(step - 1) }, enabled = step > 0) {
                            Text(stringResource(R.string.guide_previous))
                        }
                        Button(onClick = {
                            if (step == GettingStartedPolicy.LAST_STEP) onDismiss() else onStep(step + 1)
                        }) {
                            Text(
                                stringResource(
                                    if (step ==
                                        GettingStartedPolicy.LAST_STEP
                                    ) {
                                        R.string.guide_finish
                                    } else {
                                        R.string.guide_next
                                    }
                                )
                            )
                        }
                    }
                    if (step == GettingStartedPolicy.LAST_STEP) {
                        TextButton(onClick = onMarkdown) { Text(stringResource(R.string.markdown_help_title)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideStage(
    step: Int,
    demo: TextFieldValue,
    onDemo: (TextFieldValue) -> Unit,
    preview: Boolean,
    onPreview: (Boolean) -> Unit,
    providers: String
) {
    val title = when (step) {
        0 -> R.string.guide_local_title
        1 -> R.string.guide_browser_title
        2 -> R.string.guide_demo_title
        3 -> R.string.guide_drives_title
        else -> R.string.guide_help_title
    }
    HelpHeading(stringResource(title))
    when (step) {
        0 -> Text(stringResource(R.string.guide_local_body, stringResource(R.string.app_name)))

        1 -> {
            Highlight(stringResource(R.string.guide_new_highlight), stringResource(R.string.guide_new_body))
            Highlight(stringResource(R.string.guide_folders_highlight), stringResource(R.string.guide_folders_body))
            Text(stringResource(R.string.guide_import_body, stringResource(R.string.app_name)))
        }

        2 -> {
            Text(stringResource(R.string.guide_demo_privacy))
            TextButton(onClick = { onPreview(!preview) }) {
                Text(stringResource(if (preview) R.string.guide_edit else R.string.guide_preview))
            }
            if (preview) {
                MarkdownPreview(
                    demo.text,
                    notesRoot = null,
                    index = null,
                    onNoteLink = {},
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    allowExternalLinks = false
                )
            } else {
                TextField(
                    value = demo,
                    onValueChange = { value ->
                        val continued = MarkdownEditing.continueList(demo.editorText(), value.editorText())
                        onDemo(continued?.fieldValue() ?: value)
                    },
                    label = { Text(stringResource(R.string.guide_demo_label)) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().height(260.dp).testTag(DEMO_EDITOR_TAG)
                )
                FormattingToolbar(
                    onAction = { onDemo(it(demo.editorText()).fieldValue()) },
                    onPickImage = {},
                    onTakePhoto = {},
                    imagesEnabled = false
                )
            }
            Text(stringResource(R.string.guide_demo_concepts))
        }

        3 -> {
            Text(stringResource(R.string.guide_drives_body))
            Text(stringResource(R.string.guide_providers, providers))
        }

        else -> Text(stringResource(R.string.guide_help_body))
    }
}

@Composable
private fun Highlight(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HelpHeading(title)
        Text(body)
    }
}

private fun TextFieldValue.editorText() = EditorText(text, selection.start, selection.end)

private fun EditorText.fieldValue() =
    TextFieldValue(text, androidx.compose.ui.text.TextRange(selectionStart, selectionEnd))
