package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import java.io.IOException

/** Finish path/history migration before any missing path can be interpreted as a local deletion. */
internal class RenameRecovery(
    private val local: LocalNoteStore,
    private val remote: RemoteDriveStore,
    private val metadata: SyncMetadataStore,
    private val base: BaseContentStore,
    private val store: RenameStore
) {
    suspend fun recover(): Int {
        var localChanges = 0
        for (rename in store.all()) {
            if (rename.local) {
                recoverLocal(rename)
                localChanges++
            } else {
                try {
                    recoverRemote(rename)
                } catch (e: UnsupportedRemoteOperationException) {
                    store.delete(rename.path)
                    throw e
                }
            }
            base.rename(rename.path, rename.toPath)
            metadata.rename(rename.path, rename.toPath)
            store.delete(rename.path)
        }
        return localChanges
    }

    private fun recoverLocal(rename: PendingRename) {
        val paths = local.listAll().map { it.path }.toSet()
        val source = rename.path in paths
        val target = rename.toPath in paths
        if (source && !target && local.identity(rename.path) == rename.localIdentity) {
            local.renameCase(rename.path, rename.toPath)
        } else if (source || !target) {
            throw IOException("Cannot safely recover local rename: ${rename.path}")
        }
        check(local.identity(rename.toPath) == rename.localIdentity) {
            "Local rename destination has a different identity: ${rename.toPath}"
        }
    }

    private suspend fun recoverRemote(rename: PendingRename) {
        val entry = remote.snapshot().singleOrNull { it.id == rename.remote.id }
            ?: throw IOException("Cannot find the remote rename identity: ${rename.path}")
        check(entry.isFolder == rename.remote.isFolder) { "Remote rename changed kind: ${rename.path}" }
        val moved = when (entry.path) {
            rename.toPath -> entry
            rename.path -> remote.rename(entry, rename.toPath)
            else -> throw IOException("Remote rename identity moved elsewhere: ${rename.path}")
        }
        check(
            moved.id == rename.remote.id && moved.path == rename.toPath &&
                moved.isFolder == rename.remote.isFolder
        ) {
            "Remote rename did not preserve the requested identity and path: ${rename.path}"
        }
    }
}
