package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.messages.collaborationOutgoingContent
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.QueuedMessageEvent

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSpaceMessageQueueLeaseTest {
    @Test
    fun stagingHandleRetainsPhysicalIdentityWithoutGrantingDelivery() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val snapshot = f.snapshot(queue)
            assertEquals(f.runtime, snapshot.runtime)
            assertEquals(setupOwner.setupBinding(), snapshot.original.binding)
            assertEquals(SETUP_OPERATION, snapshot.original.device.bootstrapOperationId)
            assertNotEquals(f.runtime.operationId, snapshot.original.device.bootstrapOperationId)
            assertFalse((queue as Any) is CollaborationSpaceOriginalQueueLease)
            assertIs<AppResult.Failed>(f.native.queues.acquire("!room:test"))
            assertFalse(f.native.room.enabled)
            assertFalse(queue.toString().contains(setupOwner.accountId))
        } finally {
            f.close()
        }
    }

    @Test
    fun exactCaptureStagesOneNewOriginalAndPreservesExistingTransactions() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val before = f.snapshot(queue)
            val origin = f.origin(queue)
            assertIs<AppResult.Success<Unit>>(queue.enqueue(before, f.logicalId, f.text, origin))
            val after = f.snapshot(queue)
            assertEquals(before.original.messages, after.original.messages.take(2))
            val added = after.original.messages.last()
            assertEquals(f.logicalId, added.logicalId)
            assertEquals(origin, added.origin)
            assertEquals("new-original", added.transactionId)
            assertEquals(1, f.enqueues)
            assertFalse(f.native.room.enabled)
            assertFalse("queue.true" in f.native.room.calls)
        } finally {
            f.close()
        }
    }

    @Test
    fun existingLogicalIdCannotBeEnqueuedAgain() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val snapshot = f.snapshot(queue)
            val id = requireNotNull(snapshot.original.messages.first().logicalId)
            assertIs<AppResult.Failed>(queue.enqueue(snapshot, id, f.text, f.origin(queue)))
            assertEquals(0, f.enqueues)
        } finally {
            f.close()
        }
    }

    @Test
    fun changedLogicalOrPhysicalOwnerAndPartialCaptureCannotEnqueue() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val snapshot = f.snapshot(queue)
            val p = snapshot.original
            val variants =
                listOf(
                    snapshot.copy(runtime = f.runtime.copy(operationId = SETUP_OPERATION)),
                    snapshot.copy(
                        original = p.copy(binding = p.binding.copy(nativeSessionId = SETUP_JOB))
                    ),
                    snapshot.copy(
                        original = p.copy(device = p.device.copy(bootstrapOperationId = SETUP_JOB))
                    ),
                    snapshot.copy(original = p.copy(messages = p.messages.take(1))),
                    snapshot.copy(original = p.copy(messages = p.messages + p.messages.first())),
                    snapshot.copy(original = p.copy(messages = List(128) { p.messages.first() })),
                    snapshot.copy(
                        original =
                            p.copy(
                                messages = p.messages.map { it.copy(serializedContent = "changed") }
                            )
                    ),
                )
            val origin = f.origin(queue)
            for (changed in variants) assertIs<AppResult.Failed>(
                queue.enqueue(changed, f.logicalId, f.text, origin)
            )
            assertEquals(0, f.enqueues)
            assertEquals(2, f.native.room.originals.size)
        } finally {
            f.close()
        }
    }

    @Test
    fun changedNativeInventoryAfterCaptureDoesNotAddAnotherMessage() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val snapshot = f.snapshot(queue)
            val origin = f.origin(queue)
            f.native.room.originals =
                f.native.room.originals.map {
                    it.copy(
                        contentJson = it.contentJson.replace("Original message", "Changed message")
                    )
                }
            assertIs<AppResult.Failed>(queue.enqueue(snapshot, f.logicalId, f.text, origin))
            assertEquals(0, f.enqueues)
        } finally {
            f.close()
        }
    }

    @Test
    fun callerCaptureIsDetachedBeforeNativeReadSuspends() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        try {
            val queue = f.open()
            val before = f.snapshot(queue)
            val origin = f.origin(queue)
            val mutable = before.original.messages.toMutableList()
            val captured = before.copy(original = before.original.copy(messages = mutable))
            f.native.room.beforeOriginals = {
                entered.complete(Unit)
                finish.await()
            }
            val enqueue = async { queue.enqueue(captured, f.logicalId, f.text, origin) }
            entered.await()
            mutable.clear()
            assertIs<AppResult.Failed>(queue.snapshot())
            assertIs<AppResult.Failed>(queue.prepareText(f.logicalId, f.text))
            finish.complete(Unit)
            assertIs<AppResult.Success<Unit>>(enqueue.await())
            assertEquals(1, f.enqueues)
        } finally {
            finish.complete(Unit)
            f.close()
        }
    }

    @Test
    fun retirementDuringCaptureStopsEnqueueAfterNativeRead() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val snapshot = f.snapshot(queue)
            val origin = f.origin(queue)
            f.native.room.beforeOriginals = { f.restriction = f.denied }
            assertEquals(
                AppResult.Failed(f.denied),
                queue.enqueue(snapshot, f.logicalId, f.text, origin),
            )
            assertEquals(0, f.enqueues)
        } finally {
            f.close()
        }
    }

    @Test
    fun lateRetirementPreservesTheEnqueuedOriginalWithoutPublishingSuccess() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val snapshot = f.snapshot(queue)
            val origin = f.origin(queue)
            f.afterEnqueue = { f.restriction = f.denied }
            assertEquals(
                AppResult.Failed(f.denied),
                queue.enqueue(snapshot, f.logicalId, f.text, origin),
            )
            assertEquals(3, f.native.room.originals.size)
            assertEquals(1, f.enqueues)
            assertFalse(f.native.room.enabled)
        } finally {
            f.close()
        }
    }

    @Test
    fun cancelledResponseDoesNotEraseCommittedNativeOriginalOrEnqueueAgain() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val snapshot = f.snapshot(queue)
            val origin = f.origin(queue)
            f.afterEnqueue = { throw CancellationException("Response lost after enqueue") }
            assertFailsWith<CancellationException> {
                queue.enqueue(snapshot, f.logicalId, f.text, origin)
            }
            f.afterEnqueue = {}
            val retained = f.snapshot(queue)
            assertEquals(f.logicalId, retained.original.messages.last().logicalId)
            assertIs<AppResult.Failed>(queue.enqueue(retained, f.logicalId, f.text, origin))
            assertEquals(1, f.enqueues)
        } finally {
            f.close()
        }
    }

    @Test
    fun failedNativeResponseIsPreservedAndTheOriginalRemainsInspectable() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            val snapshot = f.snapshot(queue)
            val origin = f.origin(queue)
            f.result = AppResult.Failed(Failure(FailureKind.Storage, "Fixture lost response"))
            assertEquals(f.result, queue.enqueue(snapshot, f.logicalId, f.text, origin))
            assertEquals(f.logicalId, f.snapshot(queue).original.messages.last().logicalId)
            assertEquals(1, f.enqueues)
        } finally {
            f.close()
        }
    }

    @Test
    fun closeDrainsAnOutstandingCaptureAndPreventsItsLateEnqueue() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        try {
            val queue = f.open()
            val snapshot = f.snapshot(queue)
            val origin = f.origin(queue)
            f.native.room.beforeOriginals = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    finish.await()
                }
            }
            val enqueue = async { queue.enqueue(snapshot, f.logicalId, f.text, origin) }
            entered.await()
            val close = async { queue.close() }
            runCurrent()
            assertFalse(close.isCompleted)
            assertIs<AppResult.Failed>(f.native.queues.whileWriterIdle { AppResult.Success(Unit) })
            finish.complete(Unit)
            assertIs<AppResult.Failed>(enqueue.await())
            assertIs<AppResult.Success<Unit>>(close.await())
            assertEquals(0, f.enqueues)
            assertIs<AppResult.Success<Unit>>(
                f.native.queues.whileWriterIdle { AppResult.Success(Unit) }
            )
        } finally {
            finish.complete(Unit)
            f.close()
        }
    }

    @Test
    fun failedCloseKeepsPhysicalReservationAndClosesStagingHandle() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        try {
            val queue = f.open()
            f.native.room.beforeClose = { error("Fixture close failure") }
            assertIs<AppResult.Failed>(queue.close())
            assertIs<AppResult.Failed>(queue.snapshot())
            assertIs<AppResult.Failed>(queue.prepareText(f.logicalId, f.text))
            assertIs<AppResult.Failed>(f.native.queues.whileWriterIdle { AppResult.Success(Unit) })
        } finally {
            f.native.room.beforeClose = {}
            f.close()
        }
    }

    @Test
    fun unsupportedHostCannotInventAStagingWriter() = runTest {
        val f = SpaceMessageQueueFixture(backgroundScope)
        try {
            val result =
                UnavailableCollaborationRuntimeRepository()
                    .acquireSpaceMessageQueue(f.runtime, "!room:test")
            assertEquals(
                "client.collaboration_runtime_unavailable",
                assertIs<AppResult.Failed>(result).failure.code,
            )
            assertEquals(0, f.enqueues)
        } finally {
            f.close()
        }
    }
}

