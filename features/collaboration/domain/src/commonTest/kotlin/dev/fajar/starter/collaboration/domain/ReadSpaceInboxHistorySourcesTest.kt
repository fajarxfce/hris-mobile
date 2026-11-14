package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadSpaceInboxHistorySourcesTest {
    @Test
    fun bothNamespacesReturnOwnedSourcesAndPreserveTheOpaqueContinuation() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SourceEnumerationFixture(legacy)
            f.page = f.page.copy(next = "opaque-next")
            assertEquals(AppResult.Success(f.page), f.run("opaque-before", 1))
            assertEquals(listOf<Pair<String?, Int>>("opaque-before" to 1), f.reads)
            assertTrue(f.index.boundaries.isEmpty())
            f.page = CollaborationSpaceInboxHistorySourcePage(emptyList(), null)
            assertEquals(AppResult.Success(f.page), f.run("opaque-next", 1))
        }
    }

    @Test
    fun invalidRequestDoesNotAcquireStorage() = runTest {
        val f = SourceEnumerationFixture()
        for (limit in listOf(0, 17)) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.run(limit = limit)).failure.kind,
        )
        for (after in listOf("", "x".repeat(4097))) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.run(after)).failure.kind,
        )
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.operation(f.access.copy(spaceId = "invalid"))).failure.kind,
        )
        assertTrue(f.reads.isEmpty())
    }

    @Test
    fun malformedForeignDuplicatedOrUnboundedPagesCannotEscape() = runTest {
        val f = SourceEnumerationFixture()
        val original = f.page
        val foreign =
            f.source.copy(
                progress = f.source.progress.copy(owner = f.owner.copy(accountId = f.other))
            )
        val invalid = f.source.copy(version = f.source.version.copy(revision = -1))
        for (page in
            listOf(
                original.copy(sources = listOf(foreign)),
                original.copy(sources = listOf(invalid)),
                original.copy(sources = listOf(f.source, f.source)),
                original.copy(sources = List(17) { f.source }),
                original.copy(next = ""),
                original.copy(next = "x".repeat(4097)),
                original.copy(next = "same"),
                original.copy(sources = emptyList(), next = "later"),
            )) {
            f.page = page
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.run("same", 1)).failure.kind,
            )
        }
        assertEquals(8, f.reads.size)
    }

    @Test
    fun pageCollectionsAreCapturedBeforeThePostReadAdmissionSuspends() = runTest {
        val f = SourceEnumerationFixture()
        val mutable = mutableListOf(f.source)
        f.page = f.page.copy(sources = mutable)
        f.before = { if (it == "admission" && f.reads.isNotEmpty()) mutable.clear() }
        val returned =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistorySourcePage>>(f.run()).value
        assertEquals(listOf(f.source), returned.sources)
        assertTrue(mutable.isEmpty())
        assertEquals(1, f.reads.size)
    }

    @Test
    fun storageFailureIsReturnedOnceWithoutBindingOrHistoryAcquisition() = runTest {
        val f = SourceEnumerationFixture()
        f.failure = f.index.failure
        assertEquals(AppResult.Failed(f.index.failure), f.run())
        assertEquals(1, f.reads.size)
        assertTrue(f.index.boundaries.isEmpty())
    }

    @Test
    fun denialAtEachBoundaryAndIdentityReplacementDiscardLatePages() = runTest {
        for (boundary in listOf("identity", "admission", "read")) {
            val f = SourceEnumerationFixture()
            f.before = { if (it == boundary) f.thread.base.deny() }
            assertEquals(AppResult.Failed(f.thread.base.denied), f.run())
            assertEquals(if (boundary == "read") 1 else 0, f.reads.size)
        }
        for (kind in 0..3) {
            val f = SourceEnumerationFixture()
            val session = f.thread.native.session
            f.before = {
                if (it == "read")
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
            assertEquals(1, f.reads.size)
        }
    }

    @Test
    fun cancellationStopsEverySuspensionIncludingAnUncooperativeRead() = runTest {
        for (boundary in listOf("identity", "admission", "read")) {
            val f = SourceEnumerationFixture()
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
        }
        val f = SourceEnumerationFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.before = {
            if (it == "read")
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
        }
        val task = async { f.run() }
        entered.await()
        task.cancel()
        release.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, f.reads.size)
    }

    @Test
    fun stableSourceOrderIsCheckedWithoutDecodingTheContinuation() = runTest {
        val f = SourceEnumerationFixture()
        val another =
            f.source.copy(
                progress =
                    f.source.progress.copy(
                        conversationId = f.other,
                        owner = f.owner.copy(conversationId = f.other),
                    )
            )
        val ordered = listOf(f.source, another).sortedBy { it.progress.conversationId }
        f.page = f.page.copy(sources = ordered, next = "opaque")
        assertEquals(AppResult.Success(f.page), f.run(limit = 2))
        f.page = f.page.copy(sources = ordered.reversed())
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.run(limit = 2)).failure.kind,
        )
    }
}

private class SourceEnumerationFixture(legacy: Boolean = false) {
    val index = SpaceInboxHistoryAdmissionFixture(legacy)
    val thread = index.base.thread
    val access = index.access
    val owner = collaborationHistoryChangeOwner(index.base.location)
    val other = "e7000000-0000-4000-8000-000000000099"
    val source =
        CollaborationSpaceInboxHistorySourceCheckpoint(
            CollaborationSpaceInboxHistoryProgressVersion(other, 1),
            CollaborationSpaceInboxHistorySourceProgress(
                index.id,
                owner,
                null,
                other,
                null,
                false,
                null,
            ),
        )
    var page = CollaborationSpaceInboxHistorySourcePage(listOf(source), null)
    var failure: Failure? = null
    val reads = mutableListOf<Pair<String?, Int>>()
    var before: suspend (String) -> Unit = {}

    init {
        thread.currentIdentity = {
            before("identity")
            thread.identity.value
        }
        thread.currentAdmission = {
            before("admission")
            thread.base.gate.value
        }
    }

    val repository =
        object : CollaborationSpaceInboxHistorySourceRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?> =
                error("Unexpected exact read")

            override suspend fun mutate(
                access: CollaborationSpaceLocalAccess,
                mutation: CollaborationSpaceInboxHistorySourceMutation,
            ): AppResult<CollaborationSpaceInboxHistorySourceCheckpoint?> =
                error("Unexpected mutation")

            override suspend fun pending(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                expected: CollaborationSpaceInboxHistoryProgressVersion,
                limit: Int,
            ): AppResult<CollaborationSpaceInboxHistoryPendingPage?> =
                error("Unexpected pending read")

            override suspend fun sources(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationSpaceInboxHistorySourcePage> {
                assertEquals(this@SourceEnumerationFixture.access, access)
                reads += after to limit
                before("read")
                return failure?.let { AppResult.Failed(it) } ?: AppResult.Success(page)
            }
        }
    val operation =
        ReadCollaborationSpaceInboxHistorySources(
            repository,
            thread.directory,
            thread.sessions,
            thread.native.server,
        )

    suspend fun run(after: String? = null, limit: Int = 16) = operation(access, after, limit)
}
