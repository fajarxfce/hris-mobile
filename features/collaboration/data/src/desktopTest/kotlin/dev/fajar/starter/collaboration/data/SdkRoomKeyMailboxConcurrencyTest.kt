package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.sdk.SdkRoomKeyMailbox
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class SdkRoomKeyMailboxConcurrencyTest {
    @Test
    fun callbackRacingAcknowledgmentKeepsTheNewOccurrence() {
        val sequence = AtomicLong()
        val mailbox = SdkRoomKeyMailbox { "receipt-${sequence.incrementAndGet()}" }
        repeat(100) {
            mailbox.offer(listOf("!same:test"), false)
            val writing = assertNotNull(mailbox.current())
            race(
                { mailbox.acknowledge(writing.receiptId) },
                { mailbox.offer(listOf("!same:test"), false) },
            )
            val next = assertNotNull(mailbox.current())
            assertNotEquals(writing.receiptId, next.receiptId)
            assertEquals(listOf("!same:test"), next.roomIds)
            assertFalse(mailbox.acknowledge(writing.receiptId))
            assertTrue(mailbox.acknowledge(next.receiptId))
            assertNull(mailbox.current())
        }
    }

    @Test
    fun twoAcknowledgmentsOfTheSameCaptureCannotRemoveItsSuccessor() {
        val sequence = AtomicLong()
        val mailbox = SdkRoomKeyMailbox { "receipt-${sequence.incrementAndGet()}" }
        repeat(100) {
            mailbox.offer(listOf("!first:test"), false)
            val first = assertNotNull(mailbox.current())
            mailbox.offer(listOf("!next:test"), false)
            val confirmations = AtomicLong()
            race(
                { if (mailbox.acknowledge(first.receiptId)) confirmations.incrementAndGet() },
                { if (mailbox.acknowledge(first.receiptId)) confirmations.incrementAndGet() },
            )
            assertEquals(1L, confirmations.get())
            val next = assertNotNull(mailbox.current())
            assertEquals(listOf("!next:test"), next.roomIds)
            assertTrue(mailbox.acknowledge(next.receiptId))
        }
    }

    @Test
    fun concurrentCallbackBurstsRemainBoundedWithExplicitLossCoverage() {
        val sequence = AtomicLong()
        val mailbox = SdkRoomKeyMailbox { "receipt-${sequence.incrementAndGet()}" }
        mailbox.offer(listOf("!delivered:test"), false)
        val delivered = assertNotNull(mailbox.current())
        race(
            { repeat(100) { mailbox.offer(listOf("!left-$it:test"), false) } },
            { repeat(100) { mailbox.offer(listOf("!right-$it:test"), false) } },
        )
        assertEquals(delivered, mailbox.current())
        assertTrue(mailbox.acknowledge(delivered.receiptId))
        val next = assertNotNull(mailbox.current())
        assertEquals(64, next.roomIds.size)
        assertTrue(next.reconcileRequired)
        assertTrue(mailbox.acknowledge(next.receiptId))
        assertNull(mailbox.current())
    }

    @Test
    fun callbackRacingDisposalCannotLeaveWorkInAClosedRegistration() {
        val sequence = AtomicLong()
        repeat(100) {
            val mailbox = SdkRoomKeyMailbox { "receipt-${sequence.incrementAndGet()}" }
            mailbox.offer(listOf("!initial:test"), false)
            val current = assertNotNull(mailbox.current())
            race({ mailbox.close() }, { mailbox.offer(listOf("!late:test"), true) })
            assertNull(mailbox.current())
            assertFalse(mailbox.acknowledge(current.receiptId))
        }
    }

    private fun race(first: () -> Unit, second: () -> Unit) {
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val pending =
                listOf(first, second).map { action ->
                    executor.submit {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        action()
                    }
                }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            pending.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
