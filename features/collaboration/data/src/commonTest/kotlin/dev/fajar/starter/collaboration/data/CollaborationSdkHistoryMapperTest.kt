package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.toOriginalHistory
import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryChange
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CollaborationSdkHistoryMapperTest {
    @Test
    fun unknownContentFieldsAndNestedObjectsParticipateInStableOriginalFingerprint() = runTest {
        val content = buildJsonObject {
            put("body", " Original\ntext 🙂 ")
            put("msgtype", "m.text")
            put("org.hris.message_id", historyId(1))
            put(
                "custom",
                buildJsonObject {
                    put("z", "kept")
                    put("a", "retained")
                },
            )
        }
        val original = decode(content)
        val reordered =
            JsonObject(
                content.toList().reversed().toMap() +
                    ("custom" to
                        buildJsonObject {
                            put("a", "retained")
                            put("z", "kept")
                        })
            )
        assertEquals(original, decode(reordered))
        assertEquals(" Original\ntext 🙂 ", original.text)
        assertEquals(historyId(1), original.logicalId)
        assertNotEquals(
            original.contentHash,
            decode(JsonObject(content + ("custom" to JsonPrimitive("changed")))).contentHash,
        )
        assertFalse(original.toString().contains("Original"))
    }

    @Test
    fun providerRetryTimestampAndUnsignedMetadataDoNotChangeContentFingerprint() = runTest {
        val content = textContent()
        val first = decode(content)
        val retry =
            fixture(content, eventId = "\$retry", timestamp = 99).toOriginalHistory()
                as CollaborationHistoryChange.Text
        assertEquals(first.contentHash, retry.contentHash)
        assertNotEquals(first.eventId, retry.eventId)
    }

    @Test
    fun replacementEventsCannotEstablishAnOriginalEvenWhenTheyCarryItsLogicalId() = runTest {
        val original = textContent()
        val edit =
            JsonObject(
                original +
                    mapOf(
                        "m.new_content" to original,
                        "m.relates_to" to
                            buildJsonObject {
                                put("rel_type", "m.replace")
                                put("event_id", "\$original")
                            },
                    )
            )
        assertNull(fixture(edit).toOriginalHistory())
        assertNull(
            fixture(JsonObject(original + ("m.new_content" to original))).toOriginalHistory()
        )
        assertNotNull(fixture(original).toOriginalHistory())
    }

    @Test
    fun senderEventIdAndTimestampMustMatchTheSdkPrimitiveIdentity() = runTest {
        val event = fixture(textContent())
        for (altered in
            listOf(
                event.copy(sender = "@forged:example.test"),
                event.copy(eventId = "\$other"),
                event.copy(timestamp = 2),
            )) assertFailsWith<IllegalArgumentException> { altered.toOriginalHistory() }
    }

    @Test
    fun undecryptableMissingAndRedactedContentCannotBecomeEmptyText() = runTest {
        val event = fixture(textContent())
        assertNull(event.copy(kind = SdkMessageKind.UNABLE_TO_DECRYPT).toOriginalHistory())
        assertNull(event.copy(originalJson = null).toOriginalHistory())
        assertEquals(
            CollaborationHistoryChange.Redacted(event.eventId, event.sender),
            event.copy(kind = SdkMessageKind.REDACTED, originalJson = null).toOriginalHistory(),
        )
        val encrypted =
            JsonObject(
                Json.parseToJsonElement(event.originalJson!!).jsonObject +
                    ("type" to JsonPrimitive("m.room.encrypted"))
            )
        assertNull(event.copy(originalJson = encrypted.toString()).toOriginalHistory())
    }

    @Test
    fun legacyAndMalformedLogicalIdsUseProviderIdentityWithoutNormalizingBody() = runTest {
        for (id in
            listOf<JsonElement?>(null, JsonPrimitive("invalid"), JsonPrimitive(1), JsonNull)) {
            val content = textContent().toMutableMap()
            if (id == null) content.remove("org.hris.message_id")
            else content["org.hris.message_id"] = id
            assertNull(decode(JsonObject(content)).logicalId)
        }
        val first = decode(textContent())
        assertNotEquals(
            first.contentHash,
            decode(JsonObject(textContent() + ("body" to JsonPrimitive("body ")))).contentHash,
        )
    }

    @Test
    fun boundedParserRejectsDeepOrOversizeEnvelopesBeforeRecursiveWork() = runTest {
        val event = fixture(textContent())
        assertFailsWith<IllegalArgumentException> {
            event.copy(originalJson = "[".repeat(17) + "0" + "]".repeat(17)).toOriginalHistory()
        }
        assertFailsWith<IllegalArgumentException> {
            event.copy(originalJson = " ".repeat(96_001)).toOriginalHistory()
        }
        assertFailsWith<IllegalArgumentException> {
            decode(JsonObject(textContent() + ("body" to JsonPrimitive("🙂".repeat(9_000)))))
        }
        val escaped =
            JsonObject(
                textContent() + ("body" to JsonPrimitive("quotes \" and braces [ { \\ remain text"))
            )
        assertEquals(escaped["body"]!!.jsonPrimitive.content, decode(escaped).text)
    }

    private suspend fun decode(content: JsonObject) =
        assertIs<CollaborationHistoryChange.Text>(fixture(content).toOriginalHistory())

    private fun textContent() = buildJsonObject {
        put("body", "body")
        put("msgtype", "m.text")
        put("org.hris.message_id", historyId(1))
    }

    private fun fixture(content: JsonObject, eventId: String = "\$original", timestamp: Long = 1) =
        CollaborationSdkHistoryEventDto(
            eventId,
            "@fictional:example.test",
            timestamp,
            SdkMessageKind.TEXT,
            buildJsonObject {
                    put("event_id", eventId)
                    put("sender", "@fictional:example.test")
                    put("origin_server_ts", timestamp)
                    put("type", "m.room.message")
                    put("content", content)
                    put(
                        "unsigned",
                        buildJsonObject {
                            put("age", timestamp)
                            put("transaction_id", "txn-$eventId")
                        },
                    )
                }
                .toString(),
        )
}
