@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class SyncLeaveTest {
    @Test
    fun completedRunsDoNotDelayExplicitRefreshAndPendingPublicationHonorsItsPollingHint() =
        runTest {
            val f = LeaveSyncFixture()
            f.run()
            assertNull(f.replica.current?.pollAt)
            f.run()
            assertEquals(0, testScheduler.currentTime)
            var calls = 0
            f.changesCall = {
                calls++
                AppResult.Success(
                    SyncChangePage(
                        emptyList(),
                        "pending-$calls",
                        false,
                        calls == 1,
                        5,
                        f.base.clock.now(),
                    )
                )
            }
            assertTrue(assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run()).value.caughtUp)
            assertEquals(5_000, testScheduler.currentTime)
            assertNull(f.replica.current?.pollAt)
        }

    @Test
    fun aCanonicalBalanceHiddenAfterScopeLossClearsThePriorProjection() = runTest {
        val f = LeaveSyncFixture()
        f.run()
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    f.references.map { SyncChange(it, SyncOperation.UPSERT) },
                    "next",
                    false,
                    false,
                    5,
                    f.base.clock.now(),
                )
            )
        }
        f.balanceCall = { leaveFailure("leave_account_not_found", FailureKind.Validation) }
        assertEquals("leave_account_not_found", assertIs<AppResult.Failed>(f.run()).failure.code)
        assertNull(f.replica.current)
        assertEquals(1, f.replica.clears)
    }

    @Test
    fun bootstrapPageLimitLeavesResumableProgressInsteadOfLoopingOrPublishingPartialData() =
        runTest {
            val f = LeaveSyncFixture()
            f.bootstrapCall = {
                AppResult.Success(
                    SyncBootstrapPage(
                        f.collections,
                        emptyList(),
                        "page-${f.bootstrapReads}",
                        null,
                        f.base.clock.now(),
                    )
                )
            }
            assertFalse(assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run()).value.caughtUp)
            assertEquals(10, f.bootstrapReads)
            assertEquals("page-10", f.replica.current?.cursor)
            assertFalse(f.replica.current!!.published)
            assertNull(
                assertIs<AppResult.Success<LeaveReplicaFixture.Published>>(
                        f.replica.published.value
                    )
                    .value
                    .checkpoint
            )
        }

    @Test
    fun bootstrapAndCatchUpPublishRecordsTogetherWithoutFilteredDirectoryRequests() = runTest {
        val f = LeaveSyncFixture()
        val gate = CompletableDeferred<Unit>()
        val normal = f.changesCall
        f.changesCall = {
            gate.await()
            normal(it)
        }
        val sync = async { f.run() }
        runCurrent()
        assertEquals(2, f.replica.pending.size)
        assertFalse(f.replica.current!!.published)
        assertNull(
            assertIs<AppResult.Success<LeaveReplicaFixture.Published>>(f.replica.published.value)
                .value
                .checkpoint
        )
        gate.complete(Unit)
        assertTrue(assertIs<AppResult.Success<LeaveSyncOutcome>>(sync.await()).value.caughtUp)
        val page =
            assertIs<AppResult.Success<LeaveOverview?>>(f.base.observe(f.base.workspace.id).first())
                .value!!
        assertEquals(f.base.request, page.requests.items.single())
        assertEquals(f.base.balance, page.balances.items.single())
        assertEquals(0, f.base.requestReads)
        assertEquals(0, f.base.balanceReads)
        assertEquals(2, f.recordReads)
    }

    @Test
    fun anEmptyFeedPublishesAnEmptyReplicaAndDoesNotResurrectTheLegacyPage() = runTest {
        val f = LeaveSyncFixture()
        f.base.load()
        f.bootstrapCall = {
            AppResult.Success(
                SyncBootstrapPage(f.collections, emptyList(), null, "head", f.base.clock.now())
            )
        }
        assertTrue(assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run()).value.caughtUp)
        val page =
            assertIs<AppResult.Success<LeaveOverview?>>(f.base.observe(f.base.workspace.id).first())
                .value!!
        assertTrue(page.requests.items.isEmpty())
        assertTrue(page.balances.items.isEmpty())
        assertEquals(0, f.recordReads)
    }

    @Test
    fun replayDeduplicatesCollectionsAndKeepsNewerCanonicalVersionsAndDeletes() = runTest {
        val f = LeaveSyncFixture()
        val request = f.references.first()
        f.bootstrapCall = {
            AppResult.Success(
                SyncBootstrapPage(
                    f.collections,
                    listOf(request, request.copy(version = 1), f.references[1]),
                    null,
                    "head",
                    f.base.clock.now(),
                )
            )
        }
        f.requestCall = {
            AppResult.Success(LeaveReplicaRecord.Request(f.base.request.copy(version = 3)))
        }
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    listOf(
                        SyncChange(request.copy(version = 3), SyncOperation.DELETE),
                        SyncChange(request.copy(version = 2), SyncOperation.UPSERT),
                    ),
                    "after-delete",
                    false,
                    false,
                    5,
                    f.base.clock.now(),
                )
            )
        }
        assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run())
        val requestEntry =
            f.replica.pending.single { it.reference.collection == SyncCollection.LEAVE_REQUESTS }
        assertEquals(3, requestEntry.reference.version)
        assertNull(requestEntry.record)
        assertEquals(2, f.recordReads)
    }

    @Test
    fun failedHydrationCannotAdvanceTheCursorOrPartiallyUpdatePublishedRecords() = runTest {
        val f = LeaveSyncFixture()
        f.run()
        val old = f.replica.published.value
        val progress = f.replica.current
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    f.references.map { SyncChange(it, SyncOperation.UPSERT) },
                    "new",
                    false,
                    false,
                    5,
                    f.base.clock.now(),
                )
            )
        }
        f.balanceCall = { leaveFailure("client.network", FailureKind.Network) }
        assertEquals("client.network", assertIs<AppResult.Failed>(f.run()).failure.code)
        assertEquals(old, f.replica.published.value)
        assertEquals(progress, f.replica.current)
    }

    @Test
    fun expiredCursorsStageReplacementWhileScopeDenialsMustRemoveOldFacts() = runTest {
        for (code in listOf("sync_cursor_expired", "sync_scope_changed")) {
            val f = LeaveSyncFixture()
            f.run()
            val old = f.replica.published.value
            f.changesCall = { leaveFailure(code, FailureKind.Conflict) }
            val gate = CompletableDeferred<Unit>()
            val bootstrap = f.bootstrapCall
            f.bootstrapCall = {
                gate.await()
                bootstrap(it)
            }
            val refresh = async { f.run() }
            advanceTimeBy(5.seconds)
            runCurrent()
            if (code == "sync_cursor_expired") assertEquals(old, f.replica.published.value)
            else
                assertNull(
                    assertIs<AppResult.Success<LeaveReplicaFixture.Published>>(
                            f.replica.published.value
                        )
                        .value
                        .checkpoint
                )
            f.changesCall = {
                AppResult.Success(
                    SyncChangePage(emptyList(), "renewed", false, false, 5, f.base.clock.now())
                )
            }
            gate.complete(Unit)
            assertTrue(
                assertIs<AppResult.Success<LeaveSyncOutcome>>(refresh.await()).value.caughtUp
            )
            assertEquals(2, f.bootstrapReads)
        }
    }

    @Test
    fun aFailedScopeCleanupPreservesTheAuthorityFailureInsteadOfRetryingOldData() = runTest {
        val f = LeaveSyncFixture()
        f.run()
        f.replica.rejectWrite = true
        val denied = leaveFailure("sync_scope_changed", FailureKind.Conflict)
        f.changesCall = { denied }
        assertSame(denied, f.run())
        assertEquals(1, f.bootstrapReads)
    }

    @Test
    fun replacementLoginBootstrapsItsOwnCursorWhileTheSameAccountCanStillRead() = runTest {
        val f = LeaveSyncFixture()
        f.run()
        val old = f.replica.published.value
        val session = f.base.session.copy(id = "fresh-login")
        f.base.sessionState.value = AppResult.Success(session)
        f.base.workspaceState.value =
            AppResult.Success(f.base.workspace.copy(sessionId = session.id))
        assertNotNull(
            assertIs<AppResult.Success<LeaveOverview?>>(f.base.observe(f.base.workspace.id).first())
                .value
        )
        val gate = CompletableDeferred<Unit>()
        val bootstrap = f.bootstrapCall
        f.bootstrapCall = {
            gate.await()
            bootstrap(it)
        }
        val refreshing = async { f.run() }
        runCurrent()
        assertEquals(old, f.replica.published.value)
        assertEquals("fresh-login", f.replica.current?.access?.sessionId)
        assertNull(f.requestedCursors.last())
        gate.complete(Unit)
        assertTrue(assertIs<AppResult.Success<LeaveSyncOutcome>>(refreshing.await()).value.caughtUp)
    }

    @Test
    fun lateResultsAndDenialsCannotWriteOrClearAfterTheWorkspaceChanges() = runTest {
        for (stage in listOf("policy", "directory", "feed", "record")) for (denied in
            listOf(false, true)) {
            val f = LeaveSyncFixture()
            val gate = CompletableDeferred<Unit>()
            val failure = leaveFailure("access_denied", FailureKind.AccessDenied)
            when (stage) {
                "policy" -> f.base.policyCall = {
                        gate.await()
                        if (denied) failure else AppResult.Success(f.base.policy)
                    }
                "directory" -> f.base.directoryCall = {
                        gate.await()
                        if (denied) failure else AppResult.Success(f.base.directory)
                    }
                "feed" -> {
                    val normal = f.bootstrapCall
                    f.bootstrapCall = {
                        gate.await()
                        if (denied) failure else normal(it)
                    }
                }
                else -> f.requestCall = {
                        gate.await()
                        if (denied) failure
                        else AppResult.Success(LeaveReplicaRecord.Request(f.base.request))
                    }
            }
            val pending = async { f.run() }
            runCurrent()
            f.base.workspaceState.value =
                AppResult.Success(f.base.workspace.copy(id = "replacement"))
            gate.complete(Unit)
            assertEquals(
                "client.workspace_changed",
                assertIs<AppResult.Failed>(pending.await()).failure.code,
            )
            assertEquals(0, f.replica.writes, "$stage/$denied writes")
            assertEquals(0, f.replica.clears, "$stage/$denied clears")
        }
    }

    @Test
    fun cancellationDuringNonCooperativeHydrationCannotPublishAndReleasesTheExecutionGuard() =
        runTest {
            val f = LeaveSyncFixture()
            val gate = CompletableDeferred<Unit>()
            f.requestCall = {
                withContext(NonCancellable) { gate.await() }
                AppResult.Success(LeaveReplicaRecord.Request(f.base.request))
            }
            val first = async { f.run() }
            runCurrent()
            first.cancel()
            val next = async { f.run() }
            runCurrent()
            assertEquals(1, f.bootstrapReads)
            gate.complete(Unit)
            first.join()
            assertTrue(first.isCancelled)
            assertTrue(assertIs<AppResult.Success<LeaveSyncOutcome>>(next.await()).value.caughtUp)
            assertEquals(2, f.replica.writes)
        }

    @Test
    fun hydrationConcurrencyAndAcquisitionTimeAreBoundedAndPendingWorkCanResume() = runTest {
        val f = LeaveSyncFixture()
        val refs =
            (1..12).map {
                f.references
                    .first()
                    .copy(id = "00000000-0000-0000-0000-${it.toString().padStart(12, '0')}")
            }
        f.bootstrapCall = {
            AppResult.Success(
                SyncBootstrapPage(f.collections, refs, null, "head", f.base.clock.now())
            )
        }
        var active = 0
        var maximum = 0
        var cancelled = 0
        f.requestCall = {
            active++
            maximum = maxOf(maximum, active)
            try {
                awaitCancellation()
            } finally {
                active--
                cancelled++
            }
        }
        assertFalse(assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run()).value.caughtUp)
        assertEquals(4, maximum)
        assertEquals(4, cancelled)
        assertEquals(0, active)
        assertEquals(0, f.replica.writes)
        assertNull(f.replica.current?.cursor)
    }

    @Test
    fun malformedCanonicalVersionsAndAmbiguousNotFoundResponsesAreNeverTombstones() = runTest {
        for (failure in listOf(false, true)) {
            val f = LeaveSyncFixture()
            f.balanceCall = {
                if (failure) leaveFailure("leave_account_not_found", FailureKind.Validation)
                else
                    AppResult.Success(
                        LeaveReplicaRecord.Balance(
                            f.base.employee.id,
                            f.base.balance.copy(version = 2),
                        )
                    )
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.replica.writes)
        }
    }

    @Test
    fun currentPolicyIsRecheckedAfterHydrationAndReadOnlyAccessKeepsItsOwnedProjection() = runTest {
        val start = Instant.parse("2026-10-12T01:00:00Z")
        val f = LeaveSyncFixture(LeaveFixture { start + testScheduler.currentTime.milliseconds })
        f.base.policyCall = {
            AppResult.Success(f.base.policy.copy(validUntil = start + 1.seconds))
        }
        f.requestCall = {
            delay(2.seconds)
            AppResult.Success(LeaveReplicaRecord.Request(f.base.request))
        }
        assertEquals(
            "client.company_policy_expired",
            assertIs<AppResult.Failed>(f.run()).failure.code,
        )
        assertEquals(0, f.replica.writes)
        val readOnly = LeaveSyncFixture()
        readOnly.base.workspaceState.value =
            AppResult.Success(readOnly.base.workspace.copy(permissions = setOf("leave.read")))
        assertTrue(assertIs<AppResult.Success<LeaveSyncOutcome>>(readOnly.run()).value.caughtUp)
        assertEquals(setOf("leave.read"), readOnly.replica.current?.access?.permissions)
    }
}
