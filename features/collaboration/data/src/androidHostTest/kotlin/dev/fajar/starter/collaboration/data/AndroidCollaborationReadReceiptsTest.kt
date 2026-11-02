package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationReadReceiptsTest {
    @Test
    fun ownStoreReadsAndExactUnthreadedWritesReleaseEachBorrowedRoom() = runTest {
        val f = Fixture(this)
        try {
            assertEquals(
                AppResult.Success(CollaborationOwnReadReceipts("\$public", "\$private")),
                f.receipts.readOwn("!room"),
            )
            assertEquals(listOf(ReceiptType.READ, ReceiptType.READ_PRIVATE), f.reads)
            assertEquals(1, f.closes)
            for (visibility in CollaborationReadReceiptVisibility.entries) assertEquals(
                AppResult.Success(Unit),
                f.receipts.send("!room", "\$event", visibility),
            )
            assertEquals(listOf(ReceiptType.READ, ReceiptType.READ_PRIVATE), f.sends)
            assertEquals(3, f.closes)
            f.publicEventId = null
            f.privateEventId = null
            assertEquals(
                AppResult.Success(CollaborationOwnReadReceipts(null, null)),
                f.receipts.readOwn("!room"),
            )
        } finally {
            f.close()
        }
    }

    @Test
    fun invalidTargetsAndKnownDenialNeverOpenOrWriteAndForeignRoomIsReleased() = runTest {
        val f = Fixture(this)
        try {
            for (room in listOf("", "#alias", "!has space")) assertIs<AppResult.Failed>(
                f.receipts.readOwn(room)
            )
            assertIs<AppResult.Failed>(
                f.receipts.send("!room", "invalid", CollaborationReadReceiptVisibility.Private)
            )
            val denied = Failure(FailureKind.AccessDenied, "Denied", code = "fixture_denied")
            f.failure = denied
            assertEquals(AppResult.Failed(denied), f.receipts.readOwn("!room"))
            assertEquals(0, f.opens)
            f.failure = null
            f.actualRoom = "!foreign"
            assertIs<AppResult.Failed>(
                f.receipts.send("!room", "\$event", CollaborationReadReceiptVisibility.Private)
            )
            assertEquals(1, f.closes)
            assertTrue(f.sends.isEmpty())
        } finally {
            f.close()
        }
    }

    @Test
    fun malformedNativeReceiptCannotBecomeAnOwnedObservation() = runTest {
        val f = Fixture(this)
        try {
            f.privateEventId = "invalid"
            assertIs<AppResult.Failed>(f.receipts.readOwn("!room"))
            assertEquals(1, f.closes)
        } finally {
            f.close()
        }
    }

    @Test
    fun callerCancellationJoinsTheRequestBeforeReleasingItsRoom() = runTest {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        f.onSend = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(200)
                    f.events += "request.stopped"
                }
            }
        }
        try {
            val caller = launch {
                f.receipts.send("!room", "\$event", CollaborationReadReceiptVisibility.Private)
            }
            entered.await()
            caller.cancelAndJoin()
            assertEquals(listOf("request.stopped", "room.closed"), f.events)
        } finally {
            f.close()
        }
    }

    @Test
    fun parentClosureInterruptsTheWaiterAndClosesTheRequestBeforeItsRoom() = runTest {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        f.onSend = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                f.events += "request.stopped"
            }
        }
        try {
            val caller = async {
                f.receipts.send("!room", "\$event", CollaborationReadReceiptVisibility.Public)
            }
            entered.await()
            assertEquals(AppResult.Success(Unit), f.children.close())
            assertEquals(
                "client.collaboration_receipt_interrupted",
                assertIs<AppResult.Failed>(caller.await()).failure.code,
            )
            assertEquals(listOf("request.stopped", "room.closed"), f.events)
        } finally {
            f.close()
        }
    }

    @Test
    fun technicalDeadlineCancelsAnUnfinishedNativeWriteAndKeepsItUnacknowledged() = runTest {
        val f = Fixture(this)
        f.onSend = {
            try {
                awaitCancellation()
            } finally {
                f.events += "request.stopped"
            }
        }
        try {
            assertEquals(
                "client.collaboration_receipt_timeout",
                assertIs<AppResult.Failed>(
                        f.receipts.send(
                            "!room",
                            "\$event",
                            CollaborationReadReceiptVisibility.Private,
                        )
                    )
                    .failure
                    .code,
            )
            assertEquals(listOf("request.stopped", "room.closed"), f.events)
            assertEquals(1, f.sends.size)
        } finally {
            f.close()
        }
    }

    @Test
    fun cancellationDuringRoomAcquisitionNeverStartsALateReceiptWrite() = runTest {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeEncrypted = {
            entered.complete(Unit)
            release.await()
        }
        try {
            val caller = launch {
                f.receipts.send("!room", "\$event", CollaborationReadReceiptVisibility.Private)
            }
            entered.await()
            caller.cancel()
            runCurrent()
            release.complete(Unit)
            caller.join()
            assertTrue(f.sends.isEmpty())
            assertEquals(1, f.closes)
        } finally {
            f.close()
        }
    }

    @Test
    fun lateDenialWinsAndFailedRoomCleanupStaysOwnedUntilRetryWithoutRepeatingTheWrite() = runTest {
        val f = Fixture(this)
        try {
            val denied = Failure(FailureKind.AccessDenied, "Denied", code = "fixture_denied")
            f.onSend = { f.failure = denied }
            assertEquals(
                AppResult.Failed(denied),
                f.receipts.send("!room", "\$event", CollaborationReadReceiptVisibility.Private),
            )
            f.failure = null
            f.onSend = {}
            f.failClose = true
            assertIs<AppResult.Failed>(
                f.receipts.send("!room", "\$event", CollaborationReadReceiptVisibility.Private)
            )
            f.failClose = false
            assertEquals(AppResult.Success(Unit), f.children.close())
            assertEquals(2, f.sends.size)
            assertEquals(2, f.closes)
        } finally {
            f.close()
        }
    }

    private class Fixture(scope: TestScope) {
        private val lifetime =
            CoroutineScope(
                SupervisorJob() +
                    (scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            )
        val children = SdkChildResources(lifetime)
        val events = mutableListOf<String>()
        val reads = mutableListOf<ReceiptType>()
        val sends = mutableListOf<ReceiptType>()
        var opens = 0
        var closes = 0
        var actualRoom = "!room"
        var publicEventId: String? = "\$public"
        var privateEventId: String? = "\$private"
        var failure: Failure? = null
        var failClose = false
        var beforeEncrypted: suspend () -> Unit = {}
        var onSend: suspend () -> Unit = {}
        private val client =
            object : Client(NoHandle) {
                override fun getRoom(roomId: String): Room? {
                    assertEquals("!room", roomId)
                    opens++
                    return object : Room(NoHandle) {
                        override fun id() = actualRoom

                        override suspend fun isEncrypted(): Boolean {
                            beforeEncrypted()
                            return true
                        }

                        override suspend fun loadUserReceipt(
                            receiptType: ReceiptType,
                            thread: ReceiptThread,
                            userId: String,
                        ): UserReceipt? {
                            assertEquals(ReceiptThread.Unthreaded, thread)
                            assertEquals("@own:proof.invalid", userId)
                            reads += receiptType
                            return (if (receiptType == ReceiptType.READ) publicEventId
                                else privateEventId)
                                ?.let { UserReceipt(it, Receipt(null)) }
                        }

                        override suspend fun sendSingleReceipt(
                            receiptType: ReceiptType,
                            thread: ReceiptThread,
                            eventId: String,
                        ) {
                            assertEquals(ReceiptThread.Unthreaded, thread)
                            assertEquals("\$event", eventId)
                            sends += receiptType
                            onSend()
                        }

                        override fun close() {
                            if (failClose) error("Fixture cleanup failure")
                            closes++
                            events += "room.closed"
                        }
                    }
                }
            }
        val receipts =
            AndroidCollaborationReadReceipts(client, "@own:proof.invalid", children, lifetime) {
                failure
            }

        suspend fun close() {
            assertEquals(AppResult.Success(Unit), children.close())
            lifetime.cancel()
        }
    }
}
