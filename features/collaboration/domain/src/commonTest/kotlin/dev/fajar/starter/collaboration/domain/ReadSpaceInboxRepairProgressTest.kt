package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadSpaceInboxRepairProgressTest {
    @Test
    fun readingAbsentActiveAndCompletedProgressNeverCreatesDemand() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        for (state in
            listOf(
                null,
                f.initial,
                f.initial.copy(completedDemand = f.initial.latestDemand),
                f.initial.copy(
                    pass = CollaborationSpaceInboxRepairPass(f.initial.latestDemand, "cursor")
                ),
            )) {
            f.stored = state
            assertEquals(AppResult.Success(state), f.checkpoint()(f.access))
        }
        assertEquals(4, f.checkpointReads)
        assertTrue(f.replacements.isEmpty())
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.base.native.pageCalls)
    }

    @Test
    fun malformedCheckpointIsFailureRatherThanMissingWork() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        for (value in
            listOf(
                f.initial.copy(generation = "invalid"),
                f.initial.copy(
                    pass = CollaborationSpaceInboxRepairPass(f.initial.latestDemand, "")
                ),
                f.initial.copy(
                    completedDemand = f.initial.latestDemand,
                    pass = CollaborationSpaceInboxRepairPass(f.initial.latestDemand, null),
                ),
            )) {
            f.stored = value
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.checkpoint()(f.access)).failure.kind,
            )
        }
        assertTrue(f.replacements.isEmpty())
    }

    @Test
    fun knownDenialAndInvalidOwnerDoNotReadTheCheckpoint() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.checkpoint()(f.access.copy(spaceId = "invalid")))
                .failure
                .kind,
        )
        f.base.deny()
        assertEquals(AppResult.Failed(f.base.denied), f.checkpoint()(f.access))
        assertEquals(0, f.checkpointReads)
    }

    @Test
    fun ownershipChangesDuringReadOverrideCapturedSuccessOrStorageFailure() = runTest {
        for (deny in listOf(false, true)) for (failure in listOf(false, true)) {
            val f = SpaceInboxRepairUseCaseFixture()
            f.onCheckpoint = {
                if (deny) f.base.deny() else f.base.identity.value = AppResult.Success(null)
                if (failure) AppResult.Failed(f.base.storageFailure)
                else AppResult.Success(f.stored)
            }
            val result = assertIs<AppResult.Failed>(f.checkpoint()(f.access))
            assertEquals(
                if (deny) FailureKind.AccessDenied else FailureKind.Unauthorized,
                result.failure.kind,
            )
            assertEquals(1, f.checkpointReads)
        }
    }

    @Test
    fun cancellationDoesNotCreateAPassOrTreatItsLateResultAsMissing() = runTest {
        val f = SpaceInboxRepairUseCaseFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onCheckpoint = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                AppResult.Success(f.initial)
            }
        }
        val job = async { f.checkpoint()(f.access) }
        entered.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, f.checkpointReads)
        assertTrue(f.replacements.isEmpty())
    }
}
