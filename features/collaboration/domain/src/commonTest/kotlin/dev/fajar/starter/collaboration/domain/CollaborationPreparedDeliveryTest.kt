package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationTimelineLease
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

class CollaborationPreparedDeliveryTest {
    @Test
    fun existingTimelineAvoidsOnePreparationRoundButStillAdmitsBeforeAndAfterDelivery() = runTest {
        for (prepared in listOf(false, true)) {
            val f = CollaborationDeliveryFixture()
            f.prepare()
            val timeline = PreparedTimeline(f.owner, f.device)
            val reads = mutableListOf<String>()
            f.control.beforeRemote = { reads += it }
            f.afterAcquire = { assertEquals(if (prepared) 0 else 1, f.transportReads) }
            f.afterResume = { assertEquals(if (prepared) 1 else 2, f.transportReads) }
            val result = f.deliver(preparedTimeline = timeline.takeIf { prepared })
            assertIs<AppResult.Success<CollaborationDeliveryReport>>(result)
            assertEquals(if (prepared) 2 else 3, f.transportReads)
            assertEquals(List(if (prepared) 2 else 3) { "transport" }, reads)
            assertEquals(0, f.base.setup.calls)
            assertIs<CollaborationMessageState.Sent>(f.records.single().state)
            assertEquals(setOf("original"), f.knownTransactions)
            assertEquals(1, f.acquisitions)
            assertEquals(1, f.windows)
            assertEquals(1, f.closes)
            assertEquals(0, timeline.closes)
        }
    }

    @Test
    fun missingRecipientKeysCannotResumeTheOriginalAndLaterReadinessKeepsItsTransaction() =
        runTest {
            val f = CollaborationDeliveryFixture()
            f.prepare()
            val original = f.records.single()
            val timeline = PreparedTimeline(f.owner, f.device)
            f.recipientReady = false
            val failure = assertIs<AppResult.Failed>(f.deliver(preparedTimeline = timeline)).failure
            assertEquals(FailureKind.Conflict, failure.kind)
            assertEquals("client.collaboration_recipient_unavailable", failure.code)
            assertEquals(0, f.windows)
            assertEquals(listOf(original), f.records)
            assertTrue(f.journal.isEmpty())
            assertEquals(f.acquisitions, f.closes)
            assertFalse(
                f.recipientObservations.values.value.getValue(original.intent.conversationId).ready
            )
            f.recipientReady = true
            assertIs<AppResult.Success<CollaborationDeliveryReport>>(
                f.deliver(preparedTimeline = timeline)
            )
            assertTrue(
                f.recipientObservations.values.value.getValue(original.intent.conversationId).ready
            )
            assertEquals(1, f.windows)
            assertEquals(setOf("original"), f.knownTransactions)
            assertEquals(original.intent, f.records.single().intent)
            assertIs<CollaborationMessageState.Sent>(f.records.single().state)
        }

