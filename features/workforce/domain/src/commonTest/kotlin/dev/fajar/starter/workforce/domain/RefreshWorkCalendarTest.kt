@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate

class RefreshWorkCalendarTest {
    @Test
    fun aCompleteWeekUsesIndependentWorkforceAdmissionAndPublishesOnce() = runTest {
        val f = WorkCalendarFixture()
        f.workspaceState.value =
            AppResult.Success(
                f.workspace.copy(
                    policy =
                        f.workspace.policy!!.copy(
                            policy = f.policy.copy(enabledModules = setOf(CompanyModule.WORKFORCE))
                        )
                )
            )
        assertIs<AppResult.Success<Unit>>(f.load())
        val saved = assertIs<AppResult.Success<WorkCalendarSnapshot?>>(f.records.value).value!!
        assertEquals(f.employee.id, saved.selection.employeeId)
        assertEquals(LocalDate(2026, 10, 12), saved.period.from)
        assertEquals(LocalDate(2026, 10, 18), saved.period.until)
        assertEquals(7, saved.calendar!!.days.size)
        assertEquals(f.workspace.admission.expiresAt, saved.refreshAfter)
        assertEquals(1, f.policyReads)
        assertEquals(1, f.calendarReads)
        assertEquals(1, f.writes)
    }

    @Test
    fun invalidInputsAndUnownedSelectionCannotFetchAnotherEmployeesCalendar() = runTest {
        val f = WorkCalendarFixture()
        assertIs<AppResult.Failed>(f.load(WorkCalendarSelection(employeeId = "../another")))
        assertIs<AppResult.Failed>(f.load(WorkCalendarSelection(after = " ")))
        assertIs<AppResult.Failed>(f.load(WorkCalendarSelection(after = "E".repeat(33))))
        assertEquals(0, f.policyReads)
        assertEquals(
            "employee_not_found",
            assertIs<AppResult.Failed>(
                    f.load(
                        WorkCalendarSelection(employeeId = "44444444-4444-4444-4444-444444444444")
                    )
                )
                .failure
                .code,
        )
        assertEquals(0, f.calendarReads)
        assertEquals(0, f.writes)
        assertEquals(
            "invalid_calendar_range",
            assertIs<AppResult.Failed>(
                    f.load(WorkCalendarSelection(from = LocalDate(2026, 10, 13)))
                )
                .failure
                .code,
        )
        assertEquals(0, f.calendarReads)
    }

    @Test
    fun emptyDirectoryIsACompleteSnapshotAndDoesNotIssueAnEmployeeRead() = runTest {
        val f = WorkCalendarFixture()
        f.directoryCall = { AppResult.Success(f.directory.copy(items = emptyList())) }
        assertIs<AppResult.Success<Unit>>(f.load())
        val saved = assertIs<AppResult.Success<WorkCalendarSnapshot?>>(f.records.value).value!!
        assertNull(saved.selection.employeeId)
        assertNull(saved.calendar)
        assertEquals(0, f.calendarReads)
        assertEquals(1, f.writes)
    }

    @Test
    fun transportFailureRetainsSavedDataAndAuthoritativeDenialRemovesIt() = runTest {
        for ((kind, code, cleared) in
            listOf(
                Triple(FailureKind.Network, "offline", false),
                Triple(FailureKind.Timeout, "timeout", false),
                Triple(FailureKind.AccessDenied, "access_denied", true),
                Triple(FailureKind.AccessDenied, "mfa_required", true),
                Triple(FailureKind.Unexpected, "employee_not_found", true),
            )) {
            val f = WorkCalendarFixture()
            f.load()
            val saved = f.records.value
            val failure = AppResult.Failed(Failure(kind, "Safe failure", code = code))
            f.calendarCall = { failure }
            assertSame(failure, f.load())
            if (cleared) assertEquals(AppResult.Success(null), f.records.value)
            else assertEquals(saved, f.records.value)
            assertEquals(if (cleared) 1 else 0, f.clears)
        }
    }

    @Test
    fun cancelledAcquisitionCannotPublishAndALaterSelectionOwnsTheNextSnapshot() = runTest {
        val f = WorkCalendarFixture()
        val release = CompletableDeferred<Unit>()
        val regular = f.calendarCall
        f.calendarCall = { period ->
            if (period.from == LocalDate(2026, 10, 12))
                withContext(NonCancellable) { release.await() }
            regular(period)
        }
        val first = async { f.load() }
        runCurrent()
        first.cancel()
        val replacement = async { f.load(WorkCalendarSelection(from = LocalDate(2026, 10, 19))) }
        runCurrent()
        assertEquals(1, f.calendarReads)
        release.complete(Unit)
        first.join()
        assertIs<AppResult.Success<Unit>>(replacement.await())
        assertEquals(
            LocalDate(2026, 10, 19),
            assertIs<AppResult.Success<WorkCalendarSnapshot?>>(f.records.value).value!!.period.from,
        )
        assertEquals(1, f.writes)
    }