private class SpaceMessageQueueFixture(scope: CoroutineScope) {
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
    val logicalId = "a0000000-0000-4000-8000-000000000003"
    val text = "New organization message"
    val denied = Failure(FailureKind.AccessDenied, "Fixture retired", code = "fixture.retired")
    var restriction: Failure? = null
    var enqueues = 0
    var afterEnqueue: suspend () -> Unit = {}
    var result: AppResult<Unit> = AppResult.Success(Unit)

    suspend fun open(): CollaborationSpaceMessageQueueLease {
        val raw =
            assertIs<AppResult.Success<CollaborationMessageQueueLease>>(
                    native.queues.acquire("!room:test")
                )
                .value
        val recorded =
            object : CollaborationMessageQueueLease by raw {
                override suspend fun enqueue(
                    logicalId: String,
                    text: String,
                    origin: CollaborationMessageOrigin,
                    replyToEventId: String?,
                    forwarded: Boolean,
                    editTarget: CollaborationEditTarget?,
                ): AppResult<Unit> {
                    // Host fake for one raw FFI enqueue; real paused inventory/exclusion stays in
                    // use.
                    assertEquals(
                        origin,
                        assertIs<AppResult.Success<CollaborationMessageOrigin>>(
                                raw.prepareText(
                                    logicalId,
                                    text,
                                    replyToEventId,
                                    forwarded,
                                    editTarget,
                                )
                            )
                            .value,
                    )
                    enqueues++
                    native.room.originals =
                        native.room.originals +
                            QueuedMessageEvent(
                                "new-original",
                                "m.room.message",
                                collaborationOutgoingContent(
                                        logicalId,
                                        text,
                                        replyToEventId,
                                        forwarded,
                                        editTarget,
                                    )
                                    .toString(),
                            )
                    native.room.transactions = native.room.transactions + "new-original"
                    afterEnqueue()
                    return result
                }
            }
        return AndroidCollaborationSpaceMessageQueueLease(
            runtime,
            recorded,
            { native.queues.readOriginals(raw) },
            { restriction ?: native.failure },
        )
    }

    suspend fun snapshot(queue: CollaborationSpaceMessageQueueLease) =
        assertIs<AppResult.Success<CollaborationSpaceOriginalQueueSnapshot>>(queue.snapshot()).value

    suspend fun origin(queue: CollaborationSpaceMessageQueueLease) =
        assertIs<AppResult.Success<CollaborationMessageOrigin>>(queue.prepareText(logicalId, text))
            .value

    suspend fun close() = native.close()
}
