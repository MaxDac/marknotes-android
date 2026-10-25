package io.github.maxdac.marknotes.sync

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser
import io.github.maxdac.marknotes.data.AppSettings

/** An account that has just signed in, before the user picked the folder to sync. */
data class SignedInAccount(val id: String, val provider: DriveProvider, val label: String)

/** An in-app sign-in screen: calls `onDone` once signed in, `onBack` to leave it. */
typealias SignInScreen = @Composable (onDone: () -> Unit, onBack: () -> Unit) -> Unit

/**
 * Signs in to a cloud provider and creates [RemoteDriveStore]s for its accounts. The libre app ships the
 * Dropbox connector; MarkNotes Pro adds its own through [io.github.maxdac.marknotes.MarkNotesApp.extraConnectors].
 */
interface DriveConnector {
    val provider: DriveProvider

    /** Why the provider can't be used in this build, or null when it can. */
    val unavailableReason: String?

    /** Whether at most one account of this provider can be connected. */
    val singleAccount: Boolean get() = false

    /** Text shown in Settings, for example a provider's terms (null for none). */
    val notice: String? get() = null

    /** The in-app screen to show before [signIn] while [needsSignInScreen], or null when sign-in is external. */
    val signInScreen: SignInScreen? get() = null

    /** Whether [signInScreen] must be shown (and completed) before [signIn]. */
    fun needsSignInScreen(): Boolean = false

    /**
     * Starts an interactive sign-in and suspends until it completes. Credentials are stored under the
     * returned account id. Returns null when the user cancels.
     */
    suspend fun signIn(activity: ComponentActivity): SignedInAccount?

    /**
     * Whether [reconnect] is implemented: Settings shows **Reconnect** on this provider's drive cards only when
     * it is. Reconnecting signs in to the same provider account again and keeps the drive's sync state.
     */
    val supportsReconnect: Boolean get() = false

    /**
     * Optional in-app sign-in screen shown before [reconnect] for [account], or null when [reconnect] handles
     * sign-in itself (for example in a browser). Unlike [signInScreen] it must only stage the new session, never
     * replace the connected one: `onDone` makes the UI call [reconnect], which verifies and commits it; `onBack`
     * cancels.
     */
    fun reconnectScreen(account: DriveAccount): SignInScreen? = null

    /**
     * Discards whatever [reconnectScreen] staged for [account], leaving its current session as it is. Called when
     * the reconnect is cancelled and after every [reconnect]; must be idempotent and must not throw.
     */
    fun cancelReconnect(account: DriveAccount) {}

    /**
     * Signs in to [account]'s provider account again and replaces its credentials; returns false when the user
     * cancels. Before storing anything under [DriveAccount.id], verify that the new session belongs to the same
     * provider account by its immutable id (never by email or label), and throw otherwise. Never call [signOut].
     * Cancellation and failures must leave the existing credentials untouched; on success, drop cached clients
     * and stores so the next sync uses the new credentials. Called with the sync lock held: must not change the
     * drive's folder, options or sync state.
     */
    suspend fun reconnect(activity: ComponentActivity, account: DriveAccount): Boolean =
        throw UnsupportedOperationException("Reconnect is not supported by ${provider.displayName} in this build")

    /** Called whenever the activity resumes, which is when browser-based sign-ins return. */
    fun onActivityResumed() {}

    /** Browser for picking the folder to sync, or null when the provider has no folder listing. */
    suspend fun folderBrowser(accountId: String): RemoteFolderBrowser?

    /** Creates the store syncing [DriveAccount.syncFolder]. Throws RemoteUnavailableException when signed out. */
    suspend fun store(account: DriveAccount): RemoteDriveStore

    /** Forgets the credentials of [accountId]. */
    suspend fun signOut(accountId: String)

    /**
     * Deletes whatever this connector keeps on the device besides credentials (caches, SDK state), when the user
     * deletes all local data. Every account has been signed out already. Must not touch the drive.
     */
    suspend fun deleteLocalData() {}

    /** A drive account to create from a setup that predates drive accounts, or null when there is none. */
    suspend fun legacyAccount(settings: AppSettings): DriveAccount? = null
}
