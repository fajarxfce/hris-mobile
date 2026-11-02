package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryProgressRepository
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationRecoveryProgressTest {
    @Test
    fun healthyRemainingWorkSurvivesLaterSuccessAndRestartsWithoutAcknowledgment() = runTest {
        val f = ProgressFixture()
        f.change(CollaborationRecoveryChange.Begin(f.wake))
        f.change(CollaborationRecoveryChange.Attempt(progressId(1)))
        f.change(CollaborationRecoveryChange.Complete(progressId(1), false, true))
        f.change(CollaborationRecoveryChange.Attempt(progressId(2)))
        f.change(CollaborationRecoveryChange.Complete(progressId(2), false))
        val pending = f.snapshot().progress!!.sweep!!
        assertTrue(pending.continuationRequired)
        assertFalse(pending.retryRequired)
        val again = f.change(CollaborationRecoveryChange.Finish)
        assertNull(again.completedWake)
        assertEquals(CollaborationRecoverySweep(f.wake.generation), again.snapshot.progress!!.sweep)
        f.change(CollaborationRecoveryChange.Attempt(progressId(1)))
        f.change(CollaborationRecoveryChange.Complete(progressId(1), false))
        assertEquals(f.wake, f.change(CollaborationRecoveryChange.Finish).completedWake)
    }

    @Test
    fun continuationCannotEraseFailureOrAnInterruptedAttempt() = runTest {
        for (interrupted in listOf(false, true)) {
            val f = ProgressFixture()
            f.change(CollaborationRecoveryChange.Begin(f.wake))
            f.change(CollaborationRecoveryChange.Attempt(progressId(1)))
            if (!interrupted) f.change(CollaborationRecoveryChange.Complete(progressId(1), true))
            f.change(CollaborationRecoveryChange.Attempt(progressId(2)))
            f.change(CollaborationRecoveryChange.Complete(progressId(2), false, true))
            val pending = f.snapshot().progress!!.sweep!!
            assertTrue(pending.retryRequired)
            assertTrue(pending.continuationRequired)
            assertNull(f.change(CollaborationRecoveryChange.Finish).completedWake)
        }
    }

    @Test
    fun expiredTransportAndAbsentWorkspaceDoNotHideOwnedRecoveryProgress() = runTest {
        val f = ProgressFixture()
        f.setup.workspace = null
        f.setup.beforeWorkspace = { error("No selected-company dependency") }
        f.setup.session =
            f.setup.session!!.let {
                it.copy(
                    tokens = it.tokens.copy(expiresAtEpochMillis = 0),
                    binding = it.binding.copy(expiresAtEpochMillis = 0),
                )
            }
        assertNull(f.snapshot().progress)
        val begun = f.change(CollaborationRecoveryChange.Begin(f.wake))
        assertEquals(f.wake.generation, begun.snapshot.progress!!.sweep!!.wakeGeneration)
        assertEquals(0, f.setup.calls)
    }

    @Test
    fun incomingWakesCannotResetAnActiveSweepOrChangeItsCompletionGeneration() = runTest {
        val f = ProgressFixture()
        f.change(CollaborationRecoveryChange.Begin(f.wake))
        f.change(CollaborationRecoveryChange.Attempt(progressId(1)))
        f.change(CollaborationRecoveryChange.Complete(progressId(1), false))
        repeat(40) {
            val next =
                f.change(
                    CollaborationRecoveryChange.Begin(
                        CollaborationRecoveryWake(progressId(200 + it))
                    )
                )
            assertEquals(progressId(1), next.snapshot.progress!!.sweep!!.afterCompanyId)
            assertEquals(f.wake.generation, next.snapshot.progress!!.sweep!!.wakeGeneration)
            assertNull(next.completedWake)
        }
        f.change(CollaborationRecoveryChange.Attempt(progressId(2)))
        f.change(CollaborationRecoveryChange.Complete(progressId(2), false))
        val completed = f.change(CollaborationRecoveryChange.Finish)
        assertNull(completed.snapshot.progress!!.sweep)
        assertEquals(f.wake, completed.completedWake)
    }

    @Test
    fun interruptedAttemptAdvancesFairlyButRequiresAnotherSweepBeforeAcknowledgment() = runTest {
        val f = ProgressFixture()
        f.change(CollaborationRecoveryChange.Begin(f.wake))
        f.change(CollaborationRecoveryChange.Attempt(progressId(1)))
        // A new processing owner resumes after the first attempt was interrupted.
        val advanced = f.change(CollaborationRecoveryChange.Attempt(progressId(2)))
        assertTrue(advanced.snapshot.progress!!.sweep!!.retryRequired)
        f.change(CollaborationRecoveryChange.Complete(progressId(2), false))
        val again = f.change(CollaborationRecoveryChange.Finish)
        assertNull(again.completedWake)
        assertEquals(CollaborationRecoverySweep(f.wake.generation), again.snapshot.progress!!.sweep)
        for (id in 1..2) {
            f.change(CollaborationRecoveryChange.Attempt(progressId(id)))
            f.change(CollaborationRecoveryChange.Complete(progressId(id), false))
        }
        assertEquals(f.wake, f.change(CollaborationRecoveryChange.Finish).completedWake)
    }

    @Test
    fun aTransientFailureCannotBeClearedByALaterSuccessfulCompany() = runTest {
        val f = ProgressFixture()
        f.change(CollaborationRecoveryChange.Begin(f.wake))
        f.change(CollaborationRecoveryChange.Attempt(progressId(1)))
        f.change(CollaborationRecoveryChange.Complete(progressId(1), true))
        f.change(CollaborationRecoveryChange.Attempt(progressId(2)))
        f.change(CollaborationRecoveryChange.Complete(progressId(2), false))
        assertTrue(f.snapshot().progress!!.sweep!!.retryRequired)
        assertNull(f.change(CollaborationRecoveryChange.Finish).completedWake)
    }

    @Test
    fun unfinishedFinalCompanyCannotPublishACleanCompletion() = runTest {
        val f = ProgressFixture()
        f.change(CollaborationRecoveryChange.Begin(f.wake))
        f.change(CollaborationRecoveryChange.Attempt(progressId(1)))
        val finished = f.change(CollaborationRecoveryChange.Finish)
        assertNull(finished.completedWake)
        assertEquals(
            CollaborationRecoverySweep(f.wake.generation),
            finished.snapshot.progress!!.sweep,
        )
    }

    @Test
    fun anOldCompletionCannotOverwriteACompetingCheckpoint() = runTest {
        val f = ProgressFixture()
        f.change(CollaborationRecoveryChange.Begin(f.wake))
        val first = f.change(CollaborationRecoveryChange.Attempt(progressId(1))).snapshot
        val later = f.change(CollaborationRecoveryChange.Attempt(progressId(2))).snapshot
        val stale = f.update(first, CollaborationRecoveryChange.Complete(progressId(1), false))
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(stale).failure.kind)
        assertEquals(later, f.snapshot())
    }

    @Test
    fun malformedAndBackwardTransitionsNeverWriteProgress() = runTest {
        val f = ProgressFixture()
        for (change in
            listOf(
                CollaborationRecoveryChange.Attempt(progressId(1)),
                CollaborationRecoveryChange.Complete(progressId(1), false),
                CollaborationRecoveryChange.Finish,
                CollaborationRecoveryChange.Begin(CollaborationRecoveryWake("invalid")),
            )) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.update(f.snapshot(), change)).failure.kind,
        )
        assertEquals(0, f.writes)
        f.change(CollaborationRecoveryChange.Begin(f.wake))
        f.change(CollaborationRecoveryChange.Attempt(progressId(2)))
        val writes = f.writes
        for (change in
            listOf(
                CollaborationRecoveryChange.Attempt(progressId(1)),
                CollaborationRecoveryChange.Attempt(progressId(2)),
                CollaborationRecoveryChange.Complete(progressId(3), false),
            )) assertIs<AppResult.Failed>(f.update(f.snapshot(), change))
        assertEquals(writes, f.writes)
    }

    @Test
    fun lateAccountReplacementRejectsReadAndWritePublicationWithoutUndoingOldFacts() = runTest {
        val f = ProgressFixture()
        val initial = f.snapshot()
        val session = f.setup.session
        f.afterRead = { f.setup.session = session!!.copy(id = "replacement") }
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.read()).failure.kind)
        f.afterRead = {}
        f.setup.session = session
        f.afterWrite = { f.setup.session = session!!.copy(id = "replacement") }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.update(initial, CollaborationRecoveryChange.Begin(f.wake)))
                .failure
                .kind,
        )
        assertNotNull(f.stored)
        assertEquals(f.wake.generation, f.stored!!.sweep!!.wakeGeneration)
    }

    @Test
    fun storageFailureCannotReturnACompletionOrClearTheRetainedSweep() = runTest {
        val f = ProgressFixture()
        f.change(CollaborationRecoveryChange.Begin(f.wake))
        val before = f.snapshot()
        f.failure = f.storage
        assertEquals(AppResult.Failed(f.storage), f.read())
        assertEquals(
            AppResult.Failed(f.storage),
            f.update(before, CollaborationRecoveryChange.Finish),
        )
        assertEquals(before.progress, f.stored)
    }

    @Test
    fun alreadyCancelledOperationsPerformNoRepositoryIoAndLateCancellationKeepsTheCommit() =
        runTest {
            val f = ProgressFixture()
            val snapshot = f.snapshot()
            val reads = f.reads
            val cancelled = launch {
                currentCoroutineContext().cancel()
                f.read()
            }
            cancelled.join()
            assertTrue(cancelled.isCancelled)
            val update = launch {
                currentCoroutineContext().cancel()
                f.update(snapshot, CollaborationRecoveryChange.Begin(f.wake))
            }
            update.join()
            assertEquals(reads, f.reads)
            assertEquals(0, f.writes)
            f.afterWrite = { currentCoroutineContext().cancel() }
            val late = launch {
                f.update(snapshot, CollaborationRecoveryChange.Begin(f.wake))
                error("Cancelled publication")
            }
            late.join()
            assertTrue(late.isCancelled)
            assertNotNull(f.stored)
        }

    @Test
    fun lostGlobalAcknowledgmentRepeatsAFullSweepAfterTheLocalFinish() = runTest {
        val f = ProgressFixture()
        f.change(CollaborationRecoveryChange.Begin(f.wake))
        f.change(CollaborationRecoveryChange.Attempt(progressId(1)))
        f.change(CollaborationRecoveryChange.Complete(progressId(1), false))
        assertEquals(f.wake, f.change(CollaborationRecoveryChange.Finish).completedWake)
        val restarted = f.change(CollaborationRecoveryChange.Begin(f.wake))
        assertEquals(
            CollaborationRecoverySweep(f.wake.generation),
            restarted.snapshot.progress!!.sweep,
        )
        assertNull(restarted.completedWake)
    }

    @Test
    fun absentOrForeignAccountsCannotReadOrMutateCapturedProgress() = runTest {
        val f = ProgressFixture()
        val snapshot = f.snapshot()
        val session = f.setup.session!!
        val reads = f.reads
        for (replacement in
            listOf(
                null,
                session.copy(
                    binding = session.binding.copy(audience = "https://foreign.example.test/")
                ),
            )) {
            f.setup.session = replacement
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.read()).failure.kind,
            )
            assertIs<AppResult.Failed>(
                f.update(snapshot, CollaborationRecoveryChange.Begin(f.wake))
            )
        }
        assertEquals(reads, f.reads)
        assertEquals(0, f.writes)
    }
}

