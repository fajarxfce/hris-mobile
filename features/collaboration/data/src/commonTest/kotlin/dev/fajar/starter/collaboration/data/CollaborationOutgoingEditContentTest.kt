package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationText
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CollaborationOutgoingEditContentTest {
    private val operation = historyId(90)
    private val target =
        CollaborationEditTarget(
            "!room:example.test",
            "\$original",
            historyId(91),
            "@self:example.test",
            "a".repeat(64),
            1_000,
            900,
            "\$quoted",
            true,
        )

    @Test
    fun outgoingReplacementAndReceiverAgreeOnCompleteContentAndOriginalContext() = runTest {
        val text = " Revised\ntext 🙂 "
        val content = outgoing(text)
        val origin =
            collaborationOutgoingOrigin(
                target.sender,
                operation,
                text,
                target.replyToEventId,
                target.forwarded,
                target,
            )
        val raw = event(content)
        val edit = assertNotNull(raw.toMessageEdit())
        assertEquals(origin.contentHash, edit.contentHash)
        assertEquals(text, edit.text)
        assertEquals(operation, edit.operationId)
        assertEquals(target.originalLogicalId, edit.originalLogicalId)
        assertEquals(target.eventId, edit.targetEventId)
        assertEquals(target.replyToEventId, edit.replyToEventId)
        assertTrue(edit.forwarded)
        assertNull(raw.toOriginalHistory())
        assertFalse("org.hris.message_id" in content)
        assertEquals(JsonObject(emptyMap()), content["m.mentions"])
        assertEquals(origin, collaborationEditOrigin(target.sender, operation, text, target))
    }

    @Test
    fun previewNeverSplitsSurrogatesOrTruncatesTheActualReplacement() = runTest {
        val text = "a".repeat(255) + "🙂" + "b".repeat(300)
        val content = outgoing(text)
        val body = content.getValue("body").jsonPrimitive.content
        assertEquals("* " + "a".repeat(255) + "…", body)
        body.encodeToByteArray(throwOnInvalidSequence = true)
        assertEquals(
            text,
            content.getValue("m.new_content").jsonObject.getValue("body").jsonPrimitive.content,
        )
        assertEquals(text, assertNotNull(event(content).toMessageEdit()).text)
    }

    @Test
    fun maximumEscapedUnicodeTextRetainsHeadroomForTheEncryptedEnvelope() = runTest {
        val text = "界".repeat(8192) + "\"".repeat(8192)
        assertTrue(validCollaborationText(text))
        val longIds =
            target.copy(eventId = "\$" + "\"".repeat(254), replyToEventId = "\$" + "\\".repeat(254))
        val content =
            collaborationOutgoingContent(
                operation,
                text,
                longIds.replyToEventId,
                longIds.forwarded,
                longIds,
            )
        val bytes = content.toString().encodeToByteArray().size
        assertTrue(bytes <= 43_200, "Serialized content exceeds the reviewed wire budget")
        // This bounds serialization only; native Megolm encryption is checked by device proof.
        assertEquals(text, assertNotNull(event(content).toMessageEdit()).text)
    }

    @Test
    fun retargetingAndChangedReplyContextCannotReuseThePreparedFingerprint() = runTest {
        val content = outgoing("Changed")
        val changed =
            collaborationEditContent(operation, "Changed", target.copy(eventId = "\$another"))
        assertNotEquals(
            collaborationHistoryContentHash(content),
            collaborationHistoryContentHash(changed),
        )
        assertFailsWith<IllegalArgumentException> {
            collaborationOutgoingContent(operation, "Changed", null, true, target)
        }
        assertFailsWith<IllegalArgumentException> {
            collaborationOutgoingContent(operation, "Changed", target.replyToEventId, false, target)
        }
        assertFailsWith<IllegalArgumentException> {
            collaborationEditContent(requireNotNull(target.originalLogicalId), "Changed", target)
        }
        assertFailsWith<IllegalArgumentException> {
            collaborationOutgoingOrigin(
                "@other:test",
                operation,
                "Changed",
                target.replyToEventId,
                true,
                target,
            )
        }
    }

    private fun outgoing(text: String) =
        collaborationOutgoingContent(
            operation,
            text,
            target.replyToEventId,
            target.forwarded,
            target,
        )

    private fun event(content: JsonObject) =
        CollaborationSdkHistoryEventDto(
            "\$edit",
            target.sender,
            2_000,
            SdkMessageKind.TEXT,
            buildJsonObject {
                    put("event_id", "\$edit")
                    put("sender", target.sender)
                    put("origin_server_ts", 2_000)
                    put("type", "m.room.message")
                    put("content", content)
                }
                .toString(),
        )
}
