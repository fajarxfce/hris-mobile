package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RecoverCollaborationSpaceOriginalIncomingProviderTest {
    @Test
    fun missingCompanyProgressRestartsOnlyTheCapturedTask() = runTest {
        val f = SpaceOriginalProviderRecoveryFixture()
        f.provider.progress = null
        val before = assertNotNull(f.base.snapshot).demand
        assertEquals(AppResult.Success(true), f.run())
        val room = f.transition.room()
        assertEquals(CollaborationSpaceIncomingPhase.Latest, room.phase)
        assertEquals(f.room.captured.generation, room.generation)
        assertEquals(before.nextPosition, room.position)
        assertNull(room.retry)
        assertNull(f.provider.progress)
        assertTrue(f.provider.requests.isEmpty())
        assertTrue(f.provider.saved.isEmpty())
        assertTrue(f.provider.sourceReads.isEmpty())
        assertEquals(1, f.transition.writes)
    }

    @Test
    fun unknownHeadAfterDrainedExhaustionRetainsBackoffAndExistingHistory() = runTest {
        val f = SpaceOriginalProviderRecoveryFixture()
        assertEquals(AppResult.Success(true), f.run())
        val retry = assertNotNull(f.transition.room().retry)
        assertEquals(30_000L, retry.eligibleAtEpochMillis - retry.attemptedAtEpochMillis)
        assertEquals(1, retry.failures)
        assertEquals(f.transition.checkpoint, f.provider.progress)
        assertEquals(listOf(f.room.captured.headEventId), f.provider.sourceReads)
        assertTrue(f.provider.requests.isEmpty())
    }

    @Test
    fun unresolvedEncryptedReferenceWaitsLocallyWithoutRestartingProvider() = runTest {
        val f = SpaceOriginalProviderRecoveryFixture()
        f.pending = listOf(f.head(resolved = false))
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(f.room.captured, f.transition.room())
        assertEquals(0, f.transition.writes)
        assertTrue(f.provider.sourceReads.isEmpty())
        assertTrue(f.provider.requests.isEmpty())
    }

    @Test
    fun previouslyResolvedHeadDoesNotFetchTheSameHistoryAgain() = runTest {
        val f = SpaceOriginalProviderRecoveryFixture()
        f.provider.references[requireNotNull(f.room.captured.headEventId)] = f.head()
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(0, f.transition.writes)
        assertEquals(1, f.provider.sourceReads.size)
    }

    @Test
    fun unfinishedCursorAndAbsentHintRemainUnchanged() = runTest {
        for (variant in 0..1) {
            val f = SpaceOriginalProviderRecoveryFixture()
            if (variant == 0) f.provider.progress = f.transition.checkpoint.copy(next = "next")
            else f.capture(f.room.captured.copy(headEventId = null))
            assertEquals(AppResult.Success(false), f.run())
            assertEquals(0, f.transition.writes)
            assertTrue(f.provider.sourceReads.isEmpty())
        }
    }

    @Test
    fun latestAndProviderPhasesCannotBeRecoveredAsPending() = runTest {
        for (phase in
            listOf(
                CollaborationSpaceIncomingPhase.Latest,
                CollaborationSpaceIncomingPhase.Provider,
            )) {
            val f = SpaceOriginalProviderRecoveryFixture()
            f.capture(f.room.captured.copy(phase = phase))
            assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertTrue(f.provider.calls.isEmpty())
        }
    }

    @Test
    fun aGroupProgressFrameCannotReplaceOriginalCompanyProgress() = runTest {
        val f = SpaceOriginalProviderRecoveryFixture()
        f.provider.progress =
            f.transition.checkpoint.copy(
                provider =
                    CollaborationHistoryProviderIdentity(
                        f.base.position.companyId,
                        f.provider.traversal.deviceId,
                    )
            )
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(0, f.transition.writes)
    }

    @Test
    fun missingFlowOversizedOrResolvedPendingPageIsNotEmptyHistory() = runTest {
        for (variant in 0..3) {
            val f = SpaceOriginalProviderRecoveryFixture()
            when (variant) {
                0 -> f.emptyFlow = true
                1 -> f.pending = listOf(f.head(false), f.head(false))
                2 -> f.pending = listOf(f.head(true))
                else ->
                    f.malformedPage =
                        CollaborationHistoryReferences(null, listOf(f.head(false)), null)
            }
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(0, f.transition.writes)
            assertTrue(f.provider.sourceReads.isEmpty())
        }
    }

    @Test
    fun headLookupRequiresMatchingCheckpointIdentityAndResolvedReference() = runTest {
        for (variant in 0..3) {
            val f = SpaceOriginalProviderRecoveryFixture()
            val head = f.head()
            f.provider.sourceResponses[requireNotNull(f.room.captured.headEventId)] =
                when (variant) {
                    0 ->
                        CollaborationHistorySource(f.transition.checkpoint.copy(revision = 1), null)
                    1 ->
                        CollaborationHistorySource(
                            f.transition.checkpoint,
                            head.copy(resolved = false),
                        )
                    2 ->
                        CollaborationHistorySource(f.transition.checkpoint, head.copy(sequence = 2))
                    else ->
                        CollaborationHistorySource(
                            f.transition.checkpoint,
                            head.copy(event = head.event.copy(eventId = "\$other")),
                        )
                }
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(0, f.transition.writes)
        }
    }

    @Test
    fun progressChangedAfterDiscoveryCannotTriggerObsoleteRecovery() = runTest {
        val f = SpaceOriginalProviderRecoveryFixture()
        f.provider.hooks["progress"] = {
            f.provider.progress = f.transition.checkpoint.copy(revision = 1)
        }
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(0, f.transition.writes)
        assertEquals(f.room.captured, f.transition.room())
    }

    @Test
    fun replayConfirmsPriorRecoveryWithoutLookingUpHistoryAgain() = runTest {
        val f = SpaceOriginalProviderRecoveryFixture()
        assertEquals(AppResult.Success(true), f.run())
        val calls = f.provider.sourceReads.size
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(1, f.transition.writes)
        assertEquals(calls, f.provider.sourceReads.size)
    }

    @Test
    fun absentTaskAndNewGenerationNeverRestartOldWork() = runTest {
        for (variant in 0..2) {
            val f = SpaceOriginalProviderRecoveryFixture()
            val demand = assertNotNull(f.base.snapshot).demand
            when (variant) {
                0 -> f.base.snapshot = null
                1 -> f.base.demand(demand.copy(rooms = emptyList()))
                else ->
                    f.base.demand(
                        demand.copy(
                            rooms = listOf(f.room.captured.copy(generation = f.base.id(135)))
                        )
                    )
            }
            assertEquals(AppResult.Success(false), f.run())
            assertEquals(0, f.transition.writes)
            assertFalse("pending" in f.provider.calls)
        }
    }

    @Test
    fun expiredCredentialAgeAndSelectedCompanyDoNotErasePendingOwnership() = runTest {
        val f = SpaceOriginalProviderRecoveryFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        f.provider.progress = null
        assertEquals(AppResult.Success(true), f.run())
    }

    @Test
    fun sourceOrMetadataChangeDuringReadsPreventsRecovery() = runTest {
        for (stage in listOf("demand", "pending", "source:\$head", "progress")) {
            for (change in listOf("source", "metadata", "registration", "account", "denial")) {
                val f = SpaceOriginalProviderRecoveryFixture()
                f.provider.hooks[stage] = {
                    when (change) {
                        "source" -> f.base.installed = null
                        "metadata" -> f.base.retained = null
                        "registration" -> f.base.registration = null
                        "account" -> f.base.native.setup.session = null
                        "denial" -> f.base.directory.deny()
                    }
                }
                val failure = assertIs<AppResult.Failed>(f.run()).failure
                if (change == "denial") assertEquals(f.base.directory.denied, failure)
                assertEquals(0, f.transition.writes)
                assertEquals(f.room.captured, f.transition.room())
            }
        }
    }

    @Test
    fun failedPendingAndSourceReadsArePreservedWithoutRestart() = runTest {
        for (stage in listOf("pending", "source:\$head", "progress")) {
            val f = SpaceOriginalProviderRecoveryFixture()
            f.provider.failures[stage] = f.base.directory.storageFailure
            assertEquals(
                f.base.directory.storageFailure,
                assertIs<AppResult.Failed>(f.run()).failure,
            )
            assertEquals(0, f.transition.writes)
        }
    }

    @Test
    fun committedRecoveryWithLostReplySurvivesIndependentProducerProgress() = runTest {
        val f = SpaceOriginalProviderRecoveryFixture()
        f.transition.lostReply = true
        f.provider.hooks["written"] = { f.transition.independentProducer() }
        assertEquals(AppResult.Success(true), f.run())
        assertEquals("later", assertNotNull(f.base.snapshot).demand.sources.single().position)
        assertEquals(CollaborationSpaceIncomingPhase.Latest, f.transition.room().phase)
        assertEquals(1, f.transition.writes)
    }

    @Test
    fun producerBeforeCasCannotBeOverwrittenByRecovery() = runTest {
        val f = SpaceOriginalProviderRecoveryFixture()
        f.provider.hooks["write"] = { f.transition.independentProducer() }
        val failure = assertIs<AppResult.Failed>(f.run()).failure
        assertTrue(isCollaborationSpaceIncomingQueueContention(failure))
        assertEquals(1L, failure.retryAfterSeconds)
        assertEquals(f.room.captured, f.transition.room())
        assertEquals(1, f.transition.writes)
    }

    @Test
    fun failedWriteWithoutCommitDoesNotClaimRecovery() = runTest {
        val f = SpaceOriginalProviderRecoveryFixture()
        f.transition.commit = false
        f.transition.lostReply = true
        assertEquals(f.base.directory.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(f.room.captured, f.transition.room())
    }

    @Test
    fun localRecoveryDeadlineNeverTurnsIntoAnAutomaticProviderRequest() = runTest {
        for (stage in
            listOf("metadata", "registration", "pending", "source:\$head", "progress", "write")) {
            val f = SpaceOriginalProviderRecoveryFixture()
            f.provider.hooks[stage] = { awaitCancellation() }
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(f.room.captured, f.transition.room())
            assertTrue(f.provider.requests.isEmpty())
        }
    }

    @Test
    fun cancellationAfterWriteLeavesDurableRecoveryForNextInvocation() = runTest {
        val f = SpaceOriginalProviderRecoveryFixture()
        f.provider.hooks["written"] = { throw CancellationException("retired") }
        assertFailsWith<CancellationException> { f.run() }
        assertEquals(CollaborationSpaceIncomingPhase.Latest, f.transition.room().phase)
        assertEquals(f.transition.checkpoint, f.provider.progress)
        f.provider.hooks.clear()
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(1, f.transition.writes)
    }
}
