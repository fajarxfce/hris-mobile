package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReconcileSpaceInboxOriginalTest {
    @Test
    fun exactTerminalEvidencePublishesProgressAndCompletionTogetherWithoutTransport() = runTest {
        val f = SpaceInboxCommandFixture()
        assertEquals(AppResult.Success(true), f.usecase()(f.access, f.reference))
        assertTrue(f.completed)
        assertEquals(
            CollaborationMessageDelivery.SENT,
            f.publications.single().activities.single().preview?.delivery,
        )
        assertEquals(0, f.source.base.canonicalReads)
        assertEquals(0, f.source.base.observations)
        assertTrue(f.source.base.writes.isEmpty())
        assertTrue(f.source.base.schedules.isEmpty())
    }

    @Test
    fun missingOrQueuedCommandsRetainPendingWithoutReadingTheIndex() = runTest {
        for (phase in listOf("missing", "queued", "staging", "staged")) {
            val f = SpaceInboxCommandFixture()
            val sent = f.sent().state as CollaborationMessageState.Sent
            f.source.base.stored =
                when (phase) {
                    "missing" -> null
                    "staging" ->
                        f.queued()
                            .copy(
                                revision = 1,
                                state =
                                    CollaborationMessageState.Staging(
                                        sent.device,
                                        emptySet(),
                                        sent.origin,
                                    ),
                            )
                    "staged" ->
                        f.queued()
                            .copy(
                                revision = 2,
                                state =
                                    CollaborationMessageState.Staged(
                                        sent.device,
                                        sent.transactionId,
                                        sent.origin,
                                    ),
                            )
                    else -> f.queued()
                }
            assertEquals(AppResult.Success(false), f.usecase()(f.access, f.reference))
            assertEquals(0, f.source.inboxReads)
            assertEquals(0, f.source.activityReads)
            assertFalse(f.completed)
            assertTrue(f.publications.isEmpty())
        }
    }

    @Test
    fun repeatedIndexContentionStopsAtThreeAttemptsWithTheSameOriginal() = runTest {
        val f = SpaceInboxCommandFixture()
        f.conflicts = 3
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.usecase()(f.access, f.reference)).failure.kind,
        )
        assertEquals(3, f.publishAttempts)
        assertEquals(3, f.source.base.reads)
        assertFalse(f.completed)
        assertTrue(f.publications.isEmpty())
    }

    @Test
    fun aConcurrentNewerEditIsRereadBeforeCompletionPublication() = runTest {
        val f = SpaceInboxCommandFixture()
        f.conflicts = 1
        f.beforePublish = {
            if (f.publishAttempts == 1) {
                val anchor = f.anchor()
                f.replaceAnchor(anchor.copy(preview = anchor.preview!!.copy(text = "New edit")))
                f.source.inboxState =
                    f.source.inboxState.copy(
                        version = f.source.inboxState.version!!.copy(revision = 1)
                    )
            }
        }
        assertEquals(AppResult.Success(true), f.usecase()(f.access, f.reference))
        assertEquals(2, f.publishAttempts)
        assertEquals("New edit", f.publications.single().entry.newest?.preview?.text)
    }

    @Test
    fun cancellationDuringTheAnchorReadEscapesWithoutAWrite() = runTest {
        val f = SpaceInboxCommandFixture()
        val entered = CompletableDeferred<Unit>()
        f.source.beforeActivityRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.usecase()(f.access, f.reference) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, f.publishAttempts)
        assertFalse(f.completed)
    }

    @Test
    fun denialDuringCommandReadStopsBeforeAnyIndexAcquisition() = runTest {
        val f = SpaceInboxCommandFixture()
        f.source.base.beforeRead = { f.source.base.base.deny() }
        assertEquals(
            FailureKind.AccessDenied,
            assertIs<AppResult.Failed>(f.usecase()(f.access, f.reference)).failure.kind,
        )
        assertEquals(0, f.source.inboxReads)
        assertEquals(0, f.publishAttempts)
        assertFalse(f.completed)
    }

    @Test
    fun anAccountReplacementBeforePublicationRejectsTheLateOriginal() = runTest {
        val f = SpaceInboxCommandFixture()
        f.source.beforeActivityRead = { f.source.base.identity.value = AppResult.Success(null) }
        assertIs<AppResult.Failed>(f.usecase()(f.access, f.reference))
        assertEquals(0, f.publishAttempts)
        assertFalse(f.completed)
    }

    @Test
    fun commandReadFailuresAndMismatchedIntentNeverManufactureFailedOrSentPreviews() = runTest {
        val f = SpaceInboxCommandFixture()
        val before = f.anchor()
        f.source.base.readFailure = f.source.base.base.storageFailure
        assertEquals(
            AppResult.Failed(f.source.base.base.storageFailure),
            f.usecase()(f.access, f.reference),
        )
        f.source.base.readFailure = null
        f.source.base.stored =
            f.sent().copy(intent = f.sent().intent.copy(text = "Not the retained original"))
        assertIs<AppResult.Failed>(f.usecase()(f.access, f.reference))
        assertEquals(before, f.anchor())
        assertEquals(0, f.source.inboxReads)
        assertFalse(f.completed)
    }

    @Test
    fun anUncertainPublicationRetainsItsFailureAndDoesNotStartAWriteLoop() = runTest {
        val f = SpaceInboxCommandFixture()
        f.publishFailure = f.source.base.base.storageFailure
        assertEquals(
            AppResult.Failed(f.source.base.base.storageFailure),
            f.usecase()(f.access, f.reference),
        )
        assertEquals(1, f.publishAttempts)
        assertTrue(f.completed)
        assertEquals(CollaborationMessageDelivery.SENT, f.anchor().preview?.delivery)
    }
}
