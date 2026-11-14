package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.RetainCollaborationSpaceInboxHistoryChanges
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RetainSpaceInboxHistoryChangesTest {
    @Test
    fun bothCanonicalNamespacesRetainTheCheckedCursorAndUnresolvedOutcome() = runTest {
        for (legacy in listOf(false, true)) {
            val f = HistoryHandoffAdmissionFixture(legacy)
            val retained = success(f.run())
            assertEquals(f.current.version.generation, retained.version.generation)
            assertEquals(1L, retained.version.revision)
            assertEquals(f.changes.owner, retained.progress.owner)
            assertEquals(
                4L,
                assertIs<CollaborationHistoryChangeCursor.After>(retained.progress.historyCursor)
                    .revision,
            )
            assertEquals(listOf(f.changes.event), f.writes.single().unresolvedEventIds)
            assertTrue(retained.progress.historyComplete)
        }
    }

    @Test
    fun anEmptyRawContinuationIsRetainedWithoutFabricatingAReference() = runTest {
        val f = HistoryHandoffAdmissionFixture()
        val next = CollaborationHistoryChangeCursor.Within(f.changes.window, 1, "opaque-a")
        f.read = f.read.copy(changes = f.changes.page.copy(eventIds = emptyList(), next = next))
        f.published = f.published.copy(unresolvedEventIds = emptyList())
        val retained = success(f.run())
        assertEquals(next, retained.progress.historyCursor)
        assertFalse(retained.progress.historyComplete)
        assertTrue(f.writes.single().unresolvedEventIds.isEmpty())
    }

    @Test
    fun confirmedAbsenceCompletesWithoutCreatingASourceVersion() = runTest {
        val f = HistoryHandoffAdmissionFixture()
        f.read = f.read.copy(changes = CollaborationHistoryChanges.Absent)
        f.published =
            f.published.copy(
                receipt = CollaborationHistoryInboxReceipt(null, null),
                unresolvedEventIds = emptyList(),
            )
        val retained = success(f.run())
        assertNull(retained.progress.historyCursor)
        assertTrue(retained.progress.historyComplete)
        assertEquals(1, f.writes.size)
    }

    @Test
    fun staleOrUncertainWritesReturnOnceWithoutGuessingCompletion() = runTest {
        for (conflict in listOf(false, true)) {
            val f = HistoryHandoffAdmissionFixture()
            f.response = { if (conflict) AppResult.Success(null) else AppResult.Failed(f.failure) }
            val result = assertIs<AppResult.Failed>(f.run())
            assertEquals(
                if (conflict) FailureKind.Conflict else FailureKind.Storage,
                result.failure.kind,
            )
            if (!conflict) assertEquals(f.failure, result.failure)
            assertEquals(1, f.writes.size)
        }
    }

    @Test
    fun malformedOrForeignPublicationCannotReachStorage() = runTest {
        for (kind in 0..3) {
            val f = HistoryHandoffAdmissionFixture()
            f.published =
                when (kind) {
                    0 -> f.published.copy(owner = f.published.owner.copy(roomId = "!foreign"))
                    1 -> f.published.copy(retainedEventIds = listOf(f.changes.event))
                    2 -> f.published.copy(unresolvedEventIds = emptyList())
                    else -> f.published.copy(receipt = f.published.receipt.copy(source = null))
                }
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test
    fun theStorageReceiptMustBeTheExactNextVersionAndReplacement() = runTest {
        for (kind in 0..3) {
            val f = HistoryHandoffAdmissionFixture()
            f.response = { write ->
                val expected = f.retained(write)
                AppResult.Success(
                    when (kind) {
                        0 ->
                            expected.copy(
                                version = expected.version.copy(generation = f.changes.generation)
                            )
                        1 -> expected.copy(version = expected.version.copy(revision = 0))
                        2 -> expected.copy(version = expected.version.copy(revision = 2))
                        else ->
                            expected.copy(
                                progress = expected.progress.copy(historyComplete = false)
                            )
                    }
                )
            }
            assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(1, f.writes.size)
        }
    }

    @Test
    fun denialBeforeOrAfterWritingNeverExposesARetainedReceipt() = runTest {
        for (boundary in listOf("admission", "write")) {
            val f = HistoryHandoffAdmissionFixture()
            f.before = { if (it == boundary) f.changes.base.base.deny() }
            assertEquals(AppResult.Failed(f.changes.base.base.denied), f.run())
            assertEquals(if (boundary == "write") 1 else 0, f.writes.size)
        }
    }

    @Test
    fun replacedSessionAccountAndAudienceDiscardLateResults() = runTest {
        for (boundary in listOf("admission", "write")) for (kind in 0..3) {
            val f = HistoryHandoffAdmissionFixture()
            val session = f.changes.base.native.session
            f.before = {
                if (it == boundary)
                    f.changes.base.identity.value =
                        AppResult.Success(
                            when (kind) {
                                0 -> null
                                1 -> session.copy(id = "replacement")
                                2 ->
                                    session.copy(
                                        user = session.user.copy(id = f.changes.generation)
                                    )
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
            assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(if (boundary == "write") 1 else 0, f.writes.size)
        }
    }

    @Test
    fun cancellationAtAnySuspendedBoundaryReturnsNoCompletion() = runTest {
        for (boundary in listOf("identity", "admission", "write")) {
            val f = HistoryHandoffAdmissionFixture()
            val entered = CompletableDeferred<Unit>()
            f.before = {
                if (it == boundary) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val result = async { f.run() }
            entered.await()
            result.cancelAndJoin()
            assertTrue(result.isCancelled)
        }
    }

    @Test
    fun callerMutationDuringAdmissionCannotChangeThePreparedHandoff() = runTest {
        val f = HistoryHandoffAdmissionFixture()
        val ids = mutableListOf(f.changes.event)
        f.published = f.published.copy(unresolvedEventIds = ids)
        f.before = { if (it == "admission") ids.clear() }
        success(f.run())
        assertEquals(listOf(f.changes.event), f.writes.single().unresolvedEventIds)
    }
}

private class HistoryHandoffAdmissionFixture(legacy: Boolean = false) {
    val changes = SpaceHistoryChangesFixture(legacy)
    val access = changes.access
    val current =
        CollaborationSpaceInboxHistorySourceCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(
                "d1000000-0000-4000-8000-000000000001",
                0,
            ),
            CollaborationSpaceInboxHistorySourceProgress(
                changes.id,
                changes.owner,
                null,
                "d2000000-0000-4000-8000-000000000001",
                null,
                false,
                null,
            ),
        )
    var read = CollaborationSpaceHistoryChangesRead(changes.owner, changes.page)
    var published =
        CollaborationSpaceInboxHistoryCommit(
            changes.owner,
            CollaborationHistoryInboxReceipt(
                CollaborationHistorySourceVersion(changes.generation, 4),
                null,
            ),
            emptyList(),
            listOf(changes.event),
        )
    val failure = Failure(FailureKind.Storage, "Fixture uncertain write")
    val writes = mutableListOf<CollaborationSpaceInboxHistorySourceMutation>()
    var before: suspend (String) -> Unit = {}
    var response:
        (CollaborationSpaceInboxHistorySourceMutation) -> AppResult<
                CollaborationSpaceInboxHistorySourceCheckpoint?
            > =
        {
            AppResult.Success(retained(it))
        }
    val repository =
        object : CollaborationSpaceInboxHistorySourceRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?> =
                error("No implicit reread")

            override suspend fun sources(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationSpaceInboxHistorySourcePage> = error("No source scan")

            override suspend fun pending(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                expected: CollaborationSpaceInboxHistoryProgressVersion,
                limit: Int,
            ): AppResult<CollaborationSpaceInboxHistoryPendingPage?> = error("No reference scan")

            override suspend fun mutate(
                access: CollaborationSpaceLocalAccess,
                mutation: CollaborationSpaceInboxHistorySourceMutation,
            ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?> {
                assertEquals(this@HistoryHandoffAdmissionFixture.access, access)
                writes += mutation
                before("write")
                return response(mutation)
            }
        }

    init {
        changes.base.currentIdentity = {
            before("identity")
            changes.base.identity.value
        }
        changes.base.currentAdmission = {
            before("admission")
            changes.base.base.gate.value
        }
    }

    fun retained(write: CollaborationSpaceInboxHistorySourceMutation) =
        CollaborationSpaceInboxHistorySourceCheckpoint(
            current.version.copy(revision = 1),
            write.replacement,
        )

    suspend fun run() =
        RetainCollaborationSpaceInboxHistoryChanges(
            repository,
            changes.base.directory,
            changes.base.sessions,
            changes.base.native.server,
        )(access, current, read, published)
}

private fun <T> success(value: AppResult<T>): T = assertIs<AppResult.Success<T>>(value).value
