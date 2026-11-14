package dev.fajar.starter.collaboration.presentation

import androidx.paging.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.spaceinbox.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceInboxPagingSourceTest {
    @Test
    fun initialReadBoundsRequestedSizeAndMapsBothOpaqueDirections() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        val page =
            assertIs<
                PagingSource.LoadResult.Page<
                    CollaborationSpaceInboxWindowAnchor,
                    CollaborationSpaceInboxEntry,
                >
            >(
                f.source.load(f.refresh(150))
            )
        assertEquals(f.entries, page.data)
        assertEquals(CollaborationSpaceInboxWindowAnchor.Newer("newer"), page.prevKey)
        assertEquals(CollaborationSpaceInboxWindowAnchor.Older("older"), page.nextKey)
        assertEquals<List<Pair<CollaborationSpaceInboxWindowAnchor, Int>>>(
            listOf(CollaborationSpaceInboxWindowAnchor.Latest to 50),
            f.calls,
        )
        f.source.invalidate()
        runCurrent()
    }

    @Test
    fun refreshKeepsTheVisibleConversationInsteadOfAnOldRevisionCursor() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        val page =
            PagingSource.LoadResult.Page<
                CollaborationSpaceInboxWindowAnchor,
                CollaborationSpaceInboxEntry,
            >(
                f.entries,
                CollaborationSpaceInboxWindowAnchor.Newer("old-before"),
                CollaborationSpaceInboxWindowAnchor.Older("old-after"),
            )
        val state = PagingState(listOf(page), 1, PagingConfig(50, enablePlaceholders = false), 0)
        assertEquals(
            CollaborationSpaceInboxWindowAnchor.Conversation(f.entries[1].conversationId),
            f.source.getRefreshKey(state),
        )
        assertNull(f.source.getRefreshKey(PagingState(emptyList(), null, PagingConfig(50), 0)))
        f.source.invalidate()
        runCurrent()
    }

    @Test
    fun equalAndLaggingNoticesDoNotInvalidateACompletedWindow() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        f.source.load(f.refresh())
        runCurrent()
        f.publish(AppResult.Success(f.version))
        runCurrent()
        f.publish(AppResult.Success(f.version.copy(revision = 3)))
        runCurrent()
        assertFalse(f.source.invalid)
        assertEquals(1, f.calls.size)
        f.source.invalidate()
        runCurrent()
    }

    @Test
    fun aNewRevisionInvalidatesOnceWithoutReadingAnotherPage() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        var invalidations = 0
        f.source.registerInvalidatedCallback { invalidations++ }
        f.source.load(f.refresh())
        runCurrent()
        f.publish(AppResult.Success(f.version.copy(revision = 5)))
        runCurrent()
        f.publish(AppResult.Success(f.version.copy(revision = 6)))
        runCurrent()
        assertTrue(f.source.invalid)
        assertEquals(1, invalidations)
        assertEquals(1, f.calls.size)
    }

    @Test
    fun generationReplacementAndRemovalInvalidateRetainedRows() = runTest {
        for (removed in listOf(false, true)) {
            val f = SpaceInboxPagingFixture(backgroundScope)
            f.source.load(f.refresh())
            runCurrent()
            f.publish(
                AppResult.Success(if (removed) null else f.version.copy(generation = chatId(502)))
            )
            runCurrent()
            assertTrue(f.source.invalid)
            assertEquals(1, f.calls.size)
        }
    }

    @Test
    fun aVersionPublishedDuringTheReadRejectsTheOlderResultSynchronously() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        f.read = {
            f.publish(AppResult.Success(f.version.copy(revision = 5)))
            AppResult.Success(f.window)
        }
        assertIs<PagingSource.LoadResult.Invalid<*, *>>(f.source.load(f.refresh()))
        assertTrue(f.source.invalid)
        runCurrent()
    }

    @Test
    fun anAlreadyLaggingNoticeDoesNotRejectANewerRead() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        f.read = { AppResult.Success(f.window.copy(version = f.version.copy(revision = 5))) }
        assertIs<PagingSource.LoadResult.Page<*, *>>(f.source.load(f.refresh()))
        f.publish(AppResult.Success(f.version))
        runCurrent()
        f.publish(AppResult.Success(f.version.copy(revision = 5)))
        runCurrent()
        assertFalse(f.source.invalid)
        f.source.invalidate()
        runCurrent()
    }

    @Test
    fun changedWithoutANewPublicationWaitsInsteadOfRecreatingInALoop() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        f.read = { AppResult.Success(CollaborationSpaceInboxWindow.Changed) }
        val pending = async { f.source.load(f.refresh()) }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(1, f.calls.size)
        f.publish(AppResult.Success(f.version.copy(revision = 5)))
        runCurrent()
        assertIs<PagingSource.LoadResult.Invalid<*, *>>(pending.await())
        assertTrue(f.source.invalid)
        assertEquals(1, f.calls.size)
    }

    @Test
    fun explicitDisposalReleasesAChangedWaitWithoutAnotherPublication() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        f.read = { AppResult.Success(CollaborationSpaceInboxWindow.Changed) }
        val pending = async { f.source.load(f.refresh()) }
        runCurrent()
        f.source.invalidate()
        runCurrent()
        assertIs<PagingSource.LoadResult.Invalid<*, *>>(pending.await())
        assertEquals(1, f.calls.size)
    }

    @Test
    fun failedReadsRemainErrorsUntilARealPublicationAllowsRecovery() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        f.read = { AppResult.Failed(f.failure) }
        val error = assertIs<PagingSource.LoadResult.Error<*, *>>(f.source.load(f.refresh()))
        assertEquals(f.failure, assertIs<SpaceChatInboxLoadException>(error.throwable).failure)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(f.source.invalid)
        assertEquals(1, f.calls.size)
        f.publish(AppResult.Success(f.version.copy(revision = 5)))
        runCurrent()
        assertTrue(f.source.invalid)
    }

    @Test
    fun observedFailureStopsReadsAndAdmittedRecoveryInvalidatesTheFailedGeneration() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        f.publish(AppResult.Failed(f.failure))
        runCurrent()
        assertIs<PagingSource.LoadResult.Error<*, *>>(f.source.load(f.refresh()))
        assertTrue(f.calls.isEmpty())
        f.publish(AppResult.Success(f.version))
        runCurrent()
        assertTrue(f.source.invalid)
    }

    @Test
    fun cancellationRejectsAnUncooperativeLateWindow() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var returned = false
        f.read = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                AppResult.Success(f.window)
            }
        }
        val job = async {
            f.source.load(f.refresh())
            returned = true
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { job.await() }
        assertFalse(returned)
        f.source.invalidate()
        runCurrent()
    }

    @Test
    fun aFailurePublishedDuringReadCannotPublishCapturedPrivateRows() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        val denial = Failure(FailureKind.AccessDenied, "Denied")
        f.read = {
            f.publish(AppResult.Failed(denial))
            AppResult.Success(f.window)
        }
        val result = assertIs<PagingSource.LoadResult.Error<*, *>>(f.source.load(f.refresh()))
        assertEquals(denial, assertIs<SpaceChatInboxLoadException>(result.throwable).failure)
        assertTrue(f.source.invalid)
        runCurrent()
    }

    @Test
    fun continuationNeverMixesTwoWindowVersionsInOnePagingGeneration() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        f.source.load(f.refresh())
        f.read = { AppResult.Success(f.window.copy(version = f.version.copy(revision = 5))) }
        assertIs<PagingSource.LoadResult.Invalid<*, *>>(
            f.source.load(
                PagingSource.LoadParams.Append(
                    CollaborationSpaceInboxWindowAnchor.Older("older"),
                    50,
                    false,
                )
            )
        )
        assertTrue(f.source.invalid)
        assertEquals(2, f.calls.size)
        runCurrent()
    }

    @Test
    fun invalidLoadDirectionsNeverReachTheDomainRead() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        assertIs<PagingSource.LoadResult.Error<*, *>>(
            f.source.load(
                PagingSource.LoadParams.Refresh(
                    CollaborationSpaceInboxWindowAnchor.Older("older"),
                    50,
                    false,
                )
            )
        )
        assertIs<PagingSource.LoadResult.Error<*, *>>(
            f.source.load(
                PagingSource.LoadParams.Append(
                    CollaborationSpaceInboxWindowAnchor.Newer("newer"),
                    50,
                    false,
                )
            )
        )
        assertTrue(f.calls.isEmpty())
        f.source.invalidate()
        runCurrent()
    }

    @Test
    fun invalidationAndParentDisposalReleaseTheOnlyPublicationObserver() = runTest {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + StandardTestDispatcher(testScheduler))
        val f = SpaceInboxPagingFixture(scope)
        f.source.load(f.refresh())
        runCurrent()
        assertEquals(1, owner.children.count())
        f.source.invalidate()
        runCurrent()
        assertEquals(0, owner.children.count())
        val next = SpaceInboxPagingFixture(scope)
        next.source.load(next.refresh())
        runCurrent()
        assertEquals(1, owner.children.count())
        owner.cancelAndJoin()
        assertEquals(0, owner.children.count())
    }

    @Test
    fun aSuccessfulExplicitRetryClearsTheFailedReadBeforeEqualPublication() = runTest {
        val f = SpaceInboxPagingFixture(backgroundScope)
        f.read = { AppResult.Failed(f.failure) }
        assertIs<PagingSource.LoadResult.Error<*, *>>(f.source.load(f.refresh()))
        f.read = { AppResult.Success(f.window) }
        assertIs<PagingSource.LoadResult.Page<*, *>>(f.source.load(f.refresh()))
        f.publish(AppResult.Success(f.version))
        runCurrent()
        assertFalse(f.source.invalid)
        assertEquals(2, f.calls.size)
        f.source.invalidate()
        runCurrent()
    }
}
