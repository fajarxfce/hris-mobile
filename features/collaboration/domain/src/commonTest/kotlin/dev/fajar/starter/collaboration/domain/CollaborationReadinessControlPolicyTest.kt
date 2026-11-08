package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationReadinessControlPolicyTest {
    private val fields =
        mapOf(
            "schema_version" to "2",
            "event_id" to "11111111-1111-4111-8111-111111111111",
            "company_id" to "22222222-2222-4222-8222-222222222222",
            "account_id" to "33333333-3333-4333-8333-333333333333",
            "native_session_id" to "44444444-4444-4444-8444-444444444444",
            "device_id" to "55555555-5555-4555-8555-555555555555",
            "publication_revision" to "9007199254740993",
            "published_at" to "1794171600123",
            "expires_at" to "1794258000123",
        )

    @Test
    fun v2KeepsLongPrecisionAndRejectsRevisionlessOrExtendedSignals() {
        val parsed = assertNotNull(parseCollaborationReadinessControl(fields))
        assertEquals(9_007_199_254_740_993L, parsed.publicationRevision)
        assertEquals(
            Long.MAX_VALUE,
            assertNotNull(
                    parseCollaborationReadinessControl(
                        fields + ("publication_revision" to Long.MAX_VALUE.toString())
                    )
                )
                .publicationRevision,
        )
        assertEquals("CollaborationReadinessControl([redacted])", parsed.toString())
        for (name in fields.keys) assertNull(
            parseCollaborationReadinessControl(fields - name),
            name,
        )
        for (name in fields.keys) assertNull(
            parseCollaborationReadinessControl(fields + (name to "x".repeat(37))),
            name,
        )
        assertNull(parseCollaborationReadinessControl(fields + ("body" to "must not escape")))
        assertNull(
            parseCollaborationReadinessControl(
                (fields - "publication_revision") + ("schema_version" to "1")
            )
        )
        for (name in listOf("publication_revision", "published_at", "expires_at")) {
            for (bad in listOf("0", "-1", "01", "+1", "1.0", "9223372036854775808")) {
                assertNull(parseCollaborationReadinessControl(fields + (name to bad)), "$name:$bad")
            }
        }
    }

    @Test
    fun oldSourceClientOrReplacedParentCannotSelectAnotherScope() {
        val control = assertNotNull(parseCollaborationReadinessControl(fields))
        val parent =
            CollaborationNativeParent(
                "local",
                "https://example.test",
                control.accountId,
                control.nativeSessionId,
            )
        val owner =
            CollaborationOwner(
                parent.sessionId,
                parent.audience,
                parent.accountId,
                control.companyId,
                parent.nativeSessionId,
            )
        val signal = CollaborationReadinessSignal(owner, control.deviceId, control)
        assertEquals(
            CollaborationReadinessScope(parent, control.companyId, control.deviceId),
            collaborationReadinessScope(signal, parent),
        )
        for (other in
            listOf(
                parent.copy(sessionId = "replacement"),
                parent.copy(accountId = fields.getValue("company_id")),
                parent.copy(nativeSessionId = fields.getValue("device_id")),
                parent.copy(audience = "https://other.test"),
            )) {
            assertNull(collaborationReadinessScope(signal, other))
        }
        assertNull(
            collaborationReadinessScope(signal.copy(sourceDeviceId = control.eventId), parent)
        )
        assertNull(
            collaborationReadinessScope(
                signal.copy(sourceOwner = owner.copy(companyId = control.eventId)),
                parent,
            )
        )
    }

    @Test
    fun reorderedSignalsCoalesceAndAnOldCompletionCannotLoseNewerPendingWork() {
        val first = assertNotNull(retainCollaborationReadinessRevision(null, 3))
        assertEquals(CollaborationReadinessProgress(0, 3), first)
        assertEquals(first, retainCollaborationReadinessRevision(first, 1))
        val newer = assertNotNull(retainCollaborationReadinessRevision(first, 5))
        val oldCompletion = acknowledgeCollaborationReadinessRevision(newer, 3)
        assertEquals(CollaborationReadinessProgress(3, 5), oldCompletion)
        assertEquals(oldCompletion, retainCollaborationReadinessRevision(oldCompletion, 2))
        val done = acknowledgeCollaborationReadinessRevision(oldCompletion, 5)
        for (duplicate in listOf(1L, 3L, 4L, 5L)) assertNull(
            retainCollaborationReadinessRevision(done, duplicate)
        )
        assertEquals(
            CollaborationReadinessProgress(5, 6),
            retainCollaborationReadinessRevision(done, 6),
        )
        val last = CollaborationReadinessProgress(5, Long.MAX_VALUE)
        assertEquals(
            CollaborationReadinessProgress(Long.MAX_VALUE, Long.MAX_VALUE),
            acknowledgeCollaborationReadinessRevision(last, Long.MAX_VALUE),
        )
        assertFalse(validCollaborationReadinessProgress(CollaborationReadinessProgress(6, 5)))
    }
}
