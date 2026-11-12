package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.captureCollaborationSpaceRoomKeyBatch
import kotlin.test.*

class CollaborationSpaceRoomKeyBatchPolicyTest {
    @Test
    fun capturePreservesExactRuntimeSourceAndReceipt() {
        assertEquals(batch, captureCollaborationSpaceRoomKeyBatch(batch))
    }

    @Test
    fun mutableInputCannotChangeTheCapturedRoomSet() {
        val rooms = mutableListOf("!first:test")
        val captured =
            assertNotNull(captureCollaborationSpaceRoomKeyBatch(batch.copy(roomIds = rooms)))
        rooms.clear()
        assertEquals(listOf("!first:test"), captured.roomIds)
    }

    @Test
    fun emptyHintsRequireExplicitReconciliation() {
        assertNull(captureCollaborationSpaceRoomKeyBatch(batch.copy(roomIds = emptyList())))
        assertNotNull(
            captureCollaborationSpaceRoomKeyBatch(
                batch.copy(roomIds = emptyList(), reconcileRequired = true)
            )
        )
    }

    @Test
    fun capacityIsBoundedBeforeCopyingInputs() {
        assertNotNull(
            captureCollaborationSpaceRoomKeyBatch(batch.copy(roomIds = List(64) { "!$it:test" }))
        )
        assertNull(
            captureCollaborationSpaceRoomKeyBatch(batch.copy(roomIds = List(65) { "!$it:test" }))
        )
    }

    @Test
    fun duplicateDomainLocatorsAreRejected() {
        assertNull(
            captureCollaborationSpaceRoomKeyBatch(
                batch.copy(roomIds = listOf("!same:test", "!same:test"))
            )
        )
    }

    @Test
    fun unrepresentableRoomIdentityCannotPassDomainCapture() {
        for (room in listOf("", "plain", "!bad\n:test", "!" + "a".repeat(1024))) assertNull(
            captureCollaborationSpaceRoomKeyBatch(batch.copy(roomIds = listOf(room)))
        )
    }

    @Test
    fun invalidReceiptIsNotInventedOrReplaced() {
        for (receipt in listOf("", "callback", "a".repeat(1024))) assertNull(
            captureCollaborationSpaceRoomKeyBatch(batch.copy(receiptId = receipt))
        )
    }

    @Test
    fun sameRoomsWithANewReceiptRemainADifferentOccurrence() {
        val next =
            assertNotNull(captureCollaborationSpaceRoomKeyBatch(batch.copy(receiptId = id(7))))
        assertNotEquals(batch, next)
        assertEquals(batch.roomIds, next.roomIds)
    }

    @Test
    fun malformedPhysicalSourceCannotBeCaptured() {
        for (source in
            listOf(
                batch.source.copy(storeId = "bad"),
                batch.source.copy(matrixUserId = "bad"),
                batch.source.copy(matrixDeviceId = ""),
            )) assertNull(captureCollaborationSpaceRoomKeyBatch(batch.copy(source = source)))
    }

    @Test
    fun malformedLogicalRuntimeCannotBeCaptured() {
        for (runtime in
            listOf(
                batch.runtime.copy(deviceId = "bad"),
                batch.runtime.copy(operationId = "bad"),
                batch.runtime.copy(owner = batch.runtime.owner.copy(spaceId = "bad")),
            )) assertNull(captureCollaborationSpaceRoomKeyBatch(batch.copy(runtime = runtime)))
    }

    @Test
    fun reconciliationSurvivesWhenSomeRoomIdsAreKnown() {
        assertTrue(
            assertNotNull(
                    captureCollaborationSpaceRoomKeyBatch(batch.copy(reconcileRequired = true))
                )
                .reconcileRequired
        )
    }

    @Test
    fun diagnosticsNeverExposeRoomIdsOrReceipts() {
        assertEquals("CollaborationSpaceRoomKeyBatch([redacted])", batch.toString())
    }

    private fun id(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    private val batch =
        CollaborationSpaceRoomKeyBatch(
            CollaborationSpaceRuntimeReady(
                CollaborationSpaceOwner(
                    CollaborationNativeParent("local", "https://hris.example.test/", id(1), id(2)),
                    id(3),
                ),
                id(4),
                id(5),
            ),
            CollaborationSpaceIncomingSource("a".repeat(64), "@sender:test", "ORIGINAL"),
            id(6),
            listOf("!room:test"),
            false,
        )
}
