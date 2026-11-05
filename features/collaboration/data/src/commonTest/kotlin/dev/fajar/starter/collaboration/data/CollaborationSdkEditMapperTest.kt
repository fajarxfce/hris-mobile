package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CollaborationSdkEditMapperTest {
    private val logical = "00000000-0000-4000-8000-000000000001"
    private val operation = "00000000-0000-4000-8000-000000000002"

    @Test
    fun aSeparateNativeReplacementMustBelongToTheOriginalItemsSenderAndTarget() = runTest {
        val raw = fixture(content())
        val detached =
            CollaborationSdkReplacementDto(
                "\$original",
                raw.sender,
                requireNotNull(raw.originalJson),
            )
        assertEquals(raw.toMessageEdit(), detached.toMessageEdit())
        assertFailsWith<IllegalArgumentException> {
            detached.copy(targetEventId = "\$another").toMessageEdit()
        }
        assertFailsWith<IllegalArgumentException> {
            detached.copy(sender = "@other:example.test").toMessageEdit()
        }
        assertFalse(detached.toString().contains("New"))
    }

    @Test
    fun replacementPreservesItsOwnIdentityAndOriginalContextWithoutBecomingAnOriginal() = runTest {
        val raw = fixture(content())
        val edit = assertNotNull(raw.toMessageEdit())
        assertEquals("\$edit", edit.eventId)
        assertEquals("\$original", edit.targetEventId)
        assertEquals(2_000L, edit.timestamp)
        assertEquals(" New\ntext 🙂 ", edit.text)
        assertEquals(operation, edit.operationId)
        assertEquals(logical, edit.originalLogicalId)
        assertEquals("\$quoted", edit.replyToEventId)
        assertTrue(edit.forwarded)
        assertNull(raw.toOriginalHistory())
        assertFalse(edit.toString().contains("New"))
    }

    @Test
    fun completeContentFingerprintIgnoresObjectOrderAndIncludesUnknownFields() = runTest {
        val first = content()
        val edit = assertNotNull(fixture(first).toMessageEdit())
        val reordered = JsonObject(first.toList().reversed().toMap())
        assertEquals(edit, fixture(reordered).toMessageEdit())
        val changed = JsonObject(first + ("unknown" to JsonPrimitive("changed")))
        assertNotEquals(
            edit.contentHash,
            assertNotNull(fixture(changed).toMessageEdit()).contentHash,
        )
        val raw = fixture(first)
        val envelope = Json.parseToJsonElement(raw.originalJson!!).jsonObject
        val metadata = JsonObject(envelope + ("unsigned" to buildJsonObject { put("age", 123) }))
        assertEquals(edit, raw.copy(originalJson = metadata.toString()).toMessageEdit())
    }

    @Test
    fun detachedSenderEventAndServerTimestampMustMatchTheRawEnvelope() = runTest {
        val raw = fixture(content())
        for (changed in
            listOf(
                raw.copy(eventId = "\$different"),
                raw.copy(sender = "@different:example.test"),
                raw.copy(timestamp = 2_001),
            )) assertFailsWith<IllegalArgumentException> { changed.toMessageEdit() }
        val envelope = Json.parseToJsonElement(raw.originalJson!!).jsonObject
        val stringTime = JsonObject(envelope + ("origin_server_ts" to JsonPrimitive("2000")))
        assertFailsWith<IllegalArgumentException> {
            raw.copy(originalJson = stringTime.toString()).toMessageEdit()
        }
    }

    @Test
    fun absentPlainUndecryptableAndRedactedDataDoNotFabricateAReplacement() = runTest {
        val raw = fixture(content())
        for (kind in
            listOf(
                SdkMessageKind.REDACTED,
                SdkMessageKind.UNABLE_TO_DECRYPT,
                SdkMessageKind.UNSUPPORTED,
            )) {
            assertNull(raw.copy(kind = kind).toMessageEdit())
        }
        assertNull(raw.copy(originalJson = null).toMessageEdit())
        assertNull(fixture(replacement()).toMessageEdit())
        val noRelation = JsonObject(content() - "m.relates_to")
        assertNull(fixture(noRelation).toMessageEdit())
        val encrypted =
            JsonObject(
                Json.parseToJsonElement(raw.originalJson!!).jsonObject +
                    ("type" to JsonPrimitive("m.room.encrypted"))
            )
        assertNull(raw.copy(originalJson = encrypted.toString()).toMessageEdit())
    }

    @Test
    fun knownIdentifiersAndNestedRelationsCannotHaveMalformedTypes() = runTest {
        for (value in listOf<JsonElement>(JsonNull, JsonPrimitive(1), JsonPrimitive("invalid"))) {
            assertFailsWith<IllegalArgumentException> {
                fixture(JsonObject(content() + ("org.hris.edit_id" to value))).toMessageEdit()
            }
            assertFailsWith<IllegalArgumentException> {
                fixture(content(JsonObject(replacement() + ("org.hris.message_id" to value))))
                    .toMessageEdit()
            }
            assertFailsWith<IllegalArgumentException> {
                fixture(content(JsonObject(replacement() + ("m.relates_to" to value))))
                    .toMessageEdit()
            }
            val relation = buildJsonObject { put("m.in_reply_to", value) }
            assertFailsWith<IllegalArgumentException> {
                fixture(content(JsonObject(replacement() + ("m.relates_to" to relation))))
                    .toMessageEdit()
            }
        }
        assertFailsWith<IllegalArgumentException> {
            fixture(content(JsonObject(replacement() + ("m.new_content" to replacement()))))
                .toMessageEdit()
        }
        assertFailsWith<IllegalArgumentException> {
            fixture(JsonObject(content() + ("body" to JsonPrimitive(1)))).toMessageEdit()
        }
        val provider =
            JsonObject(
                replacement() - "org.hris.message_id" - "m.relates_to" - "org.hris.forwarded"
            )
        val edit =
            assertNotNull(
                fixture(JsonObject(content(provider) - "org.hris.edit_id")).toMessageEdit()
            )
        assertNull(edit.originalLogicalId)
        assertNull(edit.operationId)
        assertNull(edit.replyToEventId)
        assertFalse(edit.forwarded)
    }

    @Test
    fun rawAndNestedContentBoundsApplyBeforeAdmittingAnyBody() = runTest {
        val raw = fixture(content())
        for (body in listOf("[".repeat(17) + "0" + "]".repeat(17), " ".repeat(96_001))) {
            assertFailsWith<IllegalArgumentException> {
                raw.copy(originalJson = body).toMessageEdit()
            }
        }
        assertFailsWith<IllegalArgumentException> {
            fixture(
                    content(
                        JsonObject(replacement() + ("body" to JsonPrimitive("🙂".repeat(9_000))))
                    )
                )
                .toMessageEdit()
        }
        val nested = buildJsonObject {
            put("rel_type", "m.replace")
            put("event_id", "\$other")
        }
        assertFailsWith<IllegalArgumentException> {
            fixture(content(JsonObject(replacement() + ("m.relates_to" to nested)))).toMessageEdit()
        }
    }

    private fun replacement() = buildJsonObject {
        put("msgtype", "m.text")
        put("body", " New\ntext 🙂 ")
        put("org.hris.message_id", logical)
        put("org.hris.forwarded", true)
        put(
            "m.relates_to",
            buildJsonObject {
                put("m.in_reply_to", buildJsonObject { put("event_id", "\$quoted") })
            },
        )
    }

    private fun content(next: JsonObject = replacement()) = buildJsonObject {
        put("msgtype", "m.text")
        put("body", "* New text")
        put("org.hris.edit_id", operation)
        put("m.new_content", next)
        put(
            "m.relates_to",
            buildJsonObject {
                put("rel_type", "m.replace")
                put("event_id", "\$original")
            },
        )
        put("unknown", "retained")
    }

    private fun fixture(content: JsonObject) =
        CollaborationSdkHistoryEventDto(
            "\$edit",
            "@author:example.test",
            2_000,
            SdkMessageKind.TEXT,
            buildJsonObject {
                    put("event_id", "\$edit")
                    put("sender", "@author:example.test")
                    put("origin_server_ts", 2_000)
                    put("type", "m.room.message")
                    put("content", content)
                }
                .toString(),
        )
}
