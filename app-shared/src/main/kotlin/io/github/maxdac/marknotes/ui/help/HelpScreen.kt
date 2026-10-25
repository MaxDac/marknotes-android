package io.github.maxdac.marknotes.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.maxdac.marknotes.shared.R
import io.github.maxdac.marknotes.ui.editor.MarkdownPreview

@Composable
fun HelpScreen(onReplay: () -> Unit, onMarkdown: () -> Unit, onBack: () -> Unit) {
    HelpPage(stringResource(R.string.help_title), onBack) {
        Text(stringResource(R.string.help_intro))
        TextButton(onClick = onReplay) { Text(stringResource(R.string.getting_started_title)) }
        TextButton(onClick = onMarkdown) { Text(stringResource(R.string.markdown_help_title)) }
    }
}

@Composable
fun MarkdownHelpScreen(onBack: () -> Unit) {
    HelpPage(stringResource(R.string.markdown_help_title), onBack) {
        Text(stringResource(R.string.markdown_intro))
        Text(stringResource(R.string.markdown_preview_safety))
        MarkdownExample(R.string.markdown_basic_title, R.string.markdown_basic_source)
        MarkdownExample(R.string.markdown_blocks_title, R.string.markdown_blocks_source)
        MarkdownExample(R.string.markdown_extensions_title, R.string.markdown_extensions_source)
        HelpHeading(stringResource(R.string.markdown_controls_title))
        Text(stringResource(R.string.markdown_controls))
        HelpHeading(stringResource(R.string.markdown_code_title))
        Text(stringResource(R.string.markdown_code))
        MarkdownExample(R.string.markdown_code_example_title, R.string.markdown_code_source)
        HelpHeading(stringResource(R.string.markdown_marknotes_title))
        Text(stringResource(R.string.markdown_marknotes))
        MarkdownExample(R.string.markdown_links_title, R.string.markdown_links_source)
        Text(stringResource(R.string.markdown_images))
        Text(stringResource(R.string.markdown_image_source), fontFamily = FontFamily.Monospace)
        HelpHeading(stringResource(R.string.markdown_limits_title))
        Text(stringResource(R.string.markdown_limits))
    }
}

@Composable
internal fun HelpHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun MarkdownExample(title: Int, source: Int) {
    val markdown = stringResource(source)
    HelpHeading(stringResource(title))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.markdown_source_label), style = MaterialTheme.typography.labelLarge)
            Text(markdown, fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.markdown_result_label), style = MaterialTheme.typography.labelLarge)
            MarkdownPreview(
                text = markdown,
                notesRoot = null,
                index = null,
                onNoteLink = {},
                modifier = Modifier.fillMaxWidth().height(220.dp),
                allowExternalLinks = false
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.help_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) { content() }
    }
}
