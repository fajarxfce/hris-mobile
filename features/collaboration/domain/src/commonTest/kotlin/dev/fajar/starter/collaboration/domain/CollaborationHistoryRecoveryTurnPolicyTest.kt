package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationHistoryRecoveryTurnPolicyTest {
    private val active = CollaborationHistoryRecoveryPosition(id(1), 40)
    private val base =
        CollaborationHistoryProgress(
            id(99),
            4,
            "provider-next",
            40,
            CollaborationIncomingHistoryPosition("provider-head", null),
            recovery = active,
        )

    @Test
    fun firstRequestCapturesTheExistingReferenceBoundary() {
        assertEquals(
            active,
            planCollaborationHistoryRecoveryRequest(base.copy(recovery = null), id(1)),
        )
    }

    @Test
    fun replayingActiveGenerationKeepsCursorAndUnresolvedState() {
        val progress = base.copy(recovery = active.copy(after = "cursor-15", unresolved = true))
        assertEquals(
            progress.recovery,
            planCollaborationHistoryRecoveryRequest(progress.copy(references = 80), id(1)),
        )
    }

    @Test
    fun replayingRequestedGenerationDoesNotResetTheActivePass() {
        val progress =
            base.copy(recovery = active.copy(after = "cursor-15", requestedGeneration = id(2)))
        assertEquals(progress.recovery, planCollaborationHistoryRecoveryRequest(progress, id(2)))
    }

    @Test
    fun laterRequestsCoalesceWithoutRewindingAnUnfinishedBoundary() {
        var progress = base.copy(recovery = active.copy(after = "cursor-15", unresolved = true))
        for (generation in 2..10) {
            val requested = planCollaborationHistoryRecoveryRequest(progress, id(generation))!!
            assertEquals(
                active.copy(
                    after = "cursor-15",
                    unresolved = true,
                    requestedGeneration = id(generation),
                ),
                requested,
            )
            progress = progress.copy(recovery = requested)
        }
    }

    @Test
    fun completedCurrentGenerationStaysVisitedDespiteNewPrimaryReferences() {
        val completed = active.copy(complete = true, unresolved = true)
        assertEquals(
            completed,
            planCollaborationHistoryRecoveryRequest(
                base.copy(references = 80, recovery = completed),
                id(1),
            ),
        )
    }

    @Test
    fun completedOldGenerationStartsAFreshFiniteAttempt() {
        assertEquals(
            CollaborationHistoryRecoveryPosition(id(2), 80),
            planCollaborationHistoryRecoveryRequest(
                base.copy(
                    references = 80,
                    recovery = active.copy(complete = true, unresolved = true),
                ),
                id(2),
            ),
        )
    }

    @Test
    fun emptyPublishedHistoryCompletesAnEmptyAttempt() {
        val empty = base.copy(references = 0, recovery = null)
        assertEquals(
            CollaborationHistoryRecoveryPosition(id(1), 0, complete = true),
            planCollaborationHistoryRecoveryRequest(empty, id(1)),
        )
    }

    @Test
    fun requestRejectsInvalidIdentityAndNonIncomingHistory() {
        for (generation in
            listOf("", "invalid", "00000000-0000-0000-0000-000000000000")) assertNull(
            planCollaborationHistoryRecoveryRequest(base, generation)
        )
        assertNull(
            planCollaborationHistoryRecoveryRequest(
                base.copy(incoming = null, recovery = null),
                id(1),
            )
        )
        assertNull(planCollaborationHistoryRecoveryRequest(base.copy(references = -1), id(1)))
    }

    @Test
    fun pageIsTruncatedAtTheOriginalBoundaryAfterPrimaryAppends() {
        val progress =
            base.copy(references = 80, recovery = active.copy(through = 20, after = "cursor-15"))
        val bounded = boundedCollaborationHistoryRecoveryReferences(page(progress, 16L..31L))!!
        assertEquals((16L..19L).toList(), bounded.events.map { it.sequence })
        assertNull(bounded.after)
    }

    @Test
    fun pendingRowsBeyondTheBoundaryProduceAnEmptyFiniteEnd() {
        val progress = base.copy(references = 80)
        val bounded = boundedCollaborationHistoryRecoveryReferences(page(progress, 40L..55L))!!
        assertTrue(bounded.events.isEmpty())
        assertNull(bounded.after)
    }

    @Test
    fun sparseUnresolvedReferencesKeepTheirOriginalSequence() {
        val source = page(base, listOf(2L, 19L, 38L), false)
        assertEquals(source, boundedCollaborationHistoryRecoveryReferences(source))
    }

    @Test
    fun completedMissingOrMalformedPagesAreRejectedBeforeAdvancement() {
        assertNull(
            boundedCollaborationHistoryRecoveryReferences(
                page(base.copy(recovery = active.copy(complete = true)))
            )
        )
        assertNull(boundedCollaborationHistoryRecoveryReferences(page().copy(progress = null)))
        assertNull(boundedCollaborationHistoryRecoveryReferences(page(), 17))
        assertNull(
            boundedCollaborationHistoryRecoveryReferences(
                page().copy(events = listOf(reference(1), reference(1))),
                2,
            )
        )
        assertNull(
            boundedCollaborationHistoryRecoveryReferences(
                page().copy(events = listOf(reference(1).copy(resolved = true)), after = null)
            )
        )
    }

    @Test
    fun advancePreservesNewRequestedGenerationAndIndependentPrimaryProgress() {
        val current =
            base.copy(
                revision = 9,
                references = 80,
                next = "later-provider",
                incoming = CollaborationIncomingHistoryPosition("later-head", "older-wave"),
                recovery = active.copy(requestedGeneration = id(3)),
            )
        assertEquals(
            active.copy(after = "cursor-15", unresolved = true, requestedGeneration = id(3)),
            planCollaborationHistoryRecoveryAdvance(current, page(), 1),
        )
    }

    @Test
    fun finalPagePromotesTheLatestRequestAtTheCurrentReferenceBoundary() {
        val expected =
            base.copy(recovery = active.copy(after = "cursor-31", requestedGeneration = id(2)))
        val current =
            expected.copy(
                revision = 8,
                references = 80,
                recovery = expected.recovery!!.copy(requestedGeneration = id(3)),
            )
        assertEquals(
            CollaborationHistoryRecoveryPosition(id(3), 80),
            planCollaborationHistoryRecoveryAdvance(current, page(expected, 32L..39L, false), 8),
        )
    }

    @Test
    fun finalUnresolvedAttemptCompletesWithoutResolvingItsContent() {
        val expected = base.copy(recovery = active.copy(after = "cursor-31"))
        assertEquals(
            active.copy(complete = true, unresolved = true),
            planCollaborationHistoryRecoveryAdvance(expected, page(expected, 32L..39L, false), 8),
        )
    }

    @Test
    fun laterCleanPageKeepsUnresolvedEvidenceFromAnEarlierPage() {
        val expected = base.copy(recovery = active.copy(after = "cursor-15", unresolved = true))
        assertEquals(
            active.copy(after = "cursor-31", unresolved = true),
            planCollaborationHistoryRecoveryAdvance(expected, page(expected, 16L..31L), 0),
        )
    }

    @Test
    fun emptyFiniteEndKeepsEarlierUnresolvedEvidence() {
        val expected = base.copy(recovery = active.copy(after = "cursor-31", unresolved = true))
        assertEquals(
            active.copy(complete = true, unresolved = true),
            planCollaborationHistoryRecoveryAdvance(expected, page(expected, emptyList(), false), 0),
        )
    }

    @Test
    fun changedHistoryProviderOrActivePositionCannotAdvance() {
        val changes =
            listOf(
                base.copy(generation = id(98)),
                base.copy(revision = 3),
                base.copy(references = 39),
                base.copy(provider = CollaborationHistoryProviderIdentity(id(90), id(91))),
                base.copy(recovery = active.copy(generation = id(2))),
                base.copy(recovery = active.copy(after = "other-cursor")),
                base.copy(recovery = active.copy(through = 30)),
                base.copy(recovery = active.copy(complete = true)),
            )
        for (current in changes) assertNull(
            planCollaborationHistoryRecoveryAdvance(current, page(), 0)
        )
        val requested = base.copy(recovery = active.copy(requestedGeneration = id(2)))
        assertNull(planCollaborationHistoryRecoveryAdvance(base, page(requested), 0))
    }

    @Test
    fun unresolvedCountAndCapturedFiniteBoundaryMustMatchTheAttempt() {
        assertNull(planCollaborationHistoryRecoveryAdvance(base, page(), -1))
        assertNull(planCollaborationHistoryRecoveryAdvance(base, page(), 17))
        val bounded = base.copy(recovery = active.copy(through = 10))
        assertNull(planCollaborationHistoryRecoveryAdvance(bounded, page(bounded), 0))
        assertNull(planCollaborationHistoryRecoveryAdvance(base, page(base, emptyList(), false), 1))
    }

    @Test
    fun continuousRequestsStillReachTheTailBeforeTheLatestPassStarts() {
        var current = base.copy(references = 37, recovery = active.copy(through = 37))
        val visited = mutableListOf<Long>()
        for ((index, sequence) in listOf(0L..15L, 16L..31L, 32L..36L).withIndex()) {
            val attempted = page(current, sequence, index < 2)
            val requested = planCollaborationHistoryRecoveryRequest(current, id(index + 2))!!
            val changed =
                current.copy(references = 80, revision = current.revision + 1, recovery = requested)
            val next =
                planCollaborationHistoryRecoveryAdvance(changed, attempted, attempted.events.size)!!
            visited += attempted.events.map { it.sequence }
            current = changed.copy(revision = changed.revision + 1, recovery = next)
        }
        assertEquals((0L..36L).toList(), visited)
        assertEquals(CollaborationHistoryRecoveryPosition(id(4), 80), current.recovery)
    }

    private fun page(
        progress: CollaborationHistoryProgress = base,
        sequence: Iterable<Long> = 0L..15L,
        more: Boolean = true,
    ): CollaborationHistoryReferences {
        val events = sequence.map(::reference)
        return CollaborationHistoryReferences(
            progress,
            events,
            if (more) "cursor-${events.last().sequence}" else null,
        )
    }

    private fun reference(sequence: Long) =
        CollaborationHistoryReference(
            sequence,
            CollaborationProviderHistoryEvent(
                "\$reference-$sequence",
                "@fictional:example.test",
                sequence,
                "m.room.encrypted",
            ),
        )

    private fun id(value: Int) = "a1000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
}
