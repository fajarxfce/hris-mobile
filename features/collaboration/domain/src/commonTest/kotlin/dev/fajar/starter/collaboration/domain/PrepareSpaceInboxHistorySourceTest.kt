package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class PrepareSpaceInboxHistorySourceTest {
    @Test
    fun firstPreparationUsesTheActualBindingAndReopenDoesNotWriteAgain() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SourceLifecycleFixture(legacy)
            val first =
                assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceCheckpoint>>(f.run())
                    .value
            assertEquals(f.owner, first.progress.owner)
            assertEquals(f.index.id, first.progress.conversationId)
            assertEquals(0L, first.version.revision)
            assertEquals(AppResult.Success(first), f.run())
            assertEquals(1, f.writes.size)
            assertEquals(2, f.reads)
            assertTrue(f.index.references.isEmpty())
            assertTrue(f.index.proposals.isEmpty())
        }
    }

    @Test
    fun replacementIndexResetsTraversalWithoutRemovingUnresolvedReferences() = runTest {
        val f = SourceLifecycleFixture()
        val initial =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceCheckpoint>>(f.run())
                .value
        f.stored = initial.copy(progress = initial.progress.copy(historyComplete = true))
        f.index.current =
            f.index.current.copy(version = CollaborationSpaceInboxVersion(f.other, 0, 0))
        val next =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceCheckpoint>>(f.run())
                .value
        assertEquals(f.other, next.progress.destinationGeneration)
        assertFalse(next.progress.historyComplete)
        assertNull(next.progress.historyCursor)
        assertNull(next.progress.pendingWindow)
        val mutation = f.writes.last()
        assertTrue(mutation.retainedEventIds.isEmpty())
        assertTrue(mutation.unresolvedEventIds.isEmpty())
        assertEquals(initial.version.revision + 1, next.version.revision)
        assertEquals(initial.version.generation, next.version.generation)
    }

    @Test
    fun contentionReturnsOnceAndLostCommittedResponseIsRecoveredWithoutAnotherWrite() = runTest {
        val conflict = SourceLifecycleFixture()
        conflict.onWrite = { AppResult.Success(null) }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(conflict.run()).failure.kind)
        assertEquals(1, conflict.writes.size)
        val f = SourceLifecycleFixture()
        f.onWrite = { mutation ->
            f.save(mutation)
            AppResult.Failed(f.index.failure)
        }
        assertEquals(AppResult.Failed(f.index.failure), f.run())
        val retained = assertNotNull(f.stored)
        assertEquals(AppResult.Success(retained), f.run())
        assertEquals(1, f.writes.size)
    }

    @Test
    fun malformedCheckpointOrIncorrectWriteResultNeverClaimsPreparation() = runTest {
        for (badRevision in listOf(-1L, 1L)) {
            val f = SourceLifecycleFixture()
            f.onWrite = { mutation ->
                AppResult.Success(
                    f.save(mutation)
                        .copy(
                            version =
                                CollaborationSpaceInboxHistoryProgressVersion(f.other, badRevision)
                        )
                )
            }
            assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(f.run()).failure.kind)
        }
        val f = SourceLifecycleFixture()
        assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceCheckpoint>>(f.run())
        f.stored =
            f.stored!!.copy(
                progress = f.stored!!.progress.copy(owner = f.owner.copy(accountId = f.other))
            )
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(1, f.writes.size)
    }

    @Test
    fun missingBindingAndOperationalFailureDoNotStartSourceWork() = runTest {
        val missing = SourceLifecycleFixture()
        missing.thread.room.retained = AppResult.Success(null)
        assertIs<AppResult.Failed>(missing.run())
        assertEquals(0, missing.reads)
        assertTrue(missing.writes.isEmpty())
        val f = SourceLifecycleFixture()
        f.readFailure = f.index.failure
        assertEquals(AppResult.Failed(f.index.failure), f.run())
        assertEquals(1, f.reads)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun denialAtEachSuspensionDiscardsLateResultsAndPreventsFurtherWrites() = runTest {
        for (boundary in listOf("identity", "admission", "binding", "index", "read", "write")) {
            val f = SourceLifecycleFixture()
            f.before = { if (it == boundary) f.thread.base.deny() }
            assertEquals(AppResult.Failed(f.thread.base.denied), f.run())
            assertEquals(if (boundary == "write") 1 else 0, f.writes.size)
        }
    }

    @Test
    fun accountSessionOrAudienceReplacementAfterWritingCannotExposeTheOldCheckpoint() = runTest {
        for (kind in 0..3) {
            val f = SourceLifecycleFixture()
            val session = f.thread.native.session
            f.before = {
                if (it == "write")
                    f.thread.identity.value =
                        AppResult.Success(
                            when (kind) {
                                0 -> null
                                1 -> session.copy(id = "replacement")
                                2 -> session.copy(user = session.user.copy(id = f.other))
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
            assertEquals(1, f.writes.size)
        }
    }

    @Test
    fun cancellationStopsEveryBoundaryIncludingAnUncooperativeCommittedWrite() = runTest {
        for (boundary in listOf("identity", "admission", "binding", "index", "read", "write")) {
            val f = SourceLifecycleFixture()
            val entered = CompletableDeferred<Unit>()
            f.before = {
                if (it == boundary) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val task = async { f.run() }
            entered.await()
            task.cancelAndJoin()
            assertTrue(task.isCancelled)
            assertNull(f.stored)
        }
        val f = SourceLifecycleFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onWrite = { mutation ->
            val committed = f.save(mutation)
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(committed)
        }
        val task = async { f.run() }
        entered.await()
        task.cancel()
        release.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertNotNull(f.stored)
        assertEquals(1, f.writes.size)
    }
}

private class SourceLifecycleFixture(legacy: Boolean = false) {
    val index = SpaceInboxHistoryAdmissionFixture(legacy)
    val thread = index.base.thread
    val access = index.access
    val owner = collaborationHistoryChangeOwner(index.base.location)
    val pass = "e9000000-0000-4000-8000-000000000001"
    val other = "e9000000-0000-4000-8000-000000000009"
    var stored: CollaborationSpaceInboxHistorySourceCheckpoint? = null
    var reads = 0
    val writes = mutableListOf<CollaborationSpaceInboxHistorySourceMutation>()
    var before: suspend (String) -> Unit = {}
    var readFailure: Failure? = null
    var onWrite:
        suspend (CollaborationSpaceInboxHistorySourceMutation) -> AppResult<
                CollaborationSpaceInboxHistorySourceCheckpoint?
            > =
        {
            AppResult.Success(save(it))
        }

    init {
        index.before = { before(it) }
        thread.currentIdentity = {
            before("identity")
            thread.identity.value
        }
        thread.currentAdmission = {
            before("admission")
            thread.base.gate.value
        }
    }

    fun save(
        mutation: CollaborationSpaceInboxHistorySourceMutation
    ): CollaborationSpaceInboxHistorySourceCheckpoint {
        val next =
            CollaborationSpaceInboxHistorySourceCheckpoint(
                CollaborationSpaceInboxHistoryProgressVersion(
                    mutation.expected?.version?.generation ?: other,
                    mutation.expected?.version?.revision?.plus(1) ?: 0,
                ),
                mutation.replacement,
            )
        stored = next
        return next
    }

    val bindings =
        object : CollaborationSpaceRoomBindingRepository by thread.bindings {
            override suspend fun getByConversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> {
                before("binding")
                return thread.bindings.getByConversation(access, conversationId)
            }
        }

    val repository =
        object : CollaborationSpaceInboxHistorySourceRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?> {
                assertEquals(this@SourceLifecycleFixture.access, access)
                assertEquals(index.id, conversationId)
                reads++
                before("read")
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(stored)
            }

            override suspend fun mutate(
                access: CollaborationSpaceLocalAccess,
                mutation: CollaborationSpaceInboxHistorySourceMutation,
            ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?> {
                assertEquals(this@SourceLifecycleFixture.access, access)
                writes += mutation
                before("write")
                return onWrite(mutation)
            }

            override suspend fun sources(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationSpaceInboxHistorySourcePage> = error("No source enumeration")

            override suspend fun pending(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                expected: CollaborationSpaceInboxHistoryProgressVersion,
                limit: Int,
            ): AppResult<CollaborationSpaceInboxHistoryPendingPage?> = error("No pending read")
        }

    suspend fun run() =
        PrepareCollaborationSpaceInboxHistorySource(
            repository,
            index.inbox,
            bindings,
            thread.directory,
            thread.sessions,
            thread.native.server,
        )(access, index.id, pass)
}
