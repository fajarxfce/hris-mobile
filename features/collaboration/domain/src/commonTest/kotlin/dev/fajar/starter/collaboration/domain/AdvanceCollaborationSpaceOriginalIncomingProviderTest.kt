package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class AdvanceCollaborationSpaceOriginalIncomingProviderTest {
    @Test
    fun savedExhaustionCanConfirmProviderWithoutCreatingAnotherPage() = runTest {
        val f =
            SpaceOriginalProviderTransitionFixture(phase = CollaborationSpaceIncomingPhase.Provider)
        f.receipt = f.receipt.copy(previous = f.checkpoint, references = 0)
        assertTrue(validCollaborationSpaceOriginalProviderAcquisition(f.receipt))
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.room().phase)
        assertTrue(f.provider.requests.isEmpty())
        assertEquals(1, f.writes)
    }

    @Test
    fun groupRegistrationReceiptIsNotTheOriginalPhysicalDevice() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        f.receipt =
            f.receipt.copy(
                room =
                    f.receipt.room.copy(
                        traversal = f.provider.traversal.copy(deviceId = f.base.runtime.deviceId)
                    )
            )
        assertTrue(validCollaborationSpaceOriginalProviderAcquisition(f.receipt))
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertFalse("progress" in f.provider.calls)
        assertEquals(0, f.writes)
    }

    @Test
    fun registrationRevisionCannotChangeWithinOneLocalTransition() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        f.provider.hooks["progress"] = {
            val old = assertNotNull(f.base.registration)
            f.base.registration = old.copy(revision = old.revision + 1)
        }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(0, f.writes)
    }

    @Test
    fun admittedCheckpointClearsOnlyThisTasksRetry() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        val captured = f.captured.copy(retry = CollaborationSpaceIncomingRetry(10, 30_010, 1))
        f.receipt = f.receipt.copy(room = f.receipt.room.copy(captured = captured))
        f.base.demand(assertNotNull(f.base.snapshot).demand.copy(rooms = listOf(captured)))
        assertEquals(AppResult.Success(true), f.run())
        assertNull(f.room().retry)
        assertEquals(captured.sourceId, f.room().sourceId)
    }

    @Test
    fun latestCheckpointMovesToProviderAndExhaustionMovesToPending() = runTest {
        for (exhausted in listOf(false, true)) {
            val f = SpaceOriginalProviderTransitionFixture(exhausted)
            assertEquals(AppResult.Success(true), f.run())
            assertEquals(
                if (exhausted) CollaborationSpaceIncomingPhase.Pending
                else CollaborationSpaceIncomingPhase.Provider,
                f.room().phase,
            )
            assertEquals(f.captured, f.room().copy(phase = f.captured.phase))
            assertEquals(1, f.writes)
            assertEquals(f.checkpoint, f.provider.progress)
            assertTrue(f.provider.requests.isEmpty())
            assertTrue(f.provider.saved.isEmpty())
            assertTrue(f.provider.sourceReads.isEmpty())
        }
    }

    @Test
    fun continuationNeedsNoQueueWriteUntilProviderIsExhausted() = runTest {
        for (exhausted in listOf(false, true)) {
            val f =
                SpaceOriginalProviderTransitionFixture(
                    exhausted,
                    CollaborationSpaceIncomingPhase.Provider,
                )
            assertEquals(AppResult.Success(true), f.run())
            assertEquals(if (exhausted) 1 else 0, f.writes)
        }
    }

    @Test
    fun replayConfirmsRetainedCheckpointWithoutAnotherQueueWrite() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(1, f.writes)
        f.provider.progress = null
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(1, f.writes)
    }

    @Test
    fun expiredCredentialsAndSelectedCompanyDoNotInvalidateLocalConfirmation() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        f.base.native.setup.now += 365.days
        f.base.native.setup.workspace = null
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(1, f.writes)
    }

    @Test
    fun malformedReceiptCannotReadOrWriteLocalState() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        f.receipt = f.receipt.copy(references = 21)
        assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertTrue(f.provider.calls.isEmpty())
        assertEquals(0, f.base.sourceReads)
    }

    @Test
    fun originalCompanyFrameCannotBecomeGroupProviderFrame() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        f.receipt =
            f.receipt.copy(
                progress =
                    f.checkpoint.copy(
                        provider =
                            CollaborationHistoryProviderIdentity(
                                f.base.position.companyId,
                                f.provider.traversal.deviceId,
                            )
                    )
            )
        assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(0, f.writes)
        assertTrue(f.provider.calls.isEmpty())
    }

    @Test
    fun absentOrSupersededTaskReturnsFalseWithoutChangingTheQueue() = runTest {
        for (variant in 0..2) {
            val f = SpaceOriginalProviderTransitionFixture()
            val demand = assertNotNull(f.base.snapshot).demand
            when (variant) {
                0 -> f.base.snapshot = null
                1 -> f.base.demand(demand.copy(rooms = emptyList()))
                else ->
                    f.base.demand(
                        demand.copy(rooms = listOf(f.captured.copy(generation = f.base.id(130))))
                    )
            }
            assertEquals(AppResult.Success(false), f.run())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun changedHeadOrSourceUnderSameGenerationCannotBeAcknowledged() = runTest {
        for (variant in 0..1) {
            val f = SpaceOriginalProviderTransitionFixture()
            val changed =
                if (variant == 0) f.captured.copy(headEventId = "\$later")
                else f.captured.copy(sourceId = "b".repeat(64))
            f.base.demand(assertNotNull(f.base.snapshot).demand.copy(rooms = listOf(changed)))
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun absentOrChangedCompanyCheckpointCannotAdvanceTheTask() = runTest {
        for (variant in 0..2) {
            val f = SpaceOriginalProviderTransitionFixture()
            f.provider.progress =
                when (variant) {
                    0 -> null
                    1 -> f.checkpoint.copy(revision = f.checkpoint.revision + 1)
                    else -> f.checkpoint.copy(generation = f.base.id(131))
                }
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun replacedPhysicalStoreCannotReadTheQueue() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        f.base.installed = f.base.source.copy(storeId = "b".repeat(64))
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertTrue(f.provider.calls.isEmpty())
    }

    @Test
    fun missingMetadataAndRegistrationCannotBeReacquiredHere() = runTest {
        for (variant in 0..1) {
            val f = SpaceOriginalProviderTransitionFixture()
            if (variant == 0) f.base.retained = null else f.base.registration = null
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(0, f.writes)
            assertTrue(f.provider.requests.isEmpty())
        }
    }

    @Test
    fun changesDuringProgressReadLeaveTheQueueUntouched() = runTest {
        for (change in
            listOf("account", "native", "source", "metadata", "registration", "denial")) {
            val f = SpaceOriginalProviderTransitionFixture()
            f.provider.hooks["progress"] = {
                when (change) {
                    "account" -> f.base.native.setup.session = null
                    "native" -> {
                        val old = assertNotNull(f.base.native.setup.session)
                        f.base.native.setup.session =
                            old.copy(binding = old.binding.copy(serverId = f.base.id(132)))
                    }
                    "source" -> f.base.installed = null
                    "metadata" -> f.base.retained = null
                    "registration" -> f.base.registration = null
                    "denial" -> f.base.directory.deny()
                }
            }
            val failure = assertIs<AppResult.Failed>(f.run()).failure
            if (change == "denial") assertEquals(f.base.directory.denied, failure)
            assertEquals(0, f.writes)
            assertEquals(f.captured, f.room())
        }
    }

    @Test
    fun committedWriteWithLostReplyIsConfirmedFromRetainedTask() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        f.lostReply = true
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.room().phase)
        assertEquals(1, f.writes)
    }

    @Test
    fun lostReplyWithoutCommitRemainsStorageFailure() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        f.commit = false
        f.lostReply = true
        assertEquals(f.base.directory.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(f.captured, f.room())
        assertEquals(1, f.writes)
    }

    @Test
    fun aReturnedSnapshotAloneCannotProveTheWrite() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        f.commit = false
        f.fabricatedReply = true
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(f.captured, f.room())
    }

    @Test
    fun producerBeforeCasGetsBoundedContentionWithoutOverwritingItsPosition() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        f.provider.hooks["write"] = { f.independentProducer() }
        val failure = assertIs<AppResult.Failed>(f.run()).failure
        assertTrue(isCollaborationSpaceIncomingQueueContention(failure))
        assertEquals(1L, failure.retryAfterSeconds)
        assertEquals("later", assertNotNull(f.base.snapshot).demand.sources.single().position)
        assertEquals(f.captured, f.room())
        assertEquals(1, f.writes)
    }

    @Test
    fun producerAfterCommitDoesNotInvalidateTheExactLogicalReceipt() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        f.lostReply = true
        f.provider.hooks["written"] = { f.independentProducer() }
        assertEquals(AppResult.Success(true), f.run())
        assertEquals("later", assertNotNull(f.base.snapshot).demand.sources.single().position)
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.room().phase)
        assertEquals(1, f.writes)
    }

    @Test
    fun denialAfterCommitSurvivesCleanupFailureAndCannotPublishSuccess() = runTest {
        val f = SpaceOriginalProviderTransitionFixture()
        f.provider.hooks["written"] = {
            f.base.directory.deny()
            f.base.directory.afterRestrict = { AppResult.Failed(f.base.directory.storageFailure) }
        }
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(CollaborationSpaceIncomingPhase.Pending, f.room().phase)
        assertEquals(f.checkpoint, f.provider.progress)
    }

    @Test
    fun timeoutBoundsEveryLocalSuspensionWithoutRetryingProvider() = runTest {
        for (stage in listOf("metadata", "registration", "demand", "progress", "write")) {
            val f = SpaceOriginalProviderTransitionFixture()
            f.provider.hooks[stage] = { awaitCancellation() }
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(f.captured, f.room())
            assertTrue(f.provider.requests.isEmpty())
        }
    }

    @Test
    fun parentCancellationPropagatesAndLeavesCommittedFactsForLaterConfirmation() = runTest {
        for (stage in listOf("progress", "written")) {
            val f = SpaceOriginalProviderTransitionFixture()
            f.provider.hooks[stage] = { throw CancellationException("owner retired") }
            assertFailsWith<CancellationException> { f.run() }
            assertEquals(
                if (stage == "written") CollaborationSpaceIncomingPhase.Pending
                else f.captured.phase,
                f.room().phase,
            )
            assertEquals(f.checkpoint, f.provider.progress)
        }
    }
}
