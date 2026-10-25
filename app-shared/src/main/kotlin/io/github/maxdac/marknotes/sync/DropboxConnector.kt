package io.github.maxdac.marknotes.sync

import androidx.activity.ComponentActivity
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import io.github.maxdac.marknotes.auth.CredentialStore
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dropbox through the official SDK. Sign-in uses OAuth with PKCE; the refresh token is stored encrypted. */
class DropboxConnector(
    private val credentials: CredentialStore,
    private val appKey: String,
    private val diagnostics: DiagnosticReporter = DiagnosticReporter.NONE
) : DriveConnector {
    override val provider = DriveProvider.DROPBOX

    private val config = DbxRequestConfig.newBuilder("MarkNotes").build()
    private var pendingSignIn: CompletableDeferred<DbxCredential?>? = null
    private val stores = HashMap<String, Pair<String, DropboxDriveStore>>()
    private val sessions = DropboxSession(credentials::load, credentials::save, { stores.remove(it) }) { credential ->
        withContext(Dispatchers.IO) {
            val account = DbxClientV2(config, credential).users().currentAccount
            DropboxIdentity(account.accountId, account.email.ifBlank { account.name.displayName })
        }
    }

    override val supportsReconnect = true

    override val unavailableReason: String?
        get() = if (appKey == UNCONFIGURED) {
            "No Dropbox app key in this build. See the README to set marknotes.dropboxAppKey."
        } else {
            null
        }

    override suspend fun signIn(activity: ComponentActivity): SignedInAccount? {
        val credential = authorize(activity) ?: return null
        val id = "dropbox-" + UUID.randomUUID()
        val identity = sessions.saveNew(id, credential)
        return SignedInAccount(id, provider, identity.label)
    }

    override suspend fun reconnect(activity: ComponentActivity, account: DriveAccount): Boolean {
        require(account.provider == provider)
        return sessions.reconnect(account.id) { authorize(activity) }
    }

    private suspend fun authorize(activity: ComponentActivity): DbxCredential? {
        check(pendingSignIn == null) { "A Dropbox sign-in is already in progress" }
        val deferred = CompletableDeferred<DbxCredential?>()
        pendingSignIn = deferred
        return try {
            Auth.startOAuth2PKCE(activity, appKey, config, SCOPES)
            deferred.await()
        } finally {
            pendingSignIn = null
        }
    }

    /** Completes a pending sign-in: the activity resumes when the Dropbox sign-in returns. */
    override fun onActivityResumed() {
        val deferred = pendingSignIn ?: return
        deferred.complete(Auth.getDbxCredential())
    }

    override suspend fun folderBrowser(accountId: String): RemoteFolderBrowser = DropboxFolderBrowser(client(accountId))

    override suspend fun store(account: DriveAccount): RemoteDriveStore {
        stores[account.id]?.takeIf { it.first == account.syncFolder }?.let { return it.second }
        try {
            sessions.rememberIdentity(account.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Retried when the store is next created; the sync itself reports a signed-out or offline drive.
            diagnostics.report(DiagnosticCode.SIGN_IN, e)
        }
        return DropboxDriveStore(client(account.id), account.syncFolder).also {
            stores[account.id] = account.syncFolder to it
        }
    }

    override suspend fun signOut(accountId: String) {
        stores.remove(accountId)
        try {
            withContext(Dispatchers.IO) { client(accountId).auth().tokenRevoke() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.report(DiagnosticCode.SIGN_OUT, e)
            // The credentials are forgotten regardless.
        }
        credentials.remove(accountId)
    }

    private fun client(accountId: String): DbxClientV2 {
        if (credentials.load(accountId) == null) throw RemoteUnavailableException("Sign in to Dropbox again")
        return DbxClientV2(config, sessions.credential(accountId))
    }

    private companion object {
        const val UNCONFIGURED = "unconfigured"
        val SCOPES = listOf("account_info.read", "files.metadata.read", "files.content.read", "files.content.write")
    }
}
