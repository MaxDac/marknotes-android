package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.sync.LocalEntry
import io.github.maxdac.marknotes.sync.SyncScheduler
import kotlinx.coroutines.CancellationException

/** Everything needed to sync the local note tree with one drive. */
class DriveSyncTarget(
    val driveId: String,
    val remote: RemoteDriveStore,
    val metadata: SyncMetadataStore,
    val base: BaseContentStore,
    val pending: ConflictCopyStore = InMemoryConflictCopyStore(),
    val renames: RenameStore = InMemoryRenameStore()
)

/**
 * Outcome of syncing one drive: the last [SyncResult], or the [error] that stopped it, with the exception itself as
 * [failure] so callers can tell temporary network failures from permanent ones.
 */
data class DriveSyncOutcome(
    val driveId: String,
    val result: SyncResult?,
    val error: String?,
    val failure: Exception? = null
)

/**
 * [outcomes] holds the last pass of each drive; [conflicts] the conflicted copies made by every pass.
 */
data class MultiDriveSyncResult(val outcomes: List<DriveSyncOutcome>, val conflicts: List<SyncConflict>) {
    val localChanged: Boolean get() = conflicts.isNotEmpty() || outcomes.any { (it.result?.localChanges ?: 0) > 0 }
}

/**
 * Keeps the local note tree and every connected drive identical.
 *
 * Each drive is synced in turn with its own metadata and base copies. The order comes from the shared sync core's
 * [SyncScheduler]: whenever a drive brings changes into the local tree, the other drives are synced again so they
 * receive them, at most [maxPassesPerDrive] times each.
 */
class MultiDriveSync(
    private val local: LocalNoteStore,
    private val deviceId: String = "",
    private val maxPassesPerDrive: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis
) {
    var diagnostics: DiagnosticReporter = DiagnosticReporter.NONE

    fun engine(target: DriveSyncTarget) =
        SyncEngine(
            local, target.remote, target.metadata, target.base, target.driveId, deviceId, target.pending,
            target.renames, clock
        ).also {
            it.diagnostics = diagnostics
        }

    suspend fun sync(targets: List<DriveSyncTarget>): MultiDriveSyncResult {
        val byId = targets.associateBy { it.driveId }
        val outcomes = HashMap<String, DriveSyncOutcome>()
        val conflicts = mutableListOf<SyncConflict>()
        SyncScheduler(targets.map { it.driveId }, maxPassesPerDrive).use { scheduler ->
            while (true) {
                val driveId = scheduler.next() ?: break
                for (target in targets) engine(target).recoverRenames()
                val entries = local.listAll().map { LocalEntry(it.path, it.isFolder, it.modifiedMillis, null) }
                for (target in targets) rememberLocalDeletions(entries, target.metadata)
                val outcome = try {
                    DriveSyncOutcome(driveId, engine(byId.getValue(driveId)).sync(), null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    diagnostics.report(DiagnosticCode.SYNC, e)
                    DriveSyncOutcome(driveId, null, e.message ?: e.javaClass.simpleName, e)
                }
                outcomes[driveId] = outcome
                conflicts += outcome.result?.conflicts.orEmpty()
                scheduler.report(driveId, outcome.result?.localChanges ?: 0, outcome.result == null)
            }
        }
        return MultiDriveSyncResult(targets.mapNotNull { outcomes[it.driveId] }, conflicts)
    }
}
