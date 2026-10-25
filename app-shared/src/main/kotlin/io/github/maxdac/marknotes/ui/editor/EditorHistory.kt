package io.github.maxdac.marknotes.ui.editor

/**
 * Session-local undo/redo history of the note body, with the selection on either side of each edit.
 *
 * Like the desktop and web editors (CodeMirror history), contiguous typing is grouped into one step: a typed edit
 * joins the previous one when nothing happened in between (no cursor move, undo or toolbar edit), it follows within
 * [GROUP_DELAY_MS] and it doesn't add or remove a line break. Toolbar, mention and image edits are always one step.
 */
internal class EditorHistory(private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }) {
    private val past = ArrayDeque<EditorText>()
    private val future = ArrayDeque<EditorText>()

    /** The state after the last typed edit while its group is still open. */
    private var openGroup: EditorText? = null
    private var lastTyped = 0L

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Records the change from [before] to [after]; selection-only changes are ignored. */
    fun record(before: EditorText, after: EditorText, typing: Boolean = false) {
        if (before.text == after.text) return
        val now = clock()
        val joins = typing &&
            openGroup == before &&
            now - lastTyped <= GROUP_DELAY_MS &&
            lineBreaks(before.text) == lineBreaks(after.text)
        if (!joins) {
            past.addLast(before)
            if (past.size > MAX_STEPS) past.removeFirst()
        }
        future.clear()
        openGroup = if (typing) after else null
        lastTyped = now
    }

    /** Returns the state to restore, or null if there's nothing to undo; [current] becomes redoable. */
    fun undo(current: EditorText): EditorText? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(current)
        openGroup = null
        return previous
    }

    /** Returns the state to restore, or null if there's nothing to redo; [current] becomes undoable. */
    fun redo(current: EditorText): EditorText? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        openGroup = null
        return next
    }

    fun clear() {
        past.clear()
        future.clear()
        openGroup = null
    }

    private fun lineBreaks(text: String) = text.count { it == '\n' }

    companion object {
        const val GROUP_DELAY_MS = 1_000L
        const val MAX_STEPS = 200
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
