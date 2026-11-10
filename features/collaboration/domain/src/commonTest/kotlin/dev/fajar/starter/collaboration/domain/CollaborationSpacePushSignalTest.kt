package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationSpacePushSignalTest {
    @Test
    fun exactGroupEnvelopeKeepsLogicalIdentitySeparateFromCompanyHints() {
        val f = SpacePushFixture()
        for (visible in listOf(false, true)) {
            val fields = f.fields(visible)
            val signal = assertNotNull(parseCollaborationSpacePushSignal(fields))
            assertEquals(f.owner.spaceId, signal.spaceId)
            assertEquals(f.base.receipt.id, signal.deviceId)
            assertEquals(visible, signal.roomId != null)
            assertNull(parseCollaborationPushSignal(fields))
            assertEquals("CollaborationSpacePushSignal([redacted])", signal.toString())
        }
        assertNull(parseCollaborationSpacePushSignal(CollaborationPushFixture.fields()))
    }

    @Test
    fun unknownMixedMissingAndUnsupportedFieldsAreRejected() {
        val f = SpacePushFixture().fields()
        for (fields in
            listOf(
                f + ("companyId" to f.getValue("spaceId")),
                f - "spaceId",
                f + ("text" to "private"),
                f + ("schemaVersion" to "2"),
                f + ("eventCode" to "collaboration.chat_available"),
                (f - "spaceId") + ("companyId" to f.getValue("spaceId")),
            )) {
            assertNull(parseCollaborationSpacePushSignal(fields))
        }
    }

    @Test
    fun numbersAreCanonicalBoundedAndHaveAtMostOneDayRetention() {
        val fields = SpacePushFixture().fields()
        for (key in listOf("revision", "issuedAt", "expiresAt")) {
            for (value in
                listOf("+1", "01", " 1", "1 ", "-1", "9223372036854775808", "1.0")) assertNull(
                parseCollaborationSpacePushSignal(fields + (key to value)),
                "$key/$value",
            )
        }
        for ((key, value) in
            listOf(
                "revision" to "0",
                "issuedAt" to "86401",
                "expiresAt" to "86402",
                "issuedAt" to "253402300800",
                "expiresAt" to "253402300800",
            )) assertNull(parseCollaborationSpacePushSignal(fields + (key to value)))
        assertNotNull(
            parseCollaborationSpacePushSignal(fields + ("revision" to Long.MAX_VALUE.toString()))
        )
    }

    @Test
    fun invalidIdentityAndPartialOrUnsafeProviderLocatorsAreRejected() {
        val f = SpacePushFixture()
        for (key in
            listOf("eventId", "accountId", "spaceId", "nativeSessionId", "deviceId")) for (value in
            listOf("", "other", "a".repeat(256), f.owner.spaceId + "\n")) assertNull(
            parseCollaborationSpacePushSignal(f.fields() + (key to value))
        )
        for (key in listOf("matrixRoomId", "matrixEventId")) {
            assertNull(parseCollaborationSpacePushSignal(f.fields(true) - key))
            for (value in listOf("", "bad\n", "a".repeat(256))) assertNull(
                parseCollaborationSpacePushSignal(f.fields(true) + (key to value))
            )
        }
        for (room in listOf("!", "!room space", "!room\u007f", "!é", "room")) assertNull(
            parseCollaborationSpacePushSignal(f.fields(true) + ("matrixRoomId" to room))
        )
    }
}
