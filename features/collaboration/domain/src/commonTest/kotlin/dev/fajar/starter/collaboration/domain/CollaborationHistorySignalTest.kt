package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationTimelineRequiresHistory
import kotlin.test.*

class CollaborationHistorySignalTest {
    private val remote =
        CollaborationTimelineItem(
            "sdk-row",
            "$" + "original",
            null,
            null,
            "@fixture:example.invalid",
            1_000,
            false,
            CollaborationMessageKind.TEXT,
            "Fixture text",
            false,
            CollaborationMessageDelivery.SENT,
        )

    @Test
    fun localSendStatesNeverInvalidateRemoteOriginalsEvenAfterAcknowledgement() {
        for (eventId in listOf(null, remote.eventId)) {
            var previous = emptyList<CollaborationTimelineItem>()
            for (status in CollaborationMessageDelivery.entries) {
                val current =
                    listOf(
                        remote.copy(
                            own = true,
                            localEcho = true,
                            eventId = eventId,
                            delivery = status,
                        )
                    )
                assertFalse(collaborationTimelineRequiresHistory(previous, current))
                previous = current
            }
        }
    }

    @Test
    fun firstRemoteEchoAndAnotherRemoteMessageRequestOriginals() {
        assertTrue(collaborationTimelineRequiresHistory(emptyList(), listOf(remote)))
        assertTrue(
            collaborationTimelineRequiresHistory(
                listOf(remote.copy(localEcho = true)),
                listOf(remote),
            )
        )
        assertTrue(
            collaborationTimelineRequiresHistory(
                listOf(remote),
                listOf(remote, remote.copy(eventId = "$" + "next")),
            )
        )
    }

    @Test
    fun rowIdentityTransactionMetadataDeliveryAndReorderingDoNotChangeOriginals() {
        val next = remote.copy(eventId = "$" + "next")
        assertFalse(
            collaborationTimelineRequiresHistory(
                listOf(remote, next),
                listOf(
                    next,
                    remote.copy(
                        key = "replacement-row",
                        transactionId = "late-local-transaction",
                        delivery = CollaborationMessageDelivery.PENDING,
                    ),
                ),
            )
        )
    }

    @Test
    fun editsAndRedactionsInvalidateTheSameRemoteEvent() {
        for (changed in
            listOf(
                remote.copy(edited = true, body = "Edited fixture"),
                remote.copy(edited = true),
                remote.copy(kind = CollaborationMessageKind.REDACTED, body = null),
            )) {
            assertTrue(collaborationTimelineRequiresHistory(listOf(remote), listOf(changed)))
            assertFalse(collaborationTimelineRequiresHistory(listOf(changed), listOf(changed)))
        }
    }

    @Test
    fun decryptionRecoveryInvalidatesUnchangedEventIdentity() {
        val encrypted = remote.copy(kind = CollaborationMessageKind.UNABLE_TO_DECRYPT, body = null)
        assertTrue(collaborationTimelineRequiresHistory(emptyList(), listOf(encrypted)))
        assertFalse(collaborationTimelineRequiresHistory(listOf(encrypted), listOf(encrypted)))
        assertTrue(collaborationTimelineRequiresHistory(listOf(encrypted), listOf(remote)))
    }

    @Test
    fun windowRemovalAloneDoesNotRepresentRemoteContentDeletion() {
        val next = remote.copy(eventId = "$" + "next")
        assertFalse(collaborationTimelineRequiresHistory(listOf(remote, next), listOf(next)))
        assertFalse(collaborationTimelineRequiresHistory(listOf(remote), emptyList()))
    }

    @Test
    fun authenticatedContentCoordinatesStillInvalidateOriginals() {
        for (changed in
            listOf(
                remote.copy(sender = "@other:example.invalid"),
                remote.copy(timestamp = 2_000),
                remote.copy(logicalId = "10000000-0000-4000-8000-000000000001"),
                remote.copy(replyToEventId = "$" + "quoted"),
                remote.copy(forwarded = true),
                remote.copy(own = true),
            )) {
            assertTrue(collaborationTimelineRequiresHistory(listOf(remote), listOf(changed)))
        }
    }

    @Test
    fun unsupportedRemoteContentStillRequestsItsOriginal() {
        assertTrue(
            collaborationTimelineRequiresHistory(
                emptyList(),
                listOf(remote.copy(kind = CollaborationMessageKind.UNSUPPORTED, body = null)),
            )
        )
    }

    @Test
    fun absentOrInvalidRemoteEventIdentityCannotRequestHistory() {
        for (id in listOf(null, "", "not-an-event", "$" + "invalid space")) {
            assertFalse(
                collaborationTimelineRequiresHistory(emptyList(), listOf(remote.copy(eventId = id)))
            )
        }
    }
}
