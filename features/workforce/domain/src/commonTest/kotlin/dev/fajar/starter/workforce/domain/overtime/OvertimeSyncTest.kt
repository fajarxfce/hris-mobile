@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate

class OvertimeSyncTest {
    @Test
    fun theFeedPublishesOwnedFactsAndLocalFiltersDoNotAcquire() = runTest {
        val f = OvertimeFixture()
        assertEquals(AppResult.Success(OvertimeSyncOutcome(true, 5)), f.sync(f.workspace.id))
        assertEquals(1, f.bootstrapReads)
        assertEquals(1, f.canonicalReads)
        assertEquals(0, f.requestReads)
        assertTrue(
            f.feedRequests.all {
                it.first == setOf(SyncCollection.OVERTIME_REQUESTS) && it.third == 8
            }
        )
        assertNotNull(f.replicas.current?.refreshedAt)
        for (status in listOf(null, OvertimeStatus.PLANNED, OvertimeStatus.APPROVED)) {
            val stored =
                assertIs<AppResult.Success<OvertimeSnapshot<OvertimeOverview>?>>(
                        f.observe(f.workspace.id, OvertimeSelection(status = status)).first()
                    )
                    .value!!
            assertEquals(
                if (status == OvertimeStatus.APPROVED) 0 else 1,
                stored.value.requests.items.size,
            )
        }
        val otherMonth =
            assertIs<AppResult.Success<OvertimeSnapshot<OvertimeOverview>?>>(
                    f.observe(
                            f.workspace.id,
                            OvertimeSelection(period = overtimeMonth(LocalDate(2026, 9, 1))),
                        )
                        .first()
                )
                .value!!
        assertTrue(otherMonth.value.requests.items.isEmpty())
        assertEquals(1, f.canonicalReads)
    }

