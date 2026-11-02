package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.CollaborationPushFixture.Companion.fields
import dev.fajar.starter.collaboration.domain.policies.parseCollaborationPushSignal
import kotlin.test.*

class CollaborationPushSignalPolicyTest {
    @Test
    fun bothBackendEnvelopesContainOnlyLocalOwnerHints() {
        for (visible in listOf(false, true)) {
            val parsed = assertNotNull(parseCollaborationPushSignal(fields(visible)))
            assertEquals(CollaborationPushFixture.ACCOUNT, parsed.accountId)
            assertEquals(CollaborationPushFixture.FAMILY, parsed.nativeSessionId)
            assertEquals("CollaborationPushSignal([redacted])", parsed.toString())
        }
    }

    @Test
    fun exactFieldSetBoundsAndPairedProviderIdsAreRequired() {
        val full = fields(true)
        for (name in fields().keys) assertNull(parseCollaborationPushSignal(full - name), name)
        for (name in listOf("matrixRoomId", "matrixEventId")) assertNull(
            parseCollaborationPushSignal(full - name),
            name,
        )
        for (extra in listOf("text", "destination", "workKey", "title")) assertNull(
            parseCollaborationPushSignal(full + (extra to "ignored"))
        )
        for (name in full.keys) assertNull(
            parseCollaborationPushSignal(full + (name to "x".repeat(256))),
            name,
        )
        assertNull(parseCollaborationPushSignal(fields() + ("schemaVersion" to "2")))
        assertNull(
            parseCollaborationPushSignal(
                fields() + ("eventCode" to "communications.inbox_available")
            )
        )
    }

    @Test
    fun identifiersAreCanonicalAndCannotSmuggleDisplayOrNavigationContent() {
        for (name in listOf("eventId", "accountId", "companyId", "nativeSessionId", "deviceId")) {
            for (bad in listOf("A1111111-1111-4111-8111-111111111111", "account", "", "../room")) {
                assertNull(parseCollaborationPushSignal(fields() + (name to bad)), "$name:$bad")
            }
        }
        for (room in listOf("room", "!", "!room name", "!room\n")) assertNull(
            parseCollaborationPushSignal(fields(true) + ("matrixRoomId" to room))
        )
        for (event in listOf("event", "$", "$" + "event name", "$" + "event\n")) assertNull(
            parseCollaborationPushSignal(fields(true) + ("matrixEventId" to event))
        )
    }

    @Test
    fun numericFieldsRejectOverflowNoncanonicalAndInvalidTtl() {
        for (name in listOf("revision", "issuedAt", "expiresAt")) {
            for (value in listOf("01", "+1", "-1", "1.0", " 1", "9223372036854775808")) assertNull(
                parseCollaborationPushSignal(fields() + (name to value)),
                "$name:$value",
            )
        }
        for (revision in listOf("0", "9223372036854775808")) assertNull(
            parseCollaborationPushSignal(fields() + ("revision" to revision))
        )
        for (expiry in listOf("1793491200", "1793491199", "1793577601", "253402300800")) assertNull(
            parseCollaborationPushSignal(fields() + ("expiresAt" to expiry))
        )
        assertNotNull(
            parseCollaborationPushSignal(
                fields() + ("revision" to "9007199254740991") + ("expiresAt" to "1793577600")
            )
        )
    }

    @Test
    fun largestProducerEnvelopeKeepsItsDecimalLongRevisionWithoutFloatingPointConversion() {
        val packet =
            fields(true) +
                mapOf(
                    "revision" to (Long.MAX_VALUE - 1).toString(),
                    "matrixRoomId" to "!" + "r".repeat(254),
                    "matrixEventId" to "$" + "e".repeat(254),
                    "expiresAt" to "1793577600",
                )
        assertNotNull(parseCollaborationPushSignal(packet))
    }

    @Test
    fun oldAndFutureTimestampsAreHintsWithoutDisplayAuthority() {
        for (issued in listOf(0L, 253_402_300_798L)) {
            assertNotNull(
                parseCollaborationPushSignal(
                    fields() + ("issuedAt" to "$issued") + ("expiresAt" to "${issued + 1}")
                )
            )
        }
    }
}