    @Test
    fun lateResultsAndFailuresCannotPublishOrClearTheReplacementScope() = runTest {
        for (stage in listOf("policy", "directory", "calendar")) for (failed in
            listOf(false, true)) {
            val f = WorkCalendarFixture()
            f.load()
            val saved = f.records.value
            val release = CompletableDeferred<Unit>()
            val denial =
                AppResult.Failed(
                    Failure(FailureKind.AccessDenied, "Revoked", code = "company_access_denied")
                )
            when (stage) {
                "policy" -> f.policyCall = {
                        release.await()
                        if (failed) denial else AppResult.Success(f.policy)
                    }
                "directory" -> f.directoryCall = {
                        release.await()
                        if (failed) denial else AppResult.Success(f.directory)
                    }
                else -> {
                    val regular = f.calendarCall
                    f.calendarCall = {
                        release.await()
                        if (failed) denial else regular(it)
                    }
                }
            }
            val pending = async { f.load() }
            runCurrent()
            f.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
            release.complete(Unit)
            assertEquals(
                "client.workspace_changed",
                assertIs<AppResult.Failed>(pending.await()).failure.code,
            )
            assertEquals(saved, f.records.value)
            assertEquals(1, f.writes)
            assertEquals(0, f.clears)
        }
    }

    @Test
    fun freshPolicyControlsAcquisitionWhileOldWorkspaceFreshnessDoesNotDiscardAuthorizedData() =
        runTest {
            val base = Instant.parse("2026-10-12T01:00:00Z")
            val f = WorkCalendarFixture { base + testScheduler.currentTime.milliseconds }
            f.policyCall = {
                delay(2.seconds)
                AppResult.Success(f.policy.copy(validUntil = base + 1.seconds))
            }
            assertEquals(
                "client.company_policy_expired",
                assertIs<AppResult.Failed>(f.load()).failure.code,
            )
            assertEquals(0, f.directoryReads)
            f.policyCall = {
                AppResult.Success(
                    f.policy.copy(
                        serverTime = f.clock.now(),
                        validUntil = f.clock.now() + 60.seconds,
                    )
                )
            }
            f.workspaceState.value =
                AppResult.Success(
                    f.workspace.copy(
                        admission =
                            f.workspace.admission.copy(expiresAt = f.clock.now() + 1.seconds)
                    )
                )
            val regular = f.calendarCall
            f.calendarCall = {
                delay(2.seconds)
                regular(it)
            }
            assertIs<AppResult.Success<Unit>>(f.load())
            assertEquals(1, f.calendarReads)
            assertEquals(1, f.writes)
        }

    @Test
    fun acquisitionHasAFiniteBudgetAndCompareAndSetConflictsDoNotRetry() = runTest {
        val f = WorkCalendarFixture()
        var cancelled = false
        f.calendarCall = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        assertEquals("client.calendar_timeout", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertTrue(cancelled)
        assertEquals(1, f.calendarReads)
        assertEquals(0, f.writes)
        val other = WorkCalendarFixture()
        other.rejectWrite = true
        assertEquals(
            "client.calendar_changed",
            assertIs<AppResult.Failed>(other.load()).failure.code,
        )
        assertEquals(1, other.calendarReads)
        assertEquals(0, other.writes)
    }

    @Test
    fun liveAvailabilityBlocksDiscoveryWithoutExpiringSavedFacts() = runTest {
        for (code in
            listOf("company_module_disabled", "client_update_required", "company_maintenance")) {
            val f = WorkCalendarFixture()
            f.policyCall = {
                AppResult.Success(
                    when (code) {
                        "company_module_disabled" -> f.policy.copy(enabledModules = emptySet())
                        "client_update_required" ->
                            f.policy.copy(minimumBuilds = CompanyMinimumBuilds(2, 0, 0))
                        else ->
                            f.policy.copy(
                                maintenance =
                                    CompanyMaintenance(f.clock.now(), f.clock.now() + 60.seconds),
                                maintenanceActive = true,
                            )
                    }
                )
            }
            assertEquals(code, assertIs<AppResult.Failed>(f.load()).failure.code)
            assertEquals(0, f.directoryReads)
        }
        val f = WorkCalendarFixture()
        val start = f.clock.now() + 30.seconds
        f.policyCall = {
            AppResult.Success(
                f.policy.copy(maintenance = CompanyMaintenance(start, start + 60.seconds))
            )
        }
        assertIs<AppResult.Success<Unit>>(f.load())
        assertEquals(
            f.clock.now() + 1.hours,
            assertIs<AppResult.Success<WorkCalendarSnapshot?>>(f.records.value).value!!.refreshAfter,
        )
    }
}
