@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.domain.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workforce.domain.WorkCalendarFixture
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.calendarFailure
import dev.fajar.starter.workforce.domain.replica.entities.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.datetime.*

class WorkforceSyncTest {
    @Test
    fun ownedSourcesPublishTogetherAndCalendarQueriesDoNotAcquire() = runTest {
        val f = WorkforceSyncFixture()
        assertEquals(AppResult.Success(WorkforceSyncOutcome(true, 5)), f.sync(f.base.workspace.id))
        assertTrue(f.feedRequests.all { it.first == workforceCollections && it.third == 32 })
        assertEquals(4, f.canonicalReads.size)
        assertEquals(0, f.base.calendarReads)
        val saved =
            assertIs<AppResult.Success<WorkCalendarSnapshot?>>(
                    f.base.observe(f.base.workspace.id).first()
                )
                .value!!
        assertEquals(7, saved.calendar!!.days.size)
        assertEquals(CalendarOrigin.ROSTER, (saved.calendar.days.first() as WorkDay.Work).origin)
        assertEquals(CalendarOrigin.HOLIDAY, (saved.calendar.days[1] as WorkDay.Off).origin)
        val other =
            assertIs<AppResult.Success<WorkCalendarSnapshot?>>(
                    f.base
                        .observe(
                            f.base.workspace.id,
                            WorkCalendarSelection(from = LocalDate(2020, 2, 3)),
                        )
                        .first()
                )
                .value!!
        assertEquals(7, other.calendar!!.days.size)
        assertEquals(4, f.canonicalReads.size)
    }

    @Test
    fun aLargeBootstrapResumesItsCheckpointWithoutPublishingPartialHistory() = runTest {
        val f = WorkforceSyncFixture()
        for (index in 1..330) {
            val record =
                f.attendance.copy(
                    id = workforceId(1000 + index),
                    day =
                        f.attendance.day.copy(
                            date = LocalDate(2000, 1, 1).plus(index, DateTimeUnit.DAY)
                        ),
                )
            f.records[SyncCollection.ATTENDANCE_DAYS to record.id] = record
        }
        assertEquals(AppResult.Success(WorkforceSyncOutcome(false, 5)), f.sync(f.base.workspace.id))
        assertEquals(10, f.bootstrapReads)
        assertNull((f.replicas.published.value as AppResult.Success).value.checkpoint)
        assertEquals(320, f.canonicalReads.size)
        assertEquals(AppResult.Success(WorkforceSyncOutcome(true, 5)), f.sync(f.base.workspace.id))
        assertEquals(334, f.canonicalReads.size)
        assertEquals(334, (f.replicas.published.value as AppResult.Success).value.entries.size)
        assertEquals(11, f.bootstrapReads)
    }

    @Test
    fun failedHydrationDoesNotAdvanceTheDurableCursorOrHidePublishedFacts() = runTest {
        val f = WorkforceSyncFixture()
        f.sync(f.base.workspace.id)
        val checkpoint = f.replicas.current
        val saved = f.replicas.published.value
        f.changesCall = {
            f.changes(
                listOf(
                    SyncChange(workforceReference(f.roster).copy(version = 1), SyncOperation.UPSERT)
                ),
                "next",
            )
        }
        val failure = calendarFailure("offline", FailureKind.Network)
        f.canonicalCall = { _, _ -> failure }
        assertSame(failure, f.sync(f.base.workspace.id))
        assertEquals(checkpoint, f.replicas.current)
        assertEquals(saved, f.replicas.published.value)
        assertNotNull((f.base.observe(f.base.workspace.id).first() as AppResult.Success).value)
    }

    @Test
    fun duplicatesCoalesceByCollectionAndVersionedTombstonesDoNotHydrate() = runTest {
        val f = WorkforceSyncFixture()
        val sameId = f.holiday.copy(id = f.roster.id)
        f.records[SyncCollection.WORK_HOLIDAYS to sameId.id] = sameId
        f.sync(f.base.workspace.id)
        val reads = f.canonicalReads.size
        val reference = workforceReference(f.roster)
        f.changesCall = {
            f.changes(
                listOf(
                    SyncChange(reference.copy(version = 1), SyncOperation.UPSERT),
                    SyncChange(reference.copy(version = 2), SyncOperation.DELETE),
                    SyncChange(reference.copy(version = 2), SyncOperation.UPSERT),
                ),
                "deleted",
            )
        }
        assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
        val records = (f.replicas.published.value as AppResult.Success).value.entries
        assertFalse(records.any { it.record == f.roster })
        assertTrue(records.any { it.record == sameId })
        assertEquals(reads, f.canonicalReads.size)
    }

