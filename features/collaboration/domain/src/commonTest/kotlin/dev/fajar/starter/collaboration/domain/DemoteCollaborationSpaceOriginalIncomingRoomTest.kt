package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class DemoteCollaborationSpaceOriginalIncomingRoomTest {
    @Test
    fun exhaustedWaitingHistoryTransfersPrimaryAndWakeTogether() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        val previous = assertNotNull(f.base.snapshot).demand
        val oldWake = f.wake
        assertEquals(AppResult.Success(true), f.run())
        assertNull(f.retainedTask())
        val recovery = assertNotNull(f.recovery())
        assertTrue(validCollaborationSpaceHistoryRecovery(recovery))
        assertEquals(recovery.generation, f.wake.generation)
        assertNotEquals(oldWake, f.wake)
        assertEquals(
            previous.copy(rooms = emptyList(), historyRecovery = recovery),
            assertNotNull(f.base.snapshot).demand,
        )
        assertEquals(1, f.writes)
        assertTrue(f.provider.requests.isEmpty())
        assertTrue(f.provider.saved.isEmpty())
        assertFalse(f.pending.pending.single().resolved)
    }

    @Test
    fun resolvedContentUsesCleanCompletionWithoutRequestingAnotherPass() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        f.pending.pending = emptyList()
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(0, f.writes)
        assertNull(f.recovery())
    }

    @Test
    fun missingProgressAndProviderContinuationKeepPrimaryWork() = runTest {
        for (missing in listOf(true, false)) {
            val f = SpaceOriginalKeyDemotionFixture()
            f.provider.progress =
                if (missing) null else assertNotNull(f.provider.progress).copy(next = "older")
            if (missing) f.pending.pending = emptyList()
            assertEquals(AppResult.Success(false), f.run())
            assertEquals(0, f.writes)
            assertNotNull(f.retainedTask())
        }
    }

    @Test
    fun anUnfinishedFiniteSeekCannotBeDemoted() = runTest {
        for (retry in listOf(true, false)) {
            val f = SpaceOriginalKeyDemotionFixture()
            val progress = assertNotNull(f.provider.progress)
            f.provider.progress =
                progress.copy(
                    incoming =
                        assertNotNull(progress.incoming)
                            .copy(
                                pendingAfter = "seek",
                                pendingThrough = 2,
                                pendingRetryRequired = retry,
                            )
                )
            assertEquals(AppResult.Success(false), f.run())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun missingRequestedHeadKeepsProviderRecoverySeparate() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        f.provider.references.clear()
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun eitherResolvedOrUnresolvedHeadCanProvePublishedProviderCoverage() = runTest {
        for (resolved in listOf(true, false)) {
            val f = SpaceOriginalKeyDemotionFixture()
            val head = f.pending.head(resolved)
            f.provider.references[head.event.eventId] = head
            assertEquals(AppResult.Success(true), f.run())
        }
    }

    @Test
    fun malformedHeadIdentityOrSequenceCannotTransferTheTask() = runTest {
        for (change in listOf("id", "sequence", "sender")) {
            val f = SpaceOriginalKeyDemotionFixture()
            val head = f.pending.head(false)
            f.provider.references[head.event.eventId] =
                when (change) {
                    "id" -> head.copy(event = head.event.copy(eventId = "\$other"))
                    "sequence" -> head.copy(sequence = 2)
                    else -> head.copy(event = head.event.copy(sender = "invalid"))
                }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun aChangedHeadCheckpointIsNotCoverageForTheWaitingRead() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        val head = f.pending.head(false)
        f.provider.sourceResponses[head.event.eventId] =
            CollaborationHistorySource(assertNotNull(f.provider.progress).copy(revision = 1), head)
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun aNewPrimaryAppendBeforeTransferKeepsTheOriginalTask() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        f.provider.hooks["progress"] = {
            f.provider.progress =
                assertNotNull(f.provider.progress).copy(revision = 1, references = 3)
        }
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(0, f.writes)
        assertNotNull(f.retainedTask())
    }

    @Test
    fun obsoleteTaskDoesNotCreateAnExtraGenerationOnReplay() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        assertEquals(AppResult.Success(true), f.run())
        val recovery = f.recovery()
        val wake = f.wake
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(recovery, f.recovery())
        assertEquals(wake, f.wake)
        assertEquals(1, f.writes)
    }

    @Test
    fun replacedTaskIsLeftUntouchedWithoutHistoryReads() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        val captured = f.pending.room
        f.pending.capture(captured.captured.copy(generation = f.base.id(999)))
        assertEquals(AppResult.Success(false), f.demote(f.base.runtime, captured))
        assertFalse("pending" in f.provider.calls)
        assertEquals(0, f.writes)
    }

    @Test
    fun changedFactsUnderOneTaskGenerationFail() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        val captured = f.pending.room
        f.pending.capture(captured.captured.copy(phase = CollaborationSpaceIncomingPhase.Provider))
        assertIs<AppResult.Failed>(f.demote(f.base.runtime, captured))
        assertEquals(0, f.writes)
    }

    @Test
    fun companyHistoryCannotBorrowSpaceProviderIdentity() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        f.provider.progress =
            assertNotNull(f.provider.progress)
                .copy(
                    provider =
                        CollaborationHistoryProviderIdentity(
                            f.base.position.companyId,
                            f.base.runtime.deviceId,
                        )
                )
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun failedEmptyOrMalformedReferenceReadsCannotProveWaitingContent() = runTest {
        for (change in listOf("missingFlow", "failure", "oversize", "resolved")) {
            val f = SpaceOriginalKeyDemotionFixture()
            when (change) {
                "missingFlow" -> f.pending.emptyFlow = true
                "failure" -> f.provider.failures["pending"] = f.base.directory.storageFailure
                "oversize" ->
                    f.pending.malformedPage =
                        CollaborationHistoryReferences(
                            f.provider.progress,
                            List(2) { f.pending.head(false) },
                            null,
                        )
                else -> f.pending.pending = listOf(f.pending.head(true))
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun independentProducerWinningTheCasPreservesBothQueueAndOldWake() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        val wake = f.wake
        f.provider.hooks["write"] = { f.queue.transition.independentProducer() }
        assertIs<AppResult.Failed>(f.run())
        assertNotNull(f.retainedTask())
        assertNull(f.recovery())
        assertEquals(wake, f.wake)
        assertEquals("later", assertNotNull(f.base.snapshot).demand.sources.single().position)
        assertEquals(1, f.writes)
    }

    @Test
    fun lostSuccessfulReplyIsConfirmedByItsUniqueRecoveryRequest() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        f.lostReply = true
        f.provider.hooks["written"] = { f.queue.transition.independentProducer() }
        assertEquals(AppResult.Success(true), f.run())
        assertNull(f.retainedTask())
        assertEquals(f.requested?.generation, assertNotNull(f.recovery()).generation)
        assertEquals(1, f.writes)
    }

    @Test
    fun failedOrFabricatedUncommittedRepliesAreNotReceipts() = runTest {
        for (fabricated in listOf(false, true)) {
            val f = SpaceOriginalKeyDemotionFixture()
            f.commit = false
            f.lostReply = !fabricated
            f.fabricatedReply = fabricated
            val wake = f.wake
            assertIs<AppResult.Failed>(f.run())
            assertNotNull(f.retainedTask())
            assertNull(f.recovery())
            assertEquals(wake, f.wake)
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun failedReadbackAfterCommitCannotPublishAReceiptOrBlindlyWriteAgain() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        f.provider.hooks["written"] = {
            f.provider.failures["demand"] = f.base.directory.storageFailure
        }
        assertEquals(f.base.directory.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertNull(f.retainedTask())
        assertNotNull(f.recovery())
        assertEquals(1, f.writes)
    }

    @Test
    fun successfulRepositoryReplyIsCopiedBeforeLaterSuspendedAdmission() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        val mutableRooms = mutableListOf<CollaborationSpaceIncomingRoomDemand>()
        f.response = { it.copy(demand = it.demand.copy(rooms = mutableRooms)) }
        f.provider.hooks["written"] = {
            f.provider.hooks["metadata"] = { mutableRooms.add(f.pending.room.captured) }
        }
        assertEquals(AppResult.Success(true), f.run())
        assertTrue(mutableRooms.isNotEmpty())
        assertNull(f.retainedTask())
        assertEquals(1, f.writes)
    }

    @Test
    fun activeRecoveryKeepsItsCursorAndCoalescesAFreshLaterRequest() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        val active =
            CollaborationSpaceHistoryRecovery(
                f.base.id(960),
                revision = 3,
                afterPartition = "before",
                unresolved = true,
                requestedGeneration = f.base.id(961),
            )
        f.base.demand(assertNotNull(f.base.snapshot).demand.copy(historyRecovery = active))
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(active.copy(requestedGeneration = f.requested?.generation), f.recovery())
        assertNotEquals(active.generation, f.requested?.generation)
        assertNotEquals(active.requestedGeneration, f.requested?.generation)
    }

    @Test
    fun newlyPublishedHistoryAfterGlobalEofStartsAFreshPass() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        val done =
            CollaborationSpaceHistoryRecovery(
                f.base.id(960),
                revision = 3,
                complete = true,
                unresolved = true,
            )
        f.base.demand(assertNotNull(f.base.snapshot).demand.copy(historyRecovery = done))
        assertEquals(AppResult.Success(true), f.run())
        assertFalse(assertNotNull(f.recovery()).complete)
        assertNotEquals(done.generation, f.recovery()?.generation)
    }

    @Test
    fun metadataMustAlreadyBePublishedBeforePrimaryRemoval() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        f.base.retained = null
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
        assertNotNull(f.retainedTask())
    }

    @Test
    fun aNewPrimaryTaskArrivingAfterCommitSurvivesConfirmation() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        f.lostReply = true
        val newer = f.pending.room.captured.copy(generation = f.base.id(999))
        f.provider.hooks["written"] = {
            f.base.demand(assertNotNull(f.base.snapshot).demand.copy(rooms = listOf(newer)))
        }
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(newer, f.retainedTask())
        assertNotNull(f.recovery())
    }

    @Test
    fun staleCredentialsDoNotHideOwnedKeyWaitFacts() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        f.base.native.setup.now += 365.days
        assertEquals(AppResult.Success(true), f.run())
        assertTrue(f.provider.requests.isEmpty())
    }

    @Test
    fun changedAdmissionMetadataAndSourceCannotPublishTransfer() = runTest {
        for (stage in listOf("pending", "source:\$head", "progress", "written")) for (change in
            listOf("denial", "account", "source", "metadata", "registration")) {
            val f = SpaceOriginalKeyDemotionFixture()
            f.provider.hooks[stage] = {
                when (change) {
                    "denial" -> f.base.directory.deny()
                    "account" -> f.base.native.setup.session = null
                    "source" -> f.base.installed = null
                    "metadata" -> f.base.retained = null
                    else -> f.base.registration = null
                }
            }
            val failure = assertIs<AppResult.Failed>(f.run())
            if (change == "denial") assertEquals(f.base.directory.denied, failure.failure)
            if (stage != "written") assertEquals(0, f.writes)
        }
    }

    @Test
    fun timeoutStopsTheSingleInvocationWithoutAQueueWrite() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        f.provider.hooks["pending"] = { delay(10_000) }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(0, f.writes)
    }

    @Test
    fun cancellationPropagatesWithoutAQueueWrite() = runTest {
        val f = SpaceOriginalKeyDemotionFixture()
        val entered = CompletableDeferred<Unit>()
        f.provider.hooks["pending"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.run() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, f.writes)
    }
}
