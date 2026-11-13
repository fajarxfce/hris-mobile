package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SubmitSpaceComposerInboxTest {
    @Test
    fun thePendingPreviewIsDurableBeforeTheSchedulerHintRuns() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.base.onSchedule = {
            assertEquals(f.submission, f.retained[f.submission.id])
            assertEquals(f.submission.nextDraft, f.inboxState.draft?.value)
            assertNull(f.inboxState.entry?.draft)
            assertEquals(f.original.text, f.inboxState.entry?.newest?.preview?.text)
            assertEquals(
                CollaborationMessageDelivery.PENDING,
                f.inboxState.entry?.newest?.preview?.delivery,
            )
            AppResult.Success(Unit)
        }
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        assertEquals(1, f.handoffAttempts)
    }

    @Test
    fun anIndependentIndexConflictRetriesTheSameGestureWithoutDuplicatingIt() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.beforeHandoff = { if (f.handoffAttempts == 1) moveIndex(f) }
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        assertEquals(2, f.handoffAttempts)
        assertEquals(listOf(f.submission), f.handoffs)
        assertEquals(f.original.text, f.inboxState.entry?.newest?.preview?.text)
    }

    @Test
    fun continuousContentionStopsAfterThreeAttemptsAndPreservesTheEditor() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.editor = f.original
        f.beforeHandoff = { moveIndex(f) }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.submit()(f.access, f.original, f.submission)).failure.kind,
        )
        assertEquals(3, f.handoffAttempts)
        assertEquals(f.original, f.editor)
        assertTrue(f.retained.isEmpty() && f.base.schedules.isEmpty())
    }

    @Test
    fun aSourceOnlyAcknowledgementCannotConfirmAnAbsentPreviewAndReplayRepairsIt() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.forcedHandoff = CollaborationComposerHandoffResult.STORED
        f.beforeHandoff = {
            f.retained[f.submission.id] = f.submission
            f.editor = f.submission.nextDraft
        }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure.kind,
        )
        assertTrue(f.base.schedules.isEmpty())
        val newer = f.submission.nextDraft.copy(revision = indexId, text = "Preserve newer input")
        f.editor = newer
        f.forcedHandoff = null
        f.beforeHandoff = {}
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        assertEquals(newer, f.editor)
        assertEquals(newer, f.inboxState.entry?.draft?.value)
        assertEquals(f.original.text, f.inboxState.entry?.newest?.preview?.text)
        assertTrue(f.handoffs.isEmpty())
    }

    @Test
    fun aLostReplyWithUnreadableIndexKeepsTheOriginalFailureUntilExactReplay() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val interrupted = f.base.base.storageFailure
        f.handoffFailure = interrupted
        f.afterHandoff = {
            f.activityFailure = Failure(FailureKind.Storage, "Fictional read failure")
        }
        assertEquals(AppResult.Failed(interrupted), f.submit()(f.access, null, f.submission))
        assertTrue(f.base.schedules.isEmpty())
        f.activityFailure = null
        f.base.canonical = null
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        assertEquals(1, f.handoffAttempts)
    }

    @Test
    fun cancellationDuringAnchorAcquisitionCannotCommitAnyPartOfTheGesture() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val entered = CompletableDeferred<Unit>()
        f.beforeActivityRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val work = async { f.submit()(f.access, null, f.submission) }
        entered.await()
        work.cancelAndJoin()
        assertTrue(work.isCancelled)
        assertEquals(0, f.handoffAttempts)
        assertNull(f.editor)
        assertNull(f.inboxState.entry)
        assertTrue(f.retained.isEmpty() && f.base.schedules.isEmpty())
    }

    @Test
    fun denialDuringAnchorAcquisitionStopsBeforeMetadataOrSourcePublication() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.beforeActivityRead = { f.base.base.deny() }
        assertEquals(
            FailureKind.AccessDenied,
            assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure.kind,
        )
        assertEquals(0, f.base.canonicalReads)
        assertEquals(0, f.handoffAttempts)
        assertTrue(f.base.schedules.isEmpty())
    }

    @Test
    fun aDifferentOriginalNamespaceCannotConfirmTheRetainedGroupGesture() = runTest {
        val f = SpaceComposerUseCaseFixture()
        assertEquals(AppResult.Success(f.submission), f.submit()(f.access, null, f.submission))
        val source = f.inboxState.entry!!.newest!!
        f.forcedActivities =
            CollaborationSpaceInboxActivities(
                f.inboxState.version,
                listOf(
                    source.copy(
                        source =
                            CollaborationSpaceInboxSource.CompanyOriginal(
                                f.access.spaceId,
                                f.original.conversationId,
                                f.submission.id,
                            )
                    )
                ),
            )
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.submit()(f.access, null, f.submission)).failure.kind,
        )
        assertEquals(1, f.handoffAttempts)
        assertEquals(1, f.base.schedules.size)
    }

    private fun moveIndex(f: SpaceComposerUseCaseFixture) {
        f.inboxState =
            f.inboxState.copy(
                version =
                    CollaborationSpaceInboxVersion(
                        indexId,
                        (f.inboxState.version?.revision ?: -1) + 1,
                        f.inboxState.version?.lastOrdinal ?: 0,
                    )
            )
    }

    private val indexId = "d5000000-0000-4000-8000-000000000001"
}
