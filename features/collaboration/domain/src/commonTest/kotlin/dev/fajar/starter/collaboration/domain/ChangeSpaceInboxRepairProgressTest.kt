package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ChangeSpaceInboxRepairProgressTest {
    @Test
    fun requestingAgainPreservesTheCapturedPassAndExactReplayDoesNotRewrite() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val captured = CollaborationSpaceInboxRepairPass(f.initial.latestDemand, "page-two")
        f.stored = f.initial.copy(pass = captured)
        val request = CollaborationSpaceInboxRepairProgressChange.Request(f.id(20))
        val value =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress>>(
                    f.change()(f.access, request)
                )
                .value
        assertEquals(captured, value.pass)
        assertEquals(f.id(20), value.latestDemand)
        assertNotEquals(f.initial.generation, value.generation)
        assertEquals(AppResult.Success(value), f.change()(f.access, request))
        assertEquals(1, f.replacements.size)
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.base.native.pageCalls)
    }

    @Test
    fun completionAcknowledgesOnlyItsCapturedDemandAndThenBecomesIdle() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val pass = CollaborationSpaceInboxRepairPass(f.initial.latestDemand, "last-page")
        f.stored = f.initial.copy(latestDemand = f.id(20), pass = pass)
        val first =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress>>(
                    f.change()(
                        f.access,
                        CollaborationSpaceInboxRepairProgressChange.Advance(pass, null),
                    )
                )
                .value
        assertNull(first.pass)
        assertEquals(f.initial.latestDemand, first.completedDemand)
        assertEquals(f.id(20), first.latestDemand)
        val started =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress>>(
                    f.change()(f.access, CollaborationSpaceInboxRepairProgressChange.Begin)
                )
                .value
        val pendingPass = assertNotNull(started.pass)
        assertEquals(f.id(20), pendingPass.demand)
        val finished =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress>>(
                    f.change()(
                        f.access,
                        CollaborationSpaceInboxRepairProgressChange.Advance(pendingPass, null),
                    )
                )
                .value
        val writes = f.replacements.size
        assertEquals(
            AppResult.Success(finished),
            f.change()(f.access, CollaborationSpaceInboxRepairProgressChange.Begin),
        )
        assertNull(finished.pass)
        assertEquals(finished.latestDemand, finished.completedDemand)
        assertEquals(writes, f.replacements.size)
    }

    @Test
    fun genuineContentionStopsAfterThreeAttemptsWithoutScanningOrTransport() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.onReplace = { _, _ -> AppResult.Success(null) }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    f.change()(
                        f.access,
                        CollaborationSpaceInboxRepairProgressChange.Request(f.id(20)),
                    )
                )
                .failure
                .kind,
        )
        assertEquals(3, f.checkpointReads)
        assertEquals(3, f.replacements.size)
        assertEquals(setOf(f.id(20)), f.replacements.map { it.second.latestDemand }.toSet())
        assertEquals(f.initial, f.stored)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun competingLaterDemandIsRereadBeforeCapturedPassCompletion() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val pass = CollaborationSpaceInboxRepairPass(f.initial.latestDemand, "page-two")
        f.stored = f.initial.copy(pass = pass)
        f.onReplace = { expected, next ->
            if (f.replacements.size == 1) {
                f.stored = f.stored!!.copy(generation = f.id(30), latestDemand = f.id(31))
                AppResult.Success(null)
            } else {
                assertEquals(f.stored, expected)
                f.stored = next
                AppResult.Success(next)
            }
        }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress>>(
                    f.change()(
                        f.access,
                        CollaborationSpaceInboxRepairProgressChange.Advance(pass, null),
                    )
                )
                .value
        assertEquals(f.id(31), result.latestDemand)
        assertEquals(pass.demand, result.completedDemand)
        assertNull(result.pass)
        assertEquals(2, f.replacements.size)
    }

    @Test
    fun lostCommittedResponseIsRecoveredByExactRequestReplayWithoutAnotherWrite() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.onReplace = { _, next ->
            f.stored = next
            AppResult.Failed(f.base.storageFailure)
        }
        val request = CollaborationSpaceInboxRepairProgressChange.Request(f.id(20))
        assertEquals(AppResult.Failed(f.base.storageFailure), f.change()(f.access, request))
        val saved = assertNotNull(f.stored)
        assertEquals(f.id(20), saved.latestDemand)
        assertEquals(AppResult.Success(saved), f.change()(f.access, request))
        assertEquals(1, f.replacements.size)
    }

    @Test
    fun operationalFailureStopsImmediatelyWithoutClaimingProgress() = runTest {
        for (readFailure in listOf(false, true)) {
            val f = SpaceInboxRepairUseCaseFixture()
            if (readFailure) f.onCheckpoint = { AppResult.Failed(f.base.storageFailure) }
            else f.onReplace = { _, _ -> AppResult.Failed(f.base.storageFailure) }
            assertEquals(
                AppResult.Failed(f.base.storageFailure),
                f.change()(f.access, CollaborationSpaceInboxRepairProgressChange.Begin),
            )
            assertEquals(1, f.checkpointReads)
            assertEquals(if (readFailure) 0 else 1, f.replacements.size)
            assertEquals(f.initial, f.stored)
        }
    }

    @Test
    fun denialDuringContentionAndOwnershipReplacementAfterCommitStopOldWork() = runTest {
        for (denied in listOf(false, true)) {
            val f = SpaceInboxRepairUseCaseFixture()
            f.onReplace = { _, next ->
                if (denied) {
                    f.base.deny()
                    AppResult.Success(null)
                } else {
                    f.stored = next
                    f.base.identity.value = AppResult.Success(null)
                    AppResult.Success(next)
                }
            }
            val result =
                assertIs<AppResult.Failed>(
                    f.change()(f.access, CollaborationSpaceInboxRepairProgressChange.Begin)
                )
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                result.failure.kind,
            )
            assertEquals(1, f.checkpointReads)
            assertEquals(1, f.replacements.size)
        }
    }

    @Test
    fun cancellationAfterAnUncooperativeWriteDoesNotStartAnotherAttempt() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onReplace = { _, _ ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                AppResult.Success(null)
            }
        }
        val job = async { f.change()(f.access, CollaborationSpaceInboxRepairProgressChange.Begin) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, f.checkpointReads)
        assertEquals(1, f.replacements.size)
    }

    @Test
    fun invalidTransitionsAndMalformedRepositoryResponsesCannotAdvanceWork() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val invalid =
            listOf(
                CollaborationSpaceInboxRepairProgressChange.Request("invalid"),
                CollaborationSpaceInboxRepairProgressChange.Advance(
                    CollaborationSpaceInboxRepairPass(f.id(1), "cursor"),
                    "cursor",
                ),
                CollaborationSpaceInboxRepairProgressChange.Restart(
                    CollaborationSpaceInboxRepairPass(f.id(1), null)
                ),
            )
        for (change in invalid) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.change()(f.access, change)).failure.kind,
        )
        assertEquals(0, f.checkpointReads)
        f.stored = f.initial.copy(generation = "invalid")
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(
                    f.change()(f.access, CollaborationSpaceInboxRepairProgressChange.Begin)
                )
                .failure
                .kind,
        )
        assertTrue(f.replacements.isEmpty())
        f.stored = f.initial
        f.onReplace = { _, next -> AppResult.Success(next.copy(latestDemand = f.id(99))) }
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(
                    f.change()(f.access, CollaborationSpaceInboxRepairProgressChange.Begin)
                )
                .failure
                .kind,
        )
        assertEquals(1, f.replacements.size)
    }

    @Test
    fun stalePageCannotFinishANewerPassAndRestartKeepsTheLaterDemand() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val pass = CollaborationSpaceInboxRepairPass(f.initial.latestDemand, "page-two")
        f.stored = f.initial.copy(latestDemand = f.id(20), pass = pass)
        val stale = pass.copy(after = "older-page")
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    f.change()(
                        f.access,
                        CollaborationSpaceInboxRepairProgressChange.Advance(stale, null),
                    )
                )
                .failure
                .kind,
        )
        assertTrue(f.replacements.isEmpty())
        val restarted =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress>>(
                    f.change()(f.access, CollaborationSpaceInboxRepairProgressChange.Restart(pass))
                )
                .value
        assertEquals(pass.copy(after = null), restarted.pass)
        assertEquals(f.id(20), restarted.latestDemand)
        assertEquals(1, f.replacements.size)
    }
}
