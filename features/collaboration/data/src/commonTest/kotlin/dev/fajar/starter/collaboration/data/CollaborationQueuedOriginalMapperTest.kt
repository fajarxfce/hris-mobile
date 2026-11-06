package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.CollaborationQueuedOriginalDto
import dev.fajar.starter.collaboration.data.mappers.toQueuedOriginal
import dev.fajar.starter.collaboration.data.messages.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CollaborationQueuedOriginalMapperTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val sender = "@fictional:example.test"

    @Test
    fun capturedOriginalKeepsExactBytesAndCompletePreparedFingerprint() = runTest {
        val text = "Original 🙂 with a quote"
        val content = collaborationTextContent(id, text, "$" + "quoted", forwarded = true)
        val raw = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), content)
        val dto = CollaborationQueuedOriginalDto("native-tx", "m.room.message", raw)
        val original = dto.toQueuedOriginal(sender)
        assertEquals(raw, original.serializedContent)
        assertEquals(id, original.logicalId)
        assertEquals(
            collaborationTextOrigin(sender, id, text, "$" + "quoted", true),
            original.origin,
        )
        assertEquals("CollaborationQueuedOriginal([redacted])", original.toString())
        assertEquals("CollaborationQueuedOriginalDto([redacted])", dto.toString())
    }

    @Test
    fun unknownFieldsRemainSignificantWhileJsonObjectOrderDoesNot() = runTest {
        val content = collaborationTextContent(id, "Original")
        val dto = CollaborationQueuedOriginalDto("native-tx", "m.room.message", content.toString())
        val original = dto.toQueuedOriginal(sender)
        val extended =
            JsonObject(
                content + ("unknown" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))
            )
        val changed = dto.copy(contentJson = extended.toString()).toQueuedOriginal(sender)
        assertNotEquals(original.origin.contentHash, changed.origin.contentHash)
        val reordered = JsonObject(extended.entries.reversed().associate { it.key to it.value })
        assertEquals(
            changed.origin,
            dto.copy(contentJson = reordered.toString()).toQueuedOriginal(sender).origin,
        )
        assertNotEquals(
            changed.origin,
            dto.copy(contentJson = reordered.toString())
                .toQueuedOriginal("@other:example.test")
                .origin,
        )
    }

    @Test
    fun missingOrInvalidLogicalIdentityCannotBeInventedFromTheBody() = runTest {
        for (logical in
            listOf<JsonElement?>(null, JsonPrimitive("not-an-id"), JsonPrimitive(123))) {
            val content = buildJsonObject {
                put("body", id)
                put("msgtype", "m.text")
                logical?.let { put("org.hris.message_id", it) }
            }
            val original =
                CollaborationQueuedOriginalDto("native-tx", "m.room.message", content.toString())
                    .toQueuedOriginal(sender)
            assertNull(original.logicalId)
        }
    }

    @Test
    fun nestedNonObjectMalformedAndOversizedContentIsRejected() = runTest {
        val invalid =
            listOf(
                "[]",
                "null",
                "{",
                "{" + "\"nested\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}",
                "{\"body\":\"" + "é".repeat(48_000) + "\"}",
            )
        for (raw in invalid) assertFails {
            CollaborationQueuedOriginalDto("native-tx", "m.room.message", raw)
                .toQueuedOriginal(sender)
        }
        assertFails {
            CollaborationQueuedOriginalDto("bad transaction", "m.room.message", "{}")
                .toQueuedOriginal(sender)
        }
        assertFails {
            CollaborationQueuedOriginalDto("native-tx", "m.room.redaction", "{}")
                .toQueuedOriginal(sender)
        }
    }
}
