package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SpaceInboxHistoryPendingAdmissionTest {
    @Test
    fun pendingReadUsesTheExactPhysicalVersionInBothSourceNamespaces() = runTest {
        for (legacy in listOf(false, true)) {
            val f = PendingHistoryAdmissionFixture(legacy)
            assertEquals(AppResult.Success(f.page), f.run(PendingOperation.Read))
            assertEquals(1, f.reads)
            assertTrue(f.writes.isEmpty())
            assertTrue(f.index.boundaries.isEmpty())
            assertTrue(f.index.references.isEmpty())
        }
    }

    @Test
    fun conflictingForeignAndOversizedPagesNeverReachTheOwner() = runTest {
        val conflict = PendingHistoryAdmissionFixture()
        conflict.onPending = { AppResult.Success(null) }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(conflict.run(PendingOperation.Read)).failure.kind,
        )
        for (kind in 0..2) {
            val f = PendingHistoryAdmissionFixture()
            f.onPending = {
                AppResult.Success(
                    when (kind) {
                        0 -> f.page.copy(version = f.expected.version.copy(revision = 6))
                        1 ->
                            f.page.copy(
                                references = listOf(f.reference.copy(conversationId = f.other))
                            )
                        else -> f.page.copy(references = List(17) { f.reference })
                    }
                )
            }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.run(PendingOperation.Read)).failure.kind,
            )
            assertEquals(1, f.reads)
        }
    }

    @Test
    fun aReturnedPageIsDetachedBeforeTheNextAdmissionSuspends() = runTest {
        val f = PendingHistoryAdmissionFixture()
        val references = mutableListOf(f.reference)
        f.onPending = { AppResult.Success(f.page.copy(references = references)) }
        f.before = { if (it == "admission" && f.reads > 0) references.clear() }
        val read =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryPendingPage>>(
                    f.run(PendingOperation.Read)
                )
                .value
        assertEquals(listOf(f.reference), read.references)
        assertTrue(references.isEmpty())
    }

    @Test
    fun publicationRetentionPreservesRawHistoryAndUsesOneMutation() = runTest {
        val f = PendingHistoryAdmissionFixture()
        val saved =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceCheckpoint>>(
                    f.run(PendingOperation.Retain)
                )
                .value
        assertEquals(6L, saved.version.revision)
        assertEquals(f.expected.progress.historyCursor, saved.progress.historyCursor)
        assertEquals(f.expected.progress.historyComplete, saved.progress.historyComplete)
        assertTrue(saved.progress.pendingWindow!!.complete)
        assertEquals(listOf(f.reference.eventId), f.writes.single().retainedEventIds)
        assertEquals(0, f.reads)
        assertTrue(f.index.boundaries.isEmpty())
    }

    @Test
    fun callerCollectionMutationCannotChangeTheRetainedProposal() = runTest {
        val f = PendingHistoryAdmissionFixture()
        val events = mutableListOf(f.reference.eventId)
        val references = mutableListOf(f.reference)
        f.published = f.published.copy(retainedEventIds = events)
        f.page = f.page.copy(references = references)
        f.before = {
            if (it == "admission") {
                events.clear()
                references.clear()
            }
        }
        assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceCheckpoint>>(
            f.run(PendingOperation.Retain)
        )
        assertEquals(listOf(f.reference.eventId), f.writes.single().retainedEventIds)
    }

    @Test
    fun emptyCompletionChecksOnlyDestinationAndAlreadyCompletedStateDoesNotWrite() = runTest {
        val f = PendingHistoryAdmissionFixture()
        val saved =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourceCheckpoint>>(
                    f.run(PendingOperation.Complete)
                )
                .value
        assertTrue(saved.progress.pendingWindow!!.complete)
        assertTrue(f.writes.single().retainedEventIds.isEmpty())
        assertTrue(f.writes.single().unresolvedEventIds.isEmpty())
        assertEquals(listOf("index"), f.index.boundaries)
        assertTrue(f.index.proposals.isEmpty())
        f.expected = saved
        f.page = f.page.copy(version = saved.version, window = saved.progress.pendingWindow)
        assertEquals(AppResult.Success(saved), f.run(PendingOperation.Complete))
        assertEquals(1, f.writes.size)
    }

    @Test
    fun replacementOrMalformedDestinationPreventsEmptyCompletion() = runTest {
        for (version in
            listOf(
                CollaborationSpaceInboxVersion("f8000000-0000-4000-8000-000000000099", 1, 1),
                CollaborationSpaceInboxVersion("invalid", 1, 1),
            )) {
            val f = PendingHistoryAdmissionFixture()
            f.index.current = f.index.current.copy(version = version)
            assertIs<AppResult.Failed>(f.run(PendingOperation.Complete))
            assertTrue(f.writes.isEmpty())
        }
    }

    @Test
    fun uncertainAndConflictingWritesAreNeverRetriedImplicitly() = runTest {
        for (operation in listOf(PendingOperation.Retain, PendingOperation.Complete)) {
            val f = PendingHistoryAdmissionFixture()
            f.onWrite = { mutation ->
                f.save(mutation)
                AppResult.Failed(f.index.failure)
            }
            assertEquals(AppResult.Failed(f.index.failure), f.run(operation))
            assertNotNull(f.stored)
            assertEquals(1, f.writes.size)
            val conflict = PendingHistoryAdmissionFixture()
            conflict.onWrite = { AppResult.Success(null) }
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(conflict.run(operation)).failure.kind,
            )
            assertEquals(1, conflict.writes.size)
        }
    }

    @Test
    fun denialAfterEachRepositoryBoundaryRejectsItsLateResult() = runTest {
        for (operation in PendingOperation.entries) for (boundary in operation.boundaries()) {
            val f = PendingHistoryAdmissionFixture()
            f.before = { if (it == boundary) f.thread.base.deny() }
            assertEquals(AppResult.Failed(f.thread.base.denied), f.run(operation))
            assertEquals(if (boundary == "write") 1 else 0, f.writes.size)
        }
    }

    @Test
    fun cancellationOwnsEverySuspensionAndDiscardsAnUncooperativeWriteCompletion() = runTest {
        for (operation in PendingOperation.entries) for (boundary in operation.boundaries()) {
            val f = PendingHistoryAdmissionFixture()
            val entered = CompletableDeferred<Unit>()
            f.before = {
                if (it == boundary) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val result = async { f.run(operation) }
            entered.await()
            result.cancelAndJoin()
            assertTrue(result.isCancelled)
        }
        val f = PendingHistoryAdmissionFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onWrite = { mutation ->
            val saved = f.save(mutation)
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(saved)
        }
        val result = async { f.run(PendingOperation.Retain) }
        entered.await()
        result.cancel()
        release.complete(Unit)
        result.join()
        assertTrue(result.isCancelled)
        assertNotNull(f.stored)
    }

    @Test
    fun replacedSessionAccountOrAudienceCannotReceivePriorAccountResults() = runTest {
        for (operation in PendingOperation.entries) for (kind in 0..3) {
            val f = PendingHistoryAdmissionFixture()
            val session = f.thread.native.session
            val boundary = if (operation == PendingOperation.Read) "pending" else "write"
            f.before = {
                if (it == boundary)
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
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.run(operation)).failure.kind,
            )
        }
    }

    @Test
    fun invalidRequestsAndUnexpectedWriteVersionsNeverClaimCompletion() = runTest {
        for (operation in PendingOperation.entries) {
            val f = PendingHistoryAdmissionFixture()
            f.expected = f.expected.copy(version = f.expected.version.copy(revision = -1))
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.run(operation)).failure.kind,
            )
            assertEquals(0, f.reads)
            assertTrue(f.writes.isEmpty())
            assertTrue(f.index.boundaries.isEmpty())
        }
        for (operation in listOf(PendingOperation.Retain, PendingOperation.Complete)) {
            val f = PendingHistoryAdmissionFixture()
            f.onWrite = { AppResult.Success(f.save(it).copy(version = f.expected.version)) }
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.run(operation)).failure.kind,
            )
            assertEquals(1, f.writes.size)
        }
    }
}

