package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingScanWritePolicyTest {
    private val source = CollaborationSpaceIncomingSource("1".repeat(64), "@source:test", "DEVICE")
    private val lost = AppResult.Failed(Failure(FailureKind.Storage, "Fictional lost reply"))

    @Test
    fun exactPageConfirmsDespiteLostReplyAndIndependentProducerWork() {
        val f = SpaceIncomingScanFixture()
        val before = initial(f)
        val next = page(f, before)
        val physical =
            CollaborationSpaceIncomingPhysicalRoomDemand(
                source.storeId,
                "!new:test",
                f.id(80),
                next.nextPosition,
                "\$new",
                CollaborationSpaceIncomingMembership.Joined,
                true,
            )
        val later =
            next.copy(
                nextPosition = next.nextPosition + 1,
                physicalRooms = listOf(physical),
                sources =
                    listOf(
                        next.sources
                            .single()
                            .copy(position = "later", inventoryGeneration = f.id(81))
                    ),
            )
        assertEquals(AppResult.Success(true), confirm(f, before, next, later, lost))
    }

    @Test
    fun completedScanConfirmsWhilePreservingALaterRequest() {
        val f = SpaceIncomingScanFixture()
        val before = page(f, initial(f))
        val next = page(f, before, end = true)
        assertNull(next.scan)
        assertNull(next.rescanGeneration)
        val later = next.copy(rescanGeneration = f.id(90))
        assertEquals(AppResult.Success(true), confirm(f, before, next, later, lost))
        assertEquals(f.id(90), later.rescanGeneration)
    }

    @Test
    fun knownCasMissWithUnchangedCursorRequestsShortRetry() {
        val f = SpaceIncomingScanFixture()
        val before = initial(f)
        val next = page(f, before)
        val later = before.copy(rescanGeneration = f.id(90))
        val result =
            assertIs<AppResult.Failed>(confirm(f, before, next, later, AppResult.Success(null)))
        assertTrue(isCollaborationSpaceIncomingQueueContention(result.failure))
        assertEquals(1L, result.failure.retryAfterSeconds)
        assertEquals(lost, confirm(f, before, next, later, lost))
    }

    @Test
    fun oldRequestStillPresentCannotWitnessAnUncommittedEmptyScan() {
        val f = SpaceIncomingScanFixture()
        val before = initial(f)
        val next = page(f, before, end = true)
        assertNull(next.scan)
        assertIs<AppResult.Failed>(confirm(f, before, next, before, lost))
        assertTrue(
            isCollaborationSpaceIncomingQueueContention(
                assertIs<AppResult.Failed>(
                        confirm(f, before, next, before, AppResult.Success(null))
                    )
                    .failure
            )
        )
    }

    @Test
    fun cursorWithoutEveryExactNewTaskCannotConfirmThePage() {
        val f = SpaceIncomingScanFixture()
        val before = initial(f)
        val next = page(f, before)
        for (later in
            listOf(
                next.copy(rooms = next.rooms.dropLast(1)),
                next.copy(
                    rooms =
                        next.rooms.mapIndexed { i, room ->
                            if (i == 0) room.copy(generation = f.id(90)) else room
                        }
                ),
            )) {
            assertEquals(lost, confirm(f, before, next, later, lost))
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(
                        confirm(f, before, next, later, AppResult.Success(snapshot(f, next)))
                    )
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun changedSourceIdentityOrErasedInventoryCannotConfirmThePage() {
        val f = SpaceIncomingScanFixture()
        val before = initial(f)
        val next = page(f, before)
        for (progress in
            listOf(
                next.sources.single().copy(source = source.copy(matrixDeviceId = "OTHER")),
                next.sources.single().copy(inventoryGeneration = null),
            )) {
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(
                        confirm(
                            f,
                            before,
                            next,
                            next.copy(sources = listOf(progress)),
                            AppResult.Success(snapshot(f, next)),
                        )
                    )
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun foreignOrMalformedReadbackCannotTurnAReplyIntoAdmission() {
        val f = SpaceIncomingScanFixture()
        val before = initial(f)
        val next = page(f, before)
        val foreign =
            next.copy(
                scan =
                    next.scan!!.copy(
                        cursor =
                            next.scan.cursor!!.copy(
                                boundary = f.boundary.copy(accountId = f.id(90))
                            )
                    )
            )
        for (later in listOf(foreign, next.copy(nextPosition = 0))) {
            assertIs<AppResult.Failed>(
                confirm(f, before, next, later, AppResult.Success(snapshot(f, next)))
            )
        }
        assertIs<AppResult.Failed>(
            confirmCollaborationSpaceIncomingScanWrite(
                f.access,
                before,
                next,
                null,
                AppResult.Success(snapshot(f, next)),
            )
        )
    }

    @Test
    fun pagePlanCannotErasePreviouslyQueuedRooms() {
        val f = SpaceIncomingScanFixture()
        val before = page(f, initial(f))
        val next = page(f, before, end = true).copy(rooms = emptyList())
        assertIs<AppResult.Failed>(
            confirm(f, before, next, next, AppResult.Success(snapshot(f, next)))
        )
    }

    private fun initial(f: SpaceIncomingScanFixture) =
        f.current()
            .copy(
                sources =
                    listOf(CollaborationSpaceIncomingSourceProgress(source, "position", f.id(70)))
            )

    private fun page(
        f: SpaceIncomingScanFixture,
        previous: CollaborationSpaceIncomingDemand,
        end: Boolean = false,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                discoverCollaborationSpaceIncomingRooms(
                    f.access,
                    previous,
                    if (end) f.page(0, null) else f.page(),
                    if (end) emptyList() else List(4) { f.id(100 + it) },
                    f.id(91),
                )
            )
            .value

    private fun snapshot(f: SpaceIncomingScanFixture, demand: CollaborationSpaceIncomingDemand) =
        CollaborationSpaceIncomingDemandSnapshot(f.id(99), demand)

    private fun confirm(
        f: SpaceIncomingScanFixture,
        previous: CollaborationSpaceIncomingDemand,
        next: CollaborationSpaceIncomingDemand,
        retained: CollaborationSpaceIncomingDemand,
        written: AppResult<CollaborationSpaceIncomingDemandSnapshot?>,
    ) =
        confirmCollaborationSpaceIncomingScanWrite(
            f.access,
            previous,
            next,
            snapshot(f, retained),
            written,
        )
}
