package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationIncomingHistoryWaveTest {
    @Test
    fun newWaveUsesTheLastCompletedHeadAndPartialContinuationKeepsItsOriginalStop() = runTest {
        val f = fixture(null)
        val scope = f.delivery.historyBackground(TestTimeSource())
        f.page = f.page.copy(start = "new-head", requestedUntilPosition = "completed-head")
        assertIs<AppResult.Success<*>>(
            f.acquireBackground(scope, CollaborationHistoryPageOrigin.Latest)
        )
        assertEquals(
            CollaborationIncomingHistoryPosition("new-head", "completed-head"),
            f.progress!!.incoming,
        )
        f.page = f.page.copy(start = "next", requestedPosition = "next", next = null)
        assertIs<AppResult.Success<*>>(f.acquireBackground(scope))
        assertEquals(listOf<String?>(null, "next"), f.positions)
        assertEquals(listOf<String?>("completed-head", "completed-head"), f.stops)
        assertEquals("new-head", f.progress!!.incoming!!.head)
        assertEquals(0, f.active)
    }

    @Test
    fun explicitPartialRestartCannotTreatItsUnfinishedHeadAsACompletedBoundary() = runTest {
        val f = fixture("saved-next")
        val scope = f.delivery.historyBackground(TestTimeSource())
        f.page = f.page.copy(start = "replacement-head", requestedUntilPosition = "old-stop")
        assertIs<AppResult.Success<*>>(
            f.acquireBackground(scope, CollaborationHistoryPageOrigin.Latest)
        )
        assertEquals(listOf<String?>("old-stop"), f.stops)
        assertEquals(
            CollaborationIncomingHistoryPosition("replacement-head", "old-stop"),
            f.progress!!.incoming,
        )
    }

    @Test
    fun missingForeignOrMalformedReturnedBoundsCannotReachTheAtomicCommit() = runTest {
        for (until in listOf(null, "different-stop", "bad\nstop")) {
            val f = fixture("saved-next")
            val before = f.progress
            f.page =
                f.page.copy(
                    start = "saved-next",
                    requestedPosition = "saved-next",
                    requestedUntilPosition = until,
                )
            assertIs<AppResult.Failed>(f.acquire())
            assertEquals(before, f.progress)
            assertEquals(0, f.stages)
            assertEquals(listOf<String?>("old-stop"), f.stops)
        }
    }

    @Test
    fun malformedIncomingStateIsRejectedBeforeTransport() = runTest {
        for (incoming in
            listOf(
                CollaborationIncomingHistoryPosition("", null),
                CollaborationIncomingHistoryPosition("head", "bad\nstop"),
                CollaborationIncomingHistoryPosition("head", null, "after"),
                CollaborationIncomingHistoryPosition("head", null, pendingThrough = 21),
                CollaborationIncomingHistoryPosition("head", null, pendingRetryRequired = true),
            )) {
            val f = fixture("saved-next")
            f.progress = f.progress!!.copy(incoming = incoming)
            assertIs<AppResult.Failed>(f.acquire())
            assertEquals(0, f.reads)
        }
    }

    @Test
    fun cancellationAndLateDenialKeepTheOriginalWaveAndNoPendingReader() = runTest {
        for (cancel in listOf(false, true)) {
            val f = fixture("saved-next")
            val scope = f.delivery.historyBackground(TestTimeSource())
            val before = f.progress
            f.page =
                f.page.copy(
                    start = "saved-next",
                    requestedPosition = "saved-next",
                    requestedUntilPosition = "old-stop",
                )
            val entered = CompletableDeferred<Unit>()
            f.beforePageReturns = {
                if (cancel) {
                    entered.complete(Unit)
                    awaitCancellation()
                } else f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
            }
            if (cancel) {
                val task = launch { f.acquireBackground(scope) }
                entered.await()
                task.cancelAndJoin()
            } else assertEquals(AppResult.Failed(f.local.denied), f.acquireBackground(scope))
            assertEquals(before, f.progress)
            assertEquals(0, f.stages)
            assertEquals(0, f.active)
        }
    }

    @Test
    fun fixedPendingPassExcludesAppendsAndEndsOnItsOriginalHighWater() {
        val f = ProviderHistoryAcquisitionFixture()
        val refs =
            (0L..19L).map { sequence ->
                CollaborationHistoryReference(
                    sequence,
                    f.page.events.first().copy(eventId = "\$reference-$sequence"),
                )
            }
        val progress =
            CollaborationHistoryProgress(
                ProviderHistoryAcquisitionFixture.generation,
                2,
                "next",
                100,
                CollaborationIncomingHistoryPosition("head", null, "old-after", 10, true),
            )
        val page = CollaborationHistoryReferences(progress, refs, "new-after")
        val bounded = assertNotNull(boundedCollaborationIncomingReferences(page))
        assertEquals((0L..9L).toList(), bounded.events.map { it.sequence })
        assertNull(bounded.after)
        assertTrue(bounded.progress!!.incoming!!.pendingRetryRequired)
        assertEquals("new-after", page.after)
        assertEquals(20, page.events.size)
    }

    private fun fixture(next: String?) =
        ProviderHistoryAcquisitionFixture(CollaborationHistoryPurpose.Incoming).apply {
            progress =
                CollaborationHistoryProgress(
                    ProviderHistoryAcquisitionFixture.generation,
                    1,
                    next,
                    20,
                    CollaborationIncomingHistoryPosition("completed-head", "old-stop"),
                )
        }

    private suspend fun ProviderHistoryAcquisitionFixture.acquireBackground(
        scope: CollaborationOperationScope.Background,
        origin: CollaborationHistoryPageOrigin = CollaborationHistoryPageOrigin.SavedPosition,
    ) =
        useCase(
            scope,
            traversal.deviceId,
            traversal.conversationId,
            origin,
            CollaborationHistoryPurpose.Incoming,
        )
}
