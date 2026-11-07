package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSpaceOriginalQueueLeaseTest {
    @Test
    fun snapshotRetainsOriginalNamespaceAndHoldsTheOnlyPhysicalWriter() = runTest {
        val f = SpaceOriginalQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val snapshot =
                assertIs<AppResult.Success<CollaborationSpaceOriginalQueueSnapshot>>(
                        queue.snapshot()
                    )
                    .value
            assertEquals(f.runtime, snapshot.runtime)
            assertEquals(setupOwner.setupBinding(), snapshot.original.binding)
            assertEquals(SETUP_OPERATION, snapshot.original.device.bootstrapOperationId)
            assertNotEquals(f.runtime.operationId, snapshot.original.device.bootstrapOperationId)
            assertNotEquals(f.runtime.deviceId, snapshot.original.device.deviceId)
            assertNotEquals(
                f.runtime.owner.parent.nativeSessionId,
                snapshot.original.binding.nativeSessionId,
            )
            assertEquals(2, snapshot.original.messages.size)
            assertIs<AppResult.Failed>(f.native.queues.acquire("!room:test"))
            assertFalse(f.native.room.enabled)
            assertFalse(queue.toString().contains(setupOwner.accountId))
            assertIs<AppResult.Success<*>>(queue.close())
            assertIs<AppResult.Failed>(queue.snapshot())
            assertIs<AppResult.Success<*>>(
                f.native.queues.whileWriterIdle { AppResult.Success(Unit) }
            )
        } finally {
            f.close()
        }
    }

    @Test
    fun changedNamespaceMissingExtraOrChangedOriginalsCannotResume() = runTest {
        val f = SpaceOriginalQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val snapshot =
                assertIs<AppResult.Success<CollaborationSpaceOriginalQueueSnapshot>>(
                        queue.snapshot()
                    )
                    .value
            val physical = snapshot.original
            val variants =
                listOf(
                    snapshot.copy(runtime = f.runtime.copy(operationId = SETUP_OPERATION)),
                    snapshot.copy(
                        original =
                            physical.copy(
                                binding = physical.binding.copy(nativeSessionId = SETUP_JOB)
                            )
                    ),
                    snapshot.copy(
                        original =
                            physical.copy(
                                device = physical.device.copy(bootstrapOperationId = SETUP_JOB)
                            )
                    ),
                    snapshot.copy(original = physical.copy(messages = physical.messages.take(1))),
                    snapshot.copy(
                        original =
                            physical.copy(messages = physical.messages + physical.messages.first())
                    ),
                    snapshot.copy(
                        original =
                            physical.copy(
                                messages =
                                    physical.messages.map { it.copy(serializedContent = "changed") }
                            )
                    ),
                    snapshot.copy(
                        original = physical.copy(messages = List(129) { physical.messages.first() })
                    ),
                )
            for (variant in variants) assertIs<AppResult.Failed>(
                queue.resume(variant) { error("No invented receipt") }
            )
            assertFalse("queue.true" in f.native.room.calls)
            assertEquals(2, f.native.room.originals.size)
        } finally {
            f.close()
        }
    }

    @Test
    fun changedNativeInventoryAfterCaptureRejectsDeliveryWithoutDroppingOriginals() = runTest {
        val f = SpaceOriginalQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val snapshot =
                assertIs<AppResult.Success<CollaborationSpaceOriginalQueueSnapshot>>(
                        queue.snapshot()
                    )
                    .value
            f.native.room.originals =
                f.native.room.originals.map {
                    it.copy(
                        contentJson = it.contentJson.replace("Original message", "Changed message")
                    )
                }
            assertIs<AppResult.Failed>(queue.resume(snapshot) { error("No invented receipt") })
            assertFalse(f.native.room.enabled)
            assertEquals(2, f.native.room.originals.size)
        } finally {
            f.close()
        }
    }

    @Test
    fun callerCollectionIsDetachedBeforeAReadSuspendsAndExactProgressIsObserved() = runTest {
        val f = SpaceOriginalQueueFixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        try {
            val queue = f.open()
            val original =
                assertIs<AppResult.Success<CollaborationSpaceOriginalQueueSnapshot>>(
                        queue.snapshot()
                    )
                    .value
            val mutable = original.original.messages.toMutableList()
            val snapshot = original.copy(original = original.original.copy(messages = mutable))
            f.native.room.beforeOriginals = {
                entered.complete(Unit)
                finish.await()
            }
            val observations = mutableListOf<CollaborationQueueDelivery>()
            val send = async {
                queue.resume(snapshot) {
                    observations += it
                    AppResult.Success(Unit)
                }
            }
            entered.await()
            mutable.clear()
            assertIs<AppResult.Failed>(queue.snapshot())
            finish.complete(Unit)
            runCurrent()
            assertTrue(f.native.room.enabled)
            f.native.room.emit(RoomSendQueueUpdate.SentEvent("original-a", "\$event-a"))
            runCurrent()
            assertEquals(setOf("original-b"), observations.single().pending)
            f.native.room.emit(RoomSendQueueUpdate.SentEvent("original-b", "\$event-b"))
            val result = assertIs<AppResult.Success<CollaborationQueueDelivery>>(send.await()).value
            assertEquals(
                listOf("original-a", "original-b"),
                result.receipts.map { it.transactionId },
            )
            assertTrue(result.pending.isEmpty())
            assertFalse(f.native.room.enabled)
            assertFalse("send.enqueue" in f.native.room.calls)
        } finally {
            finish.complete(Unit)
            f.close()
        }
    }

    @Test
    fun logicalRevocationDuringReadWithholdsTheSnapshotAndPreventsResume() = runTest {
        val f = SpaceOriginalQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val original =
                assertIs<AppResult.Success<CollaborationSpaceOriginalQueueSnapshot>>(
                        queue.snapshot()
                    )
                    .value
            f.native.room.beforeOriginals = { f.restriction = denied }
            assertEquals(AppResult.Failed(denied), queue.snapshot())
            assertEquals(
                AppResult.Failed(denied),
                queue.resume(original) { error("No revoked delivery") },
            )
            assertFalse(f.native.room.enabled)
            assertEquals(2, f.native.room.originals.size)
        } finally {
            f.close()
        }
    }

    @Test
    fun cancelledWaiterKeepsItsLateReceiptAndCloseDrainsCallbackBeforeReleasingWriter() = runTest {
        val f = SpaceOriginalQueueFixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var close: Deferred<AppResult<Unit>>? = null
        try {
            val queue = f.open()
            val snapshot =
                assertIs<AppResult.Success<CollaborationSpaceOriginalQueueSnapshot>>(
                        queue.snapshot()
                    )
                    .value
            val observations = mutableListOf<CollaborationQueueDelivery>()
            val send = launch {
                queue.resume(snapshot) {
                    observations += it
                    entered.complete(Unit)
                    withContext(NonCancellable) { finish.await() }
                    AppResult.Success(Unit)
                }
            }
            runCurrent()
            send.cancelAndJoin()
            f.restriction = denied
            f.native.room.emit(RoomSendQueueUpdate.SentEvent("original-a", "\$late-event"))
            entered.await()
            close = async { queue.close() }
            runCurrent()
            assertFalse(close.isCompleted)
            assertFalse(f.native.room.enabled)
            assertEquals(
                listOf(CollaborationQueueReceipt("original-a", "\$late-event")),
                observations.single().receipts,
            )
            assertIs<AppResult.Failed>(
                f.native.queues.whileWriterIdle<Unit> { error("Callback owns the writer") }
            )
            finish.complete(Unit)
            assertIs<AppResult.Success<*>>(close.await())
            assertIs<AppResult.Success<*>>(
                f.native.queues.whileWriterIdle { AppResult.Success(Unit) }
            )
        } finally {
            finish.complete(Unit)
            close?.join()
            f.close()
        }
    }

    @Test
    fun primaryReceiptFailureSurvivesLaterRestrictionAndNativeDeliveryPauses() = runTest {
        val f = SpaceOriginalQueueFixture(backgroundScope)
        val journal =
            Failure(FailureKind.Storage, "Fixture journal failure", code = "fixture.journal")
        try {
            val queue = f.open()
            val snapshot =
                assertIs<AppResult.Success<CollaborationSpaceOriginalQueueSnapshot>>(
                        queue.snapshot()
                    )
                    .value
            f.native.room.onEnable = {
                f.native.room.emit(RoomSendQueueUpdate.SentEvent("original-a", "\$observed"))
            }
            val result =
                queue.resume(snapshot) {
                    f.restriction = denied
                    AppResult.Failed(journal)
                }
            assertEquals(AppResult.Failed(journal), result)
            assertFalse(f.native.room.enabled)
            assertEquals(2, f.native.room.originals.size)
        } finally {
            f.close()
        }
    }

    @Test
    fun failedCloseKeepsThePhysicalWriterReservedAndTheLogicalHandleClosed() = runTest {
        val f = SpaceOriginalQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            f.native.room.beforeClose = { error("Fixture close failure") }
            assertIs<AppResult.Failed>(queue.close())
            assertIs<AppResult.Failed>(queue.snapshot())
            assertIs<AppResult.Failed>(
                f.native.queues.whileWriterIdle<Unit> { error("Failed close retains writer") }
            )
        } finally {
            f.native.room.beforeClose = {}
            f.close()
        }
    }

    @Test
    fun missingSettlementIsOnlyAnAbsenceAndNeverAnInventedReceipt() = runTest {
        val f = SpaceOriginalQueueFixture(backgroundScope)
        var settlements = 0
        try {
            val queue =
                f.open { original ->
                    object : CollaborationMessageQueueLease by original {
                        override suspend fun settleConfirmed(
                            original: CollaborationQueuedOriginal,
                            eventId: String,
                        ): AppResult<CollaborationQueueSettlement> {
                            assertEquals("original-a", original.transactionId)
                            assertEquals("\$confirmed", eventId)
                            settlements++
                            return AppResult.Success(CollaborationQueueSettlement.AlreadyAbsent)
                        }
                    }
                }
            val snapshot =
                assertIs<AppResult.Success<CollaborationSpaceOriginalQueueSnapshot>>(
                        queue.snapshot()
                    )
                    .value
            assertEquals(
                AppResult.Success(CollaborationQueueSettlement.AlreadyAbsent),
                queue.settleConfirmed(snapshot.original.messages.first(), "\$confirmed"),
            )
            f.restriction = denied
            assertEquals(
                AppResult.Failed(denied),
                queue.settleConfirmed(snapshot.original.messages.first(), "\$confirmed"),
            )
            assertEquals(1, settlements)
            assertFalse("queue.true" in f.native.room.calls)
        } finally {
            f.close()
        }
    }

    @Test
    fun unsupportedHostCannotProvideAnOriginalWriter() = runTest {
        val f = SpaceOriginalQueueFixture(backgroundScope)
        try {
            val result =
                UnavailableCollaborationRuntimeRepository()
                    .acquireOriginalQueue(f.runtime, "!room:test")
            assertEquals(
                "client.collaboration_runtime_unavailable",
                assertIs<AppResult.Failed>(result).failure.code,
            )
        } finally {
            f.close()
        }
    }

    private val denied =
        Failure(FailureKind.AccessDenied, "Fixture owner retired", code = "fixture.retired")
}

private class SpaceOriginalQueueFixture(scope: CoroutineScope) {
    val native = OriginalQueueFixture(scope)
    val runtime =
        CollaborationSpaceRuntimeReady(
            CollaborationSpaceOwner(
                CollaborationNativeParent(
                    setupOwner.sessionId,
                    setupOwner.audience,
                    setupOwner.accountId,
                    "30000000-0000-4000-8000-000000000001",
                ),
                "30000000-0000-4000-8000-000000000002",
            ),
            "30000000-0000-4000-8000-000000000003",
            SETUP_JOB,
        )
    var restriction: Failure? = null

    suspend fun open(
        decorate: (CollaborationMessageQueueLease) -> CollaborationMessageQueueLease = { it }
    ): AndroidCollaborationSpaceOriginalQueueLease {
        val raw =
            assertIs<AppResult.Success<CollaborationMessageQueueLease>>(
                    native.queues.acquire("!room:test")
                )
                .value
        return AndroidCollaborationSpaceOriginalQueueLease(
            runtime,
            decorate(raw),
            { native.queues.readOriginals(raw) },
            { restriction ?: native.failure },
        )
    }

    suspend fun close() = native.close()
}
