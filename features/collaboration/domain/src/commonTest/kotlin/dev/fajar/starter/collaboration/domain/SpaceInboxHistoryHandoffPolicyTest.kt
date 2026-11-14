package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxHistoryHandoffPolicyTest {
    @Test
    fun bothNamespacesHandoffUnresolvedReferencesWithTheirFrozenCursor() {
        for (legacy in listOf(false, true)) {
            val f = HandoffFixture(legacy)
            val result = f.success()
            assertEquals(f.current, result.expected)
            assertEquals(f.f.owner, result.replacement.owner)
            assertEquals(listOf(f.f.event), result.unresolvedEventIds)
            assertTrue(result.retainedEventIds.isEmpty())
            assertEquals(
                CollaborationHistoryChangeCursor.After(f.f.owner, f.f.generation, 4),
                result.replacement.historyCursor,
            )
            assertTrue(result.replacement.historyComplete)
            assertEquals(f.commit.receipt, result.receipt)
        }
    }

    @Test
    fun newerSourceReceiptDoesNotSkipLaterRawHistory() {
        val f = HandoffFixture()
        f.commit =
            f.commit.copy(
                receipt =
                    f.commit.receipt.copy(
                        source = CollaborationHistorySourceVersion(f.f.generation, 90)
                    )
            )
        assertEquals(
            4L,
            assertIs<CollaborationHistoryChangeCursor.After>(f.success().replacement.historyCursor)
                .revision,
        )
    }

    @Test
    fun emptyAuxiliaryPagesRetainContinuationInsteadOfCompletingEarly() {
        val f = HandoffFixture()
        val cursor = CollaborationHistoryChangeCursor.Within(f.f.window, 2, "record-a")
        f.read = f.read.copy(changes = f.f.page.copy(eventIds = emptyList(), next = cursor))
        f.commit = f.commit.copy(unresolvedEventIds = emptyList())
        val result = f.success()
        assertEquals(cursor, result.replacement.historyCursor)
        assertFalse(result.replacement.historyComplete)
        assertTrue(result.retainedEventIds.isEmpty() && result.unresolvedEventIds.isEmpty())
    }

    @Test
    fun confirmedAbsenceCompletesWithoutInventingSourceRevisions() {
        val f = HandoffFixture()
        f.read = f.read.copy(changes = CollaborationHistoryChanges.Absent)
        f.commit =
            f.commit.copy(
                receipt = CollaborationHistoryInboxReceipt(null, null),
                unresolvedEventIds = emptyList(),
            )
        val result = f.success()
        assertNull(result.replacement.historyCursor)
        assertTrue(result.replacement.historyComplete)
        assertNull(result.receipt.source)
    }

    @Test
    fun firstIndexCreationIsAllowedAfterAnEmptyPendingIntervalWasCaptured() {
        val f = HandoffFixture()
        val pending =
            CollaborationSpaceInboxHistoryPendingWindow(f.progressGeneration, 0, null, true)
        f.current = f.current.copy(progress = f.current.progress.copy(pendingWindow = pending))
        f.commit = f.commit.copy(receipt = f.commit.receipt.copy(destination = f.destination))
        val result = f.success()
        assertEquals(f.destination.generation, result.replacement.destinationGeneration)
        assertEquals(pending, result.replacement.pendingWindow)
    }

    @Test
    fun destinationReplacementOrFirstCreationAfterProgressRequiresReconstruction() {
        for (previous in listOf<String?>(null, "c4000000-0000-4000-8000-000000000001")) {
            val f = HandoffFixture()
            val cursor = CollaborationHistoryChangeCursor.After(f.f.owner, f.f.generation, 1)
            f.current =
                f.current.copy(
                    progress =
                        f.current.progress.copy(
                            destinationGeneration = previous,
                            historyCursor = cursor,
                        )
                )
            f.read =
                f.read.copy(changes = f.f.page.copy(window = f.f.window.copy(afterRevision = 1)))
            f.commit = f.commit.copy(receipt = f.commit.receipt.copy(destination = f.destination))
            assertEquals(FailureKind.Conflict, f.failure().kind)
        }
    }

    @Test
    fun continuingPageKeepsItsCapturedWindowAndOtherLaneState() {
        val f = HandoffFixture()
        val before = CollaborationHistoryChangeCursor.Within(f.f.window, 2, "record-a")
        val next = before.copy(recordId = "record-b")
        val pending =
            CollaborationSpaceInboxHistoryPendingWindow(f.progressGeneration, 0, null, false)
        f.current =
            f.current.copy(
                progress =
                    f.current.progress.copy(
                        historyCursor = before,
                        pendingWindow = pending,
                        destinationGeneration = f.destination.generation,
                    )
            )
        f.read = f.read.copy(changes = f.f.page.copy(next = next))
        f.commit = f.commit.copy(receipt = f.commit.receipt.copy(destination = f.destination))
        val result = f.success()
        assertEquals(next, result.replacement.historyCursor)
        assertEquals(pending, result.replacement.pendingWindow)
        assertEquals(f.current.progress.passId, result.replacement.passId)
        assertFalse(result.replacement.historyComplete)
    }

    @Test
    fun everyRequestedReferenceNeedsExactlyOneOutcome() {
        for (kind in 0..3) {
            val f = HandoffFixture()
            f.commit =
                when (kind) {
                    0 -> f.commit.copy(unresolvedEventIds = emptyList())
                    1 -> f.commit.copy(retainedEventIds = listOf(f.f.event))
                    2 -> f.commit.copy(unresolvedEventIds = listOf("\$foreign"))
                    else -> f.commit.copy(unresolvedEventIds = listOf(f.f.event, f.f.event))
                }
            assertEquals(FailureKind.Validation, f.failure().kind)
        }
    }

    @Test
    fun staleForeignAndMalformedReceiptsCannotAdvance() {
        for (kind in 0..4) {
            val f = HandoffFixture()
            f.commit =
                when (kind) {
                    0 -> f.commit.copy(owner = f.f.owner.copy(roomId = "!foreign:example.test"))
                    1 -> f.commit.copy(receipt = f.commit.receipt.copy(source = null))
                    2 ->
                        f.commit.copy(
                            receipt =
                                f.commit.receipt.copy(
                                    source =
                                        CollaborationHistorySourceVersion(f.progressGeneration, 4)
                                )
                        )
                    3 ->
                        f.commit.copy(
                            receipt =
                                f.commit.receipt.copy(
                                    source = CollaborationHistorySourceVersion(f.f.generation, 3)
                                )
                        )
                    else ->
                        f.commit.copy(
                            receipt =
                                f.commit.receipt.copy(
                                    destination = f.destination.copy(revision = -1)
                                )
                        )
                }
            assertEquals(FailureKind.Validation, f.failure().kind)
        }
    }

    @Test
    fun completedOrReplacedSourceRequiresAnExplicitNextTransition() {
        val f = HandoffFixture()
        f.current = f.current.copy(progress = f.current.progress.copy(historyComplete = true))
        assertEquals(FailureKind.Conflict, f.failure().kind)
        f.current = f.current.copy(progress = f.current.progress.copy(historyComplete = false))
        f.read = f.read.copy(changes = CollaborationHistoryChanges.Restart)
        assertEquals(FailureKind.Conflict, f.failure().kind)
    }

    @Test
    fun callerOwnedListsCannotChangeAnAlreadyCapturedHandoff() {
        val f = HandoffFixture()
        val requested = mutableListOf(f.f.event)
        val unresolved = mutableListOf(f.f.event)
        f.read = f.read.copy(changes = f.f.page.copy(eventIds = requested))
        f.commit = f.commit.copy(unresolvedEventIds = unresolved)
        val result = f.success()
        requested.clear()
        unresolved.clear()
        assertEquals(listOf(f.f.event), result.unresolvedEventIds)
    }

    @Test
    fun sourceCheckpointRejectsForeignScopeCursorAndPendingGeneration() {
        for (kind in 0..6) {
            val f = HandoffFixture()
            val p = f.current.progress
            f.current =
                when (kind) {
                    0 ->
                        f.current.copy(
                            progress =
                                p.copy(owner = p.owner.copy(accountId = f.progressGeneration))
                        )
                    1 -> f.current.copy(progress = p.copy(owner = p.owner.copy(audience = "other")))
                    2 ->
                        f.current.copy(
                            progress =
                                p.copy(
                                    historyCursor =
                                        CollaborationHistoryChangeCursor.After(
                                            p.owner.copy(roomId = "!foreign"),
                                            f.f.generation,
                                            1,
                                        )
                                )
                        )
                    3 ->
                        f.current.copy(
                            progress =
                                p.copy(
                                    pendingWindow =
                                        CollaborationSpaceInboxHistoryPendingWindow(
                                            f.f.generation,
                                            0,
                                            null,
                                            false,
                                        )
                                )
                        )
                    4 ->
                        f.current.copy(
                            progress =
                                p.copy(
                                    pendingWindow =
                                        CollaborationSpaceInboxHistoryPendingWindow(
                                            f.progressGeneration,
                                            1,
                                            null,
                                            false,
                                        )
                                )
                        )
                    5 -> f.current.copy(version = f.current.version.copy(revision = -1))
                    else ->
                        f.current.copy(
                            progress =
                                p.copy(
                                    historyCursor =
                                        CollaborationHistoryChangeCursor.After(
                                            p.owner,
                                            f.f.generation,
                                            Long.MAX_VALUE,
                                        )
                                )
                        )
                }
            assertEquals(FailureKind.Validation, f.failure().kind)
        }
    }

    @Test
    fun currentIndexMayAdvanceButCannotRegressOrChangeGeneration() {
        val v = HandoffFixture().destination.copy(revision = 3, lastOrdinal = 5)
        assertTrue(collaborationSpaceInboxHistoryDestinationCovers(v, v))
        assertTrue(
            collaborationSpaceInboxHistoryDestinationCovers(
                v,
                v.copy(revision = 4, lastOrdinal = 6),
            )
        )
        assertFalse(collaborationSpaceInboxHistoryDestinationCovers(v, v.copy(revision = 2)))
        assertFalse(collaborationSpaceInboxHistoryDestinationCovers(v, v.copy(lastOrdinal = 4)))
        assertFalse(collaborationSpaceInboxHistoryDestinationCovers(v, v.copy(lastOrdinal = 6)))
        assertFalse(
            collaborationSpaceInboxHistoryDestinationCovers(
                v,
                v.copy(generation = "c5000000-0000-4000-8000-000000000001"),
            )
        )
        assertFalse(collaborationSpaceInboxHistoryDestinationCovers(v, null))
        assertFalse(collaborationSpaceInboxHistoryDestinationCovers(null, v))
        assertTrue(collaborationSpaceInboxHistoryDestinationCovers(null, null))
        assertFalse(collaborationSpaceInboxHistoryDestinationCovers(v.copy(revision = -1), v))
    }
}

private class HandoffFixture(legacy: Boolean = false) {
    val f = SpaceHistoryChangesFixture(legacy)
    val progressGeneration = "c1000000-0000-4000-8000-000000000001"
    val pass = "c2000000-0000-4000-8000-000000000001"
    val destination = CollaborationSpaceInboxVersion("c3000000-0000-4000-8000-000000000001", 0, 1)
    var current =
        CollaborationSpaceInboxHistorySourceCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(progressGeneration, 0),
            CollaborationSpaceInboxHistorySourceProgress(
                f.id,
                f.owner,
                null,
                pass,
                null,
                false,
                null,
            ),
        )
    var read = CollaborationSpaceHistoryChangesRead(f.owner, f.page)
    var commit =
        CollaborationSpaceInboxHistoryCommit(
            f.owner,
            CollaborationHistoryInboxReceipt(
                CollaborationHistorySourceVersion(f.generation, 4),
                null,
            ),
            emptyList(),
            listOf(f.event),
        )

    fun result() = collaborationSpaceInboxHistoryHandoff(f.access, current, read, commit)

    fun success() =
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryHandoff>>(result()).value

    fun failure() = assertIs<AppResult.Failed>(result()).failure
}
