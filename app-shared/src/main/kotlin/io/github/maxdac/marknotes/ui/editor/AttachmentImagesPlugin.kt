package io.github.maxdac.marknotes.ui.editor

import android.net.Uri
import io.github.maxdac.marknotes.core.notes.Attachments
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.SchemeHandler
import io.noties.markwon.image.destination.ImageDestinationProcessor
import java.io.File

/** No note-supplied URI is ever passed to a general-purpose file, content or network loader. */
internal class AttachmentImagesPlugin(private val notesRoot: File) : AbstractMarkwonPlugin() {
    override fun configure(registry: MarkwonPlugin.Registry) {
        registry.require(ImagesPlugin::class.java) { plugin ->
            plugin.removeSchemeHandler("http")
                .removeSchemeHandler("https")
                .removeSchemeHandler("data")
                .addSchemeHandler(object : SchemeHandler() {
                    override fun supportedSchemes(): Collection<String> = listOf(SCHEME)

                    override fun handle(raw: String, uri: Uri): ImageItem {
                        val destination = raw.removePrefix("$SCHEME:")
                        val file = requireNotNull(Attachments.previewFile(notesRoot, destination)) {
                            "Blocked image: only canonical note attachments can be previewed"
                        }
                        return ImageItem.withDecodingNeeded(
                            Attachments.mimeFor(destination),
                            file.inputStream().buffered()
                        )
                    }
                })
        }
    }

    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
        builder.imageDestinationProcessor(object : ImageDestinationProcessor() {
            override fun process(destination: String): String = "$SCHEME:$destination"
        })
    }

    private companion object {
        const val SCHEME = "marknotes-attachment"
    }
}