    @Test
    fun everyForeignOwnerIsRejectedBeforeBorrowingOrReadingRemoteContent() = runTest {
        for (change in 0..4) {
            val f = CollaborationDeliveryFixture()
            f.prepare()
            val owner =
                when (change) {
                    0 -> f.owner.copy(sessionId = "replacement")
                    1 -> f.owner.copy(audience = "https://other.example.test/")
                    2 -> f.owner.copy(accountId = deliveryId(900))
                    3 -> f.owner.copy(companyId = deliveryId(901))
                    else -> f.owner.copy(nativeSessionId = deliveryId(902))
                }
            val timeline = PreparedTimeline(owner, f.device)
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.deliver(preparedTimeline = timeline)).failure.kind,
            )
            assertEquals(0, f.acquisitions)
            assertEquals(0, f.transportReads)
            assertEquals(0, f.windows)
            assertEquals(0, timeline.closes)
        }
    }

    @Test
    fun inconsistentOrInvalidTimelineCoordinatesCannotSelectAQueue() = runTest {
        for (change in 0..5) {
            val f = CollaborationDeliveryFixture()
            f.prepare()
            val device =
                when (change) {
                    0 -> f.device.copy(nativeSessionId = deliveryId(900))
                    1 -> f.device.copy(deviceId = deliveryId(901))
                    2 -> f.device.copy(bootstrapOperationId = "invalid")
                    3 -> f.device.copy(roomId = "invalid")
                    else -> f.device
                }
            val timeline =
                PreparedTimeline(
                    f.owner,
                    device,
                    deviceId = if (change == 4) deliveryId(902) else f.device.deviceId,
                    matrixRoomId = if (change == 5) "!different:example.test" else device.roomId,
                )
            assertIs<AppResult.Failed>(f.deliver(preparedTimeline = timeline))
            assertEquals(0, f.acquisitions)
            assertEquals(0, f.transportReads)
            assertEquals(0, f.windows)
            assertEquals(0, timeline.closes)
        }
    }

    @Test
    fun acquiredWriterMustMatchTheOriginalBootstrapAndRoom() = runTest {
        for (roomChanged in listOf(false, true)) {
            val f = CollaborationDeliveryFixture()
            f.prepare()
            val device =
                if (roomChanged) f.device.copy(roomId = "!other:example.test")
                else f.device.copy(bootstrapOperationId = deliveryId(903))
            val timeline = PreparedTimeline(f.owner, device)
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.deliver(preparedTimeline = timeline)).failure.kind,
            )
            assertEquals(1, f.acquisitions)
            assertEquals(1, f.closes)
            assertEquals(0, f.transportReads)
            assertEquals(0, f.windows)
            assertEquals(0, timeline.closes)
        }
    }

    @Test
    fun currentRevocationOrAccessDenialStillStopsThePausedQueue() = runTest {
        for (denied in listOf(false, true)) {
            val f = CollaborationDeliveryFixture()
            f.prepare()
            val timeline = PreparedTimeline(f.owner, f.device)
            f.afterAcquire = {
                f.base.setup.onRead = {
                    if (denied) AppResult.Failed(f.base.denied)
                    else
                        AppResult.Success(
                            f.base.setup.device.copy(
                                id = f.device.deviceId,
                                state = CollaborationDeviceState.REVOKED,
                            )
                        )
                }
            }
            val failure = assertIs<AppResult.Failed>(f.deliver(preparedTimeline = timeline)).failure
            if (denied) {
                assertEquals(f.base.denied, failure)
                assertEquals(f.base.denied, f.base.gate.value.denial)
            } else assertEquals("client.collaboration_device_unavailable", failure.code)
            assertEquals(1, f.transportReads)
            assertEquals(0, f.windows)
            assertTrue(f.journal.isEmpty())
            assertEquals(1, f.closes)
            assertEquals(0, timeline.closes)
        }
    }

    @Test
    fun changedLiveRoomCannotResumeThePreviouslyOpenedRoom() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val timeline = PreparedTimeline(f.owner, f.device)
        f.afterAcquire = {
            f.control.room = f.control.room.copy(matrixRoomId = "!replacement:example.test")
        }
        assertEquals(
            "client.collaboration_conversation_mismatch",
            assertIs<AppResult.Failed>(f.deliver(preparedTimeline = timeline)).failure.code,
        )
        assertEquals(1, f.transportReads)
        assertEquals(0, f.windows)
        assertEquals(1, f.closes)
        assertEquals(0, timeline.closes)
    }

    @Test
    fun cancellationDuringLateAcquisitionClosesOnlyTheBorrowedWriter() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val timeline = PreparedTimeline(f.owner, f.device)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.afterAcquire = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val waiting = launch { f.deliver(preparedTimeline = timeline) }
        entered.await()
        waiting.cancel()
        release.complete(Unit)
        waiting.join()
        assertTrue(waiting.isCancelled)
        assertEquals(1, f.acquisitions)
        assertEquals(1, f.closes)
        assertEquals(0, f.windows)
        assertEquals(0, timeline.closes)
    }

    @Test
    fun replacementAccountAfterBorrowCannotProjectOrResume() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val timeline = PreparedTimeline(f.owner, f.device)
        f.afterAcquire = {
            f.base.setup.session =
                f.base.setup.session!!.copy(
                    user = f.base.setup.session!!.user.copy(id = deliveryId(999))
                )
        }
        assertIs<AppResult.Failed>(f.deliver(preparedTimeline = timeline))
        assertEquals(0, f.windows)
        assertEquals(0, f.projections)
        assertEquals(1, f.closes)
        assertEquals(0, timeline.closes)
    }

    @Test
    fun cancelledWaiterRetainsLateAcknowledgementOnlyForTheOriginalAccount() = runTest {
        for (replacement in listOf(false, true)) {
            val f = CollaborationDeliveryFixture()
            f.prepare()
            val timeline = PreparedTimeline(f.owner, f.device)
            f.windowLifetime = backgroundScope
            f.progress = listOf(f.window)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeProgress = {
                entered.complete(Unit)
                release.await()
            }
            val waiting = launch { f.deliver(preparedTimeline = timeline) }
            entered.await()
            waiting.cancel()
            if (replacement)
                f.base.setup.session =
                    f.base.setup.session!!.copy(
                        user = f.base.setup.session!!.user.copy(id = deliveryId(999))
                    )
            release.complete(Unit)
            waiting.join()
            assertTrue(waiting.isCancelled)
            assertEquals(if (replacement) 0 else 1, f.journal.size)
            assertEquals(0, f.projections)
            assertEquals(1, f.windows)
            assertEquals(1, f.closes)
            assertEquals(0, timeline.closes)
            if (!replacement) assertEquals(AppResult.Success(1), f.reconcile())
        }
    }

    @Test
    fun interruptedReceiptResponseReconcilesTheOriginalWithoutAnotherSend() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val timeline = PreparedTimeline(f.owner, f.device)
        f.progress = listOf(f.window)
        f.appendFailure = f.base.storage
        assertEquals(
            f.base.storage,
            assertIs<AppResult.Failed>(f.deliver(preparedTimeline = timeline)).failure,
        )
        assertEquals(1, f.journal.size)
        assertEquals(0, f.projections)
        f.appendFailure = null
        assertEquals(AppResult.Success(1), f.reconcile())
        assertEquals(1, f.windows)
        assertEquals(1, f.closes)
        assertEquals(0, timeline.closes)
    }

    @Test
    fun currentBackgroundAdmissionDoesNotReadSelectionAndExpiresBeforeResume() = runTest {
        for (expire in listOf(false, true)) {
            val f = CollaborationDeliveryFixture()
            f.prepare()
            val timeline = PreparedTimeline(f.owner, f.device)
            val elapsed = TestTimeSource()
            val scope = f.historyBackground(elapsed)
            if (expire) f.afterAcquire = { elapsed += 61.seconds }
            val result = f.deliver(scope = scope, preparedTimeline = timeline)
            if (expire) {
                assertIs<AppResult.Failed>(result)
                assertEquals(0, f.windows)
            } else {
                assertIs<AppResult.Success<CollaborationDeliveryReport>>(result)
                assertEquals(1, f.windows)
            }
            assertEquals(1, f.closes)
            assertEquals(0, timeline.closes)
        }
    }

    @Test
    fun existingTimelineNeverReopensADeniedLocalProjection() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        val timeline = PreparedTimeline(f.owner, f.device)
        f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
        assertEquals(
            f.base.denied,
            assertIs<AppResult.Failed>(f.deliver(preparedTimeline = timeline)).failure,
        )
        assertEquals(0, f.acquisitions)
        assertEquals(0, f.windows)
        assertEquals(0, f.transportReads)
        assertEquals(0, timeline.closes)
    }
}

private class PreparedTimeline(
    override val owner: CollaborationOwner,
    override val device: CollaborationMessageDevice,
    override val deviceId: String = device.deviceId,
    override val matrixRoomId: String = device.roomId,
) : CollaborationTimelineLease {
    var closes = 0
    override val updates: Flow<AppResult<List<CollaborationTimelineItem>>>
        get() = error("Delivery does not subscribe to the caller's timeline")

    override suspend fun close(): AppResult<Unit> {
        closes++
        return AppResult.Success(Unit)
    }
}
