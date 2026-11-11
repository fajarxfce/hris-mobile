package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingSyncPolicyTest {
    @Test
    fun physicalAndLogicalRoomsShareOneCapacityAndFifoNamespace() {
        val sources = listOf(progress())
        val physical = (0 until 64).map { physical(it) }
        val full = CollaborationSpaceIncomingDemand(64, sources = sources, physicalRooms = physical)
        assertTrue(validCollaborationSpaceIncomingDemand(full))
        val rejected = full.copy(rooms = listOf(logical(64)), nextPosition = 65)
        assertFalse(validCollaborationSpaceIncomingDemand(rejected))
        val requested =
            assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                    nextCollaborationSpaceIncomingDemand(
                        full,
                        CollaborationSpaceIncomingDemandChange.Request(
                            id(900),
                            id(901),
                            "$" + "next",
                        ),
                    )
                )
                .value
        assertEquals(full.physicalRooms, requested.physicalRooms)
        assertEquals(full.sources, requested.sources)
        assertTrue(requested.rooms.isEmpty())
        assertEquals(id(901), requested.rescanGeneration)
        val mixed =
            CollaborationSpaceIncomingDemand(
                2,
                rooms = listOf(logical(0)),
                sources = sources,
                physicalRooms = listOf(physical(1)),
            )
        assertTrue(validCollaborationSpaceIncomingDemand(mixed))
        assertFalse(
            validCollaborationSpaceIncomingDemand(
                mixed.copy(physicalRooms = listOf(physical(1).copy(position = 0)))
            )
        )
        assertFalse(
            validCollaborationSpaceIncomingDemand(
                mixed.copy(
                    physicalRooms = listOf(physical(1).copy(generation = logical(0).generation))
                )
            )
        )
    }

    @Test
    fun sourceReferencesAndWholeSourceInventoryAreValidatedBeforeAChange() {
        val value =
            CollaborationSpaceIncomingDemand(
                1,
                sources = listOf(progress()),
                physicalRooms = listOf(physical(0)),
            )
        val invalid =
            listOf(
                value.copy(sources = emptyList()),
                value.copy(sources = listOf(progress(), progress())),
                value.copy(
                    sources =
                        (0..64).map {
                            progress().copy(source = source().copy(storeId = storeId(1000 + it)))
                        }
                ),
                value.copy(physicalRooms = listOf(physical(0).copy(sourceId = storeId(999)))),
                value.copy(
                    physicalRooms = emptyList(),
                    rooms = listOf(logical(0).copy(sourceId = storeId(999))),
                ),
                value.copy(sources = listOf(progress().copy(position = ""))),
                value.copy(sources = listOf(progress().copy(inventoryGeneration = "invalid"))),
            )
        for (bad in invalid) {
            assertFalse(validCollaborationSpaceIncomingDemand(bad))
            assertIs<AppResult.Failed>(
                nextCollaborationSpaceIncomingDemand(
                    bad,
                    CollaborationSpaceIncomingDemandChange.Rescan(id(950)),
                )
            )
        }
    }

    @Test
    fun samePhysicalRoomFromDistinctStoresDoesNotEraseEitherSourcesWork() {
        val other = source().copy(storeId = storeId(701))
        val value =
            CollaborationSpaceIncomingDemand(
                2,
                sources = listOf(progress(), progress().copy(source = other)),
                physicalRooms =
                    listOf(
                        physical(0),
                        physical(1).copy(sourceId = other.storeId, roomId = physical(0).roomId),
                    ),
            )
        assertTrue(validCollaborationSpaceIncomingDemand(value))
        assertFalse(
            validCollaborationSpaceIncomingDemand(
                value.copy(
                    physicalRooms = value.physicalRooms.map { it.copy(sourceId = source().storeId) }
                )
            )
        )
    }

    @Test
    fun changingKnownWorkPreservesItsSourceAndDetachesTheOtherMutableInventories() {
        val sources = mutableListOf(progress())
        val physicalEntries = mutableListOf(physical(1))
        val value =
            CollaborationSpaceIncomingDemand(
                2,
                rooms = listOf(logical(0)),
                sources = sources,
                physicalRooms = physicalEntries,
            )
        val updated =
            assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                    nextCollaborationSpaceIncomingDemand(
                        value,
                        CollaborationSpaceIncomingDemandChange.Request(
                            assertIs<CollaborationSpaceIncomingTarget.Published>(logical(0).target)
                                .conversationId,
                            id(951),
                            "$" + "changed",
                        ),
                    )
                )
                .value
        sources.clear()
        physicalEntries.clear()
        assertEquals(source().storeId, updated.rooms.single().sourceId)
        assertEquals(listOf(progress()), updated.sources)
        assertEquals(listOf(physical(1)), updated.physicalRooms)
        assertTrue(validCollaborationSpaceIncomingDemand(updated))
    }

    @Test
    fun sourceIdentityUsesTheCompleteCanonicalOriginalStoreHash() {
        assertTrue(validCollaborationSpaceIncomingSource(source()))
        assertTrue(validCollaborationSpaceIncomingSourceId("abcdef0123456789".repeat(4)))
        for (invalid in
            listOf(id(700), "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertFalse(validCollaborationSpaceIncomingSourceId(invalid))
            assertFalse(validCollaborationSpaceIncomingSource(source().copy(storeId = invalid)))
        }
    }

    @Test
    fun opaquePositionsUseExactUtf8BoundsAndDiagnosticsExposeNoIdentifiers() {
        assertTrue(validCollaborationSpaceIncomingPosition("\u0000".repeat(4096)))
        assertTrue(validCollaborationSpaceIncomingPosition("漢".repeat(1365)))
        assertFalse(validCollaborationSpaceIncomingPosition("漢".repeat(1366)))
        assertFalse(validCollaborationSpaceIncomingPosition("a".repeat(4097)))
        assertFalse(validCollaborationSpaceIncomingPosition("\uD800"))
        for (value in listOf(source().toString(), progress().toString(), physical(0).toString())) {
            assertTrue("redacted" in value)
            assertFalse("proof.invalid" in value)
            assertFalse("private-position" in value)
        }
    }

    private fun source() =
        CollaborationSpaceIncomingSource(storeId(700), "@original:proof.invalid", "ORIGINAL")

    private fun progress() = CollaborationSpaceIncomingSourceProgress(source(), "private-position")

    private fun physical(n: Int) =
        CollaborationSpaceIncomingPhysicalRoomDemand(
            source().storeId,
            "!room-" + n + ":proof.invalid",
            id(100 + n),
            n.toLong(),
            "$" + "head",
            CollaborationSpaceIncomingMembership.Joined,
            false,
        )

    private fun logical(n: Int) =
        CollaborationSpaceIncomingRoomDemand(
            id(200 + n),
            id(300 + n),
            n.toLong(),
            "$" + "head",
            CollaborationSpaceIncomingPhase.Latest,
            sourceId = source().storeId,
        )

    private fun storeId(n: Int) = n.toString(16).padStart(64, '0')

    private fun id(n: Int) = "40000000-0000-4000-8000-" + n.toString().padStart(12, '0')
}
