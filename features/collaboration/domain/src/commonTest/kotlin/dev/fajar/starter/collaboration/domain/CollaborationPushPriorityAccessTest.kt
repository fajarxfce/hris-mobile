package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.CollaborationPushFixture.Companion.fields
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationPushPriorityAccessTest {
    private suspend fun snapshot(f: CollaborationPushFixture) =
        assertIs<AppResult.Success<CollaborationPushSnapshot>>(f.reader()()).value

    @Test
    fun ownedLocalReadAndExactCompletionRemainAvailableWithExpiredCredentials() = runTest {
        val f = CollaborationPushFixture()
        assertEquals(AppResult.Success(true), f.receiver()(fields(true)))
        f.session = f.initial.copy(binding = f.initial.binding.copy(expiresAtEpochMillis = 0))
        val before = snapshot(f)
        val hint = before.priority!!.hints.single()
        assertEquals(AppResult.Success(true), f.complete()(before, hint))
        val after = snapshot(f)
        assertTrue(after.priority!!.hints.isEmpty())
        assertNotEquals(before.priority.generation, after.priority.generation)
        assertEquals(AppResult.Success(false), f.complete()(before, hint))
        assertNotNull(f.retained)
    }

    @Test
    fun oldCompletionCannotEraseNewerHintOrOtherCompanyArrivingAfterCapture() = runTest {
        for (otherCompany in listOf(false, true)) {
            val f = CollaborationPushFixture()
            f.receiver()(fields(true))
            val before = snapshot(f)
            val packet =
                if (otherCompany)
                    fields(true) + ("companyId" to "66666666-6666-4666-8666-666666666666")
                else fields(true) + ("revision" to "2")
            f.receiver()(packet)
            val newer = snapshot(f)
            assertEquals(
                AppResult.Success(false),
                f.complete()(before, before.priority!!.hints.single()),
            )
            assertEquals(newer, snapshot(f))
        }
    }

    @Test
    fun malformedOrUncapturedHintCannotReadSessionOrMutateStorage() = runTest {
        val f = CollaborationPushFixture()
        f.receiver()(fields(true))
        val before = snapshot(f)
        val reads = f.reads
        val writes = f.priorityWrites
        val hint = before.priority!!.hints.single().copy(revision = 99)
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.complete()(before, hint)).failure.kind,
        )
        assertEquals(reads, f.reads)
        assertEquals(writes, f.priorityWrites)
    }

    @Test
    fun changedFamilyAndLateReadCannotExposeAnOldOwnersPriority() = runTest {
        val f = CollaborationPushFixture()
        f.receiver()(fields(true))
        val before = snapshot(f)
        f.beforePriorityRead = { f.session = null }
        assertEquals(
            "client.session_changed",
            assertIs<AppResult.Failed>(f.reader()()).failure.code,
        )
        val writes = f.priorityWrites
        assertIs<AppResult.Failed>(f.complete()(before, before.priority!!.hints.single()))
        assertEquals(writes, f.priorityWrites)
    }

    @Test
    fun failedAndCancelledCompletionKeepOwnedWorkAndJoinIo() = runTest {
        val f = CollaborationPushFixture()
        f.receiver()(fields(true))
        val before = snapshot(f)
        val hint = before.priority!!.hints.single()
        f.priorityFailure = f.storage
        assertEquals(AppResult.Failed(f.storage), f.complete()(before, hint))
        assertEquals(before.priority, f.priority)
        f.priorityFailure = null
        val entered = CompletableDeferred<Unit>()
        var closed = false
        f.beforePriorityWrite = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                closed = true
            }
        }
        val job = launch {
            f.complete()(before, hint)
            error("Cancelled completion returned")
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(closed)
        assertEquals(before.priority, f.priority)
    }

    @Test
    fun lateCancellationAfterCommittedCompletionCannotReportSuccessButKeepsFence() = runTest {
        val f = CollaborationPushFixture()
        f.receiver()(fields(true))
        val before = snapshot(f)
        f.afterPriorityWrite = { currentCoroutineContext().cancel() }
        val job = launch {
            f.complete()(before, before.priority!!.hints.single())
            error("Cancelled completion returned")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(f.priority!!.hints.isEmpty())
        assertNotEquals(before.priority!!.generation, f.priority!!.generation)
    }
}
