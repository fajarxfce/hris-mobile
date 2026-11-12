package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationHistoryRecoveryVisitPolicyTest {
    private val active = CollaborationHistoryRecoveryPosition(id(1), 4)
    private val base =
        CollaborationHistoryProgress(
            id(99),
            4,
            "provider-next",
            4,
            CollaborationIncomingHistoryPosition("provider-head", null),
            CollaborationHistoryProviderIdentity(id(80), id(81)),
            active,
        )
    private val receipt = CollaborationHistoryRecoveryPageCompletion(id(1), 4, 0, null)
    private val finished = base.copy(revision = 5, recovery = active.copy(complete = true))

    @Test
    fun absentHistoryNeedsNoFurtherPass() {
        assertEquals(
            CollaborationHistoryRecoveryVisit(false, false),
            planCollaborationHistoryRecoveryVisit(
                id(1),
                CollaborationHistoryRecoveryRead.Absent,
                null,
                null,
            ),
        )
    }

    @Test
    fun anAbsentReadCannotHideNewProgressOrCarryAReceipt() {
        assertNull(
            planCollaborationHistoryRecoveryVisit(
                id(1),
                CollaborationHistoryRecoveryRead.Absent,
                null,
                base,
            )
        )
        assertNull(
            planCollaborationHistoryRecoveryVisit(
                id(1),
                CollaborationHistoryRecoveryRead.Absent,
                receipt,
                null,
            )
        )
    }

    @Test
    fun completedCurrentGenerationStaysCompleteAfterANewerPassStarts() {
        val newer =
            finished.copy(revision = 6, recovery = CollaborationHistoryRecoveryPosition(id(2), 4))
        assertTrue(validCollaborationHistoryProgress(newer))
        assertEquals(
            CollaborationHistoryRecoveryVisit(false, false),
            planCollaborationHistoryRecoveryVisit(
                id(1),
                CollaborationHistoryRecoveryRead.Complete(finished),
                null,
                newer,
            ),
        )
    }

    @Test
    fun completedReadMustIdentifyTheRequestedGenerationAndHaveNoPageReceipt() {
        val read = CollaborationHistoryRecoveryRead.Complete(finished)
        assertNull(planCollaborationHistoryRecoveryVisit(id(2), read, null, finished))
        assertNull(planCollaborationHistoryRecoveryVisit(id(1), read, receipt, finished))
        assertNull(
            planCollaborationHistoryRecoveryVisit(
                id(1),
                CollaborationHistoryRecoveryRead.Complete(base),
                null,
                base,
            )
        )
    }

    @Test
    fun sameGenerationEofRemainsEvidenceAfterPromotionToANewerPass() {
        val newer =
            base.copy(revision = 5, recovery = CollaborationHistoryRecoveryPosition(id(2), 4))
        assertTrue(validCollaborationHistoryProgress(newer))
        assertEquals(
            CollaborationHistoryRecoveryVisit(false, false),
            planCollaborationHistoryRecoveryVisit(id(1), pending(), receipt, newer),
        )
    }

    @Test
    fun olderGenerationEofDoesNotFinishTheRequestedGlobalGeneration() {
        val previous = base.copy(recovery = active.copy(requestedGeneration = id(2)))
        val promoted =
            base.copy(revision = 5, recovery = CollaborationHistoryRecoveryPosition(id(2), 4))
        assertEquals(
            CollaborationHistoryRecoveryVisit(true, false),
            planCollaborationHistoryRecoveryVisit(id(2), pending(previous), receipt, promoted),
        )
    }

    @Test
    fun promotedRequestedGenerationCanAlreadyHaveCompletedInAnotherTurn() {
        val previous = base.copy(recovery = active.copy(requestedGeneration = id(2)))
        val promoted =
            base.copy(
                revision = 6,
                recovery =
                    CollaborationHistoryRecoveryPosition(
                        id(2),
                        4,
                        complete = true,
                        unresolved = true,
                    ),
            )
        assertTrue(validCollaborationHistoryProgress(promoted))
        assertEquals(
            CollaborationHistoryRecoveryVisit(false, true),
            planCollaborationHistoryRecoveryVisit(id(2), pending(previous), receipt, promoted),
        )
    }

    @Test
    fun intermediatePagesRequireAnotherMetadataRound() {
        val previous = base.copy(references = 20, recovery = active.copy(through = 20))
        val read = pending(previous, 0L..15L, "cursor-15")
        val next =
            previous.copy(
                revision = 5,
                recovery = assertNotNull(previous.recovery).copy(after = "cursor-15"),
            )
        assertEquals(
            CollaborationHistoryRecoveryVisit(true, false),
            planCollaborationHistoryRecoveryVisit(
                id(1),
                read,
                receipt.copy(resolved = 16, after = "cursor-15"),
                next,
            ),
        )
    }

    @Test
    fun priorAndCurrentUnresolvedEvidenceSurvivesEofPromotion() {
        for (prior in listOf(false, true)) for (unresolved in listOf(0, 1)) {
            val previous =
                base.copy(recovery = active.copy(unresolved = prior, requestedGeneration = id(2)))
            val promoted =
                base.copy(revision = 5, recovery = CollaborationHistoryRecoveryPosition(id(2), 4))
            assertEquals(
                CollaborationHistoryRecoveryVisit(false, prior || unresolved > 0),
                planCollaborationHistoryRecoveryVisit(
                    id(1),
                    pending(previous),
                    receipt.copy(resolved = 4 - unresolved, unresolved = unresolved),
                    promoted,
                ),
            )
        }
    }

    @Test
    fun waitingEvidenceFromAnOlderPassDoesNotCompleteTheRequestedPass() {
        val previous =
            base.copy(recovery = active.copy(unresolved = true, requestedGeneration = id(2)))
        val promoted =
            base.copy(revision = 5, recovery = CollaborationHistoryRecoveryPosition(id(2), 4))
        assertEquals(
            CollaborationHistoryRecoveryVisit(true, false),
            planCollaborationHistoryRecoveryVisit(
                id(2),
                pending(previous),
                receipt.copy(resolved = 0, unresolved = 4),
                promoted,
            ),
        )
    }

    @Test
    fun missingOrReplacedHistoryCannotConfirmTheVisit() {
        val changes =
            listOf(
                null,
                finished.copy(recovery = null),
                finished.copy(generation = id(98)),
                finished.copy(provider = CollaborationHistoryProviderIdentity(id(80), id(82))),
                finished.copy(provider = null),
                finished.copy(references = 3, recovery = active.copy(through = 3, complete = true)),
            )
        for (current in changes) assertNull(
            planCollaborationHistoryRecoveryVisit(id(1), pending(), receipt, current)
        )
    }

    @Test
    fun aReceiptRequiresALaterCommittedRevision() {
        assertNull(planCollaborationHistoryRecoveryVisit(id(1), pending(), receipt, base))
        assertNull(
            planCollaborationHistoryRecoveryVisit(
                id(1),
                pending(),
                receipt,
                finished.copy(revision = 4),
            )
        )
        assertNull(
            planCollaborationHistoryRecoveryVisit(
                id(1),
                pending(),
                receipt,
                finished.copy(revision = 3),
            )
        )
    }

    @Test
    fun pageReceiptMustMatchGenerationCursorAndEveryOutcomeCount() {
        for (wrong in
            listOf(
                receipt.copy(generation = id(2)),
                receipt.copy(after = "cursor-3"),
                receipt.copy(resolved = -1),
                receipt.copy(unresolved = -1),
                receipt.copy(resolved = 5),
                receipt.copy(resolved = 3),
                receipt.copy(unresolved = 1),
            )) {
            assertNull(planCollaborationHistoryRecoveryVisit(id(1), pending(), wrong, finished))
        }
        assertNull(planCollaborationHistoryRecoveryVisit(id(1), pending(), null, finished))
    }

    @Test
    fun anUnboundedOrDifferentReferencePageCannotBorrowAReceipt() {
        val read = pending()
        assertNull(
            planCollaborationHistoryRecoveryVisit(
                id(1),
                read.copy(
                    references =
                        read.references.copy(events = List(17) { read.references.events.first() })
                ),
                receipt,
                finished,
            )
        )
        assertNull(
            planCollaborationHistoryRecoveryVisit(
                id(1),
                read.copy(references = read.references.copy(progress = null)),
                receipt,
                finished,
            )
        )
    }

    @Test
    fun laterPrimaryAppendsDoNotExtendAnAlreadyFinishedPass() {
        val appended = finished.copy(revision = 6, references = 100)
        assertTrue(validCollaborationHistoryProgress(appended))
        assertEquals(
            CollaborationHistoryRecoveryVisit(false, false),
            planCollaborationHistoryRecoveryVisit(id(1), pending(), receipt, appended),
        )
    }

    @Test
    fun completedEmptyHistoryIsExplicitlyVisited() {
        val empty = base.copy(references = 0, recovery = active.copy(through = 0, complete = true))
        assertTrue(validCollaborationHistoryProgress(empty))
        assertEquals(
            CollaborationHistoryRecoveryVisit(false, false),
            planCollaborationHistoryRecoveryVisit(
                id(1),
                CollaborationHistoryRecoveryRead.Complete(empty),
                null,
                empty,
            ),
        )
    }

    @Test
    fun anotherUnknownGenerationRequiresAnotherRoundWithoutRequestingAnything() {
        val previous = base.copy(recovery = active.copy(requestedGeneration = id(2)))
        val other =
            finished.copy(
                revision = 6,
                recovery = CollaborationHistoryRecoveryPosition(id(3), 4, complete = true),
            )
        assertTrue(validCollaborationHistoryProgress(other))
        assertEquals(
            CollaborationHistoryRecoveryVisit(true, false),
            planCollaborationHistoryRecoveryVisit(id(2), pending(previous), receipt, other),
        )
        assertEquals(id(3), assertNotNull(other.recovery).generation)
    }

    @Test
    fun companyHistoryPreservesItsEstablishedAbsentProviderIdentity() {
        val original = base.copy(provider = null)
        val completed = finished.copy(provider = null)
        assertTrue(validCollaborationHistoryProgress(original))
        assertEquals(
            CollaborationHistoryRecoveryVisit(false, false),
            planCollaborationHistoryRecoveryVisit(id(1), pending(original), receipt, completed),
        )
        assertEquals(
            CollaborationHistoryRecoveryVisit(false, false),
            planCollaborationHistoryRecoveryVisit(
                id(1),
                CollaborationHistoryRecoveryRead.Complete(completed),
                null,
                completed,
            ),
        )
        assertNull(
            planCollaborationHistoryRecoveryVisit(id(1), pending(original), receipt, finished)
        )
    }

    @Test
    fun anUnchangedCheckpointRevisionCannotContainDifferentFacts() {
        val different =
            finished.copy(
                recovery = CollaborationHistoryRecoveryPosition(id(2), 4, complete = true)
            )
        assertTrue(validCollaborationHistoryProgress(different))
        assertNull(
            planCollaborationHistoryRecoveryVisit(
                id(1),
                CollaborationHistoryRecoveryRead.Complete(finished),
                null,
                different,
            )
        )
    }

    @Test
    fun invalidRequestedIdentityIsNotAnEmptyVisit() {
        for (generation in
            listOf("", "invalid", "a1000000-0000-4000-8000-000000000001-extra")) assertNull(
            planCollaborationHistoryRecoveryVisit(
                generation,
                CollaborationHistoryRecoveryRead.Absent,
                null,
                null,
            )
        )
        // Recovery uses the existing opaque message-ID contract, not an account/device UUID.
        val zero = "00000000-0000-0000-0000-000000000000"
        assertTrue(validCollaborationMessageId(zero))
        assertEquals(
            CollaborationHistoryRecoveryVisit(false, false),
            planCollaborationHistoryRecoveryVisit(
                zero,
                CollaborationHistoryRecoveryRead.Absent,
                null,
                null,
            ),
        )
    }

    private fun pending(
        progress: CollaborationHistoryProgress = base,
        sequence: Iterable<Long> = 0L..3L,
        after: String? = null,
    ): CollaborationHistoryRecoveryRead.Pending {
        assertTrue(validCollaborationHistoryProgress(progress))
        val page =
            CollaborationHistoryReferences(
                progress,
                sequence.map {
                    CollaborationHistoryReference(
                        it,
                        CollaborationProviderHistoryEvent(
                            "\$reference-$it",
                            "@fixture:example.test",
                            it,
                            "m.room.encrypted",
                        ),
                    )
                },
                after,
            )
        assertEquals(page, boundedCollaborationHistoryRecoveryReferences(page, 16))
        return CollaborationHistoryRecoveryRead.Pending(page)
    }

    private fun id(value: Int) = "a1000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
}
