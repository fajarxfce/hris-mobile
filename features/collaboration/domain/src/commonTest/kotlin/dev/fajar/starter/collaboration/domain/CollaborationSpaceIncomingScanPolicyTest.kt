package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingScanPolicyTest {
    @Test
    fun firstPageQueuesRoomsAndItsNextCursorTogether() {
        val f = SpaceIncomingScanFixture()
        val next = success(f)
        assertEquals(4L, next.nextPosition)
        assertEquals(List(4) { it.toLong() }, next.rooms.map { it.position })
        assertTrue(
            next.rooms.all {
                it.phase == CollaborationSpaceIncomingPhase.Latest && it.headEventId == null
            }
        )
        assertEquals(f.originalGeneration, next.scan!!.generation)
        assertEquals(f.after(4), next.scan.cursor!!.afterId)
        assertEquals(f.originalGeneration, next.rescanGeneration)
    }

    @Test
    fun discoveryDoesNotResetAnAlreadyQueuedPendingRoom() {
        val f = SpaceIncomingScanFixture()
        val old =
            success(f)
                .rooms
                .first()
                .copy(phase = CollaborationSpaceIncomingPhase.Pending, headEventId = "\$known")
        f.set(f.current().copy(nextPosition = 4, rooms = listOf(old)))
        val next = success(f, generations = List(3) { f.id(810 + it) })
        assertEquals(old, next.rooms.first())
        assertEquals(listOf(0L, 4L, 5L, 6L), next.rooms.map { it.position })
    }

    @Test
    fun finalPageClearsOnlyTheFinishedRescan() {
        val f = SpaceIncomingScanFixture()
        val next = success(f, f.page(position = null))
        assertNull(next.scan)
        assertNull(next.rescanGeneration)
        assertEquals(4, next.rooms.size)
    }

    @Test
    fun newerRescanSurvivesCompletionOfAnOlderActiveScan() {
        val f = SpaceIncomingScanFixture()
        f.set(success(f).copy(rescanGeneration = f.id(900)))
        val next = success(f, f.page(0, null), emptyList())
        assertNull(next.scan)
        assertEquals(f.id(900), next.rescanGeneration)
        assertEquals(f.current().rooms, next.rooms)
    }

    @Test
    fun appendsBehindTheCursorRetainOneFreshScanAfterTheFiniteBoundary() {
        val f = SpaceIncomingScanFixture()
        f.set(success(f, f.page(newer = true)))
        val next = success(f, f.page(0, null), emptyList())
        assertNull(next.scan)
        assertEquals(f.id(990), next.rescanGeneration)
    }

    @Test
    fun emptyRawPageWithContinuationStillAdvances() {
        val f = SpaceIncomingScanFixture()
        val next = success(f, f.page(0, 2), emptyList())
        assertTrue(next.rooms.isEmpty())
        assertEquals(f.after(2), next.scan!!.cursor!!.afterId)
        f.set(next)
        assertNull(success(f, f.page(0, null), emptyList()).scan)
    }

    @Test
    fun absentInventoryFinishesWithoutInventingARoom() {
        val f = SpaceIncomingScanFixture()
        val next =
            success(
                f,
                CollaborationSpaceRoomBindingPage(null, emptyList(), null, false),
                emptyList(),
            )
        assertEquals(CollaborationSpaceIncomingDemand(), next)
    }

    @Test
    fun fullOrInsufficientQueueCapacityCannotAdvanceTheScan() {
        val f = SpaceIncomingScanFixture()
        for (count in listOf(63, 64)) {
            f.set(
                CollaborationSpaceIncomingDemand(
                    count.toLong(),
                    List(count) {
                        CollaborationSpaceIncomingRoomDemand(
                            f.id(1000 + it),
                            f.id(2000 + it),
                            it.toLong(),
                            null,
                            CollaborationSpaceIncomingPhase.Pending,
                        )
                    },
                    f.originalGeneration,
                )
            )
            assertIs<AppResult.Failed>(plan(f, f.page()))
            assertEquals(count, f.current().rooms.size)
        }
    }

    @Test
    fun foreignBoundaryAndCursorNamespacesAreRejected() {
        val f = SpaceIncomingScanFixture()
        for (boundary in
            listOf(
                f.boundary.copy(audience = "https://other.example"),
                f.boundary.copy(accountId = f.id(999)),
                f.boundary.copy(spaceId = f.id(999)),
                f.boundary.copy(generation = "invalid"),
                f.boundary.copy(revision = -1),
            )) {
            val page =
                f.page()
                    .copy(
                        boundary = boundary,
                        next = CollaborationSpaceRoomBindingCursor(boundary, f.after(4)),
                    )
            assertIs<AppResult.Failed>(plan(f, page))
        }
    }

    @Test
    fun unchangedBackwardAndReplacedCursorsCannotRepeatTheSamePage() {
        val f = SpaceIncomingScanFixture()
        f.set(success(f))
        for (page in
            listOf(
                f.page(0, 4),
                f.page(0, 3),
                f.page(0, 5).copy(boundary = f.boundary.copy(generation = f.id(999))),
                f.page(0, 5).copy(next = CollaborationSpaceRoomBindingCursor(f.boundary, "bad")),
            )) {
            assertIs<AppResult.Failed>(plan(f, page, emptyList()))
        }
    }

    @Test
    fun duplicateRoomsAndMalformedBindingsCannotBecomeTasks() {
        val f = SpaceIncomingScanFixture()
        for (bindings in
            listOf(
                listOf(f.binding(1), f.binding(1)),
                listOf(f.binding(1).copy(conversation = f.binding(1).conversation.copy(id = "bad"))),
            )) {
            assertIs<AppResult.Failed>(plan(f, f.page().copy(bindings = bindings)))
        }
    }

    @Test
    fun newRoomGenerationsAndOrderingMustRemainValidAndUnique() {
        val f = SpaceIncomingScanFixture()
        for (ids in listOf(emptyList(), List(4) { "bad" }, List(4) { f.id(800) })) assertIs<
            AppResult.Failed
        >(
            plan(f, f.page(), ids)
        )
        f.set(f.current().copy(nextPosition = 9_007_199_254_740_991L))
        assertIs<AppResult.Failed>(plan(f, f.page()))
    }

    @Test
    fun activeScanCannotBeClearedByTheOldUnqualifiedRescanCompletion() {
        val f = SpaceIncomingScanFixture()
        f.set(success(f))
        assertIs<AppResult.Failed>(
            nextCollaborationSpaceIncomingDemand(
                f.current(),
                CollaborationSpaceIncomingDemandChange.CompleteRescan(f.originalGeneration),
            )
        )
    }

    @Test
    fun resultDoesNotRetainCallerOwnedMutableLists() {
        val f = SpaceIncomingScanFixture()
        val rooms = mutableListOf<CollaborationSpaceIncomingRoomDemand>()
        f.set(f.current().copy(rooms = rooms))
        val bindings = f.page().bindings.toMutableList()
        val next = success(f, f.page().copy(bindings = bindings))
        bindings.clear()
        rooms += next.rooms.first()
        assertEquals(4, next.rooms.size)
        assertEquals(4L, next.nextPosition)
    }

    private fun plan(
        f: SpaceIncomingScanFixture,
        page: CollaborationSpaceRoomBindingPage = f.page(),
        generations: List<String> = List(4) { f.id(800 + it) },
    ) = discoverCollaborationSpaceIncomingRooms(f.access, f.current(), page, generations, f.id(990))

    private fun success(
        f: SpaceIncomingScanFixture,
        page: CollaborationSpaceRoomBindingPage = f.page(),
        generations: List<String> = List(4) { f.id(800 + it) },
    ) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(plan(f, page, generations))
            .value
}
