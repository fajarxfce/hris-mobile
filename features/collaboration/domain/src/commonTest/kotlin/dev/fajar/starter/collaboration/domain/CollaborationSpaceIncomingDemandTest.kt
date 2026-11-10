package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingDemandTest {
    @Test
    fun duplicateHeadDoesNotRestartPendingRecoveryOrMoveItsFifoPosition() {
        val initial = request(empty(), 1)
        val pending = provider(initial, exhausted = true)
        assertEquals(pending, change(pending, requestChange(1, generation = 900)))
        assertEquals(CollaborationSpaceIncomingPhase.Pending, pending.rooms.single().phase)
    }

    @Test
    fun newerHeadSurvivesAnOlderAcquisitionAndCompletion() {
        val original = provider(request(empty(), 1), exhausted = true)
        val captured = original.rooms.single()
        val newer = change(original, requestChange(1, 900, "\$new"))
        assertEquals(CollaborationSpaceIncomingPhase.Latest, newer.rooms.single().phase)
        assertEquals(
            newer,
            change(newer, CollaborationSpaceIncomingDemandChange.Complete(captured)),
        )
        assertEquals(newer, change(newer, CollaborationSpaceIncomingDemandChange.Defer(captured)))
        val oldProvider = captured.copy(phase = CollaborationSpaceIncomingPhase.Latest)
        assertEquals(
            newer,
            change(
                newer,
                CollaborationSpaceIncomingDemandChange.ProviderAcquired(oldProvider, true),
            ),
        )
    }

    @Test
    fun deferredMissingKeysKeepPendingPhaseAndYieldToOtherRooms() {
        val first = provider(request(empty(), 1), exhausted = true)
        val queued = request(first, 2)
        val boundary = queued.nextPosition
        val deferred =
            change(queued, CollaborationSpaceIncomingDemandChange.Defer(queued.rooms.first()))
        assertEquals(listOf(id(2), id(1)), deferred.rooms.map { it.conversationId })
        val retry = deferred.rooms.last()
        assertEquals(CollaborationSpaceIncomingPhase.Pending, retry.phase)
        assertTrue(retry.position >= boundary)
        assertEquals(first.rooms.single().generation, retry.generation)
    }

    @Test
    fun finiteProviderContinuationPrecedesPendingCompletion() {
        val latest = request(empty(), 1)
        val provider = provider(latest, exhausted = false)
        assertEquals(CollaborationSpaceIncomingPhase.Provider, provider.rooms.single().phase)
        val pending = provider(provider, exhausted = true)
        assertEquals(CollaborationSpaceIncomingPhase.Pending, pending.rooms.single().phase)
        assertTrue(
            change(pending, CollaborationSpaceIncomingDemandChange.Complete(pending.rooms.single()))
                .rooms
                .isEmpty()
        )
        for (unfinished in listOf(latest, provider)) {
            assertIs<AppResult.Failed>(
                nextCollaborationSpaceIncomingDemand(
                    unfinished,
                    CollaborationSpaceIncomingDemandChange.Complete(unfinished.rooms.single()),
                )
            )
        }
        assertIs<AppResult.Failed>(
            nextCollaborationSpaceIncomingDemand(
                pending,
                CollaborationSpaceIncomingDemandChange.ProviderAcquired(
                    pending.rooms.single(),
                    true,
                ),
            )
        )
    }

    @Test
    fun changedFactsUnderTheSameGenerationCannotCompleteAClaim() {
        val state = provider(request(empty(), 1), exhausted = true)
        val room = state.rooms.single()
        for (wrong in listOf(room.copy(position = 1), room.copy(headEventId = "\$wrong"))) {
            assertIs<AppResult.Failed>(
                nextCollaborationSpaceIncomingDemand(
                    state,
                    CollaborationSpaceIncomingDemandChange.Complete(wrong),
                )
            )
        }
        assertIs<AppResult.Failed>(
            nextCollaborationSpaceIncomingDemand(
                state,
                CollaborationSpaceIncomingDemandChange.Defer(
                    room.copy(phase = CollaborationSpaceIncomingPhase.Provider)
                ),
            )
        )
    }

    @Test
    fun fullRoomQueueRetainsAnIndependentRescanInsteadOfDroppingTheWake() {
        val full = full()
        val overflow = request(full, 65)
        assertEquals(full.rooms, overflow.rooms)
        assertEquals(full.nextPosition, overflow.nextPosition)
        assertEquals(id(1065), overflow.rescanGeneration)
        assertEquals(overflow, request(overflow, 65))
    }

    @Test
    fun changingAnExistingRoomAtCapacityNeverEvictsAnotherRoom() {
        val full = full()
        val updated = change(full, requestChange(1, 9000, "\$new"))
        assertEquals(64, updated.rooms.size)
        assertNull(updated.rescanGeneration)
        assertEquals(full.rooms.drop(1), updated.rooms.dropLast(1))
        assertEquals(id(1), updated.rooms.last().conversationId)
    }

    @Test
    fun olderRescanCompletionCannotAcknowledgeNewOverflowOrRoomWork() {
        val initial = request(full(), 65)
        val newer = request(initial, 66)
        assertEquals(
            newer,
            change(newer, CollaborationSpaceIncomingDemandChange.CompleteRescan(id(1065))),
        )
        val completed =
            change(newer, CollaborationSpaceIncomingDemandChange.CompleteRescan(id(1066)))
        assertNull(completed.rescanGeneration)
        assertEquals(newer.rooms, completed.rooms)
    }

    @Test
    fun emptyQueueDoesNotImplicitlyCompleteAnOutstandingRescan() {
        val state = provider(request(empty(), 1), exhausted = true)
        val scan = change(state, CollaborationSpaceIncomingDemandChange.Rescan(id(8000)))
        val done =
            change(scan, CollaborationSpaceIncomingDemandChange.Complete(state.rooms.single()))
        assertTrue(done.rooms.isEmpty())
        assertEquals(id(8000), done.rescanGeneration)
    }

    @Test
    fun duplicateOrMalformedStoredCoordinatesAreRejectedBeforeAnyChange() {
        val two = request(request(empty(), 1), 2)
        val first = two.rooms.first()
        val second = two.rooms.last()
        val variants =
            listOf(
                two.copy(rooms = listOf(first, second.copy(conversationId = first.conversationId))),
                two.copy(rooms = listOf(first, second.copy(generation = first.generation))),
                two.copy(rooms = listOf(first, second.copy(position = first.position))),
                two.copy(rooms = two.rooms.reversed()),
                two.copy(nextPosition = 1),
                two.copy(nextPosition = -1),
                two.copy(rescanGeneration = "bad"),
                two.copy(rooms = listOf(first.copy(headEventId = "invalid"))),
                full()
                    .copy(
                        rooms =
                            full().rooms +
                                second.copy(
                                    conversationId = id(99),
                                    generation = id(9999),
                                    position = 64,
                                ),
                        nextPosition = 65,
                    ),
            )
        for (invalid in variants) {
            assertFalse(validCollaborationSpaceIncomingDemand(invalid))
            assertIs<AppResult.Failed>(
                nextCollaborationSpaceIncomingDemand(
                    invalid,
                    CollaborationSpaceIncomingDemandChange.Rescan(id(8000)),
                )
            )
        }
    }

    @Test
    fun requestCannotReuseAnotherRoomGenerationOrChangeItsOwnRetainedIntent() {
        val state = request(request(empty(), 1), 2)
        for (invalid in
            listOf(
                requestChange(1, 1002),
                requestChange(1, 1001, "\$different"),
                requestChange(1).copy(conversationId = "bad"),
                requestChange(1).copy(generation = "bad"),
                requestChange(1).copy(headEventId = "invalid"),
            )) {
            assertIs<AppResult.Failed>(nextCollaborationSpaceIncomingDemand(state, invalid))
        }
    }

    @Test
    fun newUnscopedScanRestartsLatestButReplayKeepsPendingPhase() {
        val unknown = change(empty(), requestChange(1, head = null))
        val pending = provider(unknown, exhausted = true)
        assertEquals(pending, change(pending, requestChange(1, head = null)))
        val newer = change(pending, requestChange(1, 9000, head = null))
        assertEquals(CollaborationSpaceIncomingPhase.Latest, newer.rooms.single().phase)
        assertEquals(id(9000), newer.rooms.single().generation)
    }

    @Test
    fun copiedSchedulingResultDoesNotAliasTheCallersMutableRoomList() {
        val source = request(empty(), 1)
        val mutable = source.rooms.toMutableList()
        val result = change(source.copy(rooms = mutable), requestChange(1))
        mutable.clear()
        assertEquals(source, result)
    }

    @Test
    fun exhaustedOrderingSpaceNeverOverflowsOrCorruptsExistingWork() {
        val state = request(empty(), 1).copy(nextPosition = 9_007_199_254_740_991L)
        assertTrue(validCollaborationSpaceIncomingDemand(state))
        assertIs<AppResult.Failed>(nextCollaborationSpaceIncomingDemand(state, requestChange(2)))
        assertIs<AppResult.Failed>(
            nextCollaborationSpaceIncomingDemand(
                state,
                CollaborationSpaceIncomingDemandChange.Defer(state.rooms.single()),
            )
        )
        val pending = provider(state, exhausted = true)
        assertTrue(
            change(pending, CollaborationSpaceIncomingDemandChange.Complete(pending.rooms.single()))
                .rooms
                .isEmpty()
        )
    }

    @Test
    fun aRepeatedCompletedCaptureCannotRemoveAnotherRoom() {
        val first = provider(request(empty(), 1), exhausted = true)
        val second = request(first, 2)
        val capture = first.rooms.single()
        val done = change(second, CollaborationSpaceIncomingDemandChange.Complete(capture))
        assertEquals(listOf(id(2)), done.rooms.map { it.conversationId })
        assertEquals(done, change(done, CollaborationSpaceIncomingDemandChange.Complete(capture)))
    }

    @Test
    fun diagnosticStringsNeverExposeRoomOrEventCoordinates() {
        val value = request(empty(), 1)
        for (text in listOf(value.toString(), value.rooms.single().toString())) {
            assertFalse(text.contains(id(1)))
            assertFalse(text.contains("\$head-1"))
            assertFalse(text.contains(id(1001)))
        }
    }

    private fun empty() = CollaborationSpaceIncomingDemand()

    private fun full(): CollaborationSpaceIncomingDemand =
        (1..64).fold(empty()) { state, room -> request(state, room) }

    private fun id(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"

    private fun requestChange(
        room: Int,
        generation: Int = room + 1000,
        head: String? = "\$head-$room",
    ) = CollaborationSpaceIncomingDemandChange.Request(id(room), id(generation), head)

    private fun request(state: CollaborationSpaceIncomingDemand, room: Int) =
        change(state, requestChange(room))

    private fun provider(state: CollaborationSpaceIncomingDemand, exhausted: Boolean) =
        change(
            state,
            CollaborationSpaceIncomingDemandChange.ProviderAcquired(state.rooms.first(), exhausted),
        )

    private fun change(
        state: CollaborationSpaceIncomingDemand,
        change: CollaborationSpaceIncomingDemandChange,
    ) =
        assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                nextCollaborationSpaceIncomingDemand(state, change)
            )
            .value
}
