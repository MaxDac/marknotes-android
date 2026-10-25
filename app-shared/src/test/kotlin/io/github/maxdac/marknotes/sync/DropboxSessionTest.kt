package io.github.maxdac.marknotes.sync

import com.dropbox.core.InvalidAccessTokenException
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.oauth.DbxOAuthError
import com.dropbox.core.oauth.DbxOAuthException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DropboxSessionTest {
    private val stored = mutableMapOf<String, String>()
    private val invalidated = mutableListOf<String>()
    private val identities = mutableMapOf("old" to "dbid:original", "new" to "dbid:original")
    private val sessions = DropboxSession(
        stored::get,
        { id, secret -> stored[id] = secret },
        invalidated::add
    ) {
        if (it.accessToken == "offline") throw IOException("offline")
        DropboxIdentity(identities[it.accessToken] ?: throw revoked(), "same@example.com")
    }

    @Test
    fun matchingStableIdentityReplacesOnlyOriginalCredentialAndInvalidatesItsCache() = runBlocking {
        sessions.saveNew("local-id", DbxCredential("old"))
        stored["other-drive"] = "untouched"
        identities.remove("old") // Revoked original session no longer needs to authenticate.

        assertTrue(sessions.reconnect("local-id") { DbxCredential("new") })

        assertEquals(setOf("local-id", "other-drive"), stored.keys)
        assertEquals("untouched", stored["other-drive"])
        assertEquals("new", sessions.credential("local-id").accessToken)
        assertEquals(listOf("local-id"), invalidated)
    }

    @Test
    fun sameEmailButDifferentIdentityCannotReplaceCredentialsOrInvalidateCache() = runBlocking {
        sessions.saveNew("local-id", DbxCredential("old"))
        identities["new"] = "dbid:different"
        val before = stored.toMap()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { sessions.reconnect("local-id") { DbxCredential("new") } }
        }

        assertEquals(before, stored)
        assertTrue(invalidated.isEmpty())
    }

    @Test
    fun cancellationAndFailedAccountLookupLeaveCredentialsAndCacheUntouched() = runBlocking {
        sessions.saveNew("local-id", DbxCredential("old"))
        val before = stored.toMap()
        assertFalse(sessions.reconnect("local-id") { null })
        assertThrows(CancellationException::class.java) {
            runBlocking { sessions.reconnect("local-id") { throw CancellationException() } }
        }
        assertThrows(InvalidAccessTokenException::class.java) {
            runBlocking { sessions.reconnect("local-id") { DbxCredential("invalid") } }
        }
        assertEquals(before, stored)
        assertTrue(invalidated.isEmpty())
    }

    @Test
    fun legacyIdentityIsEstablishedOnlyUsingOriginalAuthenticatedCredential() = runBlocking {
        stored["legacy"] = DbxCredential.Writer.writeToString(DbxCredential("old"))
        assertTrue(sessions.reconnect("legacy") { DbxCredential("new") })
        identities.remove("old")
        assertTrue(sessions.reconnect("legacy") { DbxCredential("new") })
        assertEquals("new", sessions.credential("legacy").accessToken)
    }

    @Test
    fun revokedLegacyCredentialFailsBeforeStartingAuthorizationWithoutEmailFallback() {
        stored["legacy"] = DbxCredential.Writer.writeToString(DbxCredential("revoked"))
        val before = stored.toMap()
        var authorized = false

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                sessions.reconnect("legacy") {
                    authorized = true
                    DbxCredential("new")
                }
            }
        }

        assertTrue(failure.message.orEmpty().contains("older version"))
        assertFalse(authorized)
        assertEquals(before, stored)
        assertTrue(invalidated.isEmpty())
    }

    @Test
    fun offlineLegacyLookupPropagatesWithoutStartingAuthorization() {
        stored["legacy"] = DbxCredential.Writer.writeToString(DbxCredential("offline"))
        val before = stored.toMap()
        var authorized = false

        assertThrows(IOException::class.java) {
            runBlocking {
                sessions.reconnect("legacy") {
                    authorized = true
                    DbxCredential("new")
                }
            }
        }

        assertFalse(authorized)
        assertEquals(before, stored)
    }

    @Test
    fun legacyCredentialWithRevokedRefreshTokenFailsSafely() {
        stored["legacy"] = DbxCredential.Writer.writeToString(DbxCredential("expired"))
        val sessions = DropboxSession(stored::get, { id, secret -> stored[id] = secret }) {
            throw DbxOAuthException("request", DbxOAuthError(DbxOAuthError.INVALID_GRANT, "revoked"))
        }

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { sessions.reconnect("legacy") { error("must not authorize") } }
        }

        assertTrue(failure.message.orEmpty().contains("older version"))
    }

    @Test
    fun healthyLegacySessionPinsIdentityForLaterRevocation() = runBlocking {
        stored["legacy"] = DbxCredential.Writer.writeToString(DbxCredential("old"))
        sessions.rememberIdentity("legacy")
        identities.remove("old")
        assertTrue(sessions.reconnect("legacy") { DbxCredential("new") })
    }

    @Test
    fun failedCredentialPersistenceDoesNotInvalidateCachedClient() = runBlocking {
        sessions.saveNew("local-id", DbxCredential("old"))
        val before = stored.toMap()
        val failing = DropboxSession(stored::get, { _, _ -> throw IOException("storage failed") }, invalidated::add) {
            DropboxIdentity("dbid:original", "label")
        }
        assertThrows(IOException::class.java) {
            runBlocking { failing.reconnect("local-id") { DbxCredential("new") } }
        }
        assertEquals(before, stored)
        assertTrue(invalidated.isEmpty())
    }

    private fun revoked() = InvalidAccessTokenException(null, "revoked", null)
}
