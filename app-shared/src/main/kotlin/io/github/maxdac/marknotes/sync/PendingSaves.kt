package io.github.maxdac.marknotes.sync

import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * Editors with unsaved edits register here so a sync first writes them to disk: a sync must never plan
 * against a file the editor is about to overwrite.
 *
 * Editors also take a [generation] when they open and write only while it is [current]: deleting the local data
 * ([discardAll]) starts a new generation, so an editor that is still open never writes a deleted note back.
 */
class PendingSaves(private val diagnostics: DiagnosticReporter = DiagnosticReporter.NONE) {
    private val savers = CopyOnWriteArraySet<suspend () -> Unit>()
    private val writes = CopyOnWriteArraySet<Job>()
    private val currentGeneration = AtomicLong()

    /** The current generation of the local data. */
    val generation: Long get() = currentGeneration.get()

    /** Whether the local data is still the one of [generation], so an editor opened then may write. */
    fun current(generation: Long): Boolean = currentGeneration.get() == generation

    /** Drops every pending edit (the local data is being deleted) and waits for writes already running. */
    suspend fun discardAll() {
        currentGeneration.incrementAndGet()
        writes.forEach { it.join() }
    }

    /** Registers [save], which writes the editor's pending edits now; call the returned function to remove it. */
    fun register(save: suspend () -> Unit): () -> Unit {
        savers += save
        return { savers -= save }
    }

    /** Tracks a background write started when an editor closed, so the next sync waits for it. */
    fun track(write: Job) {
        writes += write
        write.invokeOnCompletion { writes -= write }
    }

    suspend fun flushAll() {
        for (save in savers) {
            try {
                save()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Normal editor saves report locally; this catches callbacks which failed before doing so.
                diagnostics.report(DiagnosticCode.NOTE_WRITE, e)
            }
        }
        writes.forEach { it.join() }
    }
}
