package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationMessageEditPolicyTest {
    private val original =
        CollaborationHistoryMessage(
            "@author:example.test",
            "00000000-0000-4000-8000-000000000001",
            "\$original",
            1_000,
            "a".repeat(64),
            "Original body",
            listOf("\$original", "\$retry"),
            false,
            replyToEventId = "\$quoted",
            forwarded = true,
        )
    private val target =
        CollaborationHistoryEventFact(original.primaryEventId, original.sender, 1_000, false)
    private val edit =
        CollaborationMessageEdit(
            "\$edit",
            original.sender,
            original.primaryEventId,
            2_000,
            "Edited body",
            "b".repeat(64),
            "00000000-0000-4000-8000-000000000002",
            original.logicalId,
            original.replyToEventId,
            original.forwarded,
        )

    @Test
    fun serverAcceptanceHasAnExclusiveFifteenMinuteBoundary() {
        assertTrue(acceptsCollaborationMessageEdit(original, target, edit))
        assertTrue(acceptsCollaborationMessageEdit(original, target, edit.copy(timestamp = 1_000)))
        assertTrue(
            acceptsCollaborationMessageEdit(
                original,
                target,
                edit.copy(timestamp = 1_000 + CollaborationMessageEditWindowMillis - 1),
            )
        )
        assertFalse(acceptsCollaborationMessageEdit(original, target, edit.copy(timestamp = 999)))
        assertFalse(
            acceptsCollaborationMessageEdit(
                original,
                target,
                edit.copy(timestamp = 1_000 + CollaborationMessageEditWindowMillis),
            )
        )
        assertFalse(
            acceptsCollaborationMessageEdit(original, target, edit.copy(timestamp = Long.MAX_VALUE))
        )
    }

    @Test
    fun aRetriedAliasCannotExtendTheOriginalWindowOrPrecedeItsOwnAcceptance() {
        val retried = target.copy(eventId = "\$retry", timestamp = 800_000)
        val replacement = edit.copy(targetEventId = "\$retry", timestamp = 850_000)
        assertTrue(acceptsCollaborationMessageEdit(original, retried, replacement))
        assertFalse(
            acceptsCollaborationMessageEdit(
                original,
                retried,
                replacement.copy(timestamp = 901_000),
            )
        )
        assertFalse(
            acceptsCollaborationMessageEdit(
                original,
                retried,
                replacement.copy(timestamp = 799_999),
            )
        )
        val earlier = retried.copy(timestamp = 100)
        assertFalse(
            acceptsCollaborationMessageEdit(
                original,
                earlier,
                replacement.copy(timestamp = 900_100),
            )
        )
    }

    @Test
    fun authorIdentityTargetAndOriginalSemanticContextCannotBeRebound() {
        for (value in
            listOf(
                edit.copy(sender = "@other:example.test"),
                edit.copy(targetEventId = "\$elsewhere"),
                edit.copy(eventId = "\$retry"),
                edit.copy(originalLogicalId = null),
                edit.copy(replyToEventId = null),
                edit.copy(replyToEventId = "\$different"),
                edit.copy(forwarded = false),
                edit.copy(operationId = original.logicalId),
            )) assertFalse(acceptsCollaborationMessageEdit(original, target, value))
        for (value in
            listOf(
                target.copy(sender = "@other:example.test"),
                target.copy(timestamp = null),
                target.copy(timestamp = -1),
                target.copy(timestamp = 1_001),
                target.copy(redacted = true),
            )) assertFalse(acceptsCollaborationMessageEdit(original, value, edit))
        assertFalse(
            acceptsCollaborationMessageEdit(
                original.copy(redacted = true, text = null),
                target,
                edit,
            )
        )
        assertEquals("Original body", original.text)
        assertEquals("a".repeat(64), original.contentHash)
        assertEquals(listOf("\$original", "\$retry"), original.eventIds)
    }

    @Test
    fun unextendedProviderMessagesRemainCompatibleAndHiddenStateIsNotChanged() {
        val providerOriginal =
            original.copy(logicalId = null, replyToEventId = null, forwarded = false)
        val providerEdit =
            edit.copy(
                operationId = null,
                originalLogicalId = null,
                replyToEventId = null,
                forwarded = false,
            )
        assertTrue(acceptsCollaborationMessageEdit(providerOriginal, target, providerEdit))
        val hidden = original.copy(locallyHidden = true)
        assertTrue(acceptsCollaborationMessageEdit(hidden, target, edit))
        assertTrue(hidden.locallyHidden)
        assertFalse(edit.toString().contains("Edited body"))
    }

    @Test
    fun deterministicReplacementOrderingRejectsConflictingEventIdentity() {
        assertTrue(supersedesCollaborationMessageEdit(null, edit))
        assertFalse(supersedesCollaborationMessageEdit(edit, edit))
        val newer = edit.copy(eventId = "\$newer", timestamp = 2_001)
        assertTrue(supersedesCollaborationMessageEdit(edit, newer))
        assertFalse(supersedesCollaborationMessageEdit(newer, edit))
        val tied = edit.copy(eventId = "\$z")
        assertTrue(supersedesCollaborationMessageEdit(edit, tied))
        assertFalse(supersedesCollaborationMessageEdit(tied, edit))
        assertFailsWith<IllegalArgumentException> {
            supersedesCollaborationMessageEdit(edit, edit.copy(text = "Conflicting event"))
        }
        assertFailsWith<IllegalArgumentException> {
            supersedesCollaborationMessageEdit(edit, edit.copy(timestamp = 2_001))
        }
    }

    @Test
    fun anEarlierTargetCanHaveAValidEditBeforeALaterRetryWasObservedAsPrimary() {
        val earlier = target.copy(eventId = "\$retry", timestamp = 100)
        val replacement = edit.copy(targetEventId = earlier.eventId, timestamp = 500)
        assertTrue(acceptsCollaborationMessageEdit(original, earlier, replacement))
        assertFalse(
            acceptsCollaborationMessageEdit(original, earlier, replacement.copy(timestamp = 99))
        )
    }

    @Test
    fun theEarliestKnownAliasConstrainsEvenEditsTargetingTheLaterPrimary() {
        val indexed = original.copy(firstAcceptedAt = 100)
        assertTrue(acceptsCollaborationMessageEdit(indexed, target, edit.copy(timestamp = 900_099)))
        assertFalse(
            acceptsCollaborationMessageEdit(indexed, target, edit.copy(timestamp = 900_100))
        )
        assertFalse(validCollaborationHistoryMessage(original.copy(firstAcceptedAt = -1)))
        assertFalse(validCollaborationHistoryMessage(original.copy(firstAcceptedAt = 1_001)))
    }
}