private fun progressId(id: Int) = "a0000000-0000-4000-8000-${id.toString().padStart(12, '0')}"

private class ProgressFixture {
    val setup =
        CollaborationSetupFixture().apply {
            session = initial.copy(user = initial.user.copy(id = progressId(99)))
        }
    val wake = CollaborationRecoveryWake(progressId(900))
    val storage = Failure(FailureKind.Storage, "Storage unavailable")
    var stored: CollaborationRecoveryProgress? = null
    var reads = 0
    var writes = 0
    var failure: Failure? = null
    var afterRead: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    val repository =
        object : CollaborationRecoveryProgressRepository {
            override suspend fun current(
                access: CollaborationRecoveryAccess
            ): AppResult<CollaborationRecoveryProgress?> {
                reads++
                failure?.let {
                    return AppResult.Failed(it)
                }
                val value = stored
                afterRead()
                return AppResult.Success(value)
            }

            override suspend fun replace(
                access: CollaborationRecoveryAccess,
                expected: CollaborationRecoveryProgress?,
                sweep: CollaborationRecoverySweep?,
            ): AppResult<CollaborationRecoveryProgress?> {
                failure?.let {
                    return AppResult.Failed(it)
                }
                if (stored != expected) return AppResult.Success(null)
                val value = CollaborationRecoveryProgress(progressId(1000 + ++writes), sweep)
                stored = value
                afterWrite()
                return AppResult.Success(value)
            }
        }
    val server = IdentityServer(setup.initial.binding.audience, "Fictional Android")
    val read = ReadCollaborationRecoveryProgress(repository, setup.sessions, server)
    val update = UpdateCollaborationRecoveryProgress(repository, setup.sessions, server)

    suspend fun snapshot() =
        assertIs<AppResult.Success<CollaborationRecoverySnapshot>>(read()).value

    suspend fun change(change: CollaborationRecoveryChange) =
        assertIs<AppResult.Success<CollaborationRecoveryUpdate>>(update(snapshot(), change)).value
}
