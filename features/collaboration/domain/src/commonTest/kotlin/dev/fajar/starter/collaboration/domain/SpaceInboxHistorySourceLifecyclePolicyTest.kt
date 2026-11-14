package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxHistorySourceLifecyclePolicyTest {
    @Test
    fun missingProgressRetainsActualCompanyOrSpaceCoordinates() {
        for (legacy in listOf(false, true)) {
            val f = SpaceHistoryChangesFixture(legacy)
            val next =
                assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceProgress>>(
                        prepareCollaborationSpaceInboxHistorySource(
                            f.access,
                            f.id,
                            f.owner,
                            f.generation,
                            null,
                            null,
                        )
                    )
                    .value
            assertEquals(f.owner, next.owner)
            assertEquals(f.id, next.conversationId)
            assertEquals(f.generation, next.passId)
            assertNull(next.historyCursor)
            assertFalse(next.historyComplete)
            assertNull(next.pendingWindow)
        }
    }

    @Test
    fun samePassPreservesActiveAndCompletedWindowsWithoutManufacturingProgress() {
        val f = SpaceHistoryChangesFixture()
        for (completed in listOf(false, true)) {
            val current = checkpoint(f, completed)
            assertEquals(AppResult.Success(current.progress), next(f, current))
        }
    }

    @Test
    fun laterPassReusesOnlyTheCompletedRawCursorAndRecapturesPendingInsertions() {
        val f = SpaceHistoryChangesFixture()
        val current = checkpoint(f, true)
        val result =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceProgress>>(
                    next(f, current, pass = OTHER)
                )
                .value
        assertEquals(OTHER, result.passId)
        assertEquals(current.progress.historyCursor, result.historyCursor)
        assertFalse(result.historyComplete)
        assertNull(result.pendingWindow)
    }

    @Test
    fun unfinishedRawOrPendingIntervalsCannotBeRelabeledAsANewerPass() {
        val f = SpaceHistoryChangesFixture()
        val done = checkpoint(f, true)
        for (progress in
            listOf(
                done.progress.copy(historyComplete = false),
                done.progress.copy(pendingWindow = null),
                done.progress.copy(
                    pendingWindow = done.progress.pendingWindow!!.copy(complete = false)
                ),
            )) assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(next(f, done.copy(progress = progress), pass = OTHER))
                .failure
                .kind,
        )
    }

    @Test
    fun destinationReplacementResetsBothLanesEvenWithoutANewerPass() {
        val f = SpaceHistoryChangesFixture()
        val current = checkpoint(f, false)
        for (destination in listOf(null, CollaborationSpaceInboxVersion(OTHER, 0, 0))) {
            val result =
                assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceProgress>>(
                        next(f, current, destination = destination)
                    )
                    .value
            assertEquals(destination?.generation, result.destinationGeneration)
            assertEquals(current.progress.owner, result.owner)
            assertNull(result.historyCursor)
            assertNull(result.pendingWindow)
            assertFalse(result.historyComplete)
        }
    }

    @Test
    fun malformedSourceOrForeignCheckpointCannotBeAdopted() {
        val f = SpaceHistoryChangesFixture()
        val current = checkpoint(f, true)
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(
                    prepareCollaborationSpaceInboxHistorySource(
                        f.access,
                        f.id,
                        f.owner.copy(accountId = OTHER),
                        f.generation,
                        null,
                        null,
                    )
                )
                .failure
                .kind,
        )
        for (bad in
            listOf(
                current.copy(version = current.version.copy(revision = -1)),
                current.copy(
                    progress =
                        current.progress.copy(
                            owner = f.owner.copy(roomId = "!different:example.test")
                        )
                ),
            )) assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(next(f, bad)).failure.kind,
        )
    }

    @Test
    fun exhaustedRevisionAllowsUnchangedReopenButNoFurtherMutation() {
        val f = SpaceHistoryChangesFixture()
        val current =
            checkpoint(f, true).let {
                it.copy(version = it.version.copy(revision = COLLABORATION_SPACE_INBOX_MAX_ORDER))
            }
        assertEquals(AppResult.Success(current.progress), next(f, current))
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(next(f, current, pass = OTHER)).failure.kind,
        )
    }

    private fun checkpoint(f: SpaceHistoryChangesFixture, done: Boolean) =
        CollaborationSpaceInboxHistorySourceCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(f.generation, 5),
            CollaborationSpaceInboxHistorySourceProgress(
                f.id,
                f.owner,
                f.generation,
                f.generation,
                if (done) CollaborationHistoryChangeCursor.After(f.owner, f.generation, 4)
                else CollaborationHistoryChangeCursor.Within(f.window, 2, "opaque"),
                done,
                CollaborationSpaceInboxHistoryPendingWindow(f.generation, 3, "pending", done),
            ),
        )

    private fun next(
        f: SpaceHistoryChangesFixture,
        current: CollaborationSpaceInboxHistorySourceCheckpoint,
        pass: String = f.generation,
        destination: CollaborationSpaceInboxVersion? =
            CollaborationSpaceInboxVersion(f.generation, 1, 1),
    ) =
        prepareCollaborationSpaceInboxHistorySource(
            f.access,
            f.id,
            f.owner,
            pass,
            destination,
            current,
        )

    private companion object {
        const val OTHER = "e9000000-0000-4000-8000-000000000009"
    }
}
