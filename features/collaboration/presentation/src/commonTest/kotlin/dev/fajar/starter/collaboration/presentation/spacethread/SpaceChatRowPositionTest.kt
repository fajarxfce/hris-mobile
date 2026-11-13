package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import kotlin.test.*

class SpaceChatRowPositionTest {
    @Test
    fun aPendingGestureIsNewestEvenWhenTheDeviceClockIsBehindHistory() {
        val f = SpaceChatRowsFixture()
        val history =
            f.history(f.sent(20))
                .copy(sender = "@peer:example.test", timestamp = f.history().timestamp + 120_000)
        val previous = f.rows(f.thread(listOf(history)))
        val rows = f.rows(f.thread(listOf(history)), f.composer(1), previous)
        assertEquals("Original 1", rows.first().bubble.text)
        assertEquals(f.original().submittedAt.toEpochMilliseconds(), rows.first().bubble.timestamp)
        assertTrue(rows.first().position > history.timestamp)
        assertEquals(previous.single(), rows.last())
    }

    @Test
    fun everyConfirmedPrefixKeepsFourGesturesInTheirOriginalOrder() {
        val f = SpaceChatRowsFixture()
        var rows = f.rows(composer = f.composer(1, 2, 3, 4))
        val initial = rows.map { it.identity to it.position }
        for (confirmed in 1..4) {
            val commands = (1..4).map { if (it <= confirmed) f.sent(it) else f.record(it) }
            val history =
                (1..confirmed).map {
                    f.history(f.sent(it))
                        .copy(timestamp = f.history().timestamp + 60_000 + (5 - it) * 1000)
                }
            rows = f.rows(f.thread(history, commands), previous = rows)
            assertEquals(
                listOf("Original 4", "Original 3", "Original 2", "Original 1"),
                rows.map { it.bubble.text },
            )
            assertEquals(initial, rows.map { it.identity to it.position })
            assertEquals(
                confirmed,
                rows.count { it.bubble.delivery == CollaborationMessageDelivery.SENT },
            )
        }
    }

    @Test
    fun outOfOrderAcknowledgementsDoNotMoveTheStillPendingRows() {
        val f = SpaceChatRowsFixture()
        val before = f.rows(composer = f.composer(1, 2, 3, 4))
        val after =
            f.rows(
                f.thread(commands = listOf(f.sent(4), f.record(3), f.sent(2), f.record(1))),
                previous = before,
            )
        assertEquals(
            before.map { it.identity to it.position },
            after.map { it.identity to it.position },
        )
        assertEquals(
            listOf(
                CollaborationMessageDelivery.SENT,
                CollaborationMessageDelivery.PENDING,
                CollaborationMessageDelivery.SENT,
                CollaborationMessageDelivery.PENDING,
            ),
            after.map { it.bubble.delivery },
        )
    }

    @Test
    fun archivedOriginalsKeepTheSameKeysThroughIndependentHistoryPublications() {
        val f = SpaceChatRowsFixture()
        val before = f.rows(composer = f.composer(1))
        val represented =
            SpaceChatComposerPresentation(
                represented = listOf(SpaceChatPendingSubmission(f.original(), true, null, f.sent()))
            )
        val after = f.rows(f.thread(listOf(f.history())), represented, before)
        assertEquals(before.single().identity, after.single().identity)
        assertEquals(before.single().position, after.single().position)
        assertEquals(CollaborationMessageDelivery.SENT, after.single().bubble.delivery)
        assertEquals(after, f.rows(f.thread(listOf(f.history())), represented, after))
    }

    @Test
    fun identicalSnapshotsProduceEqualRowsAndDoNotAdvancePlacement() {
        val f = SpaceChatRowsFixture()
        val first = f.rows(f.thread(listOf(f.history(f.sent(10)))), f.composer(1, 2))
        var latest = first
        repeat(20) {
            latest = f.rows(f.thread(listOf(f.history(f.sent(10)))), f.composer(1, 2), latest)
            assertEquals(first, latest)
        }
    }

    @Test
    fun keysRetainCanonicalOwnershipAndDoNotCollideThroughSeparatorCharacters() {
        val f = SpaceChatRowsFixture()
        fun key(room: String, sender: String) =
            SpaceChatMessageIdentity(
                f.access.messageOwner(),
                f.conversationId,
                SpaceChatMessageSource.History(room, sender, "${'$'}event"),
            )
        val first = key("!a:b", "@c:d")
        val second = key("!a", "b:@c:d")
        assertNotEquals(first.stableKey, second.stableKey)
        assertNotEquals(
            first.stableKey,
            first.copy(owner = first.owner.copy(accountId = chatId(987))).stableKey,
        )
        assertNotEquals(first.stableKey, first.copy(conversationId = chatId(988)).stableKey)
        assertFalse(first.toString().contains("example.test"))
        assertEquals(first.stableKey, first.copy().stableKey)
    }
}
