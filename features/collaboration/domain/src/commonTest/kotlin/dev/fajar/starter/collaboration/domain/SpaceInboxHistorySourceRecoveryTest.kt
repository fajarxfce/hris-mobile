package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SpaceInboxHistorySourceRecoveryTest {
    @Test
    fun currentReadReturnsTheExactOlderPassOrAbsenceInBothNamespaces() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SourceRecoveryFixture(legacy)
            assertEquals(AppResult.Success(f.expected), f.read())
            f.stored = null
            assertEquals(AppResult.Success(null), f.read())
            assertEquals(2, f.reads)
            assertTrue(f.writes.isEmpty())
            assertTrue(f.index.boundaries.isEmpty())
        }
    }

    @Test
    fun foreignOrMalformedStoredProgressCannotBeReturned() = runTest {
        for (kind in 0..2) {
            val f = SourceRecoveryFixture()
            f.stored =
                when (kind) {
                    0 -> f.expected.copy(version = f.expected.version.copy(revision = -1))
                    1 ->
                        f.expected.copy(
                            progress =
                                f.expected.progress.copy(owner = f.owner.copy(accountId = f.pass))
                        )
                    else ->
                        f.expected.copy(
                            progress =
                                f.expected.progress.copy(
                                    conversationId = f.pass,
                                    owner = f.owner.copy(conversationId = f.pass),
                                )
                        )
                }
            assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(f.read()).failure.kind)
            assertEquals(1, f.reads)
        }
    }

    @Test
    fun explicitRestartResetsBothTraversalWindowsWithoutRemovingReferences() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SourceRecoveryFixture(legacy)
            val saved =
                assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceCheckpoint>>(
                        f.restart()
                    )
                    .value
            assertEquals(f.expected.progress.passId, saved.progress.passId)
            assertEquals(f.owner, saved.progress.owner)
            assertNull(saved.progress.historyCursor)
            assertFalse(saved.progress.historyComplete)
            assertNull(saved.progress.pendingWindow)
            assertEquals(f.expected.version.generation, saved.version.generation)
            assertEquals(f.expected.version.revision + 1, saved.version.revision)
            val write = f.writes.single()
            assertTrue(write.retainedEventIds.isEmpty())
            assertTrue(write.unresolvedEventIds.isEmpty())
            assertEquals(0, f.reads)
            assertEquals(listOf("index"), f.index.boundaries)
        }
    }

    @Test
    fun alreadyResetCompletedOrExhaustedProgressCannotRestartAgain() = runTest {
        for (kind in 0..2) {
            val f = SourceRecoveryFixture()
            f.expected =
                when (kind) {
                    0 -> f.expected.copy(progress = f.expected.progress.copy(historyCursor = null))
                    1 ->
                        f.expected.copy(progress = f.expected.progress.copy(historyComplete = true))
                    else ->
                        f.expected.copy(
                            version =
                                f.expected.version.copy(
                                    revision = COLLABORATION_SPACE_INBOX_MAX_ORDER
                                )
                        )
                }
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.restart()).failure.kind)
            assertTrue(f.writes.isEmpty())
            assertTrue(f.index.boundaries.isEmpty())
        }
    }

    @Test
    fun anotherReaderOutcomeOrOwnerCannotAuthorizeARestart() = runTest {
        val f = SourceRecoveryFixture()
        for (read in
            listOf(
                f.raw.copy(changes = CollaborationHistoryChanges.Absent),
                f.raw.copy(owner = f.owner.copy(roomId = "!foreign:example.test")),
            )) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.restart(read)).failure.kind,
        )
        assertTrue(f.writes.isEmpty())
        assertTrue(f.index.boundaries.isEmpty())
    }

    @Test
    fun destinationReplacementOrProgressContentionReturnsOnce() = runTest {
        val f = SourceRecoveryFixture()
        f.index.current = f.index.current.copy(version = f.destination.copy(generation = f.pass))
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.restart()).failure.kind)
        assertTrue(f.writes.isEmpty())
        val conflict = SourceRecoveryFixture()
        conflict.onWrite = { AppResult.Success(null) }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(conflict.restart()).failure.kind,
        )
        assertEquals(1, conflict.writes.size)
    }

    @Test
    fun lostRestartResponseIsInspectedWithoutInventingAnotherTransition() = runTest {
        val f = SourceRecoveryFixture()
        f.onWrite = {
            f.save(it)
            AppResult.Failed(f.index.failure)
        }
        assertEquals(AppResult.Failed(f.index.failure), f.restart())
        val stored = assertNotNull(f.stored)
        assertEquals(AppResult.Success(stored), f.read())
        f.expected = stored
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.restart()).failure.kind)
        assertEquals(1, f.writes.size)
    }

    @Test
    fun denialAndIdentityReplacementDiscardReadAndWriteResults() = runTest {
        for (restart in listOf(false, true)) for (kind in 0..4) {
            val f = SourceRecoveryFixture()
            val session = f.thread.native.session
            val boundary = if (restart) "write" else "read"
            f.before = {
                if (it == boundary) {
                    if (kind == 0) f.thread.base.deny()
                    else
                        f.thread.identity.value =
                            AppResult.Success(
                                when (kind) {
                                    1 -> null
                                    2 -> session.copy(id = "replacement")
                                    3 -> session.copy(user = session.user.copy(id = f.pass))
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
            }
            val result = if (restart) f.restart() else f.read()
            if (kind == 0) assertEquals(AppResult.Failed(f.thread.base.denied), result)
            else
                assertEquals(
                    FailureKind.Unauthorized,
                    assertIs<AppResult.Failed>(result).failure.kind,
                )
        }
    }

    @Test
    fun cancellationStopsEachSuspensionAndDiscardsAnUncooperativeCommittedRestart() = runTest {
        for (restart in listOf(false, true)) for (boundary in
            listOf("identity", "admission") +
                if (restart) listOf("index", "write") else listOf("read")) {
            val f = SourceRecoveryFixture()
            val entered = CompletableDeferred<Unit>()
            f.before = {
                if (it == boundary) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val task = async { if (restart) f.restart() else f.read() }
            entered.await()
            task.cancelAndJoin()
            assertTrue(task.isCancelled)
        }
        val f = SourceRecoveryFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onWrite = { mutation ->
            val stored = f.save(mutation)
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(stored)
        }
        val task = async { f.restart() }
        entered.await()
        task.cancel()
        release.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertNull(f.stored!!.progress.historyCursor)
    }

    @Test
    fun unexpectedWriteVersionAndStorageFailuresNeverClaimSuccess() = runTest {
        val f = SourceRecoveryFixture()
        f.onWrite = { AppResult.Success(f.save(it).copy(version = f.expected.version)) }
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(f.restart()).failure.kind)
        assertEquals(1, f.writes.size)
        val failed = SourceRecoveryFixture()
        failed.readFailure = failed.index.failure
        assertEquals(AppResult.Failed(failed.index.failure), failed.read())
        assertEquals(1, failed.reads)
        assertTrue(failed.writes.isEmpty())
    }
}

private class SourceRecoveryFixture(legacy: Boolean = false) {
    val index = SpaceInboxHistoryAdmissionFixture(legacy)
    val thread = index.base.thread
    val access = index.access
    val owner = collaborationHistoryChangeOwner(index.base.location)
    val generation = "f7000000-0000-4000-8000-000000000001"
    val pass = "f7000000-0000-4000-8000-000000000002"
    val destination = CollaborationSpaceInboxVersion(generation, 2, 2)
    var expected =
        CollaborationSpaceInboxHistorySourceCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(generation, 7),
            CollaborationSpaceInboxHistorySourceProgress(
                index.id,
                owner,
                generation,
                pass,
                CollaborationHistoryChangeCursor.After(owner, generation, 5),
                false,
                CollaborationSpaceInboxHistoryPendingWindow(generation, 4, "old-pending", true),
            ),
        )
    val raw = CollaborationSpaceHistoryChangesRead(owner, CollaborationHistoryChanges.Restart)
    var stored: CollaborationSpaceInboxHistorySourceCheckpoint? = expected
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
        index.current = index.current.copy(version = destination)
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
        val saved =
            CollaborationSpaceInboxHistorySourceCheckpoint(
                expected.version.copy(revision = expected.version.revision + 1),
                mutation.replacement,
            )
        stored = saved
        return saved
    }

    val repository =
        object : CollaborationSpaceInboxHistorySourceRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?> {
                assertEquals(this@SourceRecoveryFixture.access, access)
                assertEquals(index.id, conversationId)
                reads++
                before("read")
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(stored)
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
            ): AppResult<CollaborationSpaceInboxHistoryPendingPage?> =
                error("No pending acquisition")

            override suspend fun mutate(
                access: CollaborationSpaceLocalAccess,
                mutation: CollaborationSpaceInboxHistorySourceMutation,
            ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?> {
                assertEquals(this@SourceRecoveryFixture.access, access)
                writes += mutation
                before("write")
                return onWrite(mutation)
            }
        }

    suspend fun read() =
        ReadCollaborationSpaceInboxHistorySource(
            repository,
            thread.directory,
            thread.sessions,
            thread.native.server,
        )(access, index.id)

    suspend fun restart(value: CollaborationSpaceHistoryChangesRead = raw) =
        RestartCollaborationSpaceInboxHistorySource(
            repository,
            index.inbox,
            thread.directory,
            thread.sessions,
            thread.native.server,
        )(access, expected, value)
}
