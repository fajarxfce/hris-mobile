package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.FinishCollaborationSpaceRecovery
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class FinishCollaborationSpaceRecoveryTest {
    @Test
    fun unchangedExhaustedInventoryReturnsOnlyItsCapturedWake() = runTest {
        val f = Fixture()
        val done = f.finish()
        assertEquals(CollaborationSpaceRecoveryWake(id(2)), done.completedWake)
        assertNull(done.snapshot.progress!!.sweep)
        assertEquals(1, f.reads)
        assertEquals(1, f.writes)
    }

    @Test
    fun appendAfterTheLastPageRestartsWithoutAcknowledging() = runTest {
        val f = Fixture()
        f.page = f.page.copy(boundary = f.boundary.copy(revision = 8))
        val result = f.finish()
        assertNull(result.completedWake)
        assertEquals(CollaborationSpaceRecoverySweep(id(2)), result.snapshot.progress!!.sweep)
    }

    @Test
    fun firstNamespaceAppearingAfterAnEmptySweepRemainsPending() = runTest {
        val f = Fixture()
        f.original = f.original.copy(sweep = f.original.sweep!!.copy(boundary = null))
        f.stored = f.original
        assertNull(f.finish().completedWake)
        assertEquals(CollaborationSpaceRecoverySweep(id(2)), f.stored!!.sweep)
    }

    @Test
    fun appendDuringTheFinalReadRequiresAnotherSweepEvenAtTheSameBoundary() = runTest {
        val f = Fixture()
        f.page = f.page.copy(hasNewerBindings = true)
        assertNull(f.finish().completedWake)
    }

    @Test
    fun deletedReplacedOrRewoundNamespaceCannotDischargeOldWork() = runTest {
        for (boundary in
            listOf(
                null,
                Fixture().boundary.copy(generation = id(90)),
                Fixture().boundary.copy(revision = 0),
            )) {
            val f = Fixture()
            f.page = f.page.copy(boundary = boundary)
            assertIs<AppResult.Failed>(f.useCase(f.snapshot()))
            assertEquals(0, f.writes)
            assertEquals(f.original, f.stored)
        }
    }

    @Test
    fun failureAndContinuationCannotBeErasedByACurrentInventory() = runTest {
        for (retry in listOf(false, true)) {
            val f = Fixture()
            f.original =
                f.original.copy(
                    sweep =
                        f.original.sweep!!.copy(
                            retryRequired = retry,
                            continuationRequired = !retry,
                        )
                )
            f.stored = f.original
            assertNull(f.finish().completedWake)
        }
    }

    @Test
    fun uncompletedClaimsAreRejectedBeforeInventoryOrStorage() = runTest {
        for (claim in listOf(null, CollaborationSpaceRecoveryClaim(null, null))) {
            val f = Fixture()
            f.original =
                f.original.copy(
                    sweep = f.original.sweep!!.copy(reachedEnd = false, pending = claim)
                )
            f.stored = f.original
            assertIs<AppResult.Failed>(f.useCase(f.snapshot()))
            assertEquals(0, f.reads)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun readFailureStaleCheckpointAndFailedCommitNeverReturnAnAcknowledgment() = runTest {
        for (mode in 0..2) {
            val f = Fixture()
            when (mode) {
                0 -> f.readFailure = f.failure
                1 -> f.stored = f.original.copy(generation = id(70))
                else -> f.writeFailure = f.failure
            }
            assertIs<AppResult.Failed>(f.useCase(f.snapshot()))
            assertNotNull(f.stored!!.sweep)
        }
    }

    @Test
    fun ownerReplacementDuringFinalReadStopsCompletion() = runTest {
        val f = Fixture()
        f.afterRead = { f.native.setup.session = null }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.useCase(f.snapshot())).failure.kind,
        )
        assertEquals(0, f.writes)
    }

    @Test
    fun cancellationAfterCommitDoesNotPublishAnAcknowledgment() = runTest {
        val f = Fixture()
        f.afterWrite = { currentCoroutineContext().cancel() }
        val job = launch {
            f.useCase(f.snapshot())
            error("Cancelled completion")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertNull(f.stored!!.sweep)
    }

    private class Fixture {
        val base = SpaceOriginalDeliveryFixture()
        val native = base.native
        val owner = base.queue.owner
        val boundary =
            CollaborationSpaceRoomBindingBoundary(
                owner.parent.audience,
                owner.parent.accountId,
                owner.spaceId,
                id(3),
                7,
            )
        var original =
            CollaborationSpaceRecoveryProgress(
                id(1),
                CollaborationSpaceRecoverySweep(id(2), boundary, reachedEnd = true),
            )
        var stored: CollaborationSpaceRecoveryProgress? = original
        var page = CollaborationSpaceRoomBindingPage(boundary, emptyList(), null, false)
        val failure = Failure(FailureKind.Storage, "Fixture unavailable")
        var readFailure: Failure? = null
        var writeFailure: Failure? = null
        var afterRead: suspend () -> Unit = {}
        var afterWrite: suspend () -> Unit = {}
        var reads = 0
        var writes = 0
        val inventory =
            object : CollaborationSpaceRoomBindingInventoryRepository {
                override suspend fun page(
                    access: CollaborationSpaceLocalAccess,
                    after: CollaborationSpaceRoomBindingCursor?,
                    limit: Int,
                ): AppResult<CollaborationSpaceRoomBindingPage> {
                    assertEquals(owner.localDirectoryAccess(), access)
                    assertNull(after)
                    assertEquals(1, limit)
                    reads++
                    val captured = page
                    afterRead()
                    return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(captured)
                }
            }
        val repository =
            object : CollaborationSpaceRecoveryProgressRepository {
                override suspend fun current(
                    owner: CollaborationSpaceOwner
                ): AppResult<CollaborationSpaceRecoveryProgress?> = error("Use captured checkpoint")

                override suspend fun replace(
                    owner: CollaborationSpaceOwner,
                    expected: CollaborationSpaceRecoveryProgress?,
                    sweep: CollaborationSpaceRecoverySweep?,
                ): AppResult<CollaborationSpaceRecoveryProgress?> {
                    writeFailure?.let {
                        return AppResult.Failed(it)
                    }
                    if (stored != expected) return AppResult.Success(null)
                    val saved = CollaborationSpaceRecoveryProgress(id(100 + ++writes), sweep)
                    stored = saved
                    afterWrite()
                    return AppResult.Success(saved)
                }
            }
        val useCase =
            FinishCollaborationSpaceRecovery(
                repository,
                inventory,
                native.setup.sessions,
                native.server,
            )

        fun snapshot() = CollaborationSpaceRecoverySnapshot(owner, original)

        suspend fun finish() =
            assertIs<AppResult.Success<CollaborationSpaceRecoveryUpdate>>(useCase(snapshot())).value
    }

    companion object {
        private fun id(n: Int) = "90000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    }
}
