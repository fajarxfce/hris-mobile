package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.sdk.SdkQueueDelivery
import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.test.*

class SdkQueueDeliveryTest {
    @Test
    fun unknownInitialTransactionNeverAdmitsDelivery() {
        val queue = SdkQueueDelivery(setOf("owned"))
        queue.local("unknown")
        assertFalse(queue.ready())
        assertEquals(
            CollaborationQueueDeliveryReason.UNRESOLVED,
            queue.result(CollaborationQueueDeliveryReason.DRAINED).reason,
        )
    }

    @Test
    fun missingNativeRowsDoNotInventReceipts() {
        val queue = SdkQueueDelivery(setOf("previously-mapped"))
        assertFalse(queue.ready())
        assertEquals(emptyList(), queue.result(CollaborationQueueDeliveryReason.DRAINED).receipts)
    }

    @Test
    fun repeatedReceiptsAreIdempotentAndProviderAliasesAreRetained() {
        val queue = SdkQueueDelivery(setOf("original"))
        queue.local("original")
        assertTrue(queue.ready())
        queue.sent("original", "\$first")
        queue.sent("original", "\$first")
        queue.sent("original", "\$alias")
        assertTrue(queue.ended)
        assertEquals(
            listOf(
                CollaborationQueueReceipt("original", "\$first"),
                CollaborationQueueReceipt("original", "\$alias"),
            ),
            queue.result(CollaborationQueueDeliveryReason.DRAINED).receipts,
        )
    }

    @Test
    fun oneProviderEventCannotAcknowledgeDifferentTransactions() {
        val queue = SdkQueueDelivery(setOf("first", "second"))
        queue.local("first")
        queue.local("second")
        queue.ready()
        queue.sent("first", "\$event")
        queue.sent("second", "\$event")
        val result = queue.result(CollaborationQueueDeliveryReason.DRAINED)
        assertEquals(CollaborationQueueDeliveryReason.UNRESOLVED, result.reason)
        assertEquals(setOf("second"), result.pending)
        assertEquals(1, result.receipts.size)
    }

    @Test
    fun stopAndFailureKeepEarlierReceiptsWithoutInventingPendingAcknowledgements() {
        val queue = SdkQueueDelivery(setOf("first", "second"))
        queue.local("first")
        queue.local("second")
        queue.ready()
        queue.sent("first", "\$event")
        queue.failed("second")
        queue.retry("second")
        val result = queue.result(CollaborationQueueDeliveryReason.STOPPED)
        assertEquals(CollaborationQueueDeliveryReason.FAILED, result.reason)
        assertEquals(setOf("second"), result.pending)
        assertEquals(listOf(CollaborationQueueReceipt("first", "\$event")), result.receipts)
    }

    @Test
    fun lateEnqueueAndMalformedReceiptsCannotCompleteTheQueue() {
        val queue = SdkQueueDelivery(setOf("first", "second"))
        queue.local("first")
        queue.ready()
        queue.local("second")
        queue.sent("first", "\$bad\n")
        assertEquals(
            CollaborationQueueDeliveryReason.UNRESOLVED,
            queue.result(CollaborationQueueDeliveryReason.DRAINED).reason,
        )
        assertTrue(queue.result(CollaborationQueueDeliveryReason.DRAINED).receipts.isEmpty())
    }

    @Test
    fun aReceiptArrivingDuringInitialCaptureIsRetained() {
        val queue = SdkQueueDelivery(setOf("original"))
        queue.local("original")
        queue.sent("original", "\$event")
        assertFalse(queue.ready())
        assertEquals(
            listOf(CollaborationQueueReceipt("original", "\$event")),
            queue.result(CollaborationQueueDeliveryReason.DRAINED).receipts,
        )
    }

    @Test
    fun inputsAndResultsAreDetachedAndEveryCollectionIsBounded() {
        val mapped = mutableSetOf("original")
        val queue = SdkQueueDelivery(mapped)
        mapped.clear()
        queue.local("original")
        assertTrue(queue.ready())
        val before = queue.result(CollaborationQueueDeliveryReason.TIMED_OUT)
        repeat(128) { queue.sent("original", "\$event-$it") }
        queue.sent("original", "\$overflow")
        assertEquals(setOf("original"), before.pending)
        assertTrue(before.receipts.isEmpty())
        assertEquals(128, queue.result(CollaborationQueueDeliveryReason.DRAINED).receipts.size)
        assertEquals(
            CollaborationQueueDeliveryReason.UNRESOLVED,
            queue.result(CollaborationQueueDeliveryReason.DRAINED).reason,
        )
        assertFailsWith<IllegalArgumentException> {
            SdkQueueDelivery((0..128).map { "transaction-$it" }.toSet())
        }
    }
}
