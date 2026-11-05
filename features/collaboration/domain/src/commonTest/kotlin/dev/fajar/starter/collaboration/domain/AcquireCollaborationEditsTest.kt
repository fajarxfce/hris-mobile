package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AcquireCollaborationEditsTest {
    @Test
    fun allOriginalsAreRetainedBeforeBoundedReplacementBatches() = runTest {
        val f = HistoryAcquisitionFixture()
        f.includeEdits()
        assertEquals(AppResult.Success(CollaborationHistoryAcquisition(20, 0)), f.latest())
        assertEquals(listOf(16, 4), f.batches.map { it.size })
        assertEquals(listOf(16, 4), f.editBatches.map { it.size })
        assertEquals(f.window.edits, f.editBatches.flatten())
        assertEquals(1, f.reads)
    }

    @Test
    fun unrelatedMalformedOrDuplicateReplacementsCannotReachEitherStore() = runTest {
        val source = HistoryAcquisitionFixture().apply { includeEdits() }.window.edits.first()
        for (invalid in
            listOf(
                listOf(source.copy(targetEventId = "\$foreign")),
                listOf(source.copy(sender = "@other:example.test")),
                listOf(source.copy(eventId = source.targetEventId)),
                listOf(source, source),
                List(129) { source.copy(eventId = "\$too-many-" + it) },
            )) {
            val f = HistoryAcquisitionFixture()
            f.window = f.window.copy(edits = invalid)
            assertEquals(
                "client.collaboration_invalid_history_window",
                assertIs<AppResult.Failed>(f.latest()).failure.code,
            )
            assertTrue(f.batches.isEmpty())
            assertTrue(f.editBatches.isEmpty())
        }
    }

    @Test
    fun denialOrOwnerReplacementAfterAnEditBatchPreventsTheNextBatch() = runTest {
        for (denied in listOf(false, true)) {
            val f = HistoryAcquisitionFixture()
            f.includeEdits()
            f.afterEditMerge = {
                if (denied)
                    f.local.gate.value = CollaborationListAdmission("blocked", f.local.denied)
                else f.local.setup.session = null
            }
            assertIs<AppResult.Failed>(f.latest())
            assertEquals(1, f.editBatches.size)
            assertEquals(20, f.retained.size)
        }
    }

    @Test
    fun cancellationReleasesPendingEditStorageWithoutRetryingOrStartingAnotherBatch() = runTest {
        val f = HistoryAcquisitionFixture()
        f.includeEdits()
        val entered = CompletableDeferred<Unit>()
        var active = false
        f.afterEditMerge = {
            active = true
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                active = false
            }
        }
        val task = launch { f.latest() }
        entered.await()
        task.cancelAndJoin()
        assertFalse(active)
        assertEquals(1, f.editBatches.size)
        assertEquals(1, f.reads)
    }

    @Test
    fun anEditCheckpointRaceRetainsOriginalProgressAndRequiresAnOwnedRetry() = runTest {
        val f = HistoryAcquisitionFixture()
        f.includeEdits()
        f.rejectEditBatch = 1
        assertEquals(
            "client.collaboration_history_changed",
            assertIs<AppResult.Failed>(f.latest()).failure.code,
        )
        assertEquals(20, f.retained.size)
        assertEquals(1, f.editBatches.size)
        assertEquals(1, f.reads)
    }
}
