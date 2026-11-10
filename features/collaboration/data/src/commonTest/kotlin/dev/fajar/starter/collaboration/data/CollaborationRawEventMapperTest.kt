package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CollaborationRawEventMapperTest {
    @Test
    fun decryptedOriginalRetainsExactIdentityAndExistingCanonicalFingerprint() = runTest {
        val raw = fixture()
        val event = raw.toAcquiredEvent()
        val message = assertIs<CollaborationAcquiredEventContent.Original>(event.content).message
        val expected =
            CollaborationSdkHistoryEventDto("\$original", SENDER, 10, SdkMessageKind.TEXT, raw.json)
                .toOriginalHistory()
        assertEquals(expected, message)
        assertEquals("\$original", event.eventId)
        assertEquals(SENDER, event.sender)
        assertEquals(10L, event.timestamp)
        assertEquals("m.room.message", event.type)
        assertEquals(" Original\ntext 🙂 ", message.text)
        assertEquals(LOGICAL, message.logicalId)
        assertFalse(event.toString().contains("Original"))
        assertFalse(event.content.toString().contains("text"))
    }

    @Test
    fun roomFlagsCannotSubstituteForActualSdkDecryption() = runTest {
        for (joined in listOf(false, true)) for (encrypted in listOf(false, true)) {
            for ((native, expected) in
                listOf(
                    CollaborationSdkRawEventEncryption.NOT_DECRYPTED to
                        CollaborationEventDecryptionFailure.NotDecrypted,
                    CollaborationSdkRawEventEncryption.UNABLE_TO_DECRYPT to
                        CollaborationEventDecryptionFailure.UnableToDecrypt,
                )) {
                val raw =
                    fixture()
                        .copy(roomJoined = joined, roomEncrypted = encrypted, encryption = native)
                val content =
                    assertIs<CollaborationAcquiredEventContent.Unresolved>(
                        raw.toAcquiredEvent().content
                    )
                assertEquals(expected, content.reason)
                assertFalse(content.toString().contains("Original"))
            }
        }
    }

    @Test
    fun mappingDecryptionFactsDoesNotMakeRoomAdmissionDecisions() = runTest {
        val raw = fixture().copy(roomJoined = false, roomEncrypted = false)
        assertIs<CollaborationAcquiredEventContent.Original>(raw.toAcquiredEvent().content)
    }

    @Test
    fun replacementKeepsItsOwnIdentityAndCannotBecomeAnOriginal() = runTest {
        val raw = fixture(editContent(), eventId = "\$edit")
        val event = raw.toAcquiredEvent()
        val edit = assertIs<CollaborationAcquiredEventContent.Replacement>(event.content).edit
        val expected =
            CollaborationSdkHistoryEventDto("\$edit", SENDER, 10, SdkMessageKind.TEXT, raw.json)
                .toMessageEdit()
        assertEquals(expected, edit)
        assertEquals("\$edit", event.eventId)
        assertEquals("\$original", edit.targetEventId)
        assertEquals(LOGICAL, edit.originalLogicalId)
        assertEquals(OPERATION, edit.operationId)
        assertEquals("Changed", edit.text)
    }

    @Test
    fun incompleteReplacementDoesNotFallBackToOriginalText() = runTest {
        val missingRelation = JsonObject(textContent() + ("m.new_content" to textContent()))
        val missingContent = JsonObject(editContent() - "m.new_content")
        for (content in listOf(missingRelation, missingContent)) assertEquals(
            CollaborationAcquiredEventContent.Unsupported,
            fixture(content).toAcquiredEvent().content,
        )
    }

    @Test
    fun unsupportedEventOrMessageTypesCannotProduceTextOrExhaustion() = runTest {
        val events =
            listOf(
                fixture(type = "m.room.member"),
                fixture(type = "m.reaction"),
                fixture(type = "m.sticker"),
                fixture(JsonObject(textContent() + ("msgtype" to JsonPrimitive("m.image")))),
            )
        for (raw in events) {
            val event = raw.toAcquiredEvent()
            assertEquals("\$original", event.eventId)
            assertEquals(CollaborationAcquiredEventContent.Unsupported, event.content)
        }
    }

    @Test
    fun ciphertextNeverBecomesOriginalPlaintext() = runTest {
        val raw = fixture(type = "m.room.encrypted")
        for (encryption in
            listOf(
                CollaborationSdkRawEventEncryption.NOT_DECRYPTED,
                CollaborationSdkRawEventEncryption.UNABLE_TO_DECRYPT,
            )) assertIs<CollaborationAcquiredEventContent.Unresolved>(
            raw.copy(encryption = encryption).toAcquiredEvent().content
        )
        assertFailsWith<IllegalArgumentException> { raw.toAcquiredEvent() }
    }

    @Test
    fun wrongRequestedIdentityOrRoomCannotBeRelabeled() = runTest {
        val raw = fixture()
        for (changed in
            listOf(
                raw.copy(eventId = "\$other"),
                raw.copy(eventId = "bad"),
                raw.copy(roomId = "bad"),
                raw.copy(roomId = "!one:bad host"),
            )) assertFailsWith<IllegalArgumentException> { changed.toAcquiredEvent() }
        for (room in
            listOf(JsonPrimitive("!other:test"), JsonNull, JsonPrimitive(1))) assertFailsWith<
            IllegalArgumentException
        > {
            raw.field("room_id", room).toAcquiredEvent()
        }
        assertIs<CollaborationAcquiredEventContent.Original>(
            raw.field("room_id", JsonPrimitive(ROOM)).toAcquiredEvent().content
        )
    }

    @Test
    fun senderAndTypeMustBeValidProviderStrings() = runTest {
        val raw = fixture()
        for (sender in
            listOf<JsonElement?>(
                null,
                JsonNull,
                JsonPrimitive(7),
                JsonPrimitive("invalid"),
                JsonPrimitive("@bad name:test"),
            )) assertFailsWith<IllegalArgumentException> {
            raw.field("sender", sender).toAcquiredEvent()
        }
        for (type in
            listOf<JsonElement?>(
                null,
                JsonNull,
                JsonPrimitive(7),
                JsonPrimitive(""),
                JsonPrimitive("x".repeat(256)),
                JsonPrimitive("m.room.\nmessage"),
            )) assertFailsWith<IllegalArgumentException> {
            raw.field("type", type).toAcquiredEvent()
        }
    }

    @Test
    fun timestampCannotLosePrecisionOrChangeType() = runTest {
        val raw = fixture()
        for (timestamp in
            listOf<JsonElement?>(
                null,
                JsonNull,
                JsonPrimitive("10"),
                JsonPrimitive(-1),
                JsonPrimitive(1.5),
                JsonPrimitive(true),
                JsonPrimitive(9_007_199_254_740_992L),
            )) assertFailsWith<IllegalArgumentException> {
            raw.field("origin_server_ts", timestamp).toAcquiredEvent()
        }
        assertEquals(
            9_007_199_254_740_991L,
            raw.field("origin_server_ts", JsonPrimitive(9_007_199_254_740_991L))
                .toAcquiredEvent()
                .timestamp,
        )
    }

    @Test
    fun rawJsonRemainsBoundedBeforeParsingOrCanonicalization() = runTest {
        val raw = fixture()
        for (json in
            listOf(
                "",
                "{",
                "x".repeat(96_001),
                "é".repeat(48_001),
                "[".repeat(17) + "0" + "]".repeat(17),
                raw.json.replace("Original", "\uD800"),
            )) assertFails { raw.copy(json = json).toAcquiredEvent() }
    }

    @Test
    fun redactionActorOnlySuppliesATargetReadTrigger() = runTest {
        for (modern in listOf(false, true)) {
            val redaction = redaction(modern = modern)
            val event =
                fixture(type = "m.room.redaction", eventId = "\$redaction")
                    .copy(
                        json = redaction.toString(),
                        encryption = CollaborationSdkRawEventEncryption.NOT_DECRYPTED,
                    )
                    .toAcquiredEvent()
            assertEquals(MODERATOR, event.sender)
            assertEquals("\$redaction", event.eventId)
            assertEquals(
                "\$original",
                assertIs<CollaborationAcquiredEventContent.Redaction>(event.content).targetEventId,
            )
        }
    }

    @Test
    fun conflictingMalformedOrSelfRedactionTargetsAreRejected() = runTest {
        val raw = fixture(eventId = "\$redaction").copy(json = redaction().toString())
        val variants =
            listOf(
                raw.field("redacts", JsonPrimitive("\$other")),
                raw.field("redacts", JsonPrimitive(1)),
                raw.field("content", buildJsonObject { put("redacts", "\$redaction") }),
                raw.field("content", buildJsonObject { put("redacts", "invalid") }),
                raw.field("content", buildJsonObject { put("redacts", JsonNull) }),
                raw.field("content", buildJsonObject {}),
            )
        for (variant in variants) assertFailsWith<IllegalArgumentException> {
            variant.toAcquiredEvent()
        }
        assertIs<CollaborationAcquiredEventContent.Redaction>(
            raw.field("redacts", JsonPrimitive("\$original")).toAcquiredEvent().content
        )
    }

    @Test
    fun redactedTargetKeepsOriginalSenderWithoutInventingDecryption() = runTest {
        for (type in listOf("m.room.message", "m.room.encrypted")) {
            for (encryption in CollaborationSdkRawEventEncryption.entries) {
                val raw =
                    fixture(buildJsonObject {}, type = type)
                        .copy(encryption = encryption)
                        .field("unsigned", buildJsonObject { put("redacted_because", redaction()) })
                val event = raw.toAcquiredEvent()
                assertEquals(SENDER, event.sender)
                assertEquals("\$original", event.eventId)
                assertEquals(
                    "\$redaction",
                    assertIs<CollaborationAcquiredEventContent.Redacted>(event.content)
                        .redactionEventId,
                )
            }
        }
    }

    @Test
    fun redactedReplacementRetainsItsReplacementEventId() = runTest {
        val raw =
            fixture(buildJsonObject {}, eventId = "\$edit", type = "m.room.encrypted")
                .field(
                    "unsigned",
                    buildJsonObject { put("redacted_because", redaction(target = "\$edit")) },
                )
        val event = raw.toAcquiredEvent()
        assertEquals("\$edit", event.eventId)
        assertEquals(SENDER, event.sender)
        assertIs<CollaborationAcquiredEventContent.Redacted>(event.content)
    }

    @Test
    fun tombstoneMetadataMustMatchTheTargetAndHaveAValidRedactionIdentity() = runTest {
        val valid = redaction()
        val variants =
            listOf<JsonElement>(
                JsonNull,
                JsonPrimitive("invalid"),
                JsonObject(valid + ("type" to JsonPrimitive("m.room.message"))),
                JsonObject(valid + ("event_id" to JsonPrimitive("\$original"))),
                JsonObject(valid + ("event_id" to JsonPrimitive("bad"))),
                JsonObject(valid + ("sender" to JsonPrimitive("bad"))),
                JsonObject(valid + ("origin_server_ts" to JsonPrimitive("11"))),
                JsonObject(valid + ("room_id" to JsonPrimitive("!other:test"))),
                redaction(target = "\$other"),
            )
        for (redaction in variants) {
            val raw =
                fixture(buildJsonObject {})
                    .field("unsigned", buildJsonObject { put("redacted_because", redaction) })
            assertFailsWith<IllegalArgumentException> { raw.toAcquiredEvent() }
        }
    }

    @Test
    fun redactedControlEventsDoNotDeleteOriginalMessages() = runTest {
        for (type in listOf("m.room.redaction", "m.room.member", "m.reaction")) {
            val raw =
                fixture(buildJsonObject {}, type = type)
                    .field("unsigned", buildJsonObject { put("redacted_because", redaction()) })
            assertEquals(
                CollaborationAcquiredEventContent.Unsupported,
                raw.toAcquiredEvent().content,
            )
        }
    }

    @Test
    fun malformedUnsignedMetadataCannotBeIgnoredWhileRenderingText() = runTest {
        for (unsigned in
            listOf(JsonNull, JsonPrimitive("invalid"), buildJsonArray {})) assertFailsWith<
            IllegalArgumentException
        > {
            fixture().field("unsigned", unsigned).toAcquiredEvent()
        }
    }

    @Test
    fun malformedDecryptedContentCannotProduceAPartialOriginal() = runTest {
        val badOriginal = JsonObject(textContent() + ("body" to JsonPrimitive("")))
        val badEdit = JsonObject(editContent() + ("org.hris.edit_id" to JsonPrimitive("invalid")))
        assertFailsWith<IllegalArgumentException> { fixture(badOriginal).toAcquiredEvent() }
        assertFailsWith<IllegalArgumentException> {
            fixture(badEdit, eventId = "\$edit").toAcquiredEvent()
        }
    }

    @Test
    fun unknownContentFieldsStillAffectTheOriginalAndReplacementFingerprints() = runTest {
        val original =
            fixture().toAcquiredEvent().content as CollaborationAcquiredEventContent.Original
        val changed =
            fixture(JsonObject(textContent() + ("custom" to JsonPrimitive("different"))))
                .toAcquiredEvent()
                .content as CollaborationAcquiredEventContent.Original
        assertNotEquals(original.message.contentHash, changed.message.contentHash)
        val edit =
            fixture(editContent(), eventId = "\$edit").toAcquiredEvent().content
                as CollaborationAcquiredEventContent.Replacement
        val changedEdit =
            fixture(
                    JsonObject(editContent() + ("custom" to JsonPrimitive("different"))),
                    eventId = "\$edit",
                )
                .toAcquiredEvent()
                .content as CollaborationAcquiredEventContent.Replacement
        assertNotEquals(edit.edit.contentHash, changedEdit.edit.contentHash)
    }

    @Test
    fun cancelledMappingDoesNotPublishAnyContent() = runTest {
        val request = async {
            currentCoroutineContext().cancel()
            fixture().copy(json = "invalid").toAcquiredEvent()
        }
        assertFailsWith<CancellationException> { request.await() }
    }

    private fun fixture(
        content: JsonObject = textContent(),
        type: String = "m.room.message",
        eventId: String = "\$original",
    ) =
        CollaborationSdkRawEventDto(
            ROOM,
            eventId,
            true,
            true,
            CollaborationSdkRawEventEncryption.DECRYPTED,
            buildJsonObject {
                    put("event_id", eventId)
                    put("sender", SENDER)
                    put("origin_server_ts", 10)
                    put("type", type)
                    put("content", content)
                }
                .toString(),
        )

    private fun CollaborationSdkRawEventDto.field(name: String, value: JsonElement?) =
        copy(
            json =
                JsonObject(
                        Json.parseToJsonElement(json).jsonObject.toMutableMap().apply {
                            if (value == null) remove(name) else put(name, value)
                        }
                    )
                    .toString()
        )

    private fun textContent(body: String = " Original\ntext 🙂 ") = buildJsonObject {
        put("msgtype", "m.text")
        put("body", body)
        put("org.hris.message_id", LOGICAL)
        put("custom", buildJsonObject { put("preserved", true) })
    }

    private fun editContent() = buildJsonObject {
        put("msgtype", "m.text")
        put("body", "* Changed")
        put("org.hris.edit_id", OPERATION)
        put(
            "m.relates_to",
            buildJsonObject {
                put("rel_type", "m.replace")
                put("event_id", "\$original")
            },
        )
        put("m.new_content", textContent("Changed"))
    }

    private fun redaction(target: String = "\$original", modern: Boolean = true) = buildJsonObject {
        put("event_id", "\$redaction")
        put("sender", MODERATOR)
        put("origin_server_ts", 11)
        put("type", "m.room.redaction")
        put("content", buildJsonObject { if (modern) put("redacts", target) })
        if (!modern) put("redacts", target)
    }

    private companion object {
        const val ROOM = "!room:example.test"
        const val SENDER = "@author:example.test"
        const val MODERATOR = "@moderator:example.test"
        const val LOGICAL = "10000000-0000-4000-8000-000000000001"
        const val OPERATION = "10000000-0000-4000-8000-000000000002"
    }
}
