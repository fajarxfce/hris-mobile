package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.CollaborationPushFixture.Companion.fields
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationPushPriorityPolicyTest {
    private val hint = requireNotNull(parseCollaborationPushSignal(fields(true))).hint
    private val now = hint.issuedAt + 10

    @Test
    fun newestRevisionKeepsItsQueueSlotAndOlderOrConflictingPacketsCannotReplaceIt() {
        val other = hint.copy(companyId = "66666666-6666-4666-8666-666666666666")
        val newer =
            hint.copy(
                revision = Long.MAX_VALUE,
                eventId = other.companyId,
                roomId = "!new:example.test",
            )
        val queue = listOf(other, hint)
        assertEquals(listOf(other, newer), nextCollaborationPushHints(queue, newer, now))
        assertEquals(
            listOf(other, newer),
            nextCollaborationPushHints(listOf(other, newer), hint, now),
        )
        for (changed in
            listOf(
                hint.copy(eventId = other.companyId),
                hint.copy(roomId = "!changed"),
                hint.copy(matrixEventId = "\$changed"),
            )) assertEquals(queue, nextCollaborationPushHints(queue, changed, now))
    }

    @Test
    fun producerRetryMayRefreshTimesOnlyForTheSameRevisionAndProviderPair() {
        val retry = hint.copy(issuedAt = now, expiresAt = hint.expiresAt + 10)
        assertEquals(listOf(retry), nextCollaborationPushHints(listOf(hint), retry, now))
        assertEquals(listOf(retry), nextCollaborationPushHints(listOf(retry), hint, now))
        assertEquals(listOf(retry), nextCollaborationPushHints(listOf(retry), retry, now))
        assertEquals(
            listOf(hint),
            nextCollaborationPushHints(
                listOf(hint),
                retry.copy(expiresAt = hint.expiresAt - 1),
                now,
            ),
        )
    }

    @Test
    fun ttlControlsPriorityOnlyAndNewDeviceHasIndependentRevision() {
        val retained = listOf(hint)
        assertEquals(
            retained,
            nextCollaborationPushHints(retained, hint.copy(issuedAt = now + 1), now),
        )
        assertEquals(emptyList(), nextCollaborationPushHints(retained, hint, hint.expiresAt))
        val other = hint.copy(deviceId = "66666666-6666-4666-8666-666666666666", revision = 1)
        assertEquals(listOf(hint, other), nextCollaborationPushHints(retained, other, now))
    }

    @Test
    fun capacityDropsOnlyOldestPriorityAndCopiesTheCallersList() {
        val original =
            (1..16)
                .map {
                    hint.copy(
                        companyId = "a1000000-0000-4000-8000-" + it.toString().padStart(12, '0')
                    )
                }
                .toMutableList()
        val next = nextCollaborationPushHints(original, hint, now)
        assertEquals(16, next.size)
        assertEquals(original.drop(1) + hint, next)
        original.clear()
        assertEquals(16, next.size)
    }

    @Test
    fun malformedOrDuplicateRecordsCannotBecomeAnEncryptedQueue() {
        assertFalse(validCollaborationPushHints(List(17) { hint }))
        assertFalse(validCollaborationPushHints(listOf(hint, hint)))
        for (bad in
            listOf(
                hint.copy(revision = 0),
                hint.copy(companyId = "bad"),
                hint.copy(roomId = null),
                hint.copy(expiresAt = hint.issuedAt),
                hint.copy(roomId = "!unicodeé"),
                hint.copy(matrixEventId = "\$unicodeé"),
            )) {
            assertFalse(validCollaborationPushHint(bad))
            assertFailsWith<IllegalArgumentException> {
                nextCollaborationPushHints(emptyList(), bad, now)
            }
        }
        assertEquals("CollaborationPushHint([redacted])", hint.toString())
    }

    @Test
    fun parserCapturesMetadataBeforeMutablePacketChangesAndAcceptsLargestLong() {
        val packet = (fields(true) + ("revision" to Long.MAX_VALUE.toString())).toMutableMap()
        val parsed = assertNotNull(parseCollaborationPushSignal(packet))
        packet.clear()
        assertEquals(Long.MAX_VALUE, parsed.hint.revision)
        assertEquals(hint.roomId, parsed.hint.roomId)
        assertEquals(hint.companyId, parsed.hint.companyId)
        assertNull(parseCollaborationPushSignal(fields(true) + ("matrixRoomId" to "!unicodeé")))
    }
}
