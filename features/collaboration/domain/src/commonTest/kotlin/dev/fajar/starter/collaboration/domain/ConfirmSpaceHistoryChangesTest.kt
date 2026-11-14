package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRoomBindingRepository
import dev.fajar.starter.collaboration.domain.usecases.ConfirmCollaborationSpaceHistoryChanges
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ConfirmSpaceHistoryChangesTest {
    @Test
    fun emptyPagesUseActualNamespaceAndOpaqueTokenWithoutReadingMessages() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceInboxHistoryAdmissionFixture(legacy)
            f.revision = "opaque-empty-page-token"
            val read = page(f)
            val result = success(confirm(f, read))
            assertEquals(read.owner, result.owner)
            assertEquals(CollaborationHistoryInboxReceipt(f.sourceVersion, null), result.receipt)
            assertTrue(result.retainedEventIds.isEmpty() && result.unresolvedEventIds.isEmpty())
            assertNull(f.proposals.single().projection)
            assertEquals(1, f.noops)
            assertEquals(0, f.commits)
            assertTrue(
                f.snapshots.isEmpty() && f.anchorQueries.isEmpty() && f.publications.isEmpty()
            )
        }
    }

    @Test
    fun sourceAbsenceConfirmsRetainedOrMissingIndexWithoutInventingEvents() = runTest {
        for (existing in listOf(false, true)) {
            val f = SpaceInboxHistoryAdmissionFixture()
            f.revision = null
            if (existing) f.retain(f.base.activity)
            val before = f.current
            val read =
                CollaborationSpaceHistoryChangesRead(
                    collaborationHistoryChangeOwner(f.base.location),
                    CollaborationHistoryChanges.Absent,
                )
            assertEquals(
                CollaborationHistoryInboxReceipt(null, before.version),
                success(confirm(f, read)).receipt,
            )
            assertEquals(before, f.current)
            assertTrue(f.snapshots.isEmpty())
            assertEquals(0, f.commits)
        }
    }

    @Test
    fun aContinuationChecksItsOriginalWindowAgainstTheCurrentSourceVersion() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        val first = page(f)
        val raw = first.changes as CollaborationHistoryChanges.Page
        val cursor = CollaborationHistoryChangeCursor.Within(raw.window, 1, "opaque-a")
        val next = cursor.copy(recordId = "opaque-b")
        val read = first.copy(changes = raw.copy(next = next))
        f.sourceVersion = f.sourceVersion.copy(revision = 7)
        val result = success(confirm(f, read, cursor))
        assertEquals(7L, result.receipt.source?.revision)
        assertEquals(1L, (read.changes as CollaborationHistoryChanges.Page).window.throughRevision)
        assertEquals(next, read.changes.next)
        assertEquals(1, f.proposals.size)
        assertTrue(f.snapshots.isEmpty())
    }

    @Test
    fun nonemptyRestartForeignAndMalformedPagesCannotReachConfirmation() = runTest {
        for (kind in 0..5) {
            val f = SpaceInboxHistoryAdmissionFixture()
            val initial = page(f)
            val raw = initial.changes as CollaborationHistoryChanges.Page
            val read =
                when (kind) {
                    0 ->
                        initial.copy(
                            changes = raw.copy(eventIds = listOf(f.base.original.primaryEventId))
                        )
                    1 -> initial.copy(changes = CollaborationHistoryChanges.Restart)
                    2 ->
                        initial.copy(
                            owner = initial.owner.copy(organizationId = f.base.generation),
                            changes = CollaborationHistoryChanges.Absent,
                        )
                    3 -> initial.copy(changes = raw.copy(sourceRevision = ""))
                    4 ->
                        initial.copy(
                            changes = raw.copy(window = raw.window.copy(throughRevision = -1))
                        )
                    else ->
                        initial.copy(
                            changes =
                                raw.copy(
                                    next =
                                        CollaborationHistoryChangeCursor.Within(
                                            raw.window,
                                            1,
                                            "invalid cursor",
                                        )
                                )
                        )
                }
            val cursor =
                if (kind == 1)
                    CollaborationHistoryChangeCursor.After(initial.owner, raw.window.generation, 0)
                else null
            assertIs<AppResult.Failed>(confirm(f, read, cursor))
            assertTrue(f.proposals.isEmpty() && f.snapshots.isEmpty())
        }
    }

    @Test
    fun contentionAndUncertainResponsesReturnOnceWithoutRetrying() = runTest {
        for (kind in 0..2) {
            val f = SpaceInboxHistoryAdmissionFixture()
            val read = page(f)
            when (kind) {
                0 -> f.writeConflicts = 1
                1 -> f.revision = "newer-opaque-token"
                else -> f.lostResponse = f.failure
            }
            val result = assertIs<AppResult.Failed>(confirm(f, read))
            assertEquals(
                if (kind == 2) FailureKind.Storage else FailureKind.Conflict,
                result.failure.kind,
            )
            assertEquals(1, f.proposals.size)
            assertEquals(0, f.commits)
            assertTrue(f.snapshots.isEmpty())
        }
    }

    @Test
    fun aReceiptMustCoverTheSameGenerationAndEntireFrozenWindow() = runTest {
        for (kind in 0..3) {
            val f = SpaceInboxHistoryAdmissionFixture()
            val read = page(f)
            val receipt = CollaborationHistoryInboxReceipt(f.sourceVersion, null)
            f.receiptOverride =
                when (kind) {
                    0 -> receipt.copy(source = null)
                    1 -> receipt.copy(source = f.sourceVersion.copy(generation = f.base.generation))
                    2 -> receipt.copy(source = f.sourceVersion.copy(revision = 0))
                    else ->
                        receipt.copy(
                            destination = CollaborationSpaceInboxVersion(f.base.generation, 0, 0)
                        )
                }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(confirm(f, read)).failure.kind,
            )
            assertEquals(1, f.proposals.size)
        }
    }

    @Test
    fun deniedAdmissionAfterEverySuspendedBoundaryDiscardsTheReceipt() = runTest {
        for (boundary in listOf("binding", "index", "publish")) {
            val f = SpaceInboxHistoryAdmissionFixture()
            f.before = { if (it == boundary) f.base.thread.base.deny() }
            assertEquals(AppResult.Failed(f.base.thread.base.denied), confirm(f, page(f)))
            assertEquals(if (boundary == "publish") 1 else 0, f.proposals.size)
            assertEquals(0, f.commits)
        }
    }

    @Test
    fun replacementSessionAccountOrAudienceCannotReceiveLateConfirmation() = runTest {
        for (boundary in listOf("binding", "index", "publish")) for (kind in 0..3) {
            val f = SpaceInboxHistoryAdmissionFixture()
            val session = f.base.thread.native.session
            f.before = {
                if (it == boundary)
                    f.base.thread.identity.value =
                        AppResult.Success(
                            when (kind) {
                                0 -> null
                                1 -> session.copy(id = "replacement")
                                2 -> session.copy(user = session.user.copy(id = f.base.generation))
                                else ->
                                    session.copy(
                                        binding =
                                            session.binding.copy(
                                                audience = "https://foreign.example.test/"
                                            )
                                    )
                            }
                        )
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(confirm(f, page(f))).failure.kind,
            )
            assertEquals(if (boundary == "publish") 1 else 0, f.proposals.size)
        }
    }

    @Test
    fun cancellingAPendingReadOrConfirmationReturnsNoReceipt() = runTest {
        for (boundary in listOf("binding", "index", "publish")) {
            val f = SpaceInboxHistoryAdmissionFixture()
            val entered = CompletableDeferred<Unit>()
            f.before = {
                if (it == boundary) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val result = async { confirm(f, page(f)) }
            entered.await()
            result.cancelAndJoin()
            assertTrue(result.isCancelled)
            assertEquals(0, f.commits)
        }
    }

    @Test
    fun callerMutationAfterCaptureCannotIntroduceUnpublishedReferences() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        val original = page(f)
        val raw = original.changes as CollaborationHistoryChanges.Page
        val events = mutableListOf<String>()
        val read = original.copy(changes = raw.copy(eventIds = events))
        f.before = { if (it == "binding") events += f.base.original.primaryEventId }
        val result = success(confirm(f, read))
        assertEquals(1, events.size)
        assertTrue(result.retainedEventIds.isEmpty() && result.unresolvedEventIds.isEmpty())
        assertTrue(f.snapshots.isEmpty())
        assertNull(f.proposals.single().projection)
    }

    private fun page(f: SpaceInboxHistoryAdmissionFixture): CollaborationSpaceHistoryChangesRead {
        val owner = collaborationHistoryChangeOwner(f.base.location)
        return CollaborationSpaceHistoryChangesRead(
            owner,
            CollaborationHistoryChanges.Page(
                CollaborationHistoryChangeWindow(
                    owner,
                    f.sourceVersion.generation,
                    null,
                    f.sourceVersion.revision,
                ),
                requireNotNull(f.revision),
                emptyList(),
                null,
            ),
        )
    }

    private suspend fun confirm(
        f: SpaceInboxHistoryAdmissionFixture,
        read: CollaborationSpaceHistoryChangesRead,
        cursor: CollaborationHistoryChangeCursor? = null,
    ): AppResult<CollaborationSpaceInboxHistoryCommit> {
        val bindings =
            object : CollaborationSpaceRoomBindingRepository by f.base.thread.bindings {
                override suspend fun getByConversation(
                    access: CollaborationSpaceLocalAccess,
                    conversationId: String,
                ): AppResult<CollaborationSpaceRoomBinding?> {
                    f.boundary("binding")
                    return f.base.thread.bindings.getByConversation(access, conversationId)
                }
            }
        return ConfirmCollaborationSpaceHistoryChanges(
            f.history,
            f.companyHistory,
            f.inbox,
            bindings,
            f.base.thread.directory,
            f.base.thread.sessions,
            f.base.thread.native.server,
        )(f.access, f.id, cursor, read)
    }

    private fun success(result: AppResult<CollaborationSpaceInboxHistoryCommit>) =
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(result).value
}
