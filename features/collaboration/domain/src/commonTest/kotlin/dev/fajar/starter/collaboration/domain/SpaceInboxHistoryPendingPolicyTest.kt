package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxHistoryPendingPolicyTest {
    @Test
    fun firstReadCapturesPhysicalProgressRatherThanRawHistoryRevision() {
        val f = PendingHistoryPolicyFixture()
        assertTrue(f.valid())
        assertFalse(f.valid(f.page.copy(window = f.page.window.copy(throughRevision = 9))))
        assertFalse(f.valid(f.page.copy(version = f.current.version.copy(revision = 4))))
    }

    @Test
    fun retainedWindowCannotGrowAfterLaterInsertions() {
        val f = PendingHistoryPolicyFixture()
        f.current =
            f.current.copy(progress = f.current.progress.copy(pendingWindow = f.page.window))
        f.current = f.current.copy(version = f.current.version.copy(revision = 10))
        f.page = f.page.copy(version = f.current.version)
        assertTrue(f.valid())
        assertFalse(f.valid(f.page.copy(window = f.page.window.copy(throughRevision = 10))))
        assertFalse(
            f.valid(f.page.copy(references = listOf(f.reference.copy(insertedAtRevision = 6))))
        )
    }

    @Test
    fun foreignDuplicateAndMalformedReferencesAreRejected() {
        val f = PendingHistoryPolicyFixture()
        for (refs in
            listOf(
                listOf(f.reference, f.reference),
                listOf(f.reference.copy(conversationId = f.other)),
                listOf(
                    f.reference.copy(
                        owner = f.reference.owner.copy(roomId = "!foreign:example.test")
                    )
                ),
                listOf(f.reference.copy(eventId = "")),
                listOf(f.reference.copy(insertedAtRevision = -1)),
                listOf(f.reference.copy(eventId = "\$second", insertedAtRevision = 4), f.reference),
            )) assertFalse(f.valid(f.page.copy(references = refs)))
        assertFalse(validCollaborationSpaceInboxHistoryPendingPage(f.access, f.current, 0, f.page))
    }

    @Test
    fun emptyContinuationAndNonadvancingCursorCannotKeepTheLaneRunning() {
        val f = PendingHistoryPolicyFixture()
        assertFalse(f.valid(f.page.copy(references = emptyList(), next = "next")))
        val window = f.page.window.copy(after = "same")
        f.current = f.current.copy(progress = f.current.progress.copy(pendingWindow = window))
        assertFalse(f.valid(f.page.copy(window = window, next = "same")))
        assertFalse(f.valid(f.page.copy(window = window, next = "")))
        assertTrue(f.valid(f.page.copy(window = window, next = "later")))
    }

    @Test
    fun completedWindowReadsOnlyAnExplicitEmptyEnd() {
        val f = PendingHistoryPolicyFixture()
        val window = f.page.window.copy(complete = true)
        f.current = f.current.copy(progress = f.current.progress.copy(pendingWindow = window))
        assertFalse(f.valid(f.page.copy(window = window)))
        assertTrue(f.valid(f.page.copy(window = window, references = emptyList())))
    }

    @Test
    fun everyReferenceRequiresExactlyOnePublicationOutcome() {
        val f = PendingHistoryPolicyFixture()
        for (commit in
            listOf(
                f.published.copy(retainedEventIds = emptyList()),
                f.published.copy(unresolvedEventIds = listOf(f.reference.eventId)),
                f.published.copy(retainedEventIds = listOf("\$foreign")),
                f.published.copy(owner = f.published.owner.copy(accountId = f.other)),
            )) assertIs<AppResult.Failed>(f.handoff(commit))
        assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceMutation>>(f.handoff())
    }

    @Test
    fun exactReferencesCanBeCheckedAgainstANewerRawSourceGeneration() {
        val f = PendingHistoryPolicyFixture()
        val changed =
            f.published.copy(
                receipt =
                    f.published.receipt.copy(source = CollaborationHistorySourceVersion(f.other, 0))
            )
        val mutation =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceMutation>>(
                    f.handoff(changed)
                )
                .value
        assertEquals(f.current.progress.historyCursor, mutation.replacement.historyCursor)
        assertEquals(f.current.progress.historyComplete, mutation.replacement.historyComplete)
        assertTrue(mutation.replacement.pendingWindow!!.complete)
    }

    @Test
    fun absentSourceKeepsUnresolvedReferencesButCannotClaimRetention() {
        val f = PendingHistoryPolicyFixture()
        val absent = f.published.copy(receipt = f.published.receipt.copy(source = null))
        assertIs<AppResult.Failed>(f.handoff(absent))
        val mutation =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceMutation>>(
                    f.handoff(
                        absent.copy(
                            retainedEventIds = emptyList(),
                            unresolvedEventIds = listOf(f.reference.eventId),
                        )
                    )
                )
                .value
        assertEquals(listOf(f.reference.eventId), mutation.unresolvedEventIds)
        assertTrue(mutation.retainedEventIds.isEmpty())
    }

    @Test
    fun destinationReplacementRequiresLifecycleReconstruction() {
        val f = PendingHistoryPolicyFixture()
        val commit = f.published.copy(receipt = f.published.receipt.copy(destination = null))
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.handoff(commit)).failure.kind,
        )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    collaborationSpaceInboxHistoryPendingCompletion(
                        f.access,
                        f.current,
                        f.page.copy(references = emptyList()),
                        null,
                    )
                )
                .failure
                .kind,
        )
    }

    @Test
    fun emptyCompletionUsesOnlyIndexMetadataAndLeavesRawProgressUntouched() {
        val f = PendingHistoryPolicyFixture()
        val result =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceMutation>>(
                    collaborationSpaceInboxHistoryPendingCompletion(
                        f.access,
                        f.current,
                        f.page.copy(references = emptyList()),
                        f.destination,
                    )
                )
                .value
        assertTrue(result.retainedEventIds.isEmpty())
        assertTrue(result.unresolvedEventIds.isEmpty())
        assertEquals(f.current.progress.historyCursor, result.replacement.historyCursor)
        assertEquals(f.current.progress.historyComplete, result.replacement.historyComplete)
        assertTrue(result.replacement.pendingWindow!!.complete)
        assertIs<AppResult.Failed>(
            collaborationSpaceInboxHistoryPendingCompletion(
                f.access,
                f.current,
                f.page,
                f.destination,
            )
        )
    }

    @Test
    fun proposalOwnsItsCapturedOutcomeLists() {
        val f = PendingHistoryPolicyFixture()
        val retained = mutableListOf(f.reference.eventId)
        val references = mutableListOf(f.reference)
        f.page = f.page.copy(references = references)
        val mutation =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceMutation>>(
                    f.handoff(f.published.copy(retainedEventIds = retained))
                )
                .value
        retained.clear()
        references.clear()
        assertEquals(listOf(f.reference.eventId), mutation.retainedEventIds)
    }

    @Test
    fun exhaustedRevisionAllowsOnlyAnAlreadyCompletedNoop() {
        val f = PendingHistoryPolicyFixture()
        f.current =
            f.current.copy(
                version = f.current.version.copy(revision = COLLABORATION_SPACE_INBOX_MAX_ORDER),
                progress = f.current.progress.copy(pendingWindow = f.page.window),
            )
        f.page = f.page.copy(version = f.current.version)
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.handoff()).failure.kind)
        val done = f.page.window.copy(complete = true)
        f.current = f.current.copy(progress = f.current.progress.copy(pendingWindow = done))
        val mutation =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceMutation>>(
                    collaborationSpaceInboxHistoryPendingCompletion(
                        f.access,
                        f.current,
                        f.page.copy(window = done, references = emptyList()),
                        f.destination,
                    )
                )
                .value
        assertEquals(f.current.progress, mutation.replacement)
    }
}

