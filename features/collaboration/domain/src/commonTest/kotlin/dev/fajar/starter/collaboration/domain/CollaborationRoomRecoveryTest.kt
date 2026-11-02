package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRoomRecoveryRepository
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationRoomRecoveryTest {
    @Test
    fun expiredCredentialsAndAbsentWorkspaceDoNotHideOwnedRoomProgress() = runTest {
        val f = RoomRecoveryFixture()
        f.base.setup.workspace = null
        f.base.setup.beforeWorkspace = { error("No selected workspace dependency") }
        f.base.setup.session =
            f.base.setup.session!!.let {
                it.copy(
                    tokens = it.tokens.copy(expiresAtEpochMillis = 0),
                    binding = it.binding.copy(expiresAtEpochMillis = 0),
                )
            }
        assertNull(f.snapshot().progress)
        val attempted = f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1)))
        assertEquals(
            roomRecoveryId(1),
            attempted.snapshot.progress!!.position.inFlightConversationId,
        )
        assertEquals(0, f.base.setup.calls)
    }

    @Test
    fun healthyRemainingWorkSurvivesLaterRoomsAndFinishDoesNotClaimCovered() = runTest {
        val f = RoomRecoveryFixture()
        f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1)))
        f.change(CollaborationRoomRecoveryChange.Complete(roomRecoveryId(1), false, true))
        f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(2)))
        f.change(CollaborationRoomRecoveryChange.Complete(roomRecoveryId(2), false))
        val before = f.snapshot()
        assertTrue(before.progress!!.position.continuationRequired)
        assertFalse(before.progress!!.position.retryRequired)
        val finished = f.change(CollaborationRoomRecoveryChange.Finish)
        assertEquals(CollaborationRoomRecoveryCompletion.Continue, finished.completion)
        assertEquals(CollaborationRoomRecoveryPosition(), finished.snapshot.progress!!.position)
        assertNotEquals(before.progress!!.generation, finished.snapshot.progress!!.generation)
        f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1)))
        f.change(CollaborationRoomRecoveryChange.Complete(roomRecoveryId(1), false))
        assertEquals(
            CollaborationRoomRecoveryCompletion.Covered,
            f.change(CollaborationRoomRecoveryChange.Finish).completion,
        )
    }

    @Test
    fun interruptedRoomAdvancesBeforeRetryAndCannotBeClearedByHealthyWork() = runTest {
        val f = RoomRecoveryFixture()
        f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1)))
        val advanced = f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(2)))
        assertTrue(advanced.snapshot.progress!!.position.retryRequired)
        f.change(CollaborationRoomRecoveryChange.Complete(roomRecoveryId(2), false, true))
        val finished = f.change(CollaborationRoomRecoveryChange.Finish)
        assertEquals(CollaborationRoomRecoveryCompletion.Retry, finished.completion)
        assertEquals(CollaborationRoomRecoveryPosition(), finished.snapshot.progress!!.position)
    }

    @Test
    fun failedRoomAndUnfinishedFinalRoomBothPreventCleanCoverage() = runTest {
        for (completed in listOf(false, true)) {
            val f = RoomRecoveryFixture()
            f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1)))
            if (completed)
                f.change(CollaborationRoomRecoveryChange.Complete(roomRecoveryId(1), true))
            assertEquals(
                CollaborationRoomRecoveryCompletion.Retry,
                f.change(CollaborationRoomRecoveryChange.Finish).completion,
            )
        }
    }

    @Test
    fun emptyDirectoryCanFinishButOldFinishCannotEraseACompetingAttempt() = runTest {
        val f = RoomRecoveryFixture()
        val empty = f.snapshot()
        assertEquals(
            CollaborationRoomRecoveryCompletion.Covered,
            f.change(CollaborationRoomRecoveryChange.Finish).completion,
        )
        val attempted =
            f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1))).snapshot
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.update(empty, CollaborationRoomRecoveryChange.Finish))
                .failure
                .kind,
        )
        assertEquals(attempted, f.snapshot())
    }

    @Test
    fun oldCompletionsCannotOverwriteAnotherProcessingOwner() = runTest {
        val f = RoomRecoveryFixture()
        val first = f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1))).snapshot
        val later = f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(2))).snapshot
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    f.update(
                        first,
                        CollaborationRoomRecoveryChange.Complete(roomRecoveryId(1), false),
                    )
                )
                .failure
                .kind,
        )
        assertEquals(later, f.snapshot())
    }

    @Test
    fun malformedBackwardAndMismatchedTransitionsNeverWrite() = runTest {
        val f = RoomRecoveryFixture()
        for (change in
            listOf(
                CollaborationRoomRecoveryChange.Attempt("invalid"),
                CollaborationRoomRecoveryChange.Complete(roomRecoveryId(1), false),
            )) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.update(f.snapshot(), change)).failure.kind,
        )
        assertEquals(0, f.writes)
        f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(2)))
        val before = f.snapshot()
        for (change in
            listOf(
                CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1)),
                CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(2)),
                CollaborationRoomRecoveryChange.Complete(roomRecoveryId(3), false),
            )) assertIs<AppResult.Failed>(f.update(before, change))
        assertEquals(1, f.writes)
        assertEquals(before, f.snapshot())
    }

    @Test
    fun knownDenialGatesRecoveryBeforeStorageAndRemainsAuthoritativeAfterLateIo() = runTest {
        val f = RoomRecoveryFixture()
        val initial = f.snapshot()
        f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
        val reads = f.reads
        assertEquals(AppResult.Failed(f.base.denied), f.read(f.access))
        assertEquals(
            AppResult.Failed(f.base.denied),
            f.update(initial, CollaborationRoomRecoveryChange.Finish),
        )
        assertEquals(reads, f.reads)
        assertEquals(0, f.writes)
        f.base.gate.value = CollaborationListAdmission(null, null)
        f.afterWrite = { f.base.gate.value = CollaborationListAdmission("late", f.base.denied) }
        assertEquals(
            AppResult.Failed(f.base.denied),
            f.update(initial, CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1))),
        )
        assertEquals(roomRecoveryId(1), f.stored!!.position.inFlightConversationId)
    }

    @Test
    fun replacementAccountSuppressesLateReadsAndWritesWithoutUndoingCommittedFacts() = runTest {
        val f = RoomRecoveryFixture()
        val initial = f.snapshot()
        val session = f.base.setup.session
        f.afterRead = { f.base.setup.session = session!!.copy(id = "replacement") }
        assertIs<AppResult.Failed>(f.read(f.access))
        f.afterRead = {}
        f.base.setup.session = session
        f.afterWrite = { f.base.setup.session = session!!.copy(id = "replacement") }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(
                    f.update(initial, CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1)))
                )
                .failure
                .kind,
        )
        assertNotNull(f.stored)
        val reads = f.reads
        assertIs<AppResult.Failed>(f.read(f.access))
        assertEquals(reads, f.reads)
    }

    @Test
    fun storageFailureCannotReturnCompletionOrResetTheAttempt() = runTest {
        val f = RoomRecoveryFixture()
        val before = f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1))).snapshot
        f.failure = f.base.storage
        assertEquals(AppResult.Failed(f.base.storage), f.read(f.access))
        assertEquals(
            AppResult.Failed(f.base.storage),
            f.update(before, CollaborationRoomRecoveryChange.Finish),
        )
        assertEquals(before.progress, f.stored)
    }

    @Test
    fun cancellationBeforeEntryDoesNoIoAndCancellationAfterCommitRetainsProgress() = runTest {
        val f = RoomRecoveryFixture()
        val initial = f.snapshot()
        val reads = f.reads
        for (write in listOf(false, true)) {
            val job = launch {
                currentCoroutineContext().cancel()
                if (write) f.update(initial, CollaborationRoomRecoveryChange.Finish)
                else f.read(f.access)
            }
            job.join()
            assertTrue(job.isCancelled)
        }
        assertEquals(reads, f.reads)
        assertEquals(0, f.writes)
        f.afterWrite = { currentCoroutineContext().cancel() }
        val late = launch {
            f.update(initial, CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1)))
            error("Cancelled publication")
        }
        late.join()
        assertTrue(late.isCancelled)
        assertNotNull(f.stored)
    }

    @Test
    fun cancellationWhileStorageIsPendingRejectsALateNoncancellableRead() = runTest {
        val f = RoomRecoveryFixture()
        val before = f.change(CollaborationRoomRecoveryChange.Attempt(roomRecoveryId(1))).snapshot
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.afterRead = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val job = launch {
            f.read(f.access)
            error("Cancelled publication")
        }
        entered.await()
        job.cancel()
        assertFalse(job.isCompleted)
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(before.progress, f.stored)
        assertEquals(1, f.writes)
    }
}

