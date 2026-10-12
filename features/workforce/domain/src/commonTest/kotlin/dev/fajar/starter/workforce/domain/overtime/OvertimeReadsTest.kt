package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
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
            f.acquiredPeriod,
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
            assertIs<AppResult.Success<OvertimeSnapshot<OvertimeOverview>?>>(f.records.value)
                .value!!
        assertEquals(f.base.employee.id, snapshot.value.selection.employeeId)
        assertEquals(f.base.clock.now() + 1.hours, snapshot.expiresAt)
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
        for (selection in invalid) assertIs<AppResult.Failed>(f.refresh(f.workspace.id, selection))
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
        assertIs<AppResult.Success<*>>(f.load())
        assertEquals(0, f.requestReads)
        assertTrue((f.records.value as AppResult.Success).value!!.value.requests.items.isEmpty())
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
        f.requestsCall = { awaitCancellation() }
        assertEquals("client.overtime_timeout", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertEquals(30_000, testScheduler.currentTime)
        f.requestsCall = { AppResult.Success(OvertimePage(emptyList())) }
        f.rejectWrite = true
        assertEquals("client.overtime_changed", assertIs<AppResult.Failed>(f.load()).failure.code)
    }

    @Test
    fun deniedReadsPreserveTheirClassificationWhenCleanupFails() = runTest {
        for (detail in listOf(false, true)) {
            val f = OvertimeFixture()
            f.load(detail)
            f.failCleanup = true
            val failure = overtimeFailure("mfa_required", FailureKind.AccessDenied)
            f.requestsCall = { failure }
            f.detailsCall = { failure }
            assertEquals(failure, f.load(detail))
            assertNotNull(
                if (detail) (f.detailRecords.value as AppResult.Success).value
                else (f.records.value as AppResult.Success).value
            )
        }
    }

    @Test
    fun futureMaintenanceShortensTheOfflineLease() = runTest {
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
        val saved = (f.records.value as AppResult.Success).value!!
        assertEquals(f.base.clock.now() + 15.minutes, saved.expiresAt)
        assertEquals("company_maintenance", saved.access.expiryCode)
    }

    @Test
    fun observationOwnsOneExpiryTimerAndStopsWithItsCollector() = runTest {
        val start = Instant.parse("2026-10-12T01:00:00Z")
        for (detail in listOf(false, true)) {
            val f = OvertimeFixture { start + testScheduler.currentTime.milliseconds }
            f.load(detail)
            val results = mutableListOf<AppResult<*>>()
            val watch = launch {
                if (detail) f.observeRequest(f.workspace.id).collect { results += it }
                else f.observe(f.workspace.id).collect { results += it }
            }
            runCurrent()
            assertEquals(1, f.observers)
            advanceTimeBy(3_600_000)
            runCurrent()
            assertIs<AppResult.Failed>(results.last())
            watch.cancelAndJoin()
            assertEquals(0, f.observers)
        }
    }
}