    @Test
    fun failedHydrationDoesNotAdvanceTheCheckpoint() = runTest {
        val f = OvertimeFixture()
        f.sync(f.workspace.id)
        val checkpoint = f.replicas.current
        f.changesCall = {
            changes(f, listOf(SyncChange(reference(f, 1), SyncOperation.UPSERT)), "next")
        }
        f.canonicalCall = { _, _ -> overtimeFailure("client.network", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.sync(f.workspace.id))
        assertEquals(checkpoint, f.replicas.current)
        assertNotNull((f.observe(f.workspace.id).first() as AppResult.Success).value)
    }

    @Test
    fun canonicalVersionsMayAdvanceButCannotFallBehindTheirReference() = runTest {
        for (canonical in listOf(0L, 2L)) {
            val f = OvertimeFixture()
            f.bootstrapCall = { bootstrap(f, listOf(reference(f, 1))) }
            f.canonicalCall = { _, _ ->
                AppResult.Success(f.details.copy(request = f.request.copy(version = canonical)))
            }
            val result = f.sync(f.workspace.id)
            if (canonical == 0L) {
                assertEquals(
                    "client.invalid_response",
                    assertIs<AppResult.Failed>(result).failure.code,
                )
                assertEquals(0L, f.replicas.current!!.revision)
            } else {
                assertIs<AppResult.Success<*>>(result)
                assertEquals(
                    canonical,
                    (f.replicas.published.value as AppResult.Success)
                        .value
                        .entries
                        .single()
                        .reference
                        .version,
                )
            }
        }
    }

    @Test
    fun duplicatesCoalesceAndVersionedDeletesDoNotReadTheCanonicalRequest() = runTest {
        val f = OvertimeFixture()
        f.sync(f.workspace.id)
        f.changesCall = {
            changes(
                f,
                listOf(
                    SyncChange(reference(f, 1), SyncOperation.UPSERT),
                    SyncChange(reference(f, 2), SyncOperation.UPSERT),
                    SyncChange(reference(f, 2), SyncOperation.DELETE),
                    SyncChange(reference(f, 1), SyncOperation.UPSERT),
                ),
                "deleted",
            )
        }
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        assertEquals(1, f.canonicalReads)
        assertTrue(
            (f.observe(f.workspace.id).first() as AppResult.Success)
                .value!!
                .value
                .requests
                .items
                .isEmpty()
        )
        assertNull(
            (f.replicas.observeRequest(f.access, f.selection).first() as AppResult.Success).value
        )
    }

    @Test
    fun concealedOwnershipLossIsNotInventedAsADeletionAndKeepsItsDenial() = runTest {
        val f = OvertimeFixture()
        f.sync(f.workspace.id)
        f.replicas.failCleanup = true
        f.changesCall = {
            changes(f, listOf(SyncChange(reference(f, 1), SyncOperation.UPSERT)), "next")
        }
        val denial = overtimeFailure("overtime_request_not_found", FailureKind.Unavailable)
        f.canonicalCall = { _, _ -> denial }
        assertEquals(denial, f.sync(f.workspace.id))
        assertEquals("cursor/0", f.replicas.current!!.cursor)
        assertNotNull(
            (f.replicas.published.value as AppResult.Success).value.entries.single().record
        )
    }

    @Test
    fun cursorExpiryStagesReplacementWithoutErasingPublishedFacts() = runTest {
        val f = OvertimeFixture()
        f.sync(f.workspace.id)
        val original = (f.replicas.published.value as AppResult.Success).value
        f.changesCall = { overtimeFailure("sync_cursor_expired", FailureKind.Conflict) }
        f.bootstrapCall = { overtimeFailure("client.network", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.sync(f.workspace.id))
        assertEquals(original, (f.replicas.published.value as AppResult.Success).value)
        assertNotEquals(original.checkpoint!!.generation, f.replicas.current!!.generation)
        assertFalse(f.replicas.current!!.published)
        f.bootstrapCall = { bootstrap(f, emptyList()) }
        f.changesCall = { changes(f, emptyList(), "replacement") }
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        assertTrue(
            (f.observe(f.workspace.id).first() as AppResult.Success)
                .value!!
                .value
                .requests
                .items
                .isEmpty()
        )
    }

    @Test
    fun aScopeDenialCannotBeReclassifiedAsAFailedCleanup() = runTest {
        val f = OvertimeFixture()
        f.sync(f.workspace.id)
        f.replicas.failCleanup = true
        val denial = overtimeFailure("sync_scope_changed", FailureKind.Conflict)
        f.changesCall = { denial }
        assertEquals(denial, f.sync(f.workspace.id))
        assertEquals(1, f.bootstrapReads)
    }

    @Test
    fun pagesAndDirectoryCursorsHaveFiniteProgress() = runTest {
        val f = OvertimeFixture()
        f.bootstrapCall = { cursor -> bootstrap(f, emptyList(), (cursor ?: "") + "x") }
        assertEquals(AppResult.Success(OvertimeSyncOutcome(false, 5)), f.sync(f.workspace.id))
        assertEquals(10, f.bootstrapReads)
        assertFalse(f.replicas.current!!.published)
        val repeated = OvertimeFixture()
        repeated.base.directoryCall = {
            AppResult.Success(repeated.base.directory.copy(nextCursor = "E01"))
        }
        assertEquals(
            "client.invalid_response",
            assertIs<AppResult.Failed>(repeated.sync(repeated.workspace.id)).failure.code,
        )
        assertEquals(2, repeated.base.directoryReads)
        assertEquals(0, repeated.bootstrapReads)
    }

    @Test
    fun pendingPublicationHonorsTheServerDelayWithoutBusyPolling() = runTest {
        val f = OvertimeFixture {
            kotlin.time.Instant.parse("2026-10-12T01:00:00Z") +
                testScheduler.currentTime.seconds / 1000
        }
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(emptyList(), "waiting", false, true, 5, f.base.clock.now())
            )
        }
        assertEquals(AppResult.Success(OvertimeSyncOutcome(false, 5)), f.sync(f.workspace.id))
        assertEquals(30_000, testScheduler.currentTime)
        assertTrue(f.changesReads in 5..6)
        assertNull((f.replicas.published.value as AppResult.Success).value.checkpoint)
    }

    @Test
    fun lateCanonicalResponsesCannotCrossCompanyOrAccountReplacement() = runTest {
        val f = OvertimeFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.canonicalCall = { _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(f.details)
        }
        val work = async { f.sync(f.workspace.id) }
        entered.await()
        f.base.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
        release.complete(Unit)
        assertIs<AppResult.Failed>(work.await())
        assertEquals(0, f.replicas.writes)
    }

    @Test
    fun oneExecutionMutexCoversIndividualReadsAndSyncAndIncludesLockWaitInItsDeadline() = runTest {
        val f = OvertimeFixture()
        f.execution.lock()
        val load = async { f.refreshRequest(f.workspace.id, f.selection) }
        val sync = async { f.sync(f.workspace.id) }
        advanceTimeBy(30_001)
        assertEquals(
            "client.overtime_timeout",
            assertIs<AppResult.Failed>(load.await()).failure.code,
        )
        assertEquals(AppResult.Success(OvertimeSyncOutcome(false, 5)), sync.await())
        assertEquals(0, f.base.policyReads)
        f.execution.unlock()
        assertIs<AppResult.Success<*>>(f.refreshRequest(f.workspace.id, f.selection))
    }

