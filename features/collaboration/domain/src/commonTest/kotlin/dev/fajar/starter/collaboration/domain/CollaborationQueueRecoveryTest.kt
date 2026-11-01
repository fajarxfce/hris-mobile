package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationQueueRecoveryTest {
    private val device = CollaborationMessageDevice(id(1), id(2), id(3), "!room:example.test")

    @Test
    fun committedEnqueueIsRecoveredEvenWhenAnOlderInFlightSendDisappears() {
        val before = CollaborationMessageState.Staging(device, setOf("older", "still-queued"))
        assertEquals(
            CollaborationQueueRecovery.Found("original-transaction"),
            recoverCollaborationQueue(before, device, setOf("still-queued", "original-transaction")),
        )
        assertTrue(
            collaborationMessageTransition(
                before,
                CollaborationMessageState.Staged(device, "original-transaction"),
            )
        )
        assertFalse(
            collaborationMessageTransition(
                before,
                CollaborationMessageState.Staged(device, "older"),
            )
        )
    }

    @Test
    fun onlyCompleteSameDeviceSnapshotMayReportMissing() {
        val before = CollaborationMessageState.Staging(device, setOf("older"))
        assertEquals(
            CollaborationQueueRecovery.Missing,
            recoverCollaborationQueue(before, device, emptySet()),
        )
        for (changed in
            listOf(
                device.copy(nativeSessionId = id(4)),
                device.copy(deviceId = id(4)),
                device.copy(bootstrapOperationId = id(4)),
                device.copy(roomId = "!other:example.test"),
            )) assertEquals(
            CollaborationQueueRecovery.DifferentDevice,
            recoverCollaborationQueue(before, changed, emptySet()),
        )
    }

    @Test
    fun unknownMultipleTransactionsAndOversizedOrMalformedSnapshotStayAmbiguous() {
        val before = CollaborationMessageState.Staging(device, emptySet())
        for (snapshot in
            listOf(
                setOf("new-a", "new-b"),
                (1..129).map { "t$it" }.toSet(),
                setOf("bad\ntxn"),
            )) assertEquals(
            CollaborationQueueRecovery.Ambiguous,
            recoverCollaborationQueue(before, device, snapshot),
        )
    }

    @Test
    fun mappingCannotBeResetRetargetedOrAcknowledgedWithAReplacementTransaction() {
        val staged = CollaborationMessageState.Staged(device, "original")
        for (invalid in
            listOf(
                CollaborationMessageState.Queued,
                CollaborationMessageState.Staging(device, emptySet()),
                CollaborationMessageState.Staged(device, "replacement"),
                CollaborationMessageState.Sent(device, "replacement", "\$event"),
                CollaborationMessageState.Sent(device.copy(deviceId = id(4)), "original", "\$event"),
            )) assertFalse(collaborationMessageTransition(staged, invalid))
        val sent = CollaborationMessageState.Sent(device, "original", "\$event")
        assertTrue(collaborationMessageTransition(staged, sent))
        assertFalse(collaborationMessageTransition(sent, CollaborationMessageState.Queued))
    }

    @Test
    fun textKeepsMultilineContentButRejectsInvalidEncodingAndUnboundedInput() {
        assertTrue(validCollaborationText("  Hello\ncolleague\t🙂  "))
        for (invalid in
            listOf(
                "",
                " \n ",
                "a\u0000b",
                "\uD800",
                "🙂".repeat(8193),
                "界".repeat(11000),
            )) assertFalse(validCollaborationText(invalid))
        assertTrue(validCollaborationText("a".repeat(16384)))
    }

    private fun id(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
}
