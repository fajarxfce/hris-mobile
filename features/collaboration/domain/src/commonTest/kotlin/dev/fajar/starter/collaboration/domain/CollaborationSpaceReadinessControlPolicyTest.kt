package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationSpaceReadinessControlPolicyTest {
    // V1 server wire contract; numbers stay strings on the wire.
    private val fields =
        mapOf(
            "schema_version" to "1",
            "event_id" to "11111111-1111-4111-8111-111111111111",
            "space_id" to "22222222-2222-4222-8222-222222222222",
            "account_id" to "33333333-3333-4333-8333-333333333333",
            "native_session_id" to "44444444-4444-4444-8444-444444444444",
            "device_id" to "55555555-5555-4555-8555-555555555555",
            "publication_revision" to "9007199254740993",
            "published_at" to "1794171600123",
            "expires_at" to "1794258000123",
        )

    @Test
    fun serverV1PreservesRevisionPrecisionThroughTheSignedLongBoundary() {
        val parsed = assertNotNull(parseCollaborationSpaceReadinessControl(fields))
        assertEquals(9_007_199_254_740_993L, parsed.publicationRevision)
        assertEquals(1_794_171_600_123L, parsed.publishedAtEpochMillis)
        assertEquals("CollaborationSpaceReadinessControl([redacted])", parsed.toString())
        assertEquals(
            Long.MAX_VALUE,
            assertNotNull(
                    parseCollaborationSpaceReadinessControl(
                        fields + ("publication_revision" to Long.MAX_VALUE.toString())
                    )
                )
                .publicationRevision,
        )
        assertEquals(
            COLLABORATION_SPACE_READINESS_CONTROL_TYPE,
            "dev.fajar.hris.space_recipient_ready",
        )
    }

    @Test
    fun companyAndOrganizationProtocolsCannotSelectEachOthersScope() {
        val company =
            (fields - "space_id") +
                mapOf("company_id" to fields.getValue("space_id"), "schema_version" to "2")
        assertNotNull(parseCollaborationReadinessControl(company))
        assertNull(parseCollaborationSpaceReadinessControl(company))
        assertNull(parseCollaborationReadinessControl(fields))
        for (key in fields.keys) assertNull(
            parseCollaborationSpaceReadinessControl(fields - key),
            key,
        )
        for (extra in listOf("company_id", "body", "sender", "room_id", "access_token")) assertNull(
            parseCollaborationSpaceReadinessControl(fields + (extra to "untrusted")),
            extra,
        )
        for (schema in listOf("0", "2", "01", "+1", "1.0")) assertNull(
            parseCollaborationSpaceReadinessControl(fields + ("schema_version" to schema))
        )
    }

    @Test
    fun malformedOrNilCoordinatesAndNoncanonicalNumbersNeverBecomeControlFacts() {
        for (key in
            listOf("event_id", "space_id", "account_id", "native_session_id", "device_id")) {
            for (value in
                listOf(
                    "00000000-0000-0000-0000-000000000000",
                    "1-1-1-1-1",
                    "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
                    "a".repeat(37),
                    "",
                    fields.getValue(key) + "\n",
                )) assertNull(
                parseCollaborationSpaceReadinessControl(fields + (key to value)),
                "$key/$value",
            )
        }
        for (key in listOf("publication_revision", "published_at", "expires_at")) {
            for (value in
                listOf(
                    "0",
                    "-1",
                    "01",
                    "+1",
                    "1.0",
                    "1e3",
                    " 1",
                    "1\n",
                    "9223372036854775808",
                )) assertNull(
                parseCollaborationSpaceReadinessControl(fields + (key to value)),
                "$key/$value",
            )
        }
        for (key in fields.keys) assertNull(
            parseCollaborationSpaceReadinessControl(fields + (key to "x".repeat(37))),
            key,
        )
    }

    @Test
    fun publicationLifetimeHasExactBoundsWithoutMakingLocalClockAnAccessGrant() {
        for (published in listOf(1L, 253_402_214_399_999L)) {
            val bounded =
                fields +
                    mapOf(
                        "published_at" to published.toString(),
                        "expires_at" to (published + 86_400_000L).toString(),
                    )
            assertNotNull(parseCollaborationSpaceReadinessControl(bounded))
            assertNull(
                parseCollaborationSpaceReadinessControl(
                    bounded + ("expires_at" to (published + 86_400_001L).toString())
                )
            )
        }
        assertNull(
            parseCollaborationSpaceReadinessControl(
                fields +
                    mapOf("published_at" to "253402214400000", "expires_at" to "253402300800000")
            )
        )
        val parsed = assertNotNull(parseCollaborationSpaceReadinessControl(fields))
        assertFalse(validCollaborationSpaceReadinessControl(parsed.copy(publicationRevision = 0)))
        assertFalse(
            validCollaborationSpaceReadinessControl(parsed.copy(publishedAtEpochMillis = 0))
        )
        assertFalse(
            validCollaborationSpaceReadinessControl(
                parsed.copy(accountId = "00000000-0000-0000-0000-000000000000")
            )
        )
    }
}
