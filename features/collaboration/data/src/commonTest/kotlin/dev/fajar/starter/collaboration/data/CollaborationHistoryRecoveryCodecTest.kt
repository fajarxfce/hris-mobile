package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CollaborationHistoryRecoveryCodecTest {
    @Test
    fun oldCompanyBrowseIncomingAndSpaceFramesKeepTheirVersions() = runTest {
        for ((space, browse, schema) in
            listOf(Triple(false, true, 1), Triple(false, false, 2), Triple(true, false, 3))) {
            val f = Frame(space, browse)
            try {
                val original = f.progress.copy(recovery = null)
                assertEquals(original, f.codec.progress(f.checkpoint(original)))
                assertEquals(schema, f.body["schema"]?.jsonPrimitive?.int ?: 1)
                assertFalse("recovery" in f.body)
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun newFramesRetainFiniteActiveAndRequestedPassesWithMaximumProviderPositions() =
        withFrames { f ->
            val maxPosition = "\"\\".repeat(2048)
            val progress =
                f.progress.copy(
                    next = maxPosition,
                    incoming =
                        CollaborationIncomingHistoryPosition(
                            maxPosition,
                            maxPosition,
                            f.cursor,
                            3,
                            true,
                        ),
                )
            val raw = f.checkpoint(progress)
            assertEquals(progress, f.codec.progress(raw))
            assertEquals(4, f.body.getValue("schema").jsonPrimitive.int)
            assertTrue(raw.payload.length <= 32_768)
            assertFalse(raw.payload.contains(f.progress.recovery!!.generation))
        }

    @Test
    fun authenticatedOlderHeadersCannotCarryANewRecoverySection() = withFrames { f ->
        f.transform = { body ->
            JsonObject(body + ("schema" to JsonPrimitive(if (f.space) 3 else 2)))
        }
        assertFailsWith<IllegalArgumentException> { f.codec.progress(f.checkpoint()) }
    }

    @Test
    fun newHeaderCannotSilentlyLoseItsRecoverySection() = withFrames { f ->
        f.transform = { body -> JsonObject(body - "recovery") }
        assertFailsWith<IllegalArgumentException> { f.codec.progress(f.checkpoint()) }
    }

    @Test
    fun recoveryCursorMustBeAnOwnedBoundedReferenceEncoding() = withFrames { f ->
        for (cursor in
            listOf(
                "opaque",
                "0000000000000003/r-" + "a".repeat(64),
                "0000000000000099/r-" + "a".repeat(64),
            )) {
            val position = f.progress.recovery!!.copy(after = cursor)
            assertFailsWith<IllegalArgumentException> {
                f.checkpoint(f.progress.copy(recovery = position))
            }
            f.transform = { body ->
                val recovery = body.getValue("recovery").jsonObject
                JsonObject(
                    body + ("recovery" to JsonObject(recovery + ("after" to JsonPrimitive(cursor))))
                )
            }
            assertFailsWith<IllegalArgumentException> { f.codec.progress(f.checkpoint()) }
            f.transform = { it }
        }
    }

    @Test
    fun authenticatedFrameMustKeepItsCompanyOrSpaceProviderProvenance() = withFrames { f ->
        f.transform = { body ->
            if (f.space) JsonObject(body - "provider")
            else
                JsonObject(
                    body +
                        ("provider" to
                            buildJsonObject {
                                put("companyId", historyId(9300))
                                put("deviceId", historyId(9301))
                            })
                )
        }
        assertFailsWith<IllegalArgumentException> { f.codec.progress(f.checkpoint()) }
    }

    @Test
    fun copiedCiphertextCannotCrossRoomOrRevision() = withFrames { f ->
        val raw = f.checkpoint()
        assertFails { f.codec.progress(raw.copy(revision = raw.revision + 1)) }
        val wrongRoom =
            CollaborationHistoryTraversalCodec(
                f.coordinates,
                f.traversal.copy(roomId = "!other:example.test"),
                f.cipher,
                f.content,
                f.index,
            )
        assertFails { wrongRoom.progress(raw) }
    }

    @Test
    fun completedUnresolvedAttemptIsRetainedWithoutPretendingContentWasResolved() =
        withFrames { f ->
            val completed =
                f.progress.copy(
                    recovery =
                        f.progress.recovery!!.copy(
                            after = null,
                            complete = true,
                            requestedGeneration = null,
                        )
                )
            assertEquals(completed, f.codec.progress(f.checkpoint(completed)))
            assertTrue(completed.recovery!!.unresolved)
            assertEquals(3, completed.references)
        }

    private fun withFrames(block: suspend (Frame) -> Unit) = runTest {
        for (space in listOf(false, true)) {
            val frame = Frame(space)
            try {
                block(frame)
            } finally {
                frame.close()
            }
        }
    }

    private class Frame(val space: Boolean, browse: Boolean = false) {
        val content = ByteArray(32) { (it + 32).toByte() }
        val index = ByteArray(32) { it.toByte() }
        val cipher = AesGcmContentCipher()
        val access =
            CollaborationLocalAccess(
                "session",
                "https://hris.example.test/",
                historyId(9000),
                historyId(9001),
            )
        val spaceAccess =
            CollaborationSpaceLocalAccess(
                access.sessionId,
                access.audience,
                access.accountId,
                access.companyId,
            )
        val traversal =
            CollaborationHistoryTraversal(
                historyId(9002),
                "!history:example.test",
                historyId(9100),
                if (browse) CollaborationHistoryPurpose.Browse
                else CollaborationHistoryPurpose.Incoming,
            )
        val coordinates =
            if (space) CollaborationHistoryCoordinates.Space(spaceAccess, traversal.conversationId)
            else CollaborationHistoryCoordinates.Company(access, traversal.conversationId)
        val cursor = "0000000000000000/r-" + "a".repeat(64)
        val progress =
            CollaborationHistoryProgress(
                historyId(9802),
                2,
                "next",
                3,
                if (browse) null else CollaborationIncomingHistoryPosition("head", null),
                if (space) CollaborationHistoryProviderIdentity(historyId(9300), historyId(9301))
                else null,
                if (browse) null
                else
                    CollaborationHistoryRecoveryPosition(
                        historyId(9800),
                        3,
                        cursor,
                        unresolved = true,
                        requestedGeneration = historyId(9801),
                    ),
            )
        var body = JsonObject(emptyMap())
        var transform: (JsonObject) -> JsonObject = { it }
        val codec =
            CollaborationHistoryTraversalCodec(
                coordinates,
                traversal,
                object : ContentCipher by cipher {
                    override suspend fun encrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        plaintext: ByteArray,
                    ): ByteArray {
                        body = Json.parseToJsonElement(plaintext.decodeToString()).jsonObject
                        return cipher.encrypt(
                            key,
                            associatedData,
                            transform(body).toString().encodeToByteArray(),
                        )
                    }
                },
                content,
                index,
            )

        suspend fun checkpoint(value: CollaborationHistoryProgress = progress) =
            ReplicaCheckpoint(
                ReplicaHandle(
                    coordinates.scope,
                    coordinates.traversalPartition(traversal),
                    value.generation,
                ),
                value.revision,
                codec.checkpoint(value),
                true,
            )

        fun close() {
            content.fill(0)
            index.fill(0)
        }
    }
}
