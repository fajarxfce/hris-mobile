package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.collaborationSdkMessageMetadata
import dev.fajar.starter.collaboration.data.mappers.toOriginalHistory
import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryChange
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CollaborationTextContentTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val sender = "@fictional:example.test"

    @Test
    fun forwardedContentIsBoundToTheOriginWithoutOriginalAuthorOrRoomMetadata() = runTest {
        val content = collaborationTextContent(id, "Forwarded text", forwarded = true)
        assertEquals(
            setOf("body", "msgtype", "m.mentions", "org.hris.message_id", "org.hris.forwarded"),
            content.keys,
        )
        assertEquals(JsonPrimitive(true), content["org.hris.forwarded"])
        val origin = collaborationTextOrigin(sender, id, "Forwarded text", forwarded = true)
        val original = decode(content)
        assertTrue(original.forwarded)
        assertEquals(origin.contentHash, original.contentHash)
        val legacy = collaborationTextContent(id, "Forwarded text")
        assertFalse("org.hris.forwarded" in legacy)
        assertFalse(decode(legacy).forwarded)
        assertNotEquals(origin.contentHash, decode(legacy).contentHash)
        val metadata =
            collaborationSdkMessageMetadata(buildJsonObject { put("content", content) }.toString())
        assertEquals(id, metadata?.logicalId)
        assertEquals(true, metadata?.forwarded)
    }

    @Test
    fun onlyAnActualBooleanTrueMarksIncomingContentAsForwarded() = runTest {
        val original = collaborationTextContent(id, "Text")
        for (flag in
            listOf(
                JsonPrimitive(false),
                JsonPrimitive("true"),
                JsonPrimitive(1),
                JsonNull,
                buildJsonObject {},
            )) {
            val content = JsonObject(original + ("org.hris.forwarded" to flag))
            assertFalse(decode(content).forwarded)
            assertEquals(
                false,
                collaborationSdkMessageMetadata(
                        buildJsonObject { put("content", content) }.toString()
                    )
                    ?.forwarded,
            )
        }
        assertNull(collaborationSdkMessageMetadata("[".repeat(40) + "0" + "]".repeat(40)))
        assertNull(collaborationSdkMessageMetadata("{"))
    }

    @Test
    fun preparedPlaintextAndDecodedOriginalHaveTheSameCompleteFingerprint() = runTest {
        val text = "  **literal** <tag> \"quoted\"\n🙂\t "
        val content = collaborationTextContent(id, text)
        assertEquals(setOf("body", "msgtype", "m.mentions", "org.hris.message_id"), content.keys)
        assertEquals(buildJsonObject {}, content["m.mentions"])
        val expected = collaborationTextOrigin(sender, id, text)
        val original = decode(content)
        assertEquals(text, original.text)
        assertEquals(expected.sender, original.sender)
        assertEquals(expected.contentHash, original.contentHash)
        assertFalse(expected.toString().contains("fictional"))
    }

    @Test
    fun quoteReferenceIsExactAndParticipatesInTheOriginalFingerprint() = runTest {
        val target = "\$quoted-original"
        val content = collaborationTextContent(id, "Reply", target)
        assertEquals(
            setOf("body", "msgtype", "m.mentions", "org.hris.message_id", "m.relates_to"),
            content.keys,
        )
        assertEquals(
            buildJsonObject { put("m.in_reply_to", buildJsonObject { put("event_id", target) }) },
            content["m.relates_to"],
        )
        val expected = collaborationTextOrigin(sender, id, "Reply", target)
        assertEquals(expected.contentHash, decode(content).contentHash)
        assertEquals(target, decode(content).replyToEventId)
        assertEquals("Reply", decode(content).text)
        assertNotEquals(
            expected.contentHash,
            collaborationTextOrigin(sender, id, "Reply").contentHash,
        )
        assertNotEquals(
            expected.contentHash,
            collaborationTextOrigin(sender, id, "Reply", "\$other").contentHash,
        )
        for (invalid in
            listOf("", "invalid", "\$", "\$bad id", "\$é", "\$\uD800", "\$" + "x".repeat(255))) {
            assertFailsWith<IllegalArgumentException> {
                collaborationTextOrigin(sender, id, "Reply", invalid)
            }
        }
    }

    @Test
    fun logicalIdWhitespaceMentionsFormattingAndUnknownFieldsChangeTheFingerprint() = runTest {
        val expected = collaborationTextOrigin(sender, id, " text ")
        assertNotEquals(
            expected.contentHash,
            collaborationTextOrigin(sender, id, "text").contentHash,
        )
        assertNotEquals(
            expected.contentHash,
            collaborationTextOrigin(sender, id.dropLast(1) + "2", " text ").contentHash,
        )
        val content = collaborationTextContent(id, " text ")
        for ((key, value) in
            listOf(
                "formatted_body" to JsonPrimitive("<b>text</b>"),
                "custom" to JsonPrimitive(true),
                "m.mentions" to buildJsonObject { put("room", true) },
            )) {
            assertNotEquals(
                expected.contentHash,
                decode(JsonObject(content + (key to value))).contentHash,
            )
        }
    }

    @Test
    fun invalidOutgoingContentCannotProduceAnIdentity() = runTest {
        for (text in listOf("", " ", "\u0000", "a".repeat(16_385), "🙂".repeat(9_000))) {
            assertFailsWith<IllegalArgumentException> { collaborationTextOrigin(sender, id, text) }
        }
        assertFailsWith<IllegalArgumentException> { collaborationTextOrigin("invalid", id, "text") }
        assertFailsWith<IllegalArgumentException> {
            collaborationTextOrigin(sender, "invalid", "text")
        }
    }

    private suspend fun decode(content: JsonObject): CollaborationHistoryChange.Text {
        val original = buildJsonObject {
            put("event_id", "\$original")
            put("sender", sender)
            put("origin_server_ts", 1)
            put("type", "m.room.message")
            put("content", content)
        }
        return assertIs<CollaborationHistoryChange.Text>(
            CollaborationSdkHistoryEventDto(
                    "\$original",
                    sender,
                    1,
                    SdkMessageKind.TEXT,
                    original.toString(),
                )
                .toOriginalHistory()
        )
    }
}
