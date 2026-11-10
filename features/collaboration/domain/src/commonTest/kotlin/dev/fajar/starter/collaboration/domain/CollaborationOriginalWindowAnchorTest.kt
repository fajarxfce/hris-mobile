package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationOriginalWindow
import kotlin.test.*

class CollaborationOriginalWindowAnchorTest {
    private val owner = CollaborationOwner("session", "native", "account", "company", "parent")
    private val original =
        CollaborationHistoryChange.Text(
            "\$original",
            "@sender:test",
            null,
            1,
            "Original",
            "a".repeat(64),
        )
    private val edit =
        CollaborationMessageEdit(
            "\$edit",
            original.sender,
            original.eventId,
            2,
            "Edited",
            "b".repeat(64),
        )
    private val anchor =
        CollaborationEventSnapshot(
            owner,
            "device",
            "!room:test",
            true,
            true,
            CollaborationAcquiredEvent(
                edit.eventId,
                edit.sender,
                2,
                "m.room.message",
                CollaborationAcquiredEventContent.Replacement(edit),
            ),
        )
    private val window =
        CollaborationOriginalWindow(
            owner,
            "device",
            "!room:test",
            edit.eventId,
            listOf(original),
            emptyList(),
            listOf(edit),
            anchor,
        )

    private fun valid(value: CollaborationOriginalWindow) =
        validCollaborationOriginalWindow(value, owner, "device", "!room:test", value.anchorEventId)

    @Test
    fun foldedEditNeedsExactAcquisitionAndNeverBecomesAnOriginal() {
        assertFalse(valid(window.copy(acquiredAnchor = null)))
        assertTrue(valid(window))
        assertEquals(listOf(original.eventId), window.changes.map { it.eventId })
        assertTrue(window.unresolvedEventIds.isEmpty())
        assertEquals(listOf(edit), window.edits)
    }

    @Test
    fun ordinaryOriginalWindowDoesNotRequireAnotherEventRead() {
        assertTrue(valid(window.copy(anchorEventId = original.eventId, acquiredAnchor = null)))
        assertFalse(valid(window.copy(anchorEventId = "\$missing", acquiredAnchor = null)))
    }

    @Test
    fun acquiredAnchorMustKeepTheSamePhysicalOwnerRoomAndMembership() {
        for (invalid in
            listOf(
                anchor.copy(owner = owner.copy(sessionId = "replacement")),
                anchor.copy(owner = owner.copy(companyId = "another")),
                anchor.copy(owner = owner.copy(accountId = "another")),
                anchor.copy(owner = owner.copy(nativeSessionId = "another")),
                anchor.copy(deviceId = "another"),
                anchor.copy(roomId = "!another:test"),
                anchor.copy(roomJoined = false),
                anchor.copy(roomEncrypted = false),
            )) assertFalse(valid(window.copy(acquiredAnchor = invalid)))
    }

    @Test
    fun malformedAcquiredIdentityCannotBypassMissingPresentationEvidence() {
        val event = anchor.event
        for (invalid in
            listOf(
                event.copy(eventId = "\$another"),
                event.copy(eventId = "bad"),
                event.copy(sender = "bad"),
                event.copy(timestamp = -1),
                event.copy(timestamp = 9_007_199_254_740_992L),
                event.copy(type = ""),
                event.copy(type = "bad type"),
                event.copy(type = "x".repeat(129)),
            )) assertFalse(valid(window.copy(acquiredAnchor = anchor.copy(event = invalid))))
        // Even a displayed anchor cannot conceal contradictory acquired provenance.
        assertFalse(valid(window.copy(anchorEventId = original.eventId)))
    }

    @Test
    fun unresolvedAndControlAnchorsGrantNoAdditionalOriginalOrExhaustionEvidence() {
        for ((type, content) in
            listOf(
                "m.room.encrypted" to
                    CollaborationAcquiredEventContent.Unresolved(
                        CollaborationEventDecryptionFailure.UnableToDecrypt
                    ),
                "m.room.redaction" to CollaborationAcquiredEventContent.Redaction(original.eventId),
                "org.example.control" to CollaborationAcquiredEventContent.Unsupported,
            )) {
            val value =
                window.copy(
                    acquiredAnchor =
                        anchor.copy(event = anchor.event.copy(type = type, content = content))
                )
            assertTrue(valid(value))
            assertEquals(listOf(original), value.changes)
            assertTrue(value.unresolvedEventIds.isEmpty())
        }
    }

    @Test
    fun acquiredAnchorDoesNotRelaxOriginalBoundsOrReplacementIdentity() {
        assertFalse(valid(window.copy(changes = listOf(original, original))))
        assertFalse(
            valid(window.copy(changes = List(129) { original.copy(eventId = "\$event-$it") }))
        )
        assertFalse(valid(window.copy(edits = listOf(edit.copy(sender = "@other:test")))))
        assertFalse(valid(window.copy(changes = emptyList(), edits = emptyList())))
    }
}
