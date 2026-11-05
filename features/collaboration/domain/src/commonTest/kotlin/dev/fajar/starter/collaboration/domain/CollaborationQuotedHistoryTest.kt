package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationQuotedHistoryTest {
    @Test
    fun originalFingerprintAllowsMetadataUpgradeButNeverRetargetsAnExistingReply() {
        val event =
            CollaborationHistoryChange.Text(
                "\$reply",
                "@peer:example.test",
                "00000000-0000-4000-8000-000000000001",
                1,
                "Reply",
                "a".repeat(64),
                "\$target",
            )
        val original = assertNotNull(mergeCollaborationHistoryText(null, event))
        assertEquals(event.replyToEventId, original.replyToEventId)
        val legacy = original.copy(replyToEventId = null)
        assertEquals(original, mergeCollaborationHistoryText(legacy, event))
        assertNull(mergeCollaborationHistoryText(original, event.copy(replyToEventId = "\$other")))
        assertNull(mergeCollaborationHistoryText(original, event.copy(replyToEventId = null)))
        assertNull(mergeCollaborationHistoryText(legacy, event.copy(contentHash = "b".repeat(64))))
        val redacted = assertNotNull(mergeCollaborationHistoryText(original, event, true))
        assertTrue(redacted.redacted)
        assertNull(redacted.text)
        assertEquals(event.replyToEventId, redacted.replyToEventId)
    }
}
