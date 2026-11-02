package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.sync.domain.SyncResult
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** Actual shared graph, encrypted SQLite and HTTP mappings, with an explicitly simulated SDK. */
class CollaborationReadReceiptRecoveryTest {
    @Test
    fun pendingReadIntentReopensAndDeliversWithoutOutboxOrSelectedWorkspace() = runBlocking {
        withFixture { f ->
            val original = f.acquireOriginal()
            val intent = f.mark(original.eventId)
            assertTrue(f.sdk.readReceipts.isEmpty())
            assertIs<AppResult.Success<*>>(f.koin.get<RequestCollaborationRecovery>()())
            assertTrue(CollaborationRecoveryWake.WORK_KEY in f.scheduled)
            val before = f.read(original.eventId)
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(before, f.read(original.eventId))
            assertEquals(SyncResult.Complete, f.runIncoming())
            assertEquals(
                listOf(Triple(f.roomId(), original.eventId, intent.visibility)),
                f.sdk.readReceipts,
            )
            val after = f.read(original.eventId)
            assertEquals(before.readEventIds, after.readEventIds)
            assertTrue(after.pending.isEmpty())
            assertEquals(intent, after.acknowledged)
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(after, f.read(original.eventId))
            assertEquals(SyncResult.Complete, f.runIncoming())
            assertEquals(1, f.sdk.readReceipts.size)
            assertTrue(f.sdk.enqueued.isEmpty())
            assertEquals(0, f.sdk.deliveries)
            assertReleased(f)
        }
    }

    @Test
    fun receiptLimitRetainsContinuationAcrossFairRoomTurns() = runBlocking {
        withFixture { f ->
            val original = f.acquireOriginal()
            val intents = List(5) { f.mark(original.eventId) }
            assertEquals(SyncResult.Retry(), f.runIncoming())
            assertEquals(intents.drop(2), f.read(original.eventId).pending)
            assertEquals(2, f.sdk.readReceipts.size)
            assertEquals(SyncResult.Retry(), f.runIncoming())
            assertEquals(intents.drop(4), f.read(original.eventId).pending)
            assertEquals(SyncResult.Complete, f.runIncoming())
            assertTrue(f.read(original.eventId).pending.isEmpty())
            assertEquals(intents.last(), f.read(original.eventId).acknowledged)
            assertEquals(5, f.sdk.readReceipts.size)
            assertReleased(f)
        }
    }

    @Test
    fun failedReceiptDoesNotStarveIncomingHistoryOrEraseItsIntent() = runBlocking {
        withFixture { f ->
            val original = f.acquireOriginal()
            val intent = f.mark(original.eventId)
            val later = f.sdk.incoming(f.first, "Fictional later incoming")
            f.sdk.readReceiptFailure = Failure(FailureKind.Network, "Temporarily unavailable")
            val result = f.runIncoming()
            assertNotNull(assertIs<SyncResult.Retry>(result).failure)
            assertEquals(listOf(intent), f.read(original.eventId).pending)
            assertEquals(later.text, f.incomingOriginal(later)?.text)
            f.sdk.readReceiptFailure = null
            assertEquals(SyncResult.Complete, f.runIncoming())
            assertTrue(f.read(original.eventId).pending.isEmpty())
            assertReleased(f)
        }
    }