    @Test
    fun newerCanonicalValuesAreAcceptedButOlderOrForeignRecordsAreRejected() = runTest {
        for (version in listOf(0L, 3L)) {
            val f = WorkforceSyncFixture()
            f.bootstrapCall = {
                AppResult.Success(
                    SyncBootstrapPage(
                        workforceCollections,
                        listOf(workforceReference(f.roster).copy(version = 2)),
                        null,
                        "head",
                        f.base.clock.now(),
                    )
                )
            }
            f.canonicalCall = { _, _ -> AppResult.Success(f.roster.copy(version = version)) }
            val result = f.sync(f.base.workspace.id)
            if (version == 0L) {
                assertEquals(
                    "client.invalid_response",
                    assertIs<AppResult.Failed>(result).failure.code,
                )
                assertEquals(0, f.replicas.writes)
            } else
                assertEquals(
                    version,
                    (f.replicas.published.value as AppResult.Success)
                        .value
                        .entries
                        .single()
                        .reference
                        .version,
                )
        }
        val f = WorkforceSyncFixture()
        f.records.clear()
        val foreign = f.roster.copy(employeeId = workforceId(999))
        f.records[SyncCollection.ROSTER_DAYS to foreign.id] = foreign
        assertEquals(
            "employee_not_found",
            assertIs<AppResult.Failed>(f.sync(f.base.workspace.id)).failure.code,
        )
        assertNull((f.replicas.published.value as AppResult.Success).value.checkpoint)
    }

    @Test
    fun cursorRetentionStagesAReplacementWithoutDeletingTheReadableGeneration() = runTest {
        val f = WorkforceSyncFixture()
        f.sync(f.base.workspace.id)
        val saved = f.replicas.published.value
        f.changesCall = { calendarFailure("sync_cursor_expired") }
        val release = CompletableDeferred<Unit>()
        val regular = f.bootstrapCall
        f.bootstrapCall = {
            release.await()
            regular(it)
        }
        val retry = async { f.sync(f.base.workspace.id) }
        runCurrent()
        assertEquals(saved, f.replicas.published.value)
        assertEquals(0, f.replicas.clears)
        assertNotEquals(
            (saved as AppResult.Success).value.checkpoint!!.generation,
            f.replicas.current!!.generation,
        )
        retry.cancelAndJoin()
        assertEquals(saved, f.replicas.published.value)
        release.complete(Unit)
        f.changesCall = { f.changes() }
        assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
    }

    @Test
    fun confirmedDenialKeepsItsClassificationWhenCleanupFails() = runTest {
        for (code in listOf("mfa_required", "company_access_denied", "sync_scope_changed")) {
            val f = WorkforceSyncFixture()
            f.sync(f.base.workspace.id)
            val saved = f.replicas.published.value
            f.replicas.failCleanup = true
            val denied = calendarFailure(code, FailureKind.AccessDenied)
            f.changesCall = { denied }
            assertSame(denied, f.sync(f.base.workspace.id))
            assertEquals(saved, f.replicas.published.value)
        }
    }

    @Test
    fun staleResultsAndFailuresCannotClearOrPublishIntoTheReplacementWorkspace() = runTest {
        for (stage in listOf("policy", "directory", "feed", "record")) for (failed in
            listOf(false, true)) {
            val f = WorkforceSyncFixture()
            f.sync(f.base.workspace.id)
            val saved = f.replicas.published.value
            val release = CompletableDeferred<Unit>()
            val denied = calendarFailure("company_access_denied", FailureKind.AccessDenied)
            when (stage) {
                "policy" -> {
                    val regular = f.base.policyCall
                    f.base.policyCall = {
                        release.await()
                        if (failed) denied else regular()
                    }
                }
                "directory" -> {
                    val regular = f.base.directoryCall
                    f.base.directoryCall = {
                        release.await()
                        if (failed) denied else regular()
                    }
                }
                "feed" -> f.changesCall = {
                        release.await()
                        if (failed) denied else f.changes()
                    }
                else -> {
                    f.changesCall = {
                        f.changes(
                            listOf(SyncChange(workforceReference(f.roster), SyncOperation.UPSERT))
                        )
                    }
                    f.canonicalCall = { _, _ ->
                        release.await()
                        if (failed) denied else AppResult.Success(f.roster)
                    }
                }
            }
            val pending = async { f.sync(f.base.workspace.id) }
            runCurrent()
            f.base.workspaceState.value =
                AppResult.Success(f.base.workspace.copy(id = "replacement"))
            release.complete(Unit)
            assertEquals(
                "client.workspace_changed",
                assertIs<AppResult.Failed>(pending.await()).failure.code,
            )
            assertEquals(saved, f.replicas.published.value)
            assertEquals(0, f.replicas.clears)
        }
    }

