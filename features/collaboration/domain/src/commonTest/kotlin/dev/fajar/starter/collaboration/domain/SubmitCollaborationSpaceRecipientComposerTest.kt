@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SubmitCollaborationSpaceRecipientComposerTest {
    @Test
    fun capturedTextAndTimeAreRetainedWithoutCompanyOrTransport() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.draft.base.native.setup.workspace = null
        f.beforeRead = { f.draft.base.native.setup.now += 100.days }
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        assertEquals(f.submission.nextDraft, f.draft.editor)
        assertEquals(mapOf(f.submission.id to f.submission), f.retained)
        assertEquals(1, f.handoffs)
        assertTrue(f.draft.base.requests.isEmpty())
        assertEquals(0, f.draft.base.begins)
    }

    @Test
    fun replayDoesNotReadOrClearLaterTypingOrRequireAnotherContactCheck() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.retained[f.submission.id] = f.submission
        f.draft.editor = f.submission.nextDraft.copy(text = "Later typing")
        f.draft.contact = null
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        assertEquals("Later typing", f.draft.editor!!.text)
        assertEquals(0, f.draft.reads)
        assertEquals(0, f.draft.contactReads)
        assertEquals(0, f.handoffs)
    }

    @Test
    fun changedOriginalWithTheSameIdentityNeverWrites() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.retained[f.submission.id] =
            f.submission.copy(original = f.submission.original.copy(text = "Another body"))
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure.kind,
        )
        assertEquals(0, f.handoffs)
    }

    @Test
    fun concurrentEditorChangeStopsBeforeContactReadOrWrite() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.draft.editor = f.submission.nextDraft
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure.kind,
        )
        assertEquals(0, f.draft.contactReads)
        assertEquals(0, f.handoffs)
    }

    @Test
    fun missingOrSubstitutedContactCannotCreateANewOriginal() = runTest {
        for (missing in listOf(true, false)) {
            val f = SpaceRecipientOutboxFixture()
            f.draft.editor = f.draft.original
            f.draft.contact =
                if (missing) null else f.draft.contact!!.copy(accountId = f.access.accountId)
            assertIs<AppResult.Failed>(f.submit()(f.access, f.draft.original, f.submission))
            assertEquals(0, f.handoffs)
            assertEquals(f.draft.original, f.draft.editor)
        }
    }

    @Test
    fun invalidIdentityEmptyTextOrRepeatedNextRevisionStopsBeforeIo() = runTest {
        val f = SpaceRecipientOutboxFixture()
        for (invalid in
            listOf(
                f.submission.copy(nextRevision = f.submission.id),
                f.submission.copy(original = f.draft.original.copy(text = " ")),
                f.submission.copy(
                    original = f.draft.original.copy(peerAccountId = f.access.accountId)
                ),
            )) assertIs<AppResult.Failed>(f.submit()(f.access, null, invalid))
        assertEquals(0, f.reads)
        assertEquals(0, f.handoffs)
    }

    @Test
    fun lostWriteReplyIsProvenByExactOriginalWithoutAnotherAttempt() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.writeFailure = storageFailure()
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        assertEquals(1, f.handoffs)
        assertEquals(2, f.reads)
    }

    @Test
    fun unprovenOrUnreadableWriteKeepsItsOriginalFailureWithoutRetry() = runTest {
        for (readFails in listOf(true, false)) {
            val f = SpaceRecipientOutboxFixture()
            f.persist = false
            val failure = storageFailure()
            f.writeFailure = failure
            f.afterWrite = {
                if (readFails) f.readFailure = Failure(FailureKind.Unexpected, "Read unavailable")
            }
            assertEquals(AppResult.Failed(failure), f.submit()(f.access, null, f.submission))
            assertEquals(1, f.handoffs)
            assertTrue(f.retained.isEmpty())
        }
    }

    @Test
    fun capacityRejectionPreservesUnsentTextAndDoesNotRetry() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.draft.editor = f.draft.original
        f.result = CollaborationComposerHandoffResult.CAPACITY_EXCEEDED
        val result =
            assertIs<AppResult.Failed>(f.submit()(f.access, f.draft.original, f.submission))
        assertEquals("client.command_queue_full", result.failure.code)
        assertEquals(f.draft.original, f.draft.editor)
        assertEquals(1, f.handoffs)
    }

    @Test
    fun denialDuringContactReadStopsBeforeStorage() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.draft.beforeContact = { f.draft.base.deny() }
        assertEquals(
            AppResult.Failed(f.draft.base.denied),
            f.submit()(f.access, null, f.submission),
        )
        assertEquals(0, f.handoffs)
    }

    @Test
    fun denialAfterCommitDoesNotPublishButKeepsTheOriginalForRecovery() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.afterWrite = { f.draft.base.deny() }
        assertEquals(
            AppResult.Failed(f.draft.base.denied),
            f.submit()(f.access, null, f.submission),
        )
        assertEquals(f.submission, f.retained[f.submission.id])
        assertEquals(1, f.handoffs)
    }

    @Test
    fun accountReplacementDuringReadStopsBeforeDraftOrContactAccess() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.beforeRead = { f.draft.base.identity.value = AppResult.Success(null) }
        assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission))
        assertEquals(0, f.draft.reads)
        assertEquals(0, f.handoffs)
    }

    @Test
    fun cancellationJoinsThePendingReadWithoutAnyFollowup() = runTest {
        val f = SpaceRecipientOutboxFixture()
        val entered = CompletableDeferred<Unit>()
        var stopped = 0
        f.beforeRead = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped++
            }
        }
        val job = launch { f.submit()(f.access, null, f.submission) }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, stopped)
        assertEquals(0, f.handoffs)
        assertEquals(0, f.draft.reads)
    }

    @Test
    fun aStoredResponseAloneCannotProveTheOriginalWasRetained() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.persist = false
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure.kind,
        )
        assertEquals(1, f.handoffs)
        assertEquals(2, f.reads)
    }

    private fun storageFailure() =
        Failure(FailureKind.Storage, "Could not save", code = "storage-original")
}
