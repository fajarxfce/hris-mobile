package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationRetainedHistoryReferencePolicyTest {
    private val event =
        CollaborationProviderHistoryEvent(
            "\$original",
            "@person:example.test",
            123,
            "m.room.encrypted",
        )
    private val original =
        CollaborationHistoryEventFact(event.eventId, event.sender, event.timestamp, false)
    private val replacement =
        CollaborationReplacementEventFact(event.eventId, event.sender, event.timestamp, false)

    @Test
    fun exactOriginalIdentityMatchesAcrossTransportAndDecryptedTypes() {
        assertTrue(matchesCollaborationRetainedOriginal(event, original))
        assertTrue(
            matchesCollaborationRetainedOriginal(event.copy(type = "m.room.message"), original)
        )
    }

    @Test
    fun originalWithKnownTimestampRequiresExactSenderEventAndTime() {
        for (fact in
            listOf(
                original.copy(eventId = "\$other"),
                original.copy(sender = "@other:example.test"),
                original.copy(timestamp = 124),
            )) {
            assertFalse(matchesCollaborationRetainedOriginal(event, fact))
        }
    }

    @Test
    fun confirmedOriginalTombstoneMayPrecedeItsOriginalTimestamp() {
        assertTrue(
            matchesCollaborationRetainedOriginal(
                event,
                original.copy(timestamp = null, redacted = true),
            )
        )
        assertFalse(matchesCollaborationRetainedOriginal(event, original.copy(timestamp = null)))
    }

    @Test
    fun aTimestampedTombstoneCannotHideConflictingIdentity() {
        assertTrue(matchesCollaborationRetainedOriginal(event, original.copy(redacted = true)))
        assertFalse(
            matchesCollaborationRetainedOriginal(
                event,
                original.copy(timestamp = 124, redacted = true),
            )
        )
        assertFalse(
            matchesCollaborationRetainedOriginal(
                event,
                original.copy(sender = "@other:example.test", timestamp = null, redacted = true),
            )
        )
    }

    @Test
    fun exactReplacementIdentitySurvivesRedaction() {
        assertTrue(matchesCollaborationRetainedReplacement(event, replacement))
        assertTrue(
            matchesCollaborationRetainedReplacement(event, replacement.copy(redacted = true))
        )
    }

    @Test
    fun replacementRequiresExactSenderEventAndTimestamp() {
        for (fact in
            listOf(
                replacement.copy(eventId = "\$other"),
                replacement.copy(sender = "@other:example.test"),
                replacement.copy(timestamp = 124),
            )) {
            assertFalse(matchesCollaborationRetainedReplacement(event, fact))
            assertFalse(matchesCollaborationRetainedReplacement(event, fact.copy(redacted = true)))
        }
    }

    @Test
    fun malformedProviderReferenceCannotBeConfirmedByMatchingStorage() {
        for (source in
            listOf(
                event.copy(eventId = "bad"),
                event.copy(sender = "bad"),
                event.copy(timestamp = -1),
                event.copy(type = ""),
            )) {
            assertFalse(
                matchesCollaborationRetainedOriginal(
                    source,
                    original.copy(
                        eventId = source.eventId,
                        sender = source.sender,
                        timestamp = source.timestamp,
                    ),
                )
            )
            assertFalse(
                matchesCollaborationRetainedReplacement(
                    source,
                    replacement.copy(
                        eventId = source.eventId,
                        sender = source.sender,
                        timestamp = source.timestamp,
                    ),
                )
            )
        }
    }
}
