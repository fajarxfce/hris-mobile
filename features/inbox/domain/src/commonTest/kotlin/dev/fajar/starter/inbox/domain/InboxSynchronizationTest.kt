@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.initialInboxCheckpoint
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class InboxSynchronizationTest {
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publishedStatusRetainsPreviousCompletionWhileStagingAndClosesOnDenial() = runTest {
        val f = InboxFixture()
        val observation: ObserveCompanyData = f.observe
        val acquisition: SyncCompanyData = f.sync
        val workspaceId = f.workspace.id
        assertEquals(CompanyDataSet.Inbox, observation.dataSet)
        assertEquals(observation.dataSet, acquisition.dataSet)
        val values = mutableListOf<AppResult<CompanyDataStatus>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observation.observeStatus(workspaceId).collect { values += it }
            }
        runCurrent()
        assertEquals(AppResult.Success(CompanyDataStatus(null)), values.last())
        assertTrue(
            assertIs<AppResult.Success<CompanyDataSyncOutcome>>(acquisition.refresh(workspaceId))
                .value
                .caughtUp
        )
        runCurrent()
        val published = requireNotNull(f.checkpoint)
        val expected = AppResult.Success(CompanyDataStatus(assertNotNull(published.refreshedAt)))
        assertEquals(expected, values.last())
        val replacement =
            initialInboxCheckpoint(published.access, "status-replacement", published.refreshedAt)
        assertEquals(AppResult.Success(true), f.cache.begin(replacement, published.generation))
        runCurrent()
        assertEquals(expected, values.last())
        val denial =
            Failure(FailureKind.AccessDenied, "Verification required", code = "mfa_required")
        f.cache.restrict(published.access, denial)
        runCurrent()
        assertEquals(AppResult.Failed(denial), values.last())
        assertEquals(AppResult.Failed(denial), observation.observeStatus(workspaceId).first())
        assertEquals(0, f.snapshot.subscriptionCount.value)
        reader.cancelAndJoin()
        assertEquals(0, f.snapshot.subscriptionCount.value)
    }

    @Test
    fun confirmedAccessFailuresKeepTheirClassificationWhenCacheCleanupFailsOrStalls() = runTest {
        for (stage in listOf("policy", "changes", "hydration")) {
            for (stalls in listOf(false, true)) {
                val f = InboxFixture()
                assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
                val published = f.snapshot.value
                val denial =
                    AppResult.Failed(
                        Failure(
                            FailureKind.AccessDenied,
                            "Verification required",
                            code = "mfa_required",
                        )
                    )
                f.clearCall = {
                    if (stalls) awaitCancellation()
                    AppResult.Failed(
                        Failure(FailureKind.Storage, "Storage unavailable", code = "client.storage")
                    )
                }
                when (stage) {
                    "policy" -> f.policyCall = { denial }
                    "changes" -> f.changesCall = { denial }
                    else -> {
                        f.changesCall = {
                            AppResult.Success(
                                SyncChangePage(
                                    listOf(SyncChange(f.reference, SyncOperation.UPSERT)),
                                    "next",
                                    false,
                                    false,
                                    5,
                                    f.clock.now(),
                                )
                            )
                        }
                        f.itemCall = { denial }
                    }
                }
                assertEquals(denial, f.sync(f.workspace.id))
                assertEquals(1, f.clearCalls)
                assertEquals(published, f.snapshot.value)
            }
        }
    }

    @Test
    fun scopeResetGatesPublishedFactsAndStagesTheNextAdmittedAcquisition() = runTest {
        val f = InboxFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val original = f.snapshot.value
        val generation = f.checkpoint!!.generation
        val denial =
            AppResult.Failed(
                Failure(FailureKind.Conflict, "Scope changed", code = "sync_scope_changed")
            )
        f.changesCall = { denial }
        f.clearCall = { AppResult.Failed(Failure(FailureKind.Storage, "Storage unavailable")) }
        assertEquals(denial, f.sync(f.workspace.id))
        assertEquals(original, f.snapshot.value)
        assertNotEquals(generation, f.checkpoint!!.generation)
        assertFalse(f.checkpoint!!.published)
        assertEquals(denial, f.observe(f.workspace.id, InboxCacheQuery.Page()).first())
        assertEquals(1, f.bootstrapCalls)
    }

    @Test
    fun renewingWorkspaceAccessKeepsThePublishedGenerationAndContinuesFromItsCursor() = runTest {
        val base = Instant.parse("2026-10-11T00:00:00Z")
        val f = InboxFixture { base + testScheduler.currentTime.milliseconds }
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        val generation = f.checkpoint!!.generation
        advanceTimeBy(30.minutes)
        f.workspaceState.value =
            AppResult.Success(
                f.workspace.copy(
                    admission =
                        f.workspace.admission.copy(
                            checkedAt = f.clock.now(),
                            expiresAt = f.clock.now() + 1.hours,
                        )
                )
            )
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        assertEquals(1, f.bootstrapCalls)
        assertEquals(0, f.clearCalls)
        assertEquals(generation, f.checkpoint!!.generation)
        assertEquals(
            listOf(f.item),
            assertIs<AppResult.Success<InboxCachePage>>(f.snapshot.value).value.items,
        )
    }

    @Test
    fun workspaceAuthorizationExpiryDoesNotHideStoredMessages() = runTest {
        val base = Instant.parse("2026-10-11T00:00:00Z")
        val f = InboxFixture { base + testScheduler.currentTime.milliseconds }
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        f.workspaceState.value =
            AppResult.Success(
                f.workspace.copy(
                    admission = f.workspace.admission.copy(expiresAt = base + 1000.milliseconds)
                )
            )
        f.snapshot.value =
            AppResult.Success(
                InboxCachePage(
                    f.checkpoint!!.copy(refreshAfter = base + 2.hours),
                    listOf(f.item),
                    null,
                )
            )
        val emissions = mutableListOf<AppResult<InboxCachePage>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id, InboxCacheQuery.Page()).collect { emissions.add(it) }
            }
        assertIs<AppResult.Success<InboxCachePage>>(emissions.last())
        advanceTimeBy(1001)
        runCurrent()
        advanceTimeBy(72.hours)
        runCurrent()
        assertEquals(
            listOf(f.item),
            assertIs<AppResult.Success<InboxCachePage>>(emissions.last()).value.items,
        )
        assertEquals(1, emissions.size)
        assertEquals(0, f.clearCalls)
        reader.cancelAndJoin()
    }

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
    fun repeatedScopeErrorsDoNotRetryBeforeRecordingTheNewDenial() = runTest {
        val f = InboxFixture()
        val failure =
            AppResult.Failed(
                Failure(FailureKind.Conflict, "Scope changed", code = "sync_scope_changed")
            )
        f.bootstrapCall = { failure }
        assertSame(failure, f.sync(f.workspace.id))
        assertEquals(0, f.clearCalls)
        assertEquals(1, f.bootstrapCalls)
        assertFalse(f.checkpoint!!.published)
        assertEquals(failure, f.observe(f.workspace.id, InboxCacheQuery.Page()).first())
        assertSame(failure, f.sync(f.workspace.id))
        assertEquals(2, f.bootstrapCalls)
        assertEquals(2, f.restrictionWrites)
    }

    @Test
    fun storedMessagesRemainReadableAfterTheirFreshnessInterval() = runTest {
        val base = Instant.parse("2026-10-11T00:00:00Z")
        val f = InboxFixture { base + testScheduler.currentTime.milliseconds }
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        val cp = f.checkpoint!!.copy(refreshAfter = base + 1000.milliseconds)
        f.snapshot.value = AppResult.Success(InboxCachePage(cp, listOf(f.item), null))
        val emissions = mutableListOf<AppResult<InboxCachePage>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id, InboxCacheQuery.Page()).collect { emissions.add(it) }
            }
        assertIs<AppResult.Success<InboxCachePage>>(emissions.last())
        advanceTimeBy(1001)
        runCurrent()
        advanceTimeBy(72.hours)
        runCurrent()
        assertEquals(
            listOf(f.item),
            assertIs<AppResult.Success<InboxCachePage>>(emissions.last()).value.items,
        )
        assertEquals(1, emissions.size)
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

    @Test
    fun staleFreshnessContinuesTheExistingDeltaCursorInsteadOfDownloadingEverythingAgain() =
        runTest {
            val base = Instant.parse("2026-10-11T00:00:00Z")
            val f = InboxFixture { base + testScheduler.currentTime.milliseconds }
            assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
            val original = requireNotNull(f.checkpoint)
            advanceTimeBy(2.hours)
            f.changesCall = { cursor ->
                assertEquals(original.cursor, cursor)
                AppResult.Success(
                    SyncChangePage(emptyList(), "delta-next", false, false, 5, f.clock.now())
                )
            }
            assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
            assertEquals(1, f.bootstrapCalls)
            assertEquals(0, f.clearCalls)
            assertEquals(original.generation, f.checkpoint!!.generation)
            assertEquals("delta-next", f.checkpoint!!.cursor)
            assertEquals(
                listOf(f.item),
                assertIs<AppResult.Success<InboxCachePage>>(f.snapshot.value).value.items,
            )
        }

    @Test
    fun anExpiredCursorStagesReplacementWithoutHidingTheLastPublishedInbox() = runTest {
        val base = Instant.parse("2026-10-11T00:00:00Z")
        val f = InboxFixture { base + testScheduler.currentTime.milliseconds }
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        val original = requireNotNull(f.checkpoint)
        val originalPage = f.snapshot.value
        val hydration = CompletableDeferred<AppResult<InboxItem>>()
        f.changesCall = {
            if (f.changesCalls == 2)
                AppResult.Failed(
                    Failure(FailureKind.Conflict, "fixture", code = "sync_cursor_expired")
                )
            else
                AppResult.Success(
                    SyncChangePage(emptyList(), "replacement-delta", false, false, 5, f.clock.now())
                )
        }
        f.itemCall = { hydration.await() }
        val values = mutableListOf<AppResult<InboxCachePage>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id, InboxCacheQuery.Page()).toList(values)
            }
        val syncing = async { f.sync(f.workspace.id) }
        advanceTimeBy(5_001)
        runCurrent()
        assertFalse(syncing.isCompleted)
        assertNotEquals(original.generation, f.checkpoint!!.generation)
        assertEquals(originalPage, values.last())
        assertEquals(0, f.clearCalls)
        hydration.complete(AppResult.Failed(Failure(FailureKind.Network, "fixture")))
        assertIs<AppResult.Failed>(syncing.await())
        assertEquals(originalPage, f.snapshot.value)
        val changed = f.item.copy(version = f.item.version + 1, title = "Updated notice")
        f.itemCall = { AppResult.Success(changed) }
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        runCurrent()
        assertEquals(
            listOf(changed),
            assertIs<AppResult.Success<InboxCachePage>>(values.last()).value.items,
        )
        assertTrue(values.all { it is AppResult.Success && it.value.items.isNotEmpty() })
        assertEquals(0, f.clearCalls)
        reader.cancelAndJoin()
    }

    @Test
    fun anAuthoritativeScopeChangeKeepsFactsUnreadableWhenItsReplacementCannotLoad() = runTest {
        val base = Instant.parse("2026-10-11T00:00:00Z")
        val f = InboxFixture { base + testScheduler.currentTime.milliseconds }
        assertIs<AppResult.Success<InboxSyncOutcome>>(f.sync(f.workspace.id))
        val original = f.snapshot.value
        f.changesCall = {
            AppResult.Failed(Failure(FailureKind.Conflict, "fixture", code = "sync_scope_changed"))
        }
        f.bootstrapCall = { AppResult.Failed(Failure(FailureKind.Network, "fixture")) }
        assertIs<AppResult.Failed>(f.sync(f.workspace.id))
        assertEquals(0, f.clearCalls)
        assertEquals(original, f.snapshot.value)
        assertEquals(
            "sync_scope_changed",
            assertIs<AppResult.Failed>(f.observe(f.workspace.id, InboxCacheQuery.Page()).first())
                .failure
                .code,
        )
        assertIs<AppResult.Failed>(f.sync(f.workspace.id))
        assertEquals(original, f.snapshot.value)
        assertEquals(
            "sync_scope_changed",
            assertIs<AppResult.Failed>(f.observe(f.workspace.id, InboxCacheQuery.Page()).first())
                .failure
                .code,
        )
    }
}
