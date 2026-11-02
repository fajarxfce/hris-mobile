package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DeliverCollaborationReadReceiptsTest {
    @Test
    fun exactMetadataWriteIsFreshlyReadmittedBeforeItsLocalAcknowledgement() = runTest {
        val f = CollaborationReadReceiptDeliveryFixture()
        val intent = f.localState.intent
        assertEquals(AppResult.Success(CollaborationReadReceiptDelivery(1, 0)), f.run())
        assertEquals(listOf(intent.eventId to intent.visibility), f.sends)
        assertEquals(listOf(intent), f.acknowledgements)
        assertEquals(
            listOf("device", "get", "room", "send", "device", "get", "room", "ack"),
            f.events,
        )
        assertEquals(setOf(intent.eventId), f.localState.state.readEventIds)
        assertEquals(intent, f.localState.state.acknowledged)
        assertEquals(0, f.delivery.acquisitions)
        assertEquals(0, f.delivery.windows)
    }

    @Test
    fun boundedTurnRetainsBothVisibilitiesAndConcurrentNewPointsWithoutOrderingAssumptions() =
        runTest {
            val f = CollaborationReadReceiptDeliveryFixture()
            val second =
                f.localState.intent.copy(
                    id = CollaborationReadStateFixture.id(20),
                    eventId = "\$older",
                    visibility = CollaborationReadReceiptVisibility.Public,
                )
            val third = second.copy(id = CollaborationReadStateFixture.id(21), eventId = "\$newer")
            f.append(second)
            f.afterAck = {
                f.afterAck = {}
                f.append(third)
            }
            assertEquals(
                AppResult.Success(CollaborationReadReceiptDelivery(1, 2)),
                f.run(limit = 1),
            )
            assertEquals(listOf(second, third), f.localState.state.pending)
            assertEquals(AppResult.Success(CollaborationReadReceiptDelivery(2, 0)), f.run())
            assertEquals(listOf(f.localState.intent, second, third), f.acknowledgements)
            assertEquals(
                setOf(f.localState.intent.eventId, second.eventId, third.eventId),
                f.localState.state.readEventIds,
            )
        }

    @Test
    fun emptyQueueDoesNotAcquireDeviceRoomOrSdkAndInvalidRequestsNeverReadState() = runTest {
        val f = CollaborationReadReceiptDeliveryFixture()
        val scope = CollaborationOperationScope.Selected("selection")
        for (limit in listOf(0, 9)) assertIs<AppResult.Failed>(f.run(limit))
        assertIs<AppResult.Failed>(f.useCase(scope, "bad", f.conversationId, f.roomId))
        assertIs<AppResult.Failed>(f.useCase(scope, f.deviceId, "bad", f.roomId))
        assertIs<AppResult.Failed>(f.useCase(scope, f.deviceId, f.conversationId, "room"))
        assertEquals(0, f.localState.reads)
        f.localState.state = f.localState.state.copy(pending = emptyList())
        assertEquals(AppResult.Success(CollaborationReadReceiptDelivery(0, 0)), f.run())
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun freshBackgroundScopeWorksWithoutSelectionButWrongDeviceOrExpiredAuthorityCannotSend() =
        runTest {
            val f = CollaborationReadReceiptDeliveryFixture()
            val scope = f.background()
            f.local.setup.workspace = null
            f.local.setup.beforeWorkspace = {
                error("Background never follows workspace selection")
            }
            assertIs<AppResult.Failed>(
                f.useCase(scope, CollaborationReadStateFixture.id(99), f.conversationId, f.roomId)
            )
            assertTrue(f.events.isEmpty())
            assertEquals(
                AppResult.Success(CollaborationReadReceiptDelivery(1, 0)),
                f.run(scope = scope),
            )
            f.append(f.localState.intent.copy(id = CollaborationReadStateFixture.id(3)))
            f.local.setup.now += 3.hours
            assertIs<AppResult.Failed>(f.run(scope = scope))
            assertEquals(1, f.sends.size)
            assertEquals(1, f.localState.state.pending.size)
        }

    @Test
    fun malformedMissingOrUnmarkedHistoryCannotBeginAProviderWrite() = runTest {
        for (kind in
            listOf("missing", "substitution", "sender", "timestamp", "coverage", "checkpoint")) {
            val f = CollaborationReadReceiptDeliveryFixture()
            val eventId = f.localState.intent.eventId
            val original = f.localState.base.facts.getValue(eventId)
            when (kind) {
                "missing" -> f.localState.base.facts.remove(eventId)
                "substitution" ->
                    f.localState.base.facts[eventId] = original.copy(eventId = "\$other")
                "sender" -> f.localState.base.facts[eventId] = original.copy(sender = "bad")
                "timestamp" -> f.localState.base.facts[eventId] = original.copy(timestamp = null)
                "coverage" ->
                    f.localState.state = f.localState.state.copy(readEventIds = emptySet())
                "checkpoint" -> f.localState.state = f.localState.state.copy(version = null)
            }
            assertIs<AppResult.Failed>(f.run(), kind)
            assertTrue(f.sends.isEmpty(), kind)
            assertTrue(f.acknowledgements.isEmpty(), kind)
        }
    }

    @Test
    fun removedIntentDuringAdmissionCannotStartTransportAndReboundIdRejects() = runTest {
        for (rebind in listOf(false, true)) {
            val f = CollaborationReadReceiptDeliveryFixture()
            f.delivery.control.beforeRemote = {
                if (it == "room")
                    f.localState.state =
                        f.localState.state.copy(
                            pending =
                                if (rebind)
                                    listOf(
                                        f.localState.intent.copy(
                                            visibility = CollaborationReadReceiptVisibility.Public
                                        )
                                    )
                                else emptyList()
                        )
            }
            val result = f.run()
            if (rebind) assertIs<AppResult.Failed>(result)
            else assertEquals(AppResult.Success(CollaborationReadReceiptDelivery(0, 0)), result)
            assertTrue(f.sends.isEmpty())
            assertTrue(f.acknowledgements.isEmpty())
        }
    }

    @Test
    fun lostRemoteOrLocalAckResponsesRetainOriginalIntentAndExplicitRetryConverges() = runTest {
        for (commitAck in listOf(false, true)) {
            val f = CollaborationReadReceiptDeliveryFixture()
            if (commitAck) {
                f.commitAckOnFailure = true
                f.ackResult = AppResult.Failed(f.local.storage)
            } else f.sendResult = AppResult.Failed(Failure(FailureKind.Network, "Interrupted"))
            assertIs<AppResult.Failed>(f.run())
            assertEquals(if (commitAck) 0 else 1, f.localState.state.pending.size)
            f.sendResult = AppResult.Success(Unit)
            f.ackResult = AppResult.Success(true)
            val result =
                assertIs<AppResult.Success<CollaborationReadReceiptDelivery>>(f.run()).value
            assertEquals(0, result.remaining)
            assertEquals(if (commitAck) 0 else 1, result.delivered)
            assertEquals(if (commitAck) 1 else 2, f.sends.size)
            assertTrue(
                f.sends.all { it == f.localState.intent.eventId to f.localState.intent.visibility }
            )
            assertEquals(setOf(f.localState.intent.eventId), f.localState.state.readEventIds)
        }
    }

    @Test
    fun failedLocalAckWithoutCommitAndCompareAndSetConflictRetainThePoint() = runTest {
        for (ack in
            listOf(
                AppResult.Success(false),
                AppResult.Failed(Failure(FailureKind.Storage, "Interrupted")),
            )) {
            val f = CollaborationReadReceiptDeliveryFixture()
            f.ackResult = ack
            assertIs<AppResult.Failed>(f.run())
            assertEquals(listOf(f.localState.intent), f.localState.state.pending)
            assertEquals(1, f.sends.size)
            assertEquals(1, f.acknowledgements.size)
        }
    }

    @Test
    fun deviceStateRoomMappingAndConversationScopeAreRecheckedAfterSending() = runTest {
        for (late in listOf(false, true)) for (kind in
            listOf("device", "state", "platform", "room", "pending-room", "company", "self-pair")) {
            val f = CollaborationReadReceiptDeliveryFixture()
            val change: suspend () -> Unit = {
                when (kind) {
                    "device" -> f.local.setup.onRead = {
                            AppResult.Success(f.local.setup.device.copy(id = "other"))
                        }
                    "state" -> f.local.setup.onRead = {
                            AppResult.Success(
                                f.local.setup.device.copy(
                                    id = f.deviceId,
                                    state = CollaborationDeviceState.REVOKED,
                                )
                            )
                        }
                    "platform" -> f.local.setup.onRead = {
                            AppResult.Success(
                                f.local.setup.device.copy(
                                    id = f.deviceId,
                                    platform = CollaborationNativePlatform.IOS,
                                )
                            )
                        }
                    "room" ->
                        f.delivery.control.room =
                            f.delivery.control.room.copy(matrixRoomId = "!other:test")
                    "pending-room" ->
                        f.delivery.control.room =
                            f.delivery.control.room.copy(
                                state = CollaborationDirectRoomState.QUEUED
                            )
                    "company" ->
                        f.delivery.control.conversation =
                            f.delivery.control.conversation.copy(
                                companyId = CollaborationReadStateFixture.id(99)
                            )
                    "self-pair" ->
                        f.delivery.control.conversation =
                            f.delivery.control.conversation.copy(
                                secondAccountId = f.owner.accountId
                            )
                }
            }
            if (late) f.afterSend = change else change()
            assertIs<AppResult.Failed>(f.run(), "$kind/$late")
            assertEquals(if (late) 1 else 0, f.sends.size)
            assertTrue(f.acknowledgements.isEmpty())
            assertEquals(listOf(f.localState.intent), f.localState.state.pending)
        }
    }

    @Test
    fun newServerDenialSurvivesFailedPersistenceAndBlocksAllLaterAttempts() = runTest {
        val f = CollaborationReadReceiptDeliveryFixture()
        val denied =
            Failure(FailureKind.AccessDenied, "Not admitted", code = "collaboration_access_denied")
        f.sendResult = AppResult.Failed(denied)
        assertEquals(AppResult.Failed(denied), f.run())
        assertEquals(1, f.restrictions)
        f.sendResult = AppResult.Success(Unit)
        assertEquals(AppResult.Failed(denied), f.run())
        assertEquals(1, f.sends.size)
        assertTrue(f.acknowledgements.isEmpty())
        assertEquals(listOf(f.localState.intent), f.localState.state.pending)
    }

    @Test
    fun lateAccountReplacementOrExpiryCannotAcknowledgeACompletedProviderWrite() = runTest {
        for (replace in listOf(false, true)) {
            val f = CollaborationReadReceiptDeliveryFixture()
            f.afterSend = {
                if (replace)
                    f.local.setup.session = f.local.setup.session!!.copy(id = "replacement")
                else f.local.setup.now += 3.hours
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(1, f.sends.size)
            assertTrue(f.acknowledgements.isEmpty())
            assertEquals(listOf(f.localState.intent), f.localState.state.pending)
        }
    }

    @Test
    fun cancellationAndBudgetJoinPendingRequestAndDoNotStartConcurrentDelivery() = runTest {
        val f = CollaborationReadReceiptDeliveryFixture()
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        f.afterSend = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val job = launch {
            f.run()
            error("Cancelled send published")
        }
        entered.await()
        assertEquals(1, f.activeSends)
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(1, f.sends.size)
        job.cancelAndJoin()
        assertTrue(stopped)
        assertEquals(0, f.activeSends)
        assertTrue(f.acknowledgements.isEmpty())
        stopped = false
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertTrue(stopped)
        assertEquals(20_000L, testScheduler.currentTime)
        f.afterSend = {}
        assertEquals(AppResult.Success(CollaborationReadReceiptDelivery(1, 0)), f.run())
    }
}
