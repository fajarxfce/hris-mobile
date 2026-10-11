@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class InboxSynchronizationTest {
    @Test
    fun lateAccessFailureAfterWorkspaceReplacementDoesNotClearAnotherOwnersCache() = runTest {
        val f = InboxFixture()
        val pending = CompletableDeferred<AppResult<InboxItem>>()
        f.itemCall = { pending.await() }
        val work = async { f.sync(f.workspace.id) }
        runCurrent()
        f.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
        pending.complete(
            AppResult.Failed(
                Failure(FailureKind.AccessDenied, "Access revoked", code = "company_access_denied")
            )
        )
        assertEquals(
            "client.workspace_changed",
            assertIs<AppResult.Failed>(work.await()).failure.code,
        )
        assertEquals(0, f.clearCalls)
    }

    @Test
    fun bootstrapReconcilesNewerVersionsBeforePublishingOneCompleteSnapshot() = runTest {
        val f = InboxFixture()
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    listOf(SyncChange(f.reference.copy(version = 2), SyncOperation.UPSERT)),
                    "caught-up",
                    false,
                    false,
                    5,
                    f.clock.now(),
                )
            )
        }
        f.itemCall = { AppResult.Success(f.item.copy(version = if (f.changesCalls == 0) 1 else 3)) }
        assertTrue(
            assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id)).value.caughtUp
        )
        assertEquals(listOf(false, true), f.commits.map { it.published })
        assertEquals("caught-up", f.checkpoint!!.cursor)
        assertEquals(
            3,
            assertIs<AppResult.Success<InboxCachePage>>(f.snapshot.value)
                .value
                .items
                .single()
                .version,
        )
    }

    @Test
    fun hydrationFailureDoesNotAdvanceTheCursorAndTheSamePageCanBeRetried() = runTest {
        val f = InboxFixture()
        val failure =
            AppResult.Failed(Failure(FailureKind.Network, "Offline", code = "network_unavailable"))
        f.itemCall = { failure }
        assertSame(failure, f.sync(f.workspace.id))
        assertEquals(0, f.checkpoint!!.revision)
        assertNull(f.checkpoint!!.cursor)
        assertTrue(f.commits.isEmpty())
        f.itemCall = { AppResult.Success(f.item) }
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        assertEquals(2, f.bootstrapCalls)
    }

    @Test
    fun lateHydrationAfterCompanyChangeCannotPublishOrReplaceItsFailureClassification() = runTest {
        val f = InboxFixture()
        val pending = CompletableDeferred<AppResult<InboxItem>>()
        f.itemCall = { pending.await() }
        val work = async { f.sync(f.workspace.id) }
        runCurrent()
        f.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
        pending.complete(AppResult.Success(f.item))
        assertEquals(
            "client.workspace_changed",
            assertIs<AppResult.Failed>(work.await()).failure.code,
        )
        assertTrue(f.commits.isEmpty())
    }

    @Test
    fun cancellationDuringAnUncooperativeDownloadLeavesOnlyTheOriginalStagingCheckpoint() =
        runTest {
            val f = InboxFixture()
            val pending = CompletableDeferred<AppResult<InboxItem>>()
            f.itemCall = { withContext(NonCancellable) { pending.await() } }
            val work = launch { f.sync(f.workspace.id) }
            runCurrent()
            work.cancel()
            pending.complete(AppResult.Success(f.item))
            work.join()
            assertTrue(work.isCancelled)
            assertTrue(f.commits.isEmpty())
            assertEquals(0, f.checkpoint!!.revision)
        }

    @Test
    fun anEqualVersionTombstoneCannotBeUndoneByALaterDuplicateUpsert() = runTest {
        val f = InboxFixture()
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    listOf(
                        SyncChange(f.reference, SyncOperation.DELETE),
                        SyncChange(f.reference, SyncOperation.UPSERT),
                    ),
                    "deleted",
                    false,
                    false,
                    5,
                    f.clock.now(),
                )
            )
        }
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        assertEquals(1, f.itemCalls)
        assertNull(f.entries[f.item.id]!!.item)
        assertTrue(
            assertIs<AppResult.Success<InboxCachePage>>(f.snapshot.value).value.items.isEmpty()
        )
    }

    @Test
    fun workerPublicationWaitUsesTheServersDelayAndDoesNotPublishAPartialBootstrap() = runTest {
        val base = Instant.parse("2026-10-11T00:00:00Z")
        val f = InboxFixture { base + testScheduler.currentTime.milliseconds }
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    emptyList(),
                    "delta-" + f.changesCalls,
                    false,
                    f.changesCalls == 1,
                    5,
                    f.clock.now(),
                )
            )
        }
        assertTrue(
            assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id)).value.caughtUp
        )
        assertEquals(5000, testScheduler.currentTime)
        assertEquals(listOf(false, false, true), f.commits.map { it.published })
    }

    @Test
    fun repeatedScopeErrorsResetAtMostOnceAndKeepTheServerFailure() = runTest {
        val f = InboxFixture()
        val failure =
            AppResult.Failed(
                Failure(FailureKind.Conflict, "Scope changed", code = "sync_scope_changed")
            )
        f.bootstrapCall = { failure }
        assertSame(failure, f.sync(f.workspace.id))
        assertEquals(1, f.clearCalls)
        assertEquals(2, f.bootstrapCalls)
    }

    @Test
    fun cacheExpiryRemovesPrivateContentWithoutWaitingForAnotherDatabaseWrite() = runTest {
        val base = Instant.parse("2026-10-11T00:00:00Z")
        val f = InboxFixture { base + testScheduler.currentTime.milliseconds }
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        val cp = f.checkpoint!!.copy(expiresAt = base + 1000.milliseconds)
        f.snapshot.value = AppResult.Success(InboxCachePage(cp, listOf(f.item), null))
        val emissions = mutableListOf<AppResult<InboxCachePage>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id, InboxCacheQuery.Page()).collect { emissions.add(it) }
            }
        assertIs<AppResult.Success<InboxCachePage>>(emissions.last())
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(
            "client.inbox_cache_expired",
            assertIs<AppResult.Failed>(emissions.last()).failure.code,
        )
        reader.cancelAndJoin()
    }

    @Test
    fun replacingTheSessionOrLosingPermissionStopsAnExistingCacheObserver() = runTest {
        val f = InboxFixture()
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        val emissions = mutableListOf<AppResult<InboxCachePage>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id, InboxCacheQuery.Item(f.item.id)).collect {
                    emissions.add(it)
                }
            }
        f.workspaceState.value = AppResult.Success(f.workspace.copy(permissions = emptySet()))
        runCurrent()
        assertEquals(
            "sync_access_denied",
            assertIs<AppResult.Failed>(emissions.last()).failure.code,
        )
        f.sessionState.value = AppResult.Success(f.session.copy(id = "another-account"))
        runCurrent()
        assertEquals(
            "client.session_changed",
            assertIs<AppResult.Failed>(emissions.last()).failure.code,
        )
        reader.cancelAndJoin()
    }
}
