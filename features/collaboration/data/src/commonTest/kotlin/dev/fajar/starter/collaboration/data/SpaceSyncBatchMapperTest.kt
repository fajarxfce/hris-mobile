package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.toSpaceSyncBatch
import dev.fajar.starter.collaboration.data.repositories.UnavailableCollaborationRuntimeRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest

class SpaceSyncBatchMapperTest {
    @Test
    fun exactResponsePositionsAndOriginalStoreIdentitySurviveLogicalRuntimeMapping() {
        val batch = map(raw())
        assertEquals(runtime, batch.runtime)
        assertEquals(source, batch.source)
        assertEquals("original-previous", batch.previousPosition)
        assertEquals("original-next", batch.nextPosition)
        assertEquals(
            listOf(
                CollaborationSpaceIncomingRoomHint(
                    "!room:matrix.test",
                    "\$last",
                    CollaborationSpaceIncomingMembership.Joined,
                    true,
                )
            ),
            batch.rooms,
        )
        assertFalse(batch.inventoryRequired)
    }

    @Test
    fun copiedPhysicalHintsRemainIndependentOfMutableNativeLists() {
        val ids = mutableListOf("\$first", "\$last")
        val rooms = mutableListOf(room().copy(eventIds = ids))
        val batch = map(raw().copy(rooms = rooms))
        ids.clear()
        rooms.clear()
        assertEquals("\$last", batch.rooms.single().headEventId)
        assertEquals(1, batch.rooms.size)
    }

    @Test
    fun unrepresentableHeadRetainsRoomAndExplicitInventoryLoss() {
        for (head in listOf("invalid", "\$" + "x".repeat(1024))) {
            val batch = map(raw().copy(rooms = listOf(room().copy(eventIds = listOf(head)))))
            assertNull(batch.rooms.single().headEventId)
            assertTrue(batch.inventoryRequired)
        }
    }

    @Test
    fun missingHeadAndLeftMembershipRemainPhysicalResolutionHints() {
        val batch =
            map(
                raw()
                    .copy(
                        rooms =
                            listOf(
                                room()
                                    .copy(
                                        membership = SdkSyncMembership.Left,
                                        eventIds = emptyList(),
                                    )
                            )
                    )
            )
        assertNull(batch.rooms.single().headEventId)
        assertEquals(CollaborationSpaceIncomingMembership.Left, batch.rooms.single().membership)
        assertFalse(batch.inventoryRequired)
    }

    @Test
    fun duplicateUnrepresentableAndOverflowRoomsNeverDisappearWithoutLoss() {
        for (rooms in
            listOf(
                listOf(room(), room()),
                listOf(room().copy(roomId = "!" + "x".repeat(255))),
                List(65) { room().copy(roomId = "!$it:matrix.test") },
            )) {
            val batch = map(raw().copy(rooms = rooms))
            assertTrue(batch.inventoryRequired)
            assertTrue(batch.rooms.size <= 64)
            assertEquals(batch.rooms.size, batch.rooms.map { it.roomId }.distinct().size)
        }
    }

    @Test
    fun explicitNativeLossAndOversizedEventCollectionsRequireInventory() {
        assertTrue(map(raw().copy(rescanRequired = true)).inventoryRequired)
        assertTrue(
            map(raw().copy(rooms = listOf(room().copy(eventIds = List(257) { "\$event-$it" }))))
                .inventoryRequired
        )
    }

    @Test
    fun invalidSourceOrResponsePositionFailsWithoutInventingACursor() {
        for (raw in
            listOf(
                raw().copy(previousBatch = ""),
                raw().copy(nextBatch = ""),
                raw().copy(nextBatch = "x".repeat(4097)),
            )) {
            assertIs<AppResult.Failed>(raw.toSpaceSyncBatch(runtime, source))
        }
        assertIs<AppResult.Failed>(
            raw().toSpaceSyncBatch(runtime, source.copy(storeId = runtime.operationId))
        )
    }

    @Test
    fun unsupportedHostCannotSubstituteAnInMemorySourceOrSuccessfulEmptyBatch() = runTest {
        val repository = UnavailableCollaborationRuntimeRepository()
        assertNull(repository.incomingSource(runtime))
        assertIs<AppResult.Failed>(repository.observeBatches(runtime).single())
    }

    private fun raw() = SdkSyncBatch("original-previous", "original-next", listOf(room()), false)

    private fun room() =
        SdkSyncRoomChange(
            "!room:matrix.test",
            SdkSyncMembership.Joined,
            listOf("\$first", "\$last"),
            true,
        )

    private fun map(raw: SdkSyncBatch) =
        assertIs<AppResult.Success<CollaborationSpaceSyncBatch>>(
                raw.toSpaceSyncBatch(runtime, source)
            )
            .value

    private fun id(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

    private val runtime =
        CollaborationSpaceRuntimeReady(
            CollaborationSpaceOwner(
                CollaborationNativeParent("local", "https://hris.example.test/", id(1), id(2)),
                id(3),
            ),
            id(4),
            id(5),
        )
    private val source =
        CollaborationSpaceIncomingSource("a".repeat(64), "@sender:matrix.test", "ORIGINAL")
}
