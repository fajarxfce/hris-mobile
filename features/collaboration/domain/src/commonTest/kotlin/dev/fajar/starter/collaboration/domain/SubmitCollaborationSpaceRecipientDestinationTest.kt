package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SubmitCollaborationSpaceRecipientDestinationTest {
    @Test
    fun transferredOrCancelledOriginalConfirmsWithoutContactOrEditorAccess() = runTest {
        for (cancelled in listOf(false, true)) {
            val f = SpaceRecipientOutboxFixture()
            f.destination =
                record(f).let {
                    if (cancelled) it.copy(state = CollaborationMessageState.Cancelled) else it
                }
            f.draft.contact = null
            f.draft.editor = f.submission.nextDraft.copy(text = "Later typing")
            assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
            assertEquals("Later typing", f.draft.editor!!.text)
            assertEquals(1, f.destinationReads)
            assertEquals(0, f.draft.reads)
            assertEquals(0, f.draft.contactReads)
            assertEquals(0, f.handoffs)
        }
    }

    @Test
    fun differentOrLegacyDestinationStopsBeforeAnotherLocalSubmission() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceRecipientOutboxFixture()
            val current = record(f)
            f.destination =
                current.copy(
                    intent =
                        if (legacy) current.intent.copy(recipientOrigin = null)
                        else current.intent.copy(text = "Another message")
                )
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure.kind,
            )
            assertEquals(0, f.handoffs)
            assertEquals(0, f.draft.reads)
        }
    }

    @Test
    fun recoveryWinningAfterCommitConfirmsEvenWhenTheWriteReplyWasLost() = runTest {
        for (lost in listOf(false, true)) {
            val f = SpaceRecipientOutboxFixture()
            if (lost) f.writeFailure = Failure(FailureKind.Storage, "Original write reply lost")
            f.afterWrite = {
                f.destination = record(f)
                f.retained.clear()
                f.draft.editor = f.submission.nextDraft.copy(text = "More typing")
            }
            assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
            assertEquals(1, f.handoffs)
            assertEquals(2, f.destinationReads)
            assertEquals("More typing", f.draft.editor!!.text)
        }
    }

    @Test
    fun unreadableDestinationDoesNotInventAnAcknowledgementOrRepeatTheWrite() = runTest {
        val f = SpaceRecipientOutboxFixture()
        val failure = Failure(FailureKind.Storage, "Original failure")
        f.writeFailure = failure
        f.afterWrite = {
            f.retained.clear()
            f.destinationFailure = Failure(FailureKind.Storage, "Destination unreadable")
        }
        assertEquals(AppResult.Failed(failure), f.submit()(f.access, null, f.submission))
        assertEquals(1, f.handoffs)
        assertEquals(2, f.destinationReads)
    }

    @Test
    fun deniedDestinationReadCannotPublishTheRetainedOriginal() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.destination = record(f)
        f.beforeDestination = { f.draft.base.deny() }
        assertEquals(
            AppResult.Failed(f.draft.base.denied),
            f.submit()(f.access, null, f.submission),
        )
        assertEquals(0, f.handoffs)
        assertEquals(0, f.draft.reads)
    }

    @Test
    fun denialAfterAnUncertainWriteOverridesItsStorageFailure() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.writeFailure = Failure(FailureKind.Storage, "Original write reply lost")
        f.afterWrite = {
            f.retained.clear()
            f.destination = record(f)
            f.beforeDestination = { f.draft.base.deny() }
        }
        assertEquals(
            AppResult.Failed(f.draft.base.denied),
            f.submit()(f.access, null, f.submission),
        )
        assertEquals(1, f.handoffs)
    }

    @Test
    fun accountReplacementDuringDestinationReadStopsBeforeAnyPublication() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.destination = record(f)
        f.beforeDestination = { f.draft.base.identity.value = AppResult.Success(null) }
        assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission))
        assertEquals(0, f.draft.reads)
        assertEquals(0, f.handoffs)
    }

    @Test
    fun cancellationJoinsTheExactReadAndDoesNotStartAnyFollowup() = runTest {
        val f = SpaceRecipientOutboxFixture()
        val entered = CompletableDeferred<Unit>()
        var stopped = 0
        f.beforeDestination = {
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
        assertEquals(1, f.destinationReads)
        assertEquals(0, f.handoffs)
        assertEquals(0, f.draft.reads)
    }

    @Test
    fun sourceReplayDoesNotRequireOpeningTheDestinationLedger() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.retained[f.submission.id] = f.submission
        f.beforeDestination = { error("Exact source already proves retention") }
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        assertEquals(0, f.destinationReads)
        assertEquals(0, f.handoffs)
    }

    @Test
    fun missingDestinationAfterAClaimedWriteRemainsUnprovenAndBounded() = runTest {
        val f = SpaceRecipientOutboxFixture()
        f.persist = false
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure.kind,
        )
        assertEquals(1, f.handoffs)
        assertEquals(2, f.destinationReads)
        assertTrue(f.draft.base.requests.isEmpty())
    }

    private fun record(f: SpaceRecipientOutboxFixture): CollaborationSpaceMessageRecord {
        val original = f.submission
        return CollaborationSpaceMessageRecord(
            CollaborationSpaceMessageIntent(
                original.id,
                f.access.messageOwner(),
                "bbbbbbbb-0000-4000-8000-000000000600",
                original.submittedAt,
                original.original.text,
                recipientOrigin =
                    CollaborationSpaceRecipientOrigin(
                        original.original.peerAccountId,
                        original.original.savedAt,
                        original.nextRevision,
                    ),
            ),
            0,
            CollaborationMessageState.Queued,
        )
    }
}