    @Test
    fun cancellationRejectsLateNonCancellableRecordResultsAndReleasesTheExecutionGuard() = runTest {
        for (failed in listOf(false, true)) {
            val f = WorkforceSyncFixture()
            val release = CompletableDeferred<Unit>()
            val regular = f.canonicalCall
            f.canonicalCall = { collection, id ->
                withContext(NonCancellable) { release.await() }
                if (failed) calendarFailure("mfa_required", FailureKind.AccessDenied)
                else regular(collection, id)
            }
            val pending = async { f.sync(f.base.workspace.id) }
            runCurrent()
            assertEquals(4, f.activeReads)
            pending.cancel()
            release.complete(Unit)
            pending.join()
            assertTrue(pending.isCancelled)
            assertEquals(0, f.activeReads)
            assertEquals(0, f.replicas.writes)
            assertEquals(0, f.replicas.clears)
            f.canonicalCall = regular
            assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
            assertTrue(f.maximumReads <= 4)
        }
    }

    @Test
    fun timeoutIncludesMutexWaitAndProgressNeverSpinsOnAnUnchangedCursor() = runTest {
        val f = WorkforceSyncFixture()
        f.execution.lock()
        assertEquals(AppResult.Success(WorkforceSyncOutcome(false, 5)), f.sync(f.base.workspace.id))
        assertEquals(30_000, testScheduler.currentTime)
        assertEquals(0, f.base.policyReads)
        f.execution.unlock()
        f.bootstrapCall = { cursor ->
            AppResult.Success(
                SyncBootstrapPage(
                    workforceCollections,
                    emptyList(),
                    cursor ?: "same",
                    null,
                    f.base.clock.now(),
                )
            )
        }
        assertEquals(
            "client.invalid_response",
            assertIs<AppResult.Failed>(f.sync(f.base.workspace.id)).failure.code,
        )
        assertEquals(2, f.bootstrapReads)
        val other = WorkforceSyncFixture()
        other.sync(other.base.workspace.id)
        other.changesCall = { other.changes(hasMore = true) }
        assertEquals(
            "client.invalid_response",
            assertIs<AppResult.Failed>(other.sync(other.base.workspace.id)).failure.code,
        )
    }

    @Test
    fun newLoginRestartsItsCursorButRetainsOwnedFactsAndLateWritersAreRejected() = runTest {
        val f = WorkforceSyncFixture()
        f.sync(f.base.workspace.id)
        val previous = f.replicas.current!!
        val session = f.base.session.copy(id = "next-login")
        val workspace = f.base.workspace.copy(sessionId = session.id)
        f.base.sessionState.value = AppResult.Success(session)
        f.base.workspaceState.value = AppResult.Success(workspace)
        val saved =
            assertIs<AppResult.Success<WorkCalendarSnapshot?>>(f.base.observe(workspace.id).first())
                .value!!
        assertEquals(session.id, saved.access.sessionId)
        assertEquals(previous.refreshedAt, saved.fetchedAt)
        assertIs<AppResult.Success<*>>(f.sync(workspace.id))
        assertNotEquals(previous.generation, f.replicas.current!!.generation)
        assertEquals(0, f.replicas.clears)
    }

    @Test
    fun livePolicyAndEmploymentScopeAreCheckedBeforeAnyRecordRead() = runTest {
        for (code in
            listOf("company_module_disabled", "client_update_required", "company_maintenance")) {
            val f = WorkforceSyncFixture()
            f.base.policyCall = {
                AppResult.Success(
                    when (code) {
                        "company_module_disabled" -> f.base.policy.copy(enabledModules = emptySet())
                        "client_update_required" ->
                            f.base.policy.copy(minimumBuilds = CompanyMinimumBuilds(2, 0, 0))
                        else ->
                            f.base.policy.copy(
                                maintenance =
                                    CompanyMaintenance(
                                        f.base.clock.now(),
                                        f.base.clock.now() + 60.seconds,
                                    ),
                                maintenanceActive = true,
                            )
                    }
                )
            }
            assertEquals(code, assertIs<AppResult.Failed>(f.sync(f.base.workspace.id)).failure.code)
            assertEquals(0, f.bootstrapReads)
            assertEquals(0, f.base.directoryReads)
        }
        val start = Instant.parse("2026-10-12T01:00:00Z")
        val f =
            WorkforceSyncFixture(
                WorkCalendarFixture { start + testScheduler.currentTime.milliseconds }
            )
        f.base.policyCall = {
            delay(2.seconds)
            AppResult.Success(f.base.policy.copy(validUntil = start + 1.seconds))
        }
        assertEquals(
            "client.company_policy_expired",
            assertIs<AppResult.Failed>(f.sync(f.base.workspace.id)).failure.code,
        )
        assertEquals(0, f.base.directoryReads)
    }

    @Test
    fun emptyEmploymentDirectoryPublishesAnEmptyViewWithoutInventingAttendance() = runTest {
        val f = WorkforceSyncFixture()
        f.records.clear()
        f.base.directoryCall = { AppResult.Success(f.base.directory.copy(items = emptyList())) }
        assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
        val snapshot = (f.base.observe(f.base.workspace.id).first() as AppResult.Success).value!!
        assertNull(snapshot.calendar)
        assertNull(snapshot.selection.employeeId)
        assertTrue(f.canonicalReads.isEmpty())
    }
}
