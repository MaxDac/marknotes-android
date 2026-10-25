package io.github.maxdac.marknotes.ui.editor

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.shared.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownHelpRendererTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun realReferenceExamplesUseTheProductionRendererExtensions() {
        instrumentation.runOnMainSync {
            val renderer = createMarkdownRenderer(context, dark = false, notesRoot = null, allowExternalLinks = false)
            val basics = renderer.toMarkdown(context.getString(R.string.markdown_basic_source))
            assertTrue(basics.toString().contains("A manually typed level-four heading"))
            assertTrue(basics.spans().any { it.javaClass.simpleName == "HeadingSpan" })
            assertTrue(basics.spans().any { it is StrikethroughSpan })
            assertTrue(basics.spans().any { it.javaClass.simpleName == "StrongEmphasisSpan" })
            val extensions = renderer.toMarkdown(context.getString(R.string.markdown_extensions_source))
            assertTrue(extensions.spans().any { it.javaClass.simpleName == "TableRowSpan" })
            assertTrue(extensions.spans().any { it.javaClass.simpleName == "TaskListSpan" })
            val code = renderer.toMarkdown(context.getString(R.string.markdown_code_source))
            assertTrue(code.toString().contains("val greeting = \"Hello\""))
            assertTrue(code.spans().any { it is ForegroundColorSpan })
        }
    }

    @Test
    fun referenceLinksCannotLaunchActivitiesOrResolveRealNotes() {
        var noteLinks = 0
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply { addDataScheme("https") }
        val monitor = android.app.Instrumentation.ActivityMonitor(
            filter,
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null),
            true
        )
        instrumentation.addMonitor(monitor)
        try {
            instrumentation.runOnMainSync {
                val renderer = createMarkdownRenderer(
                    context,
                    dark = true,
                    notesRoot = null,
                    allowExternalLinks = false,
                    onNoteLink = { noteLinks++ }
                )
                val view = TextView(context)
                renderer.setMarkdown(view, "[External](https://example.org)")
                (view.text as Spanned).getSpans(0, view.text.length, android.text.style.ClickableSpan::class.java)
                    .forEach { it.onClick(view) }
                assertEquals(0, noteLinks)
            }
            assertEquals(0, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun Spanned.spans(): List<Any> = getSpans(0, length, Any::class.java).toList()
}
