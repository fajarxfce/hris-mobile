package dev.fajar.starter.notifications.data

import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.notifications.data.mappers.*
import kotlin.test.*

class ActionNotificationShapeTest {
    private val reference = ActionNotificationReference("card", "render")
    private val payload =
        ActionNotificationPayload(
            reference,
            "Sender",
            "Message",
            ActionNotificationKind.entries.associateWith { "opaque-${it.key}" },
            true,
        )

    @Test
    fun opaqueTransportAcceptsAllActionsWithoutPretendingToAuthenticateTheirEnvelopes() {
        assertTrue(validActionNotificationPayload(payload))
        assertTrue(validActionNotificationPayload(payload.copy(title = "", body = "")))
        for (kind in ActionNotificationKind.entries) assertFalse(
            validActionNotificationPayload(payload.copy(envelopes = payload.envelopes - kind))
        )
    }

    @Test
    fun referenceCannotInjectUriSegmentsNamespacesOrUnboundedIdentifiers() {
        for (value in
            listOf("", "a/b", "a%2Fb", "a:b", "..", "a?b", "a#b", "a\nb", "a".repeat(129))) {
            assertFalse(validActionNotificationReference(reference.copy(id = value)))
            assertFalse(validActionNotificationReference(reference.copy(renderId = value)))
        }
        assertTrue(
            validActionNotificationReference(ActionNotificationReference("a".repeat(128), "A_0-9"))
        )
    }

    @Test
    fun envelopesHaveFiniteIpcSizeAndCannotContainWhitespaceOrControlBytes() {
        assertTrue(validActionNotificationEnvelope("x".repeat(4096)))
        for (value in listOf("", "x".repeat(4097), " x", "x\n", "x\u007f", "é")) assertFalse(
            validActionNotificationEnvelope(value)
        )
    }

    @Test
    fun boundedPreviewsPreserveUnicodePairsAndRejectControlOrTruncatedInput() {
        val supplementary = "\uD83D\uDE00"
        assertTrue(
            validActionNotificationPayload(
                payload.copy(title = supplementary.repeat(80), body = supplementary.repeat(200))
            )
        )
        for (text in listOf("\uD83D", "\uDE00", "a\n", "a\u0085", "a\u007f")) {
            assertFalse(validActionNotificationPayload(payload.copy(title = text)))
            assertFalse(validActionNotificationPayload(payload.copy(body = text)))
        }
        assertFalse(validActionNotificationPayload(payload.copy(title = "x".repeat(161))))
        assertFalse(validActionNotificationPayload(payload.copy(body = "x".repeat(401))))
    }

    @Test
    fun diagnosticStringsNeverExposePreviewActionOrReplyText() {
        val input =
            ActionNotificationInput(
                reference,
                ActionNotificationKind.Reply,
                "token-secret",
                "reply-secret",
            )
        assertFalse(input.toString().contains("secret"))
        assertFalse(payload.toString().contains("Message"))
        assertFalse(reference.toString().contains("card"))
    }
}
