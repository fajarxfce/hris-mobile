package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxHistoryPageStepPolicyTest {
    @Test
    fun bothSourceNamespacesRequirePublicationBeforeHistoryRetention() {
        for (legacy in listOf(false, true)) {
            val f = HistoryPageStepFixture(legacy)
            val selected =
                assertIs<CollaborationSpaceInboxHistoryPageStep.PublishHistory>(f.history())
            assertEquals(f.source, selected.expected)
            assertEquals(f.read, selected.read)
            assertEquals(listOf(f.base.event), selected.eventIds)
            assertFalse(selected.expected.progress.historyComplete)
        }
    }

    @Test
    fun emptyAuxiliaryContinuationIsConfirmedWithoutClaimingCompletion() {
        val f = HistoryPageStepFixture()
        val cursor = CollaborationHistoryChangeCursor.Within(f.base.window, 2, "record-a")
        val read = f.read.copy(changes = f.base.page.copy(eventIds = emptyList(), next = cursor))
        val selected = assertIs<CollaborationSpaceInboxHistoryPageStep.Confirm>(f.history(read))
        assertEquals(cursor, assertIs<CollaborationHistoryChanges.Page>(selected.read.changes).next)
        assertFalse(selected.expected.progress.historyComplete)
    }

    @Test
    fun absentHistoryStillNeedsMetadataConfirmation() {
        val f = HistoryPageStepFixture()
        val read = f.read.copy(changes = CollaborationHistoryChanges.Absent)
        assertEquals(
            CollaborationSpaceInboxHistoryPageStep.Confirm(f.source, read),
            f.history(read),
        )
    }

    @Test
    fun aRawRestartCanBeSelectedOnceAndCannotResetAnUnstartedCursor() {
        val f = HistoryPageStepFixture()
        val source =
            f.source.copy(
                progress =
                    f.source.progress.copy(
                        historyCursor =
                            CollaborationHistoryChangeCursor.After(
                                f.base.owner,
                                f.base.generation,
                                2,
                            )
                    )
            )
        val read = f.read.copy(changes = CollaborationHistoryChanges.Restart)
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryPageStep>>(
            collaborationSpaceInboxHistoryPageStep(f.base.access, source, read, false)
        )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxHistoryPageStep(f.base.access, source, read, true)
                )
                .failure
                .kind,
        )
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxHistoryPageStep(f.base.access, f.source, read, false)
        )
    }

    @Test
    fun emptyPendingPageUsesItsIndependentMetadataCompletion() {
        val f = HistoryPageStepFixture()
        val empty = f.pending.copy(references = emptyList())
        val selected =
            assertIs<CollaborationSpaceInboxHistoryPageStep.CompletePending>(f.pending(empty))
        assertEquals(empty, selected.read)
        assertEquals(f.source, selected.expected)
        assertNull(selected.expected.progress.pendingWindow)
    }

    @Test
    fun publicationSelectionsDetachBothKindsOfReferenceCollections() {
        val f = HistoryPageStepFixture()
        val ids = mutableListOf(f.base.event)
        val history =
            assertIs<CollaborationSpaceInboxHistoryPageStep.PublishHistory>(
                f.history(f.read.copy(changes = f.base.page.copy(eventIds = ids)))
            )
        ids.clear()
        assertEquals(listOf(f.base.event), history.eventIds)
        assertEquals(
            listOf(f.base.event),
            assertIs<CollaborationHistoryChanges.Page>(history.read.changes).eventIds,
        )
        val references = f.pending.references.toMutableList()
        val pending =
            assertIs<CollaborationSpaceInboxHistoryPageStep.PublishPending>(
                f.pending(f.pending.copy(references = references))
            )
        references.clear()
        assertEquals(listOf(f.base.event), pending.eventIds)
        assertEquals(f.pending.references, pending.read.references)
    }

    @Test
    fun foreignOrOversizedPagesCannotChooseAStorageAction() {
        val f = HistoryPageStepFixture()
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxHistoryPageStep(
                f.base.access,
                f.source,
                f.read.copy(owner = f.base.owner.copy(accountId = f.base.generation)),
                false,
            )
        )
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxHistoryPageStep(
                f.base.access,
                f.source,
                f.read.copy(changes = f.base.page.copy(eventIds = List(17) { f.base.event })),
                false,
            )
        )
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxHistoryPendingStep(
                f.base.access,
                f.source,
                f.pending.copy(references = List(17) { f.pending.references.single() }),
            )
        )
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxHistoryPendingStep(
                f.base.access,
                f.source,
                f.pending.copy(version = f.pending.version.copy(revision = 6)),
            )
        )
    }

    @Test
    fun completedAndExhaustedLanesCannotBeStartedByAnOldPage() {
        val f = HistoryPageStepFixture()
        val completedHistory =
            f.source.copy(progress = f.source.progress.copy(historyComplete = true))
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxHistoryPageStep(
                        f.base.access,
                        completedHistory,
                        f.read,
                        false,
                    )
                )
                .failure
                .kind,
        )
        val completeWindow = f.pending.window.copy(complete = true)
        val completedPending =
            f.source.copy(progress = f.source.progress.copy(pendingWindow = completeWindow))
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxHistoryPendingStep(
                        f.base.access,
                        completedPending,
                        f.pending.copy(window = completeWindow, references = emptyList()),
                    )
                )
                .failure
                .kind,
        )
        val exhausted =
            f.source.copy(
                version = f.source.version.copy(revision = COLLABORATION_SPACE_INBOX_MAX_ORDER)
            )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxHistoryPageStep(f.base.access, exhausted, f.read, false)
                )
                .failure
                .kind,
        )
    }
}

private class HistoryPageStepFixture(legacy: Boolean = false) {
    val base = SpaceHistoryChangesFixture(legacy)
    val source =
        CollaborationSpaceInboxHistorySourceCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(base.generation, 5),
            CollaborationSpaceInboxHistorySourceProgress(
                base.id,
                base.owner,
                null,
                base.generation,
                null,
                false,
                null,
            ),
        )
    val read = CollaborationSpaceHistoryChangesRead(base.owner, base.page)
    val pending =
        CollaborationSpaceInboxHistoryPendingPage(
            source.version,
            CollaborationSpaceInboxHistoryPendingWindow(
                source.version.generation,
                source.version.revision,
                null,
                false,
            ),
            listOf(
                CollaborationSpaceInboxHistoryRepairReference(base.id, base.owner, base.event, 1)
            ),
            null,
        )

    fun history(value: CollaborationSpaceHistoryChangesRead = read) =
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryPageStep>>(
                collaborationSpaceInboxHistoryPageStep(base.access, source, value, false)
            )
            .value

    fun pending(value: CollaborationSpaceInboxHistoryPendingPage = pending) =
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryPageStep>>(
                collaborationSpaceInboxHistoryPendingStep(base.access, source, value)
            )
            .value
}
