package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.policies.admitWorkforce
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*

@OptIn(ExperimentalCoroutinesApi::class)
class OvertimeReadsTest {
    @Test
    fun overtimeOnlyAdmissionDoesNotGrantCalendarAccess() = runTest {
        val f = OvertimeFixture()
        assertIs<AppResult.Success<*>>(f.load())
        assertEquals(0, f.base.calendarReads)
        assertEquals(
            CalendarPeriod(LocalDate(2026, 10, 1), LocalDate(2026, 10, 31)),
            (f.observe(f.workspace.id).first() as AppResult.Success).value!!.value.selection.period,
        )
        assertIs<AppResult.Failed>(
            admitWorkforce(
                f.workspace.id,
                f.workspace,
                f.base.session,
                f.base.build,
                f.base.clock.now(),
            )
        )
        val snapshot =
            assertIs<AppResult.Success<OvertimeSnapshot<OvertimeOverview>?>>(
                    f.observe(f.workspace.id).first()
                )
                .value!!
        assertEquals(f.base.employee.id, snapshot.value.selection.employeeId)
        assertEquals(f.base.clock.now() + 1.hours, snapshot.refreshAfter)
    }

    @Test
    fun invalidFiltersAndForeignEmploymentCannotAcquireRequests() = runTest {
        val f = OvertimeFixture()
        val invalid =
            listOf(
                OvertimeSelection(employeeId = "invalid"),
                OvertimeSelection(
                    period = CalendarPeriod(LocalDate(2026, 1, 1), LocalDate(2026, 3, 4))
                ),
                OvertimeSelection(requestAfter = "invalid"),
            )
        for (selection in invalid) assertIs<AppResult.Failed>(
            f.observe(f.workspace.id, selection).first()
        )
        assertEquals(0, f.base.policyReads)
        assertIs<AppResult.Failed>(
            f.refreshRequest(
                f.workspace.id,
                f.selection.copy(employeeId = "99999999-9999-9999-9999-999999999999"),
            )
        )
        assertEquals(0, f.detailReads)
        assertEquals(0, f.writes)
    }