private enum class PendingOperation {
    Read,
    Retain,
    Complete;

    fun boundaries() =
        listOf("identity", "admission") +
            when (this) {
                Read -> listOf("pending")
                Retain -> listOf("write")
                Complete -> listOf("index", "write")
            }
}

private class PendingHistoryAdmissionFixture(legacy: Boolean = false) {
    val index = SpaceInboxHistoryAdmissionFixture(legacy)
    val thread = index.base.thread
    val access = index.access
    val other = "f8000000-0000-4000-8000-000000000009"
    val owner = collaborationHistoryChangeOwner(index.base.location)
    val destination = CollaborationSpaceInboxVersion(other, 1, 1)
    var expected =
        CollaborationSpaceInboxHistorySourceCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(other, 5),
            CollaborationSpaceInboxHistorySourceProgress(
                index.id,
                owner,
                other,
                other,
                CollaborationHistoryChangeCursor.After(owner, other, 10),
                true,
                null,
            ),
        )
    val reference =
        CollaborationSpaceInboxHistoryRepairReference(
            index.id,
            owner,
            index.base.original.primaryEventId,
            2,
        )
    var page =
        CollaborationSpaceInboxHistoryPendingPage(
            expected.version,
            CollaborationSpaceInboxHistoryPendingWindow(other, 5, null, false),
            listOf(reference),
            null,
        )
    var published =
        CollaborationSpaceInboxHistoryCommit(
            owner,
            CollaborationHistoryInboxReceipt(
                CollaborationHistorySourceVersion(other, 10),
                destination,
            ),
            listOf(reference.eventId),
            emptyList(),
        )
    var reads = 0
    val writes = mutableListOf<CollaborationSpaceInboxHistorySourceMutation>()
    var stored: CollaborationSpaceInboxHistorySourceCheckpoint? = null
    var before: suspend (String) -> Unit = {}
    var onPending: suspend () -> AppResult<CollaborationSpaceInboxHistoryPendingPage?> = {
        AppResult.Success(page)
    }
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
            ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?> =
                error("No speculative progress read")

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
            ): AppResult<CollaborationSpaceInboxHistoryPendingPage?> {
                assertEquals(this@PendingHistoryAdmissionFixture.access, access)
                assertEquals(index.id, conversationId)
                assertEquals(this@PendingHistoryAdmissionFixture.expected.version, expected)
                assertEquals(16, limit)
                reads++
                before("pending")
                return onPending()
            }

            override suspend fun mutate(
                access: CollaborationSpaceLocalAccess,
                mutation: CollaborationSpaceInboxHistorySourceMutation,
            ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?> {
                assertEquals(this@PendingHistoryAdmissionFixture.access, access)
                writes += mutation
                before("write")
                return onWrite(mutation)
            }
        }

    suspend fun run(operation: PendingOperation): AppResult<*> =
        when (operation) {
            PendingOperation.Read ->
                ReadCollaborationSpaceInboxHistoryPending(
                    repository,
                    thread.directory,
                    thread.sessions,
                    thread.native.server,
                )(access, expected)
            PendingOperation.Retain ->
                RetainCollaborationSpaceInboxHistoryPending(
                    repository,
                    thread.directory,
                    thread.sessions,
                    thread.native.server,
                )(access, expected, page, published)
            PendingOperation.Complete ->
                CompleteCollaborationSpaceInboxHistoryPending(
                    repository,
                    index.inbox,
                    thread.directory,
                    thread.sessions,
                    thread.native.server,
                )(access, expected, page.copy(references = emptyList()))
        }
}
