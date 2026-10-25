package io.github.maxdac.marknotes.ui.editor

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * Images offered to the editor from outside the toolbar: [uris] to read, the image type the source announced (a hint,
 * the content resolver's type wins) and [release], to call once the images were read, which gives back the temporary
 * read permission the source granted.
 */
class ReceivedImages(val uris: List<Uri>, val mimeType: String?, val release: () -> Unit = {})

/** "Paste image" in the editor's text menu, shown when the clipboard holds an image. */
const val PASTE_IMAGE_LABEL = "Paste image"

internal val IMAGE_MIME_TYPES = arrayOf("image/png", "image/jpeg", "image/gif", "image/webp")

/** The first image type of the clip, if it has one. */
internal fun ClipDescription.imageMimeType(): String? = (0 until mimeTypeCount).map(::getMimeType)
    .firstOrNull { ClipDescription.compareMimeTypes(it, "image/*") }

/** The content URIs of the clip's items. */
internal fun ClipData.uris(): List<Uri> = (0 until itemCount).mapNotNull { getItemAt(it).uri }

/**
 * Lets [content]'s text field receive images like the desktop and web apps: pasted from the clipboard (the text menu's
 * "Paste image", or Ctrl+V and Shift+Insert on a hardware keyboard when the clipboard holds only an image), inserted by
 * the keyboard app (stickers, GIFs, its clipboard) and dropped from another app in split screen. Every image goes
 * through [onImages], which stores it with the same rules as the "Insert image" action.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImageInputReceiver(
    onImages: (ReceivedImages) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val currentOnImages by rememberUpdatedState(onImages)
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    fun clipboardImageType(): String? = clipboard.primaryClipDescription?.imageMimeType()

    fun pasteImages(): Boolean {
        val clip = clipboard.primaryClip ?: return false
        val mimeType = clip.description.imageMimeType() ?: return false
        val uris = clip.uris()
        if (uris.isEmpty()) return false
        currentOnImages(ReceivedImages(uris, mimeType))
        return true
    }

    val dropTarget = remember(context) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drag = event.toAndroidDragEvent()
                val mimeType = drag.clipDescription?.imageMimeType() ?: return false
                val uris = drag.clipData?.uris().orEmpty()
                if (uris.isEmpty()) return false
                // Content from another app is only readable with the permissions that come with the drop.
                val permissions = context.findActivity()?.requestDragAndDropPermissions(drag)
                currentOnImages(ReceivedImages(uris, mimeType) { permissions?.release() })
                return true
            }
        }
    }

    val interceptor = remember {
        object : PlatformTextInputInterceptor {
            override suspend fun interceptStartInputMethod(
                request: PlatformTextInputMethodRequest,
                nextHandler: PlatformTextInputSession
            ): Nothing = nextHandler.startInputMethod(ImageAcceptingRequest(request) { currentOnImages(it) })
        }
    }

    Box(
        modifier
            .dragAndDropTarget(
                shouldStartDragAndDrop = { it.toAndroidDragEvent().clipDescription?.imageMimeType() != null },
                target = dropTarget
            )
            .appendTextContextMenuComponents {
                if (clipboardImageType() != null) {
                    item(key = PasteImageKey, label = PASTE_IMAGE_LABEL) {
                        close()
                        pasteImages()
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                val paste = event.type == KeyEventType.KeyDown &&
                    ((event.isCtrlPressed && event.key == Key.V) || (event.isShiftPressed && event.key == Key.Insert))
                // Text (also when it comes with an image) is pasted by the text field as usual.
                paste && clipboard.primaryClipDescription.isImageOnly() && pasteImages()
            }
    ) {
        InterceptPlatformTextInput(interceptor) { content() }
    }
}

private object PasteImageKey

private fun ClipDescription?.isImageOnly(): Boolean = this != null && imageMimeType() != null &&
    !hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The text field's input connection, announcing that it accepts images so keyboard apps offer theirs, and handing the
 * images they commit to [onImages].
 */
private class ImageAcceptingRequest(
    private val request: PlatformTextInputMethodRequest,
    private val onImages: (ReceivedImages) -> Unit
) : PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
        val connection = request.createInputConnection(outAttributes)
        outAttributes.contentMimeTypes = IMAGE_MIME_TYPES
        return object : InputConnectionWrapper(connection, false) {
            override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
                val mimeType = info.description.imageMimeType() ?: return false
                if (flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
                    try {
                        info.requestPermission()
                    } catch (e: SecurityException) {
                        return false
                    }
                }
                onImages(ReceivedImages(listOf(info.contentUri), mimeType) { info.releasePermission() })
                return true
            }
        }
    }
}
