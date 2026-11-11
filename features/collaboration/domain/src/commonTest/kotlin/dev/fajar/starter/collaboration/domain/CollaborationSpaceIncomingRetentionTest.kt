package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationSpaceIncomingRetentionTest {
    @Test
    fun physicalAndInventoryWorkRemainPendingAcrossStartupAndWarmResume() = runTest {
        for (inventoryOnly in listOf(false, true)) {
            val f = SpaceIncomingScanFixture()
            val retained =
                demand(f, if (inventoryOnly) 0 else 1).let {
                    if (inventoryOnly)
                        it.copy(
                            sources =
                                it.sources.map { source ->
                                    source.copy(inventoryGeneration = f.id(880))
                                }
                        )
                    else it
                }
            f.set(retained)
            val before = f.request.snapshot
            val read =
                assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(f.read(f.access)).value
            assertEquals(before, read.snapshot)
            assertTrue(read.roomsPending)
            assertFalse(read.scanPending)
            assertTrue(read.ready.isEmpty())
            for (reason in
                listOf(
                    CollaborationSpaceIncomingScanReason.Startup,
                    CollaborationSpaceIncomingScanReason.Resume,
                )) {
                assertEquals(AppResult.Success(true), f.requestScan(f.access, f.id(881), reason))
                assertEquals(before, f.request.snapshot)
                assertEquals(f.originalWake, f.request.wake)
            }
            assertEquals(2, f.request.schedules)
            assertEquals(0, f.request.writes)
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test
    fun cursorOnlyProgressDoesNotBecomePendingWorkOnWarmResume() = runTest {
        val f = SpaceIncomingScanFixture()
        f.set(demand(f, 0))
        f.request.wake = null
        val before = f.request.snapshot
        val read =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(f.read(f.access)).value
        assertFalse(read.roomsPending)
        assertFalse(read.scanPending)
        assertTrue(read.ready.isEmpty())
        assertEquals(
            AppResult.Success(true),
            f.requestScan(f.access, f.id(881), CollaborationSpaceIncomingScanReason.Resume),
        )
        assertEquals(before, f.request.snapshot)
        assertEquals(0, f.request.schedules)
        assertEquals(0, f.request.writes)
        assertNull(f.request.wake)
    }

    @Test
    fun bindingDiscoveryWaitsForPhysicalCapacityAndUsesOnlyTheReleasedSlot() = runTest {
        val f = SpaceIncomingScanFixture()
        val full = demand(f, 64).copy(rescanGeneration = f.originalGeneration)
        f.set(full)
        val before = f.request.snapshot
        val read =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(f.read(f.access)).value
        assertFalse(read.scanPending)
        assertTrue(read.roomsPending)
        assertEquals(AppResult.Success(false), f.discover(f.access))
        assertEquals(before, f.request.snapshot)
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.request.writes)
        val available = full.copy(physicalRooms = full.physicalRooms.drop(1))
        f.set(available)
        f.nextPage = f.page(count = 1, position = null)
        assertEquals(AppResult.Success(true), f.discover(f.access))
        assertEquals(1, f.requests.single().second)
        assertNull(f.requests.single().first)
        assertEquals(available.physicalRooms, f.current().physicalRooms)
        assertEquals(available.sources, f.current().sources)
        assertEquals(64L, f.current().rooms.single().position)
        assertNull(f.current().rescanGeneration)
        assertEquals(AppResult.Success(false), f.finish(f.access, f.originalWake))
    }

    @Test
    fun laterAdmissionMutationCannotEraseSourcesOrPhysicalRoomsFromALocalRead() = runTest {
        val f = SpaceIncomingScanFixture()
        val retained = demand(f, 1)
        val sources = retained.sources.toMutableList()
        val physical = retained.physicalRooms.toMutableList()
        f.set(retained.copy(sources = sources, physicalRooms = physical))
        f.request.base.hooks["demand"] = {
            f.request.base.directory.beforeAdmission = {
                sources.clear()
                physical.clear()
            }
        }
        val read =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(f.read(f.access)).value
        assertTrue(sources.isEmpty())
        assertTrue(physical.isEmpty())
        assertEquals(retained, read.snapshot!!.demand)
        assertTrue(read.roomsPending)
    }

    @Test
    fun sourceInventoryDoesNotGetClearedByFinishingTheIndependentBindingScan() = runTest {
        val f = SpaceIncomingScanFixture()
        val retained =
            demand(f, 0).let { value ->
                value.copy(
                    rescanGeneration = f.originalGeneration,
                    sources = value.sources.map { it.copy(inventoryGeneration = f.id(880)) },
                )
            }
        f.set(retained)
        f.nextPage = f.page(count = 0, position = null)
        assertEquals(AppResult.Success(true), f.discover(f.access))
        assertNull(f.current().rescanGeneration)
        assertNull(f.current().scan)
        assertEquals(retained.sources, f.current().sources)
        assertEquals(AppResult.Success(false), f.finish(f.access, f.originalWake))
        assertEquals(f.originalWake, f.request.wake)
    }

    private fun demand(f: SpaceIncomingScanFixture, count: Int): CollaborationSpaceIncomingDemand {
        val source =
            CollaborationSpaceIncomingSource("b".repeat(64), "@original:proof.invalid", "ORIGINAL")
        return CollaborationSpaceIncomingDemand(
            nextPosition = count.toLong(),
            sources = listOf(CollaborationSpaceIncomingSourceProgress(source, "private-position")),
            physicalRooms =
                (0 until count).map { index ->
                    CollaborationSpaceIncomingPhysicalRoomDemand(
                        source.storeId,
                        "!physical-" + index + ":proof.invalid",
                        f.id(2000 + index),
                        index.toLong(),
                        "$" + "head",
                        CollaborationSpaceIncomingMembership.Joined,
                        false,
                    )
                },
        )
    }
}
