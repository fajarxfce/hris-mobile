package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationMessageQueueLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.RequestFailureException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationOriginalQueueTest {
    @Test
    fun completeObservationPreservesPhysicalDeviceBootstrapAndExactOriginalContent() = runTest {
        val f = OriginalQueueFixture(backgroundScope)
        try {
            val result =
                assertIs<AppResult.Success<CollaborationOriginalQueueSnapshot>>(f.read()).value
            assertEquals(setupOwner.setupBinding(), result.binding)
            assertEquals(
                CollaborationMessageDevice(
                    setupOwner.nativeSessionId,
                    SETUP_DEVICE,
                    SETUP_OPERATION,
                    "!room:test",
                ),
                result.device,
            )
            assertEquals(
                f.room.originals.map { it.transactionId },
                result.messages.map { it.transactionId },
            )
            assertEquals(
                f.room.originals.map { it.contentJson },
                result.messages.map { it.serializedContent },
            )
            assertEquals(
                setOf("@fixture:example.test"),
                result.messages.map { it.origin.sender }.toSet(),
            )
            assertEquals(2, result.messages.map { it.origin.contentHash }.distinct().size)
            assertFalse(result.toString().contains("Original" + " message"))
            f.assertReleased()
        } finally {
            f.close()
        }
    }

    @Test
    fun emptyQueueRemainsAnEmptyFactWithoutEnqueueOrReceipt() = runTest {
        val f = OriginalQueueFixture(backgroundScope)
        f.room.transactions = emptySet()
        f.room.originals = emptyList()
        try {
            assertTrue(
                assertIs<AppResult.Success<CollaborationOriginalQueueSnapshot>>(f.read())
                    .value
                    .messages
                    .isEmpty()
            )
            f.assertReleased()
        } finally {
            f.close()
        }
    }

    @Test
    fun missingExtraOrDuplicateOriginalsCannotBeReportedAsACompleteQueue() = runTest {
        for (variant in 0..2) {
            val f = OriginalQueueFixture(backgroundScope)
            f.room.originals =
                when (variant) {
                    0 -> f.room.originals.take(1)
                    1 ->
                        f.room.originals +
                            f.room.originals.first().copy(transactionId = "extra-transaction")
                    else -> f.room.originals + f.room.originals.first()
                }
            val retained = f.room.originals.toList()
            try {
                assertIs<AppResult.Failed>(f.read())
                assertEquals(retained, f.room.originals)
                f.assertReleased()
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun queueChangingBetweenCapturedIdsAndOriginalsWithholdsTheSnapshot() = runTest {
        val f = OriginalQueueFixture(backgroundScope)
        f.room.beforeOriginals = { f.room.transactions = emptySet() }
        try {
            assertIs<AppResult.Failed>(f.read())
            assertEquals(2, f.room.originals.size)
            f.assertReleased()
        } finally {
            f.close()
        }
    }

    @Test
    fun malformedOriginalContentFailsAndRetainsItsNativeTransaction() = runTest {
        val f = OriginalQueueFixture(backgroundScope)
        f.room.originals = f.room.originals.map { it.copy(contentJson = "invalid original") }
        val retained = f.room.originals.toList()
        try {
            assertIs<AppResult.Failed>(f.read())
            assertEquals(retained, f.room.originals)
            f.assertReleased()
        } finally {
            f.close()
        }
    }

    @Test
    fun oversizedCapturedQueueFailsBeforeReadingItsOriginalBodies() = runTest {
        val f = OriginalQueueFixture(backgroundScope)
        f.room.transactions = (0..128).map { "transaction-$it" }.toSet()
        try {
            assertIs<AppResult.Failed>(f.read())
            assertEquals(0, f.room.originalReads)
            f.assertReleased()
        } finally {
            f.close()
        }
    }

    @Test
    fun observationCannotStealAnExistingDeliveryWriter() = runTest {
        val f = OriginalQueueFixture(backgroundScope)
        try {
            val writer =
                assertIs<AppResult.Success<CollaborationMessageQueueLease>>(
                        f.queues.acquire("!room:test")
                    )
                    .value
            try {
                assertEquals(
                    "client.collaboration_queue_busy",
                    assertIs<AppResult.Failed>(f.read()).failure.code,
                )
                assertEquals(0, f.room.originalReads)
                assertFalse("room.close" in f.room.calls)
            } finally {
                assertIs<AppResult.Success<*>>(writer.close())
            }
            assertIs<AppResult.Success<*>>(f.read())
        } finally {
            f.close()
        }
    }

    @Test
    fun cancellationKeepsLateNativeReadAndWriterOwnedUntilTheyDrain() = runTest {
        val f = OriginalQueueFixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.room.beforeOriginals = {
            entered.complete(Unit)
            withContext(NonCancellable) { finish.await() }
        }
        val task = async { f.read() }
        try {
            entered.await()
            task.cancel()
            runCurrent()
            assertIs<AppResult.Failed>(
                f.queues.whileWriterIdle<Unit> { error("Late read still owns writer") }
            )
            assertFalse("room.close" in f.room.calls)
            finish.complete(Unit)
            assertFailsWith<CancellationException> { task.await() }
            task.join()
            f.assertReleased()
        } finally {
            finish.complete(Unit)
            task.cancelAndJoin()
            f.close()
        }
    }

    @Test
    fun parentRestrictionDuringReadSuppressesItsLateSnapshot() = runTest {
        val f = OriginalQueueFixture(backgroundScope)
        val denied = Failure(FailureKind.AccessDenied, "Fixture denied", code = "fixture.denied")
        f.room.beforeOriginals = { f.failure = denied }
        try {
            assertEquals(AppResult.Failed(denied), f.read())
            assertEquals(2, f.room.originals.size)
            f.assertReleased()
        } finally {
            f.close()
        }
    }

    @Test
    fun primaryReadFailureSurvivesSecondaryCloseFailureAndWriterStaysOwned() = runTest {
        val f = OriginalQueueFixture(backgroundScope)
        val original =
            Failure(FailureKind.Conflict, "Fixture read failure", code = "fixture.original")
        f.room.beforeOriginals = { throw RequestFailureException(original) }
        f.room.beforeClose = { error("Fixture close failure") }
        try {
            assertEquals(AppResult.Failed(original), f.read())
            assertIs<AppResult.Failed>(
                f.queues.whileWriterIdle<Unit> { error("Failed cleanup still owns writer") }
            )
            assertEquals(2, f.room.originals.size)
        } finally {
            f.room.beforeClose = {}
            f.close()
        }
    }

    @Test
    fun failedCleanupWithholdsOtherwiseSuccessfulObservation() = runTest {
        val f = OriginalQueueFixture(backgroundScope)
        f.room.beforeClose = { error("Fixture close failure") }
        try {
            assertIs<AppResult.Failed>(f.read())
            assertIs<AppResult.Failed>(
                f.queues.whileWriterIdle<Unit> { error("Cleanup still owns writer") }
            )
        } finally {
            f.room.beforeClose = {}
            f.close()
        }
    }
}

private class OriginalQueueFixture(lifetime: CoroutineScope) {
    val room = QueueRoom(transactions = setOf("original-a", "original-b"))
    val children = SdkChildResources(lifetime)
    var failure: Failure? = null
    val queues =
        AndroidCollaborationMessageQueues(
            setupOwner,
            SETUP_DEVICE,
            SETUP_OPERATION,
            "@fixture:example.test",
            object : Client(NoHandle) {
                override fun getRoom(roomId: String): Room {
                    check(roomId == "!room:test")
                    return room
                }
            },
            lifetime,
            children,
            onInterrupted = {},
            failure = { failure },
        )

    init {
        room.originals =
            listOf(
                QueuedMessageEvent(
                    "original-a",
                    "m.room.message",
                    """{"msgtype":"m.text","body":"Original message A","org.hris.message_id":"a0000000-0000-4000-8000-000000000001","extra":{"retained":true}}""",
                ),
                QueuedMessageEvent(
                    "original-b",
                    "m.room.message",
                    """{"msgtype":"m.text","body":"Original message B","org.hris.message_id":"a0000000-0000-4000-8000-000000000002","extra":{"retained":false}}""",
                ),
            )
    }

    suspend fun read() = queues.inspectOriginals("!room:test")

    suspend fun assertReleased() {
        assertEquals(1, room.calls.count { it == "room.close" })
        assertFalse("queue.true" in room.calls)
        assertFalse("send.enqueue" in room.calls)
        assertTrue(room.timeline.retried.isEmpty())
        assertIs<AppResult.Success<*>>(queues.whileWriterIdle { AppResult.Success(Unit) })
    }

    suspend fun close() {
        assertIs<AppResult.Success<*>>(children.close())
    }
}
