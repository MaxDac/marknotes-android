package io.github.maxdac.marknotes.sync

import com.dropbox.core.InvalidAccessTokenException
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.oauth.DbxOAuthException

internal data class DropboxIdentity(val id: String, val label: String)

/** Identity and credentials are stored together in the same encrypted credential-store entry. */
internal class DropboxSession(
    private val load: (String) -> String?,
    private val save: (String, String) -> Unit,
    private val invalidate: (String) -> Unit = {},
    private val identify: suspend (DbxCredential) -> DropboxIdentity
) {
    fun credential(id: String): DbxCredential = read(id).second

    suspend fun saveNew(id: String, credential: DbxCredential): DropboxIdentity {
        val identity = identify(credential)
        save(id, encode(identity.id, credential))
        return identity
    }

    suspend fun reconnect(id: String, authorize: suspend () -> DbxCredential?): Boolean {
        val (savedIdentity, oldCredential) = read(id)
        // Network errors propagate as they are, so the user can retry; only a rejected session is final.
        val expected = savedIdentity ?: try {
            identify(oldCredential).id
        } catch (e: InvalidAccessTokenException) {
            throw legacyIdentityLost(e)
        } catch (e: DbxOAuthException) {
            throw legacyIdentityLost(e)
        }
        val replacement = authorize() ?: return false
        val actual = identify(replacement).id
        check(expected == actual) {
            "Sign in to the same Dropbox account as this drive. The drive and its sync history have been kept."
        }
        save(id, encode(actual, replacement))
        invalidate(id)
        return true
    }

    /**
     * Drives connected before account identities were saved can only be verified with their original session.
     * Matching by email instead could hand the drive's sync history to whoever now owns that address.
     */
    private fun legacyIdentityLost(cause: Exception) = IllegalStateException(
        "Cannot safely reconnect this Dropbox drive: it was connected by an older version and its sign-in has " +
            "expired, so its account can't be verified. Disconnect it and add it again.",
        cause
    )

    /** Pin legacy identities while their original session still works, before it can be revoked. */
    suspend fun rememberIdentity(id: String) {
        val (identity, credential) = read(id)
        if (identity == null) saveNew(id, credential)
    }

    private fun read(id: String): Pair<String?, DbxCredential> {
        val stored = checkNotNull(load(id)) { "Dropbox credentials are missing; the drive has been kept." }
        if (!stored.startsWith(PREFIX)) return null to DbxCredential.Reader.readFully(stored)
        val identity = stored.substringAfter(PREFIX).substringBefore('\n')
        check(identity.isNotBlank()) { "Saved Dropbox account identity is missing" }
        return identity to DbxCredential.Reader.readFully(stored.substringAfter(PREFIX).substringAfter('\n'))
    }

    private fun encode(identity: String, credential: DbxCredential): String {
        require(identity.isNotBlank() && '\n' !in identity) { "Dropbox returned an invalid account identity" }
        return "$PREFIX$identity\n${DbxCredential.Writer.writeToString(credential)}"
    }

    private companion object {
        const val PREFIX = "marknotes-dropbox-v1\n"
    }
}