    @Test
    fun receiptTimeoutJoinsTheRequestAndLeavesAnIncomingTurn() = runBlocking {
        withFixture { f ->
            val original = f.acquireOriginal()
            val intent = f.mark(original.eventId)
            val later = f.sdk.incoming(f.first, "Fictional incoming after receipt timeout")
            var joined = false
            f.sdk.beforeReadReceipt = {
                try {
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            val result = withTimeout(8.seconds) { f.runIncoming() }
            assertNotNull(assertIs<SyncResult.Retry>(result).failure)
            assertTrue(joined)
            assertEquals(later.text, f.incomingOriginal(later)?.text)
            assertEquals(listOf(intent), f.read(original.eventId).pending)
            assertReleased(f)
        }
    }

    @Test
    fun cancelledReceiptIsJoinedBeforeRuntimeReleaseAndRecoversAfterReopen() = runBlocking {
        withFixture { f ->
            val original = f.acquireOriginal()
            val intent = f.mark(original.eventId)
            val entered = CompletableDeferred<Unit>()
            var joined = false
            f.sdk.beforeReadReceipt = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            val job = async { f.runIncoming() }
            withTimeout(5.seconds) { entered.await() }
            assertEquals(1, f.sdk.activeReadReceipts)
            job.cancelAndJoin()
            assertTrue(joined)
            assertEquals(listOf(intent), f.read(original.eventId).pending)
            assertReleased(f)
            f.sdk.beforeReadReceipt = {}
            f.reopen()
            f.withoutIncomingSelection()
            repeat(3) { if (f.read(original.eventId).pending.isNotEmpty()) f.runIncoming() }
            assertTrue(f.read(original.eventId).pending.isEmpty())
            assertEquals(intent, f.read(original.eventId).acknowledged)
            assertReleased(f)
        }
    }

    @Test
    fun aNewReadDuringPendingDeliveryRetainsItsOwnPointAndCoverage() = runBlocking {
        withFixture { f ->
            val original = f.acquireOriginal()
            val first = f.mark(original.eventId)
            var next: CollaborationReadReceiptIntent? = null
            f.sdk.beforeReadReceipt = {
                f.sdk.beforeReadReceipt = {}
                next = f.mark(original.eventId, CollaborationReadReceiptVisibility.Public)
            }
            assertEquals(SyncResult.Retry(), f.runIncoming())
            val after = f.read(original.eventId)
            assertEquals(first, after.acknowledged)
            assertEquals(listOf(requireNotNull(next)), after.pending)
            assertEquals(setOf(original.eventId), after.readEventIds)
            assertEquals(SyncResult.Complete, f.runIncoming())
            assertEquals(next, f.read(original.eventId).acknowledged)
            assertEquals(
                listOf(
                    CollaborationReadReceiptVisibility.Private,
                    CollaborationReadReceiptVisibility.Public,
                ),
                f.sdk.readReceipts.map { it.third },
            )
            assertReleased(f)
        }
    }

    @Test
    fun confirmedReceiptDenialRetainsEncryptedFactsAndClosesLocalPublication() = runBlocking {
        withFixture { f ->
            val original = f.acquireOriginal()
            val intent = f.mark(original.eventId)
            val denied =
                Failure(
                    FailureKind.AccessDenied,
                    "Not admitted",
                    code = "collaboration_access_denied",
                )
            f.sdk.readReceiptFailure = denied
            f.runIncoming()
            val access = f.incomingGrant().owner.localListAccess()
            val gated =
                assertIs<AppResult.Failed>(
                    f.koin.get<ReadCollaborationReadState>()(
                        CollaborationLocalScope.Captured(access),
                        f.first,
                        f.roomId(),
                        listOf(original.eventId),
                    )
                )
            // The retained gate maps provider text to safe product copy.
            assertEquals(denied.kind, gated.failure.kind)
            assertEquals(denied.code, gated.failure.code)
            val retained =
                assertIs<AppResult.Success<CollaborationReadState>>(
                        f.koin
                            .get<CollaborationReadStateRepository>()
                            .read(access, f.first, f.roomId(), listOf(original.eventId))
                    )
                    .value
            assertEquals(listOf(intent), retained.pending)
            assertEquals(setOf(original.eventId), retained.readEventIds)
            assertNull(retained.acknowledged)
            assertReleased(f)
        }
    }

    private suspend fun CollaborationWorkerFixture.acquireOriginal():
        CollaborationHistoryChange.Text {
        val original = sdk.incoming(first, "Fictional message to read")
        assertEquals(SyncResult.Complete, runIncoming())
        assertEquals(original.text, incomingOriginal(original)?.text)
        return original
    }

    private suspend fun CollaborationWorkerFixture.mark(
        eventId: String,
        visibility: CollaborationReadReceiptVisibility = CollaborationReadReceiptVisibility.Private,
    ): CollaborationReadReceiptIntent {
        val access = incomingGrant().owner.localListAccess()
        val intent =
            CollaborationReadReceiptIntent(UUID.randomUUID().toString(), eventId, visibility)
        assertIs<AppResult.Success<*>>(
            koin.get<MarkCollaborationMessagesRead>()(
                CollaborationLocalScope.Captured(access),
                first,
                roomId(),
                listOf(eventId),
                intent,
                access,
            )
        )
        return intent
    }

    private suspend fun CollaborationWorkerFixture.read(eventId: String) =
        assertIs<AppResult.Success<CollaborationReadState>>(
                koin.get<ReadCollaborationReadState>()(
                    CollaborationLocalScope.Captured(incomingGrant().owner.localListAccess()),
                    first,
                    roomId(),
                    listOf(eventId),
                )
            )
            .value

    private suspend fun CollaborationWorkerFixture.runIncoming() =
        koin.get<CollaborationIncomingRecovery>()(incomingGrant())

    private fun CollaborationWorkerFixture.roomId() = server.collaboration.roomId(first)

    private fun assertReleased(f: CollaborationWorkerFixture) {
        assertEquals(0, f.sdk.activeReadReceipts)
        assertEquals(f.sdk.backgroundAcquisitions, f.sdk.backgroundCloses)
        assertEquals(f.sdk.childOpens, f.sdk.childCloses)
        assertEquals(0, f.sdk.backgroundInterruptions.subscriptionCount.value)
        assertEquals(0, f.sdk.backgroundCredentialFailures.subscriptionCount.value)
    }

    private suspend fun withFixture(block: suspend (CollaborationWorkerFixture) -> Unit) {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.server.collaboration.listedIds = listOf(f.first)
            f.sdk.backgroundEnabled = true
            f.withoutIncomingSelection()
            block(f)
        } finally {
            f.close()
        }
    }
}