private fun roomRecoveryId(id: Int) = "a4000000-0000-4000-8000-${id.toString().padStart(12, '0')}"

private class RoomRecoveryFixture {
    val base = MessageIntentFixture()
    val access =
        CollaborationLocalAccess(
            base.setup.session!!.id,
            base.server.audience,
            base.setup.session!!.user.id,
            base.summary!!.conversation.companyId,
        )
    var stored: CollaborationRoomRecoveryProgress? = null
    var reads = 0
    var writes = 0
    var failure: Failure? = null
    var afterRead: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    val repository =
        object : CollaborationRoomRecoveryRepository {
            override suspend fun current(
                access: CollaborationLocalAccess
            ): AppResult<CollaborationRoomRecoveryProgress?> {
                reads++
                failure?.let {
                    return AppResult.Failed(it)
                }
                val value = stored
                afterRead()
                return AppResult.Success(value)
            }

            override suspend fun replace(
                access: CollaborationLocalAccess,
                expected: CollaborationRoomRecoveryProgress?,
                position: CollaborationRoomRecoveryPosition,
            ): AppResult<CollaborationRoomRecoveryProgress?> {
                failure?.let {
                    return AppResult.Failed(it)
                }
                if (stored != expected) return AppResult.Success(null)
                val value =
                    CollaborationRoomRecoveryProgress(roomRecoveryId(1000 + ++writes), position)
                stored = value
                afterWrite()
                return AppResult.Success(value)
            }
        }
    val read =
        ReadCollaborationRoomRecovery(repository, base.lists, base.setup.sessions, base.server)
    val update =
        UpdateCollaborationRoomRecovery(repository, base.lists, base.setup.sessions, base.server)

    suspend fun snapshot() =
        assertIs<AppResult.Success<CollaborationRoomRecoverySnapshot>>(read(access)).value

    suspend fun change(change: CollaborationRoomRecoveryChange) =
        assertIs<AppResult.Success<CollaborationRoomRecoveryUpdate>>(update(snapshot(), change))
            .value
}
