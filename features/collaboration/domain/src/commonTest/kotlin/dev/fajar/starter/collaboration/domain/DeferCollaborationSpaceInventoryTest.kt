package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.usecases.DeferCollaborationSpaceInventory
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DeferCollaborationSpaceInventoryTest {
    @Test
    fun retryPreservesTheOriginalCursorPositionAndIndependentWakeWithoutNativeIo() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.captured = f.captured.copy(inventoryAfterRoomId = "!m:test")
        f.progress(f.captured)
        val wake = CollaborationSpaceIncomingWake(f.base.id(999))
        f.wake = wake
        val now = f.base.native.clock.now().toEpochMilliseconds()
        assertEquals(AppResult.Success(true), defer(f))
        assertEquals(
            f.captured.copy(inventoryRetry = CollaborationSpaceIncomingRetry(now, now + 30_000, 1)),
            f.snapshot?.demand?.sources?.single(),
        )
        assertEquals(wake, f.wake)
        assertEquals(1, f.writes)
        assertEquals(0, f.enumerations)
    }

    @Test
    fun replayAfterARecordedDeferralCannotExtendTheDeadline() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        assertEquals(AppResult.Success(true), defer(f))
        val retained = f.snapshot
        f.base.native.setup.now += 1.days
        assertEquals(AppResult.Success(true), defer(f))
        assertEquals(retained, f.snapshot)
        assertEquals(1, f.writes)
    }

    @Test
    fun aFutureCapturedRetryIsKeptWithoutAnotherWrite() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        val now = f.base.native.clock.now().toEpochMilliseconds()
        f.captured =
            f.captured.copy(inventoryRetry = CollaborationSpaceIncomingRetry(now, now + 30_000, 1))
        f.progress(f.captured)
        assertEquals(AppResult.Success(true), defer(f))
        assertEquals(0, f.writes)
        assertEquals(0, f.enumerations)
    }

    @Test
    fun newerLossOrCursorIsObsoleteWithoutDelayingNewWork() = runTest {
        for (cursor in listOf(false, true)) {
            val f = SpaceRoomInventoryRetentionFixture()
            val newer =
                if (cursor) f.captured.copy(inventoryAfterRoomId = "!new:test")
                else f.captured.copy(inventoryGeneration = f.base.id(55))
            f.progress(newer)
            assertEquals(AppResult.Success(false), defer(f))
            assertEquals(newer, f.snapshot?.demand?.sources?.single())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun continuousSyncPositionUpdatesSurviveTheRetainedRetry() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.progress(f.captured.copy(position = "later"))
        assertEquals(AppResult.Success(true), defer(f))
        assertEquals("later", f.snapshot?.demand?.sources?.single()?.position)
    }

    @Test
    fun aLostCommitReplyIsConfirmedWithoutRepeatingTheMutation() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.replyLost = true
        assertEquals(AppResult.Success(true), defer(f))
        val saved = f.snapshot
        assertEquals(AppResult.Success(true), defer(f))
        assertEquals(saved, f.snapshot)
        assertEquals(1, f.writes)
    }

    @Test
    fun aKnownCasMissUsesShortContentionWithoutInventingPersistentBackoff() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.hooks["write"] = { f.progress(f.captured.copy(position = "new-producer-position")) }
        assertTrue(
            isCollaborationSpaceIncomingQueueContention(
                assertIs<AppResult.Failed>(defer(f)).failure
            )
        )
        assertNull(f.snapshot?.demand?.sources?.single()?.inventoryRetry)
        assertEquals(1, f.writes)
    }

    @Test
    fun aStorageFailureWithoutCommitCannotClaimTheRetryWasSaved() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.commit = false
        f.replyLost = true
        assertEquals(f.storageFailure, assertIs<AppResult.Failed>(defer(f)).failure)
        assertNull(f.snapshot?.demand?.sources?.single()?.inventoryRetry)
        assertEquals(1, f.writes)
    }

    @Test
    fun newLossAfterAnUncertainCommitDoesNotConfirmTheOldDeferral() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.replyLost = true
        val newer = f.captured.copy(inventoryGeneration = f.base.id(56))
        f.hooks["committed"] = { f.progress(newer) }
        assertEquals(f.storageFailure, assertIs<AppResult.Failed>(defer(f)).failure)
        assertEquals(newer, f.snapshot?.demand?.sources?.single())
        assertEquals(1, f.writes)
    }

    @Test
    fun admissionFailuresAndQueueContentionAreNotConvertedIntoPersistentBackoff() = runTest {
        val failures =
            listOf(
                    FailureKind.Unauthorized,
                    FailureKind.AccessDenied,
                    FailureKind.Validation,
                    FailureKind.Conflict,
                )
                .map { Failure(it, "fixture") } +
                collaborationSpaceIncomingQueueContention().failure
        for (failure in failures) {
            val f = SpaceRoomInventoryRetentionFixture()
            assertEquals(failure, assertIs<AppResult.Failed>(defer(f, failure)).failure)
            assertTrue(f.calls.isEmpty())
            assertEquals(0, f.base.sourceReads)
        }
    }

    @Test
    fun invalidCaptureIsRejectedBeforeAnyAccountOrStoreRead() = runTest {
        for (generation in listOf(null, "invalid")) {
            val f = SpaceRoomInventoryRetentionFixture()
            f.captured = f.captured.copy(inventoryGeneration = generation)
            assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(defer(f)).failure.kind)
            assertTrue(f.calls.isEmpty())
            assertEquals(0, f.base.sourceReads)
        }
    }

    @Test
    fun missingOriginalAndUnconfirmedRegistrationNeverChangeTheQueue() = runTest {
        for (registration in listOf(false, true)) {
            val f = SpaceRoomInventoryRetentionFixture()
            if (registration) f.base.registration = null else f.base.installed = null
            assertIs<AppResult.Failed>(defer(f))
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test
    fun aFullRegistrationReplacementDuringTheReadRejectsTheLateSnapshot() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.hooks["demand1"] = {
            f.base.registration = assertNotNull(f.base.registration).copy(revision = 3)
        }
        assertIs<AppResult.Failed>(defer(f))
        assertEquals(0, f.writes)
    }

    @Test
    fun runtimeRetirementAtEverySuspendingBoundaryRejectsLateResults() = runTest {
        for (stage in listOf("demand1", "write", "committed", "demand2")) {
            val f = SpaceRoomInventoryRetentionFixture()
            f.hooks[stage] = { f.base.installed = null }
            assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(defer(f)).failure.kind)
            assertTrue(f.writes <= 1)
            assertEquals(0, f.enumerations)
        }
    }

    @Test
    fun confirmedDenialSurvivesFailedReadsAtEverySuspendingBoundary() = runTest {
        for (stage in listOf("demand1", "write", "committed", "demand2")) {
            val f = SpaceRoomInventoryRetentionFixture()
            f.hooks[stage] = { f.base.directory.deny() }
            f.failures[stage] = f.storageFailure
            assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(defer(f)).failure)
            assertTrue(f.writes <= 1)
        }
    }

    @Test
    fun aKnownWriteDenialSurvivesSubsequentRuntimeRetirement() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        val denied = Failure(FailureKind.AccessDenied, "fixture denied")
        f.failures["write"] = denied
        f.hooks["write"] = { f.base.installed = null }
        assertEquals(denied, assertIs<AppResult.Failed>(defer(f)).failure)
        assertNull(f.snapshot?.demand?.sources?.single()?.inventoryRetry)
    }

    @Test
    fun accountAndNativeSessionReplacementFenceEverySuspendingBoundary() = runTest {
        for (stage in listOf("demand1", "write", "committed", "demand2")) {
            for (native in listOf(false, true)) {
                val f = SpaceRoomInventoryRetentionFixture()
                f.hooks[stage] = {
                    val old = assertNotNull(f.base.native.setup.session)
                    f.base.native.setup.session =
                        if (native) old.copy(binding = old.binding.copy(serverId = f.base.id(26)))
                        else null
                }
                assertIs<AppResult.Failed>(defer(f), "$stage/$native")
                assertTrue(f.writes <= 1)
            }
        }
    }

    @Test
    fun aHungReadIsBoundedWithoutAWriteOrInlineRetry() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.hooks["demand1"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(defer(f)).failure.kind)
        assertEquals(5_000L, currentTime)
        assertEquals(1, f.reads)
        assertEquals(0, f.writes)
    }

    @Test
    fun cancellationAfterCommitKeepsTheSavedRetryAndIndependentWake() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        val wake = CollaborationSpaceIncomingWake(f.base.id(998))
        f.wake = wake
        f.hooks["committed"] = { throw CancellationException("lost waiter") }
        assertFailsWith<CancellationException> { defer(f) }
        val retained = f.snapshot
        assertNotNull(retained?.demand?.sources?.single()?.inventoryRetry)
        assertEquals(wake, f.wake)
        assertEquals(AppResult.Success(true), defer(f))
        assertEquals(retained, f.snapshot)
        assertEquals(1, f.writes)
    }

    @Test
    fun ordinaryCredentialAgeDoesNotEraseAnOwnedRetry() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        assertEquals(AppResult.Success(true), defer(f, Failure(FailureKind.Unavailable, "fixture")))
        assertNotNull(f.snapshot?.demand?.sources?.single()?.inventoryRetry)
        assertEquals(0, f.enumerations)
    }

    @Test
    fun backwardClockMovementStartsAnEligibleRetryFromTheCurrentClock() = runTest {
        val f = SpaceRoomInventoryRetentionFixture()
        val now = f.base.native.clock.now().toEpochMilliseconds()
        f.captured =
            f.captured.copy(
                inventoryRetry = CollaborationSpaceIncomingRetry(now + 1_000, now + 31_000, 1)
            )
        f.progress(f.captured)
        assertEquals(AppResult.Success(true), defer(f))
        assertEquals(
            CollaborationSpaceIncomingRetry(now, now + 60_000, 2),
            f.snapshot?.demand?.sources?.single()?.inventoryRetry,
        )
    }

    private suspend fun defer(f: SpaceRoomInventoryRetentionFixture, failure: Failure? = null) =
        DeferCollaborationSpaceInventory(
            f.demands,
            f.base.registrations,
            f.base.sync,
            f.base.directory.directory,
            f.base.native.setup.sessions,
            f.base.native.server,
            f.base.setup.build,
            f.base.native.clock,
        )(f.runtime, f.captured, failure)
}