    @Test
    fun anUnlinkedAccountPublishesAnEmptyViewWithoutReadingAnyRequest() = runTest {
        val f = OvertimeFixture()
        f.base.directoryCall = { AppResult.Success(f.base.directory.copy(items = emptyList())) }
        f.bootstrapCall = {
            AppResult.Success(
                SyncBootstrapPage(
                    setOf(SyncCollection.OVERTIME_REQUESTS),
                    emptyList(),
                    null,
                    "empty",
                    f.base.clock.now(),
                )
            )
        }
        assertIs<AppResult.Success<*>>(f.load())
        assertEquals(0, f.canonicalReads)
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
    fun detailAcquisitionRechecksOwnedEmploymentAndKeepsItsHistorySelection() = runTest {
        val f = OvertimeFixture()
        assertIs<AppResult.Success<*>>(f.load(true))
        assertEquals(1, f.base.directoryReads)
        assertEquals(
            f.selection,
            (f.detailRecords.value as AppResult.Success).value!!.value.selection,
        )
        f.base.directoryCall = { AppResult.Success(f.base.directory.copy(items = emptyList())) }
        assertEquals("employee_not_found", assertIs<AppResult.Failed>(f.load(true)).failure.code)
        assertEquals(1, f.detailReads)
        assertEquals(AppResult.Success(null), f.detailRecords.value)
    }

    @Test
    fun permissionLossDuringEitherReadCannotPublishLateData() = runTest {
        for (detail in listOf(false, true)) {
            val f = OvertimeFixture()
            suspend fun revoke() {
                f.base.workspaceState.value =
                    AppResult.Success(f.workspace.copy(permissions = emptySet()))
            }
            f.requestsCall = {
                revoke()
                AppResult.Success(OvertimePage(emptyList()))
            }
            f.detailsCall = {
                revoke()
                AppResult.Success(f.details)
            }
            assertEquals("access_denied", assertIs<AppResult.Failed>(f.load(detail)).failure.code)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun aSessionReplacementDuringCompanyPolicyAcquisitionStopsBusinessReads() = runTest {
        val f = OvertimeFixture()
        f.base.policyCall = {
            f.base.sessionState.value = AppResult.Success(f.base.session.copy(id = "replacement"))
            AppResult.Success(f.base.policy)
        }
        assertIs<AppResult.Failed>(f.load())
        assertEquals(0, f.base.directoryReads)
        assertEquals(0, f.requestReads)
    }

    @Test
    fun cancellationDiscardsANonCooperativeLateReadAndReleasesTheMutex() = runTest {
        for (detail in listOf(false, true)) {
            val f = OvertimeFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            suspend fun pending() {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            f.requestsCall = {
                pending()
                AppResult.Success(OvertimePage(emptyList()))
            }
            f.detailsCall = {
                pending()
                AppResult.Success(f.details)
            }
            val running = launch { f.load(detail) }
            entered.await()
            running.cancel()
            release.complete(Unit)
            running.join()
            assertEquals(0, f.writes)
            f.requestsCall = { AppResult.Success(OvertimePage(emptyList())) }
            f.detailsCall = { AppResult.Success(f.details) }
            assertIs<AppResult.Success<*>>(f.load(detail))
        }
    }

    @Test
    fun finiteAcquisitionAndCompetingSnapshotReplacementFailExplicitly() = runTest {
        val f = OvertimeFixture()
        f.canonicalCall = { _, _ -> awaitCancellation() }
        assertEquals(AppResult.Success(OvertimeSyncOutcome(false, 5)), f.sync(f.workspace.id))
        assertEquals(30_000, testScheduler.currentTime)
        f.canonicalCall = { _, _ -> AppResult.Success(f.details) }
        f.replicas.rejectWrite = true
        assertEquals("client.overtime_changed", assertIs<AppResult.Failed>(f.load()).failure.code)
    }

    @Test
    fun deniedReadsPreserveTheirClassificationWhenCleanupFails() = runTest {
        for (detail in listOf(false, true)) {
            val f = OvertimeFixture()
            f.load(detail)
            f.failCleanup = true
            f.replicas.failCleanup = true
            val failure = overtimeFailure("mfa_required", FailureKind.AccessDenied)
            f.base.policyCall = { failure }
            f.detailsCall = { failure }
            assertEquals(failure, f.load(detail))
            assertNotNull(
                if (detail) (f.detailRecords.value as AppResult.Success).value
                else (f.replicas.published.value as AppResult.Success).value.checkpoint
            )
        }
    }

    @Test
    fun futureMaintenanceBoundsActionsIndependentlyOfReadFreshness() = runTest {
        val f = OvertimeFixture()
        f.base.policyCall = {
            AppResult.Success(
                f.base.policy.copy(
                    maintenance =
                        CompanyMaintenance(
                            f.base.clock.now() + 15.minutes,
                            f.base.clock.now() + 1.hours,
                        )
                )
            )
        }
        assertIs<AppResult.Success<*>>(f.load())
        val saved =
            (f.replicas.observeRequest(f.access, f.selection).first() as AppResult.Success).value!!
        assertEquals(f.base.clock.now() + 1.hours, saved.refreshAfter)
        assertEquals(f.base.clock.now() + 15.minutes, saved.access.authorizationExpiresAt)
        assertEquals("company_maintenance", saved.access.authorizationExpiryCode)
    }

    @Test
    fun localObservationSurvivesStaleFreshnessAndStopsWithItsCollector() = runTest {
        val start = Instant.parse("2026-10-12T01:00:00Z")
        for (detail in listOf(false, true)) {
            val f = OvertimeFixture { start + testScheduler.currentTime.milliseconds }
            f.load(detail)
            val results = mutableListOf<AppResult<*>>()
            val watch = launch {
                if (detail) f.observeRequest(f.workspace.id, f.selection).collect { results += it }
                else f.observe(f.workspace.id).collect { results += it }
            }
            runCurrent()
            assertEquals(
                if (detail) 1 else 2,
                f.observers + f.replicas.directoryObservers + f.replicas.pageObservers,
            )
            advanceTimeBy(3 * 24 * 60 * 60_000L)
            runCurrent()
            assertIs<AppResult.Success<*>>(results.last())
            watch.cancelAndJoin()
            assertEquals(0, f.observers + f.replicas.directoryObservers + f.replicas.pageObservers)
        }
    }
}
