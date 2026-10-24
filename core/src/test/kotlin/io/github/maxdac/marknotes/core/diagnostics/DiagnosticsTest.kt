package io.github.maxdac.marknotes.core.diagnostics

import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 1_800_000_000_000L

    private fun store() = DiagnosticStore(File(temporary.root, "events"), File(temporary.root, "exports")) { now }
    private fun event(code: DiagnosticCode = DiagnosticCode.NOTE_WRITE, timestamp: Long = now) =
        DiagnosticEvent(timestamp, code, FailureKind.IO)

    @Test
    fun throwableStringsAndStructureCannotLeakIntoAnyPayload() {
        val secret = "token=secret /Notes/private.md https://account.example password note contents"
        val failure = object : IOException(secret, IllegalArgumentException(secret)) {
            override fun toString(): String = error("Must not call Throwable.toString")
        }.apply {
            addSuppressed(SecurityException(secret))
            stackTrace = arrayOf(StackTraceElement(secret, secret, secret, 7))
        }
        val event = DiagnosticEvent.from(now, DiagnosticCode.NOTE_WRITE, failure)!!
        val store = store()
        store.append(event)
        val draft = DiagnosticReport.issueDraft("MaxDac/marknotes-android", store.events(), 101, 36)
        val exported = store.export().readText()
        assertEquals("$now NOTE_WRITE IO", event.line())
        listOf(exported, draft.body, URLDecoder.decode(draft.url, "UTF-8")).forEach {
            assertFalse(it.contains("secret"))
            assertFalse(it.contains("private.md"))
            assertFalse(it.contains("account.example"))
            assertFalse(it.contains("note contents"))
        }
    }

    @Test
    fun cancellationIsNotAnErrorAndKindsAreFixed() {
        assertNull(DiagnosticEvent.from(now, DiagnosticCode.SYNC, CancellationException("secret")))
        assertEquals(FailureKind.MEMORY, DiagnosticEvent.from(now, DiagnosticCode.CRASH, OutOfMemoryError())!!.kind)
        assertEquals(FailureKind.NONE, DiagnosticEvent.from(now, DiagnosticCode.SDK_ERROR, null)!!.kind)
    }

    @Test
    fun expiryIncludesSnapshotsAndQueuedOldEvents() {
        val store = store()
        store.append(event())
        val exported = store.export()
        now += DiagnosticStore.MAX_AGE_MILLIS
        assertEquals(1, store.events().size)
        assertTrue(exported.exists())
        now++
        assertTrue(store.events().isEmpty())
        assertFalse(exported.exists())
        store.append(event(timestamp = now - DiagnosticStore.MAX_AGE_MILLIS - 1))
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun clearInvalidatesQueuedEventsAndKeepsFutureRecordingEnabled() {
        val store = store()
        val oldGeneration = store.generation()
        store.append(event())
        val exported = store.export()
        store.clear()
        store.append(event(), oldGeneration)
        assertTrue(store.events().isEmpty())
        assertFalse(exported.exists())
        store.append(event())
        assertEquals(1, store.events().size)
    }

    @Test
    fun totalBudgetIncludesExportsAndPreexistingOversizeFiles() {
        val store = store()
        val directory = temporary.newFolder("exports")
        File(directory, "old.txt").outputStream().use { stream ->
            val block = ByteArray(1_024)
            repeat((DiagnosticStore.MAX_BYTES / block.size + 1).toInt()) { stream.write(block) }
        }
        store.prune()
        repeat(100) { store.append(event(timestamp = now++)) }
        repeat(3) {
            store.export()
            val bytes = temporary.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            assertTrue(bytes <= DiagnosticStore.MAX_BYTES)
        }
        assertEquals(100, store.events().size)
    }

    @Test
    fun concurrentWritersAndClearDoNotCorruptRecordsOrExceedBudget() {
        val store = store()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 4).map { index ->
                workers.submit {
                    repeat(30) {
                        store.append(event())
                        if (index == 0) store.clear() else store.export()
                    }
                }
            }
            workers.shutdown()
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS))
            results.forEach { it.get() }
            assertTrue(store.events().all { it.code == DiagnosticCode.NOTE_WRITE })
            assertTrue(
                temporary.root.walkTopDown().filter {
                    it.isFile
                }.sumOf { it.length() } <= DiagnosticStore.MAX_BYTES
            )
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun wholeRecentEventsFitFinalEncodedUrlAndOmissionCountIsHonest() {
        val events = (0 until 100).map { event(timestamp = now + it) }
        for (repo in listOf("MaxDac/marknotes-android", "MaxDac/MarkNotes")) {
            val draft = DiagnosticReport.issueDraft(repo, events, Long.MAX_VALUE, 36)
            assertTrue(draft.url.startsWith("https://github.com/$repo/issues/new?"))
            assertTrue(draft.url.length <= 2_000)
            assertTrue(draft.url.all { it.code < 128 })
            val body = URLDecoder.decode(draft.url.substringAfter("&body="), "UTF-8")
            assertEquals(draft.body, body)
            val included = events.filter { body.contains(it.line()) }
            assertTrue(included.isNotEmpty())
            assertEquals(100 - included.size, draft.omitted)
            assertEquals(events.takeLast(included.size), included)
            assertTrue(body.contains("${draft.omitted} event(s) omitted"))
            assertTrue(included.zipWithNext().all { (a, b) -> body.indexOf(a.line()) < body.indexOf(b.line()) })
        }
    }

    @Test
    fun emptyDraftIsValidAndUntrustedDestinationsAreRejected() {
        val draft = DiagnosticReport.issueDraft("MaxDac/MarkNotes", emptyList(), 1, 29)
        assertEquals(0, draft.omitted)
        assertTrue(draft.body.contains("No events included."))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DiagnosticReport.issueDraft("attacker/repo?body=private", emptyList(), 1, 29)
        }
    }

    @Test
    fun emergencyWriteIsSynchronousAndAlwaysDelegatesEvenIfStorageFails() {
        val failure = IllegalStateException("private")
        val calls = mutableListOf<String>()
        val previous = Thread.UncaughtExceptionHandler { _, received ->
            assertTrue(received === failure)
            calls += "previous"
        }
        DiagnosticCrashHandler({ calls += "write" }, previous).uncaughtException(Thread.currentThread(), failure)
        assertEquals(listOf("write", "previous"), calls)
        calls.clear()
        org.junit.Assert.assertThrows(IOException::class.java) {
            DiagnosticCrashHandler({ throw IOException("full") }, previous)
                .uncaughtException(Thread.currentThread(), failure)
        }
        assertEquals(listOf("previous"), calls)
    }
}
