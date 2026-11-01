package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RestartCollaborationProviderHistoryTest {
    @Test
    fun explicitLatestReplacesRetainedOrExhaustedPositionUsingTheObservedCheckpoint() = runTest {
        for (position in listOf("old-position", null)) {
            val f = fixture(position)
            val before = f.progress
            assertEquals(
                AppResult.Success(CollaborationProviderHistoryAcquisition(20, false)),
                f.acquire(CollaborationHistoryPageOrigin.Latest),
            )
            assertEquals(listOf<String?>(null), f.positions)
            assertEquals(listOf(before), f.stagedProgress)
            assertEquals(listOf(CollaborationHistoryPageOrigin.Latest), f.stagedOrigins)
            assertEquals(before!!.generation, f.progress!!.generation)
            assertEquals(before.revision + 1, f.progress!!.revision)
            assertEquals("next", f.progress!!.next)
            assertEquals(listOf("get", "room", "get", "room"), f.admissions)
            assertEquals(0, f.delivery.acquisitions)
        }
    }

    @Test
    fun rejectedSavedPositionNeverRestartsImplicitlyAndExplicitRecoveryStillReadmits() = runTest {
        val f = fixture("retired-position")
        val before = f.progress
        val rejected = Failure(FailureKind.Conflict, "Unavailable")
        f.pageFailure = rejected
        assertEquals(AppResult.Failed(rejected), f.acquire())
        assertEquals(listOf<String?>("retired-position"), f.positions)
        assertEquals(before, f.progress)
        assertEquals(0, f.stages)
        assertNull(f.local.gate.value.denial)
        f.pageFailure = null
        assertIs<AppResult.Success<*>>(f.acquire(CollaborationHistoryPageOrigin.Latest))
        assertEquals(listOf("retired-position", null), f.positions)
        assertEquals(1, f.stages)
        assertEquals(3, f.local.setup.calls)
    }

    @Test
    fun failedInvalidOrLateDeniedLatestAcquisitionKeepsEveryPreviousFact() = runTest {
        for (kind in listOf("network", "owner", "position", "room", "denial", "cas")) {
            val f = fixture("retained-position")
            val before = f.progress
            when (kind) {
                "network" -> f.pageFailure = f.local.storage
                "owner" -> f.beforePageReturns = { f.local.setup.session = null }
                "position" -> f.page = f.page.copy(requestedPosition = "retained-position")
                "room" -> f.beforePageReturns = {
                        f.delivery.control.room =
                            f.delivery.control.room.copy(matrixRoomId = "!changed:test")
                    }
                "denial" -> f.beforePageReturns = {
                        f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                    }
                "cas" -> f.rejectStage = true
            }
            assertIs<AppResult.Failed>(f.acquire(CollaborationHistoryPageOrigin.Latest))
            assertEquals(before, f.progress)
            assertTrue(f.saved.isEmpty())
            assertEquals(1, f.reads)
        }
    }

    @Test
    fun cancellationKeepsTheOldCursorAndACommittedLostResponseKeepsTheNewOne() = runTest {
        val f = fixture("retained-position")
        val before = f.progress
        val entered = CompletableDeferred<Unit>()
        f.beforePageReturns = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = launch { f.acquire(CollaborationHistoryPageOrigin.Latest) }
        entered.await()
        pending.cancelAndJoin()
        assertEquals(before, f.progress)
        assertEquals(0, f.active)
        assertEquals(0, f.stages)
        f.beforePageReturns = {}
        f.stageFailure = f.local.storage
        assertEquals(
            AppResult.Failed(f.local.storage),
            f.acquire(CollaborationHistoryPageOrigin.Latest),
        )
        assertEquals("next", f.progress!!.next)
        assertEquals(before!!.revision + 1, f.progress!!.revision)
        assertEquals(2, f.reads)
        assertEquals(1, f.saved.size)
    }

    private fun fixture(position: String?) =
        ProviderHistoryAcquisitionFixture().apply {
            progress =
                CollaborationHistoryProgress(
                    ProviderHistoryAcquisitionFixture.generation,
                    8,
                    position,
                    75,
                )
        }
}
