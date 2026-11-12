package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingKeyDemotionPolicyTest {
    private fun room(index: Int) =
        CollaborationSpaceIncomingRoomDemand(
            id(index),
            id(100 + index),
            index.toLong(),
            "\$head-$index",
            CollaborationSpaceIncomingPhase.Pending,
        )

    private val first = room(1)
    private val second = room(2)
    private val base =
        CollaborationSpaceIncomingDemand(nextPosition = 3, rooms = listOf(first, second))

    @Test
    fun removingBlockedPrimaryWorkAlwaysPlansRecoveryInTheSameReplacement() {
        val result = next(base)
        assertEquals(listOf(second), result.rooms)
        assertEquals(CollaborationSpaceHistoryRecovery(id(900)), result.historyRecovery)
        assertEquals(base.nextPosition, result.nextPosition)
        assertEquals(listOf(first, second), base.rooms)
    }

    @Test
    fun anActiveRecoveryKeepsItsCursorRetryAndEvidenceWithANewerRequest() {
        val active =
            CollaborationSpaceHistoryRecovery(
                id(800),
                revision = 4,
                afterPartition = "history-before",
                moreHistoryPages = true,
                unresolved = true,
                retry = nextCollaborationSpaceIncomingRetry(null, 1_000),
            )
        val result = next(base.copy(historyRecovery = active))
        assertEquals(active.copy(requestedGeneration = id(900)), result.historyRecovery)
        assertEquals(listOf(second), result.rooms)
    }

    @Test
    fun aCompletedWaitingPassGetsAFreshGenerationForNewlyDemotedWork() {
        val completed =
            CollaborationSpaceHistoryRecovery(
                id(800),
                revision = 4,
                complete = true,
                unresolved = true,
            )
        assertEquals(
            CollaborationSpaceHistoryRecovery(id(900)),
            next(base.copy(historyRecovery = completed)).historyRecovery,
        )
    }

    @Test
    fun replayingCurrentOrRequestedRecoveryIdentityCannotDropPrimaryWork() {
        val active = CollaborationSpaceHistoryRecovery(id(800), requestedGeneration = id(900))
        for (generation in listOf(id(800), id(900))) assertIs<AppResult.Failed>(
            planCollaborationSpaceIncomingKeyDemotion(
                base.copy(historyRecovery = active),
                first,
                generation,
            )
        )
        assertIs<AppResult.Failed>(
            planCollaborationSpaceIncomingKeyDemotion(
                base.copy(
                    historyRecovery =
                        active.copy(revision = 1, requestedGeneration = null, complete = true)
                ),
                first,
                id(800),
            )
        )
    }

    @Test
    fun anAbsentOrReplacedTaskCannotCreateAnUnneededPass() {
        val absent = base.copy(rooms = listOf(second))
        assertEquals(
            AppResult.Success(absent),
            planCollaborationSpaceIncomingKeyDemotion(absent, first, id(900)),
        )
        val replaced = base.copy(rooms = listOf(first.copy(generation = id(500)), second))
        assertEquals(
            AppResult.Success(replaced),
            planCollaborationSpaceIncomingKeyDemotion(replaced, first, id(900)),
        )
    }

    @Test
    fun changedFactsUnderTheSameTaskGenerationCannotBeDemoted() {
        for (changed in
            listOf(
                first.copy(headEventId = "\$different"),
                first.copy(phase = CollaborationSpaceIncomingPhase.Provider),
                first.copy(retry = nextCollaborationSpaceIncomingRetry(null, 1_000)),
            )) {
            assertIs<AppResult.Failed>(
                planCollaborationSpaceIncomingKeyDemotion(
                    base.copy(rooms = listOf(changed, second)),
                    first,
                    id(900),
                )
            )
        }
    }

    @Test
    fun nonPendingAndMalformedTasksCannotBecomeRecoveryRequests() {
        for (captured in
            listOf(
                first.copy(phase = CollaborationSpaceIncomingPhase.Latest),
                first.copy(phase = CollaborationSpaceIncomingPhase.Provider),
                first.copy(generation = "bad"),
            )) assertIs<AppResult.Failed>(
            planCollaborationSpaceIncomingKeyDemotion(base, captured, id(900))
        )
        assertIs<AppResult.Failed>(
            planCollaborationSpaceIncomingKeyDemotion(base, first, "invalid")
        )
    }

    @Test
    fun aFullPrimaryQueueFreesCapacityWhileKeepingAllOtherRooms() {
        val rooms = (1..64).map(::room)
        val full = CollaborationSpaceIncomingDemand(nextPosition = 65, rooms = rooms)
        assertTrue(validCollaborationSpaceIncomingDemand(full))
        val result = next(full)
        assertEquals(rooms.drop(1), result.rooms)
        assertEquals(63, result.rooms.size)
        assertEquals(CollaborationSpaceHistoryRecovery(id(900)), result.historyRecovery)
    }

    @Test
    fun anOriginalTargetAndItsPublishedAliasKeepIndependentPrimaryWork() {
        val source =
            CollaborationSpaceIncomingSource("a".repeat(64), "@fixture:example.test", "DEVICE")
        val original =
            first.copy(
                target =
                    CollaborationSpaceIncomingTarget.Original(
                        CollaborationOriginalConversationPosition(id(600), id(1))
                    ),
                sourceId = source.storeId,
            )
        val alias = first.copy(generation = id(502), position = 2)
        val current =
            base.copy(
                rooms = listOf(original, alias),
                sources = listOf(CollaborationSpaceIncomingSourceProgress(source, "position-1")),
            )
        assertTrue(validCollaborationSpaceIncomingDemand(current))
        val result =
            assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                    planCollaborationSpaceIncomingKeyDemotion(current, original, id(900))
                )
                .value
        assertEquals(listOf(alias), result.rooms)
        assertEquals(CollaborationSpaceHistoryRecovery(id(900)), result.historyRecovery)
    }

    private fun next(value: CollaborationSpaceIncomingDemand): CollaborationSpaceIncomingDemand {
        assertTrue(validCollaborationSpaceIncomingDemand(value))
        return assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                planCollaborationSpaceIncomingKeyDemotion(value, first, id(900))
            )
            .value
    }

    private fun id(value: Int) = "b1000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
}
