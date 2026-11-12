@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.SdkRoomKeyBatch
import dev.fajar.starter.collaboration.data.sdk.SdkRoomKeyMailbox
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SdkRoomKeyMailboxTest {
    @Test
    fun sameRoomsArrivingDuringAWriteRemainAsANewerReceipt() {
        val mailbox = mailbox()
        mailbox.offer(listOf("!room:test"), false)
        val writing = assertNotNull(mailbox.current())
        mailbox.offer(listOf("!room:test"), false)
        assertEquals(writing, mailbox.current())
        assertTrue(mailbox.acknowledge(writing.receiptId))
        val next = assertNotNull(mailbox.current())
        assertEquals(writing.roomIds, next.roomIds)
        assertNotEquals(writing.receiptId, next.receiptId)
    }

    @Test
    fun waitingCallbacksCoalesceWithoutChangingTheDeliveredCapture() {
        val mailbox = mailbox()
        mailbox.offer(listOf("!first:test"), false)
        val writing = assertNotNull(mailbox.current())
        mailbox.offer(listOf("!second:test"), false)
        mailbox.offer(listOf("!third:test", "!second:test"), false)
        assertEquals(writing, mailbox.current())
        assertTrue(mailbox.acknowledge(writing.receiptId))
        assertEquals(listOf("!second:test", "!third:test"), mailbox.current()?.roomIds)
        assertEquals("receipt-3", mailbox.current()?.receiptId)
    }

    @Test
    fun anUnconfirmedWriteKeepsTheSameCaptureAcrossNewObservation() = runTest {
        val mailbox = mailbox()
        mailbox.offer(listOf("!first:test"), false)
        val received = mailbox.observe().first()
        mailbox.offer(listOf("!second:test"), false)
        assertEquals(received, mailbox.observe().first())
        assertEquals(received, mailbox.current())
    }

    @Test
    fun staleAcknowledgmentCannotRemovePromotedWaitingKeys() {
        val mailbox = mailbox()
        mailbox.offer(listOf("!first:test"), false)
        val first = assertNotNull(mailbox.current())
        mailbox.offer(listOf("!second:test"), false)
        assertTrue(mailbox.acknowledge(first.receiptId))
        val second = assertNotNull(mailbox.current())
        repeat(2) { assertFalse(mailbox.acknowledge(first.receiptId)) }
        assertEquals(second, mailbox.current())
        assertTrue(mailbox.acknowledge(second.receiptId))
        assertNull(mailbox.current())
    }

    @Test
    fun unknownAcknowledgmentDoesNotChangeEitherSlot() {
        val mailbox = mailbox()
        assertFalse(mailbox.acknowledge("unknown"))
        mailbox.offer(listOf("!first:test"), false)
        val first = assertNotNull(mailbox.current())
        mailbox.offer(listOf("!second:test"), true)
        assertFalse(mailbox.acknowledge("unknown"))
        assertEquals(first, mailbox.current())
        assertTrue(mailbox.acknowledge(first.receiptId))
        assertEquals(listOf("!second:test"), mailbox.current()?.roomIds)
        assertTrue(assertNotNull(mailbox.current()).reconcileRequired)
    }

    @Test
    fun mutableNativeListsAreCopiedBeforeTheCallbackReturns() {
        val mailbox = mailbox()
        val first = mutableListOf("!first:test", "!other:test")
        mailbox.offer(first, false)
        first.clear()
        val waiting = mutableListOf("!second:test")
        mailbox.offer(waiting, false)
        waiting[0] = "!mutated:test"
        val delivered = assertNotNull(mailbox.current())
        assertEquals(listOf("!first:test", "!other:test"), delivered.roomIds)
        mailbox.acknowledge(delivered.receiptId)
        assertEquals(listOf("!second:test"), mailbox.current()?.roomIds)
    }

    @Test
    fun aConsumerCannotMutateTheRetainedCallbackThroughItsSnapshot() {
        val mailbox = mailbox()
        mailbox.offer(listOf("!first:test", "!second:test"), false)
        val received = assertNotNull(mailbox.current())
        (received.roomIds as MutableList<String>).clear()
        assertEquals(listOf("!first:test", "!second:test"), mailbox.current()?.roomIds)
    }

    @Test
    fun oversizedNativeInputIsReadOnlyUpToTheBoundAndRequiresReconciliation() {
        val mailbox = mailbox()
        var reads = 0
        val large =
            object : AbstractList<String>() {
                override val size = 1_000_000

                override fun get(index: Int): String {
                    check(index < 64)
                    reads++
                    return "!room-$index:test"
                }
            }
        mailbox.offer(large, false)
        val received = assertNotNull(mailbox.current())
        assertEquals(64, reads)
        assertEquals(64, received.roomIds.size)
        assertTrue(received.reconcileRequired)
    }

    @Test
    fun invalidRawSizesBecomeReconciliationWithoutRetainingOversizedData() {
        val mailbox = mailbox()
        mailbox.offer(listOf("", "x".repeat(1025), "!valid:test"), false)
        val received = assertNotNull(mailbox.current())
        assertEquals(listOf("!valid:test"), received.roomIds)
        assertTrue(received.reconcileRequired)
    }

    @Test
    fun waitingUnionOverflowKeepsABoundedBatchAndLossSignal() {
        val mailbox = mailbox()
        mailbox.offer(listOf("!first:test"), false)
        val delivered = assertNotNull(mailbox.current())
        mailbox.offer((0 until 64).map { "!room-$it:test" }, false)
        mailbox.offer(listOf("!after-capacity:test"), false)
        assertEquals(delivered, mailbox.current())
        mailbox.acknowledge(delivered.receiptId)
        val next = assertNotNull(mailbox.current())
        assertEquals(64, next.roomIds.size)
        assertTrue(next.reconcileRequired)
    }

    @Test
    fun waitingReconciliationSurvivesLaterOrdinaryKeyCallbacks() {
        val mailbox = mailbox()
        mailbox.offer(listOf("!first:test"), false)
        val delivered = assertNotNull(mailbox.current())
        mailbox.offer(emptyList(), true)
        mailbox.offer(listOf("!second:test"), false)
        mailbox.acknowledge(delivered.receiptId)
        val next = assertNotNull(mailbox.current())
        assertTrue(next.reconcileRequired)
        assertEquals(listOf("!second:test"), next.roomIds)
    }

    @Test
    fun observationWaitsForAcknowledgmentBeforePublishingTheNextBatch() = runTest {
        val mailbox = mailbox()
        val received = mutableListOf<SdkRoomKeyBatch>()
        val observer =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                mailbox.observe().toList(received)
            }
        mailbox.offer(listOf("!first:test"), false)
        assertEquals(1, received.size)
        mailbox.offer(listOf("!second:test"), false)
        mailbox.offer(listOf("!third:test"), false)
        assertEquals(1, received.size)
        mailbox.acknowledge(received.single().receiptId)
        assertEquals(2, received.size)
        assertEquals(listOf("!second:test", "!third:test"), received.last().roomIds)
        mailbox.close()
        observer.join()
    }

    @Test
    fun closingStopsObservationAndCannotAcknowledgeUndeliveredWork() = runTest {
        val mailbox = mailbox()
        mailbox.offer(listOf("!first:test"), false)
        val first = assertNotNull(mailbox.current())
        mailbox.offer(listOf("!second:test"), false)
        mailbox.close()
        mailbox.close()
        assertNull(mailbox.current())
        assertFalse(mailbox.acknowledge(first.receiptId))
        mailbox.offer(listOf("!late:test"), true)
        assertTrue(mailbox.observe().toList().isEmpty())
    }

    @Test
    fun emptyOrdinaryCallbackIsNoWorkButRegistrationReconciliationIsWork() {
        val mailbox = mailbox()
        mailbox.offer(emptyList(), false)
        assertNull(mailbox.current())
        mailbox.offer(emptyList(), true)
        val signal = assertNotNull(mailbox.current())
        assertTrue(signal.roomIds.isEmpty())
        assertTrue(signal.reconcileRequired)
        assertTrue(mailbox.acknowledge(signal.receiptId))
    }

    @Test
    fun cancelledConsumerDoesNotAcknowledgeItsSuspendedWrite() = runTest {
        val mailbox = mailbox()
        mailbox.offer(listOf("!first:test"), false)
        val writing = CompletableDeferred<SdkRoomKeyBatch>()
        val observer = launch {
            mailbox.observe().collect { batch ->
                writing.complete(batch)
                awaitCancellation()
            }
        }
        val first = writing.await()
        mailbox.offer(listOf("!second:test"), false)
        observer.cancelAndJoin()
        assertEquals(first, mailbox.current())
        assertEquals(first, mailbox.observe().first())
        assertTrue(mailbox.acknowledge(first.receiptId))
        assertEquals(listOf("!second:test"), mailbox.current()?.roomIds)
    }

    @Test
    fun diagnosticStringsDoNotExposeRoomIdsOrReceipts() {
        val mailbox = mailbox()
        mailbox.offer(listOf("!private-room:test"), true)
        val batch = assertNotNull(mailbox.current())
        assertFalse(batch.toString().contains("private-room"))
        assertFalse(batch.toString().contains(batch.receiptId))
        assertFalse(mailbox.toString().contains("private-room"))
    }

    private fun mailbox(): SdkRoomKeyMailbox {
        var next = 0
        return SdkRoomKeyMailbox { "receipt-${++next}" }
    }
}