private class PendingHistoryPolicyFixture {
    val source = SpaceHistoryChangesFixture()
    val access = source.access
    val other = "f9000000-0000-4000-8000-000000000009"
    val destination = CollaborationSpaceInboxVersion(source.generation, 3, 4)
    var current =
        CollaborationSpaceInboxHistorySourceCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(source.generation, 5),
            CollaborationSpaceInboxHistorySourceProgress(
                source.id,
                source.owner,
                destination.generation,
                source.generation,
                CollaborationHistoryChangeCursor.After(source.owner, source.generation, 9),
                false,
                null,
            ),
        )
    val reference =
        CollaborationSpaceInboxHistoryRepairReference(source.id, source.owner, source.event, 2)
    var page =
        CollaborationSpaceInboxHistoryPendingPage(
            current.version,
            CollaborationSpaceInboxHistoryPendingWindow(current.version.generation, 5, null, false),
            listOf(reference),
            null,
        )
    val published =
        CollaborationSpaceInboxHistoryCommit(
            source.owner,
            CollaborationHistoryInboxReceipt(
                CollaborationHistorySourceVersion(source.generation, 10),
                destination,
            ),
            listOf(source.event),
            emptyList(),
        )

    fun valid(value: CollaborationSpaceInboxHistoryPendingPage = page) =
        validCollaborationSpaceInboxHistoryPendingPage(access, current, 16, value)

    fun handoff(value: CollaborationSpaceInboxHistoryCommit = published) =
        collaborationSpaceInboxHistoryPendingHandoff(access, current, page, value)
}
