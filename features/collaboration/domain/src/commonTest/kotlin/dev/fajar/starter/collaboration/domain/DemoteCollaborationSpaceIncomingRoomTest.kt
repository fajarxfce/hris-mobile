package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class DemoteCollaborationSpaceIncomingRoomTest {
    @Test
    fun publishedSpaceWorkTransfersWithoutChangingItsPhysicalProvider() = runTest {
        val f = SpaceKeyDemotionFixture()
        val before = assertNotNull(f.snapshot).demand
        val progress = f.reader.progress
        assertEquals(AppResult.Success(true), f.run())
        assertNull(f.retainedTask())
        val recovery = assertNotNull(f.recovery())
        assertEquals(recovery.generation, f.wake.generation)
        assertEquals(
            before.copy(rooms = emptyList(), historyRecovery = recovery),
            assertNotNull(f.snapshot).demand,
        )
        assertEquals(progress, f.reader.progress)
        assertEquals(1, f.writes)
        assertEquals(0, f.reader.writes)
        assertEquals(listOf<Pair<String?, Int>>(null to 1), f.reader.requests)
    }

    @Test
    fun originalPhysicalAliasDoesNotRedirectLogicalSpacePrimaryWork() = runTest {
        val f = SpaceKeyDemotionFixture()
        f.reader.originalAlias()
        val original = f.base.captured.copy(position = 1, generation = f.base.id(982))
        f.demand(
            assertNotNull(f.snapshot)
                .demand
                .copy(nextPosition = 2, rooms = listOf(f.room.captured, original))
        )
        assertTrue(validCollaborationSpaceRoomBinding(f.access, assertNotNull(f.reader.binding)))
        assertTrue(
            validCollaborationSpaceIncomingDemand(f.access, assertNotNull(f.snapshot).demand)
        )
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(listOf(original), assertNotNull(f.snapshot).demand.rooms)
        assertEquals(f.reader.conversationId, f.room.traversal.conversationId)
        assertNotEquals(f.base.position.conversationId, f.room.traversal.conversationId)
    }

    @Test
    fun companyTaskCannotUseThePublishedTransferOperation() = runTest {
        val f = SpaceKeyDemotionFixture()
        assertIs<AppResult.Failed>(f.demote(f.runtime, f.room.copy(captured = f.base.captured)))
        assertEquals(0, f.writes)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun foreignOwnerConversationDeviceOrPurposeFailsBeforeReads() = runTest {
        val seed = SpaceKeyDemotionFixture()
        for (change in listOf("owner", "conversation", "device", "purpose", "phase")) {
            val f = SpaceKeyDemotionFixture()
            val room =
                when (change) {
                    "owner" ->
                        f.room.copy(
                            conversation =
                                f.room.conversation.copy(
                                    owner = f.runtime.owner.copy(spaceId = f.base.id(900))
                                )
                        )
                    "conversation" ->
                        f.room.copy(
                            traversal = f.room.traversal.copy(conversationId = f.base.id(901))
                        )
                    "device" ->
                        f.room.copy(
                            traversal = f.room.traversal.copy(deviceId = f.physical.deviceId)
                        )
                    "purpose" ->
                        f.room.copy(
                            traversal =
                                f.room.traversal.copy(purpose = CollaborationHistoryPurpose.Browse)
                        )
                    else ->
                        f.room.copy(
                            captured =
                                f.room.captured.copy(phase = CollaborationSpaceIncomingPhase.Latest)
                        )
                }
            assertIs<AppResult.Failed>(f.demote(f.runtime, room))
            assertEquals(0, f.writes)
        }
        assertNotEquals(seed.physical.deviceId, seed.runtime.deviceId)
    }

    @Test
    fun providerMustIdentifyTheCurrentlyAdmittedOriginalPhysicalStore() = runTest {
        for (provider in
            listOf<CollaborationHistoryProviderIdentity?>(
                null,
                CollaborationHistoryProviderIdentity(
                    "60000000-0000-4000-8000-000000000999",
                    "60000000-0000-4000-8000-000000000998",
                ),
            )) {
            val f = SpaceKeyDemotionFixture()
            f.reader.progress = assertNotNull(f.reader.progress).copy(provider = provider)
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.writes)
        }
        val f = SpaceKeyDemotionFixture()
        f.reader.progress =
            assertNotNull(f.reader.progress)
                .copy(
                    provider =
                        CollaborationHistoryProviderIdentity(
                            f.physical.binding.companyId,
                            f.runtime.deviceId,
                        )
                )
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun cleanMissingAndUnfinishedProviderHistoriesKeepTheirPrimaryPath() = runTest {
        for (change in listOf("clean", "missing", "provider")) {
            val f = SpaceKeyDemotionFixture()
            when (change) {
                "clean" -> f.reader.events = emptyList()
                "missing" -> {
                    f.reader.progress = null
                    f.reader.events = emptyList()
                }
                else -> f.reader.progress = assertNotNull(f.reader.progress).copy(next = "older")
            }
            assertEquals(AppResult.Success(false), f.run())
            assertEquals(0, f.writes)
            assertNull(f.recovery())
        }
    }

    @Test
    fun unfinishedLocalReferenceSeekCannotBeDemoted() = runTest {
        val f = SpaceKeyDemotionFixture()
        val progress = assertNotNull(f.reader.progress)
        f.reader.progress =
            progress.copy(
                incoming =
                    assertNotNull(progress.incoming)
                        .copy(
                            pendingAfter = "seek",
                            pendingThrough = progress.references,
                            pendingRetryRequired = true,
                        )
            )
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun unknownRequestedHeadKeepsPrimaryProviderRecovery() = runTest {
        val f = SpaceKeyDemotionFixture()
        f.sources.clear()
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun malformedHeadAndDifferentCheckpointCannotProveCoverage() = runTest {
        for (change in listOf("event", "sequence", "progress")) {
            val f = SpaceKeyDemotionFixture()
            val head = f.reader.events.single()
            when (change) {
                "event" ->
                    f.sources[head.event.eventId] =
                        head.copy(event = head.event.copy(eventId = "\$other"))
                "sequence" ->
                    f.sources[head.event.eventId] =
                        head.copy(sequence = assertNotNull(f.reader.progress).references)
                else ->
                    f.sourceResponses[head.event.eventId] =
                        CollaborationHistorySource(
                            assertNotNull(f.reader.progress).copy(revision = 999),
                            head,
                        )
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun laterPrimaryProgressPreventsRetiringTheEarlierCapture() = runTest {
        val f = SpaceKeyDemotionFixture()
        f.hooks["progress"] = {
            val p = assertNotNull(f.reader.progress)
            f.reader.progress = p.copy(revision = p.revision + 1, references = p.references + 1)
        }
        assertEquals(AppResult.Success(false), f.run())
        assertNotNull(f.retainedTask())
        assertEquals(0, f.writes)
    }

    @Test
    fun bindingOrAliasReplacementDuringPendingReadIsNotTheCapturedRoom() = runTest {
        for (change in listOf("missing", "room", "alias")) {
            val f = SpaceKeyDemotionFixture()
            f.hooks["pending"] = {
                when (change) {
                    "missing" -> f.reader.binding = null
                    "room" -> {
                        val b = assertNotNull(f.reader.binding)
                        f.reader.binding =
                            b.copy(room = b.room.copy(roomId = "!other:matrix.hris.internal"))
                    }
                    else -> f.reader.originalAlias()
                }
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun admittedLocalWorkSurvivesCredentialAge() = runTest {
        val f = SpaceKeyDemotionFixture()
        f.base.native.setup.now += 365.days
        assertEquals(AppResult.Success(true), f.run())
    }

    @Test
    fun denialAccountSourceAndRegistrationChangesCannotPublishTransfer() = runTest {
        for (stage in listOf("pending", "progress", "written")) for (change in
            listOf("denial", "account", "source", "registration")) {
            val f = SpaceKeyDemotionFixture()
            f.hooks[stage] = {
                when (change) {
                    "denial" -> f.base.directory.deny()
                    "account" -> f.base.native.setup.session = null
                    "source" -> f.base.installed = null
                    else -> f.base.registration = null
                }
            }
            val failure = assertIs<AppResult.Failed>(f.run())
            if (change == "denial") assertEquals(f.base.directory.denied, failure.failure)
            if (stage != "written") assertEquals(0, f.writes)
        }
    }

    @Test
    fun lostReplyStillRequiresItsUniqueDurableRecoveryRequest() = runTest {
        val f = SpaceKeyDemotionFixture()
        f.lostReply = true
        assertEquals(AppResult.Success(true), f.run())
        assertNull(f.retainedTask())
        assertEquals(f.requested?.generation, f.recovery()?.generation)
    }

    @Test
    fun casLossPreservesConcurrentWorkAndOldWake() = runTest {
        val f = SpaceKeyDemotionFixture()
        val wake = f.wake
        f.hooks["write"] = {
            f.demand(assertNotNull(f.snapshot).demand.copy(rescanGeneration = f.base.id(999)))
        }
        assertIs<AppResult.Failed>(f.run())
        assertNotNull(f.retainedTask())
        assertNull(f.recovery())
        assertEquals(wake, f.wake)
        assertEquals(1, f.writes)
    }

    @Test
    fun fabricatedAndUncommittedRepliesDoNotDropPrimaryWork() = runTest {
        for (fabricated in listOf(false, true)) {
            val f = SpaceKeyDemotionFixture()
            f.commit = false
            f.lostReply = !fabricated
            f.fabricatedReply = fabricated
            assertIs<AppResult.Failed>(f.run())
            assertNotNull(f.retainedTask())
            assertNull(f.recovery())
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun replayAfterTransferCreatesNoExtraPass() = runTest {
        val f = SpaceKeyDemotionFixture()
        assertEquals(AppResult.Success(true), f.run())
        val recovery = f.recovery()
        val wake = f.wake
        assertEquals(AppResult.Success(false), f.run())
        assertEquals(recovery, f.recovery())
        assertEquals(wake, f.wake)
        assertEquals(1, f.writes)
    }

    @Test
    fun newPrimaryCaptureAfterCommitSurvivesConfirmation() = runTest {
        val f = SpaceKeyDemotionFixture()
        f.lostReply = true
        val newer = f.room.captured.copy(generation = f.base.id(999))
        f.hooks["written"] = {
            f.demand(assertNotNull(f.snapshot).demand.copy(rooms = listOf(newer)))
        }
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(newer, f.retainedTask())
    }

    @Test
    fun aFullQueueReleasesOneSlotWithoutDroppingOtherRooms() = runTest {
        val f = SpaceKeyDemotionFixture()
        val others =
            (1..63).map {
                f.room.captured.copy(
                    target = CollaborationSpaceIncomingTarget.Published(f.base.id(1000 + it)),
                    generation = f.base.id(1100 + it),
                    position = it.toLong(),
                )
            }
        f.demand(
            assertNotNull(f.snapshot)
                .demand
                .copy(nextPosition = 64, rooms = listOf(f.room.captured) + others)
        )
        assertEquals(AppResult.Success(true), f.run())
        assertEquals(others, assertNotNull(f.snapshot).demand.rooms)
        assertNotNull(f.recovery())
    }

    @Test
    fun laterPublishedHistoryRequestsANewPassAfterGlobalCompletion() = runTest {
        val f = SpaceKeyDemotionFixture()
        val done =
            CollaborationSpaceHistoryRecovery(
                f.base.id(999),
                revision = 4,
                complete = true,
                unresolved = true,
            )
        f.demand(assertNotNull(f.snapshot).demand.copy(historyRecovery = done))
        assertEquals(AppResult.Success(true), f.run())
        assertNotEquals(done.generation, f.recovery()?.generation)
        assertFalse(assertNotNull(f.recovery()).complete)
    }

    @Test
    fun aMissingStartupHeadStillRequiresActualUnresolvedProviderEof() = runTest {
        val f = SpaceKeyDemotionFixture()
        f.room = f.room.copy(captured = f.room.captured.copy(headEventId = null))
        f.demand(assertNotNull(f.snapshot).demand.copy(rooms = listOf(f.room.captured)))
        assertEquals(AppResult.Success(true), f.run())
        assertFalse(f.calls.any { it.startsWith("source:") })
    }

    @Test
    fun localUnboundQueueStillNeedsTheCurrentRegisteredSource() = runTest {
        val f = SpaceKeyDemotionFixture()
        f.room = f.room.copy(captured = f.room.captured.copy(sourceId = null))
        f.demand(
            assertNotNull(f.snapshot)
                .demand
                .copy(rooms = listOf(f.room.captured), sources = emptyList())
        )
        assertEquals(AppResult.Success(true), f.run())
    }

    @Test
    fun changedFactsWithinOneTaskGenerationCannotTransfer() = runTest {
        val f = SpaceKeyDemotionFixture()
        f.demand(
            assertNotNull(f.snapshot)
                .demand
                .copy(rooms = listOf(f.room.captured.copy(headEventId = "\$different")))
        )
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
    }

    @Test
    fun failedPostCommitReadbackIsNotAReceipt() = runTest {
        val f = SpaceKeyDemotionFixture()
        f.hooks["written"] = { f.failures["demand"] = f.base.directory.storageFailure }
        assertIs<AppResult.Failed>(f.run())
        assertNull(f.retainedTask())
        assertNotNull(f.recovery())
        assertEquals(1, f.writes)
    }

    @Test
    fun timeoutKeepsPrimaryWorkWithoutAWrite() = runTest {
        val f = SpaceKeyDemotionFixture()
        f.hooks["pending"] = { delay(10_000) }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(0, f.writes)
    }

    @Test
    fun cancellationDrainsTheInvocationWithoutWriting() = runTest {
        val f = SpaceKeyDemotionFixture()
        val entered = CompletableDeferred<Unit>()
        f.hooks["pending"] = {
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