    @Test
    fun cachedActivityReadsDoNotWaitForTheAcquisitionMutexOrRefreshTheirLease() = runTest {
        val f = OvertimeFixture()
        f.refreshRequest(f.workspace.id, f.selection)
        val saved = (f.detailRecords.value as AppResult.Success).value!!
        val reads = f.base.policyReads
        f.execution.lock()
        assertIs<AppResult.Success<*>>(
            f.refreshRequest(f.workspace.id, f.selection, OvertimeRequestAcquisition.LOCAL_FIRST)
        )
        assertEquals(reads, f.base.policyReads)
        assertEquals(saved, (f.detailRecords.value as AppResult.Success).value)
        f.execution.unlock()
    }

    @Test
    fun mixedCurrentAndHistoricalVersionsNeverPublishATornRequest() = runTest {
        val f = OvertimeFixture()
        var reads = 0
        f.detailsCall = {
            reads++
            AppResult.Success(f.details.copy(request = f.request.copy(version = reads.toLong())))
        }
        assertEquals(
            "client.overtime_changed",
            assertIs<AppResult.Failed>(
                    f.refreshRequest(f.workspace.id, f.selection.copy(historyAfter = 1))
                )
                .failure
                .code,
        )
        assertEquals(0, f.writes)
    }

    @Test
    fun historicalBackfillReusesSavedPagesAndKeepsCurrentAcquisitionTime() = runTest {
        val f = OvertimeFixture()
        val current =
            f.details.copy(
                request = f.request.copy(version = 12),
                history = OvertimePage(emptyList(), "3"),
            )
        f.canonicalCall = { _, after ->
            AppResult.Success(
                if (after == null) current else current.copy(history = OvertimePage(emptyList()))
            )
        }
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        assertEquals(listOf(null, 3L), f.canonicalSelections.map { it.second })
        val snapshot =
            (f.replicas.observeRequest(f.access, f.selection).first() as AppResult.Success).value!!
        assertEquals(f.base.clock.now(), snapshot.fetchedAt)
        assertTrue(f.replicas.historyWork.isEmpty())
        assertNotNull(f.replicas.historyPages[f.request.id to 3L])
        f.sync(f.workspace.id)
        assertEquals(2, f.canonicalReads)
    }

    @Test
    fun newerCanonicalHistoryWaitsForFeedCatchupWithoutAdvancingAnOlderFrontier() = runTest {
        val f = OvertimeFixture()
        f.canonicalCall = { _, after ->
            AppResult.Success(
                f.details.copy(
                    request = f.request.copy(version = if (after == null) 12 else 13),
                    history = OvertimePage(emptyList(), if (after == null) "3" else null),
                )
            )
        }
        assertEquals(AppResult.Success(OvertimeSyncOutcome(false, 5)), f.sync(f.workspace.id))
        assertEquals(5_000, testScheduler.currentTime)
        assertEquals(3L, f.replicas.historyWork[f.request.id]!!.after)
        assertTrue(f.replicas.historyPages.isEmpty())
    }

    @Test
    fun localCursorValidationRejectsForeignRangesAndResetsTheOldUuidOrder() {
        val f = OvertimeFixture()
        val period = overtimeMonth(LocalDate(2026, 10, 1))
        assertTrue(isOvertimeReplicaCursor("r/2026-10-12/${f.request.id}", period))
        assertFalse(isOvertimeReplicaCursor("r/2026-09-12/${f.request.id}", period))
        assertFalse(isOvertimeReplicaCursor("r/2026-10-32/${f.request.id}", period))
        val chosen =
            (selectStoredOvertime(f.base.directory, OvertimeSelection(requestAfter = f.request.id))
                    as AppResult.Success)
                .value
        assertNull(chosen.requestAfter)
    }

    @Test
    fun aSlowFailedCleanupCannotReplaceAnObservedAccessDenial() = runTest {
        val f = OvertimeFixture()
        f.refreshRequest(f.workspace.id, f.selection)
        val saved = f.detailRecords.value
        f.beforeWrite = { awaitCancellation() }
        val denial = overtimeFailure("mfa_required", FailureKind.AccessDenied)
        f.detailsCall = { denial }
        assertEquals(denial, f.refreshRequest(f.workspace.id, f.selection))
        assertEquals(2_000, testScheduler.currentTime)
        assertEquals(saved, f.detailRecords.value)
    }

    private fun reference(f: OvertimeFixture, version: Long) =
        SyncReference(SyncCollection.OVERTIME_REQUESTS, f.request.id, version)

    private fun bootstrap(f: OvertimeFixture, items: List<SyncReference>, next: String? = null) =
        AppResult.Success(
            SyncBootstrapPage(
                setOf(SyncCollection.OVERTIME_REQUESTS),
                items,
                next,
                if (next == null) "cursor/0" else null,
                f.base.clock.now(),
            )
        )

    private fun changes(f: OvertimeFixture, items: List<SyncChange>, cursor: String) =
        AppResult.Success(SyncChangePage(items, cursor, false, false, 5, f.base.clock.now()))
}
