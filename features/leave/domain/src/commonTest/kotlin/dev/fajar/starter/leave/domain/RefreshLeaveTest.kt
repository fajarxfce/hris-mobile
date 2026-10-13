@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class RefreshLeaveTest {
    @Test
    fun balancesAndHistoryUseLeaveAdmissionAndPublishOneCompleteView() = runTest {
        val f = LeaveFixture()
        f.workspaceState.value =
            AppResult.Success(
                f.workspace.copy(
                    policy =
                        f.workspace.policy!!.copy(
                            policy = f.policy.copy(enabledModules = setOf(CompanyModule.LEAVE))
                        )
                )
            )
        assertIs<AppResult.Success<Unit>>(
            f.load(LeaveSelection(year = 2025, status = LeaveStatus.APPROVED))
        )
        val saved = assertIs<AppResult.Success<LeaveOverview?>>(f.records.value).value!!
        assertEquals(f.employee.id, saved.selection.employeeId)
        assertEquals(2025, saved.selection.year)
        assertEquals(LeaveDays(21), saved.balances.items.single().available)
        assertEquals(2025, saved.balances.items.single().year)
        assertEquals(LeaveStatus.APPROVED, saved.requests.items.single().status)
        assertEquals(f.workspace.admission.expiresAt, saved.refreshAfter)
        assertEquals(1, f.writes)
    }

    @Test
    fun invalidSelectionCannotStartIoAndAnUnownedEmployeeCannotAcquireData() = runTest {
        val f = LeaveFixture()
        for (selection in
            listOf(
                LeaveSelection(employeeId = "../other"),
                LeaveSelection(year = 2201),
                LeaveSelection(year = 1899),
                LeaveSelection(employmentAfter = " "),
                LeaveSelection(balanceAfter = "bad code"),
                LeaveSelection(requestAfter = "x"),
            )) {
            assertIs<AppResult.Failed>(f.load(selection))
        }
        assertEquals(0, f.policyReads)
        assertEquals(
            "employee_not_found",
            assertIs<AppResult.Failed>(f.load(LeaveSelection(employeeId = f.workspace.company.id)))
                .failure
                .code,
        )
        assertEquals(0, f.balanceReads)
        assertEquals(0, f.requestReads)
        assertEquals(0, f.writes)
    }

    @Test
    fun emptyDiscoveryIsACompleteEmptyViewAndNeedsNoEmployeeCalls() = runTest {
        val f = LeaveFixture()
        f.directoryCall = { AppResult.Success(f.directory.copy(items = emptyList())) }
        assertIs<AppResult.Success<Unit>>(f.load())
        val saved = assertIs<AppResult.Success<LeaveOverview?>>(f.records.value).value!!
        assertNull(saved.selection.employeeId)
        assertTrue(saved.balances.items.isEmpty())
        assertTrue(saved.requests.items.isEmpty())
        assertEquals(0, f.balanceReads)
        assertEquals(0, f.requestReads)
    }

    @Test
    fun failedAcquisitionCannotPublishHalfAViewAndOnlyAuthorityLossClearsTheCache() = runTest {
        for ((kind, code, cleared) in
            listOf(
                Triple(FailureKind.Network, "offline", false),
                Triple(FailureKind.Timeout, "timeout", false),
                Triple(FailureKind.AccessDenied, "mfa_required", true),
                Triple(FailureKind.AccessDenied, "access_denied", true),
            )) {
            val f = LeaveFixture()
            f.load()
            val old = f.records.value
            val failure = AppResult.Failed(Failure(kind, "Safe failure", code = code))
            f.requestsCall = { failure }
            assertSame(failure, f.load(LeaveSelection(year = 2025)))
            assertEquals(if (cleared) AppResult.Success(null) else old, f.records.value)
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun lateResultsAndDenialsCannotOverwriteOrClearAReplacementScope() = runTest {
        for (stage in listOf("policy", "directory", "balances", "requests")) for (failed in
            listOf(false, true)) {
            val f = LeaveFixture()
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
                "balances" -> f.balancesCall = {
                        release.await()
                        if (failed) denial else AppResult.Success(LeavePage(listOf(f.balance)))
                    }
                else -> f.requestsCall = {
                        release.await()
                        if (failed) denial else AppResult.Success(LeavePage(listOf(f.request)))
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
            assertEquals(0, f.clears)
        }
    }

    @Test
    fun cancellationDuringPendingIoReleasesAcquisitionWithoutPublishingItsResult() = runTest {
        val f = LeaveFixture()
        val release = CompletableDeferred<Unit>()
        val regular = f.balancesCall
        f.balancesCall = { year ->
            if (year == 2026) withContext(NonCancellable) { release.await() }
            regular(year)
        }
        val first = async { f.load() }
        runCurrent()
        first.cancel()
        val replacement = async { f.load(LeaveSelection(year = 2025)) }
        runCurrent()
        assertEquals(1, f.balanceReads)
        release.complete(Unit)
        first.join()
        assertIs<AppResult.Success<Unit>>(replacement.await())
        assertEquals(1, f.writes)
        assertEquals(
            2025,
            assertIs<AppResult.Success<LeaveOverview?>>(f.records.value).value!!.selection.year,
        )
    }

    @Test
    fun timeoutsExpiryAndCasConflictsDoNotRetryOrPublish() = runTest {
        val f = LeaveFixture()
        var cancelled = false
        f.requestsCall = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        assertEquals("client.leave_timeout", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertTrue(cancelled)
        assertEquals(1, f.requestReads)
        val other = LeaveFixture()
        other.rejectWrite = true
        assertEquals("client.leave_changed", assertIs<AppResult.Failed>(other.load()).failure.code)
        assertEquals(0, other.writes)
    }

    @Test
    fun policyIsCheckedBeforeEachReadAndBoundsTheOfflineLease() = runTest {
        for (code in
            listOf("company_module_disabled", "client_update_required", "company_maintenance")) {
            val f = LeaveFixture()
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
        val base = Instant.parse("2026-10-12T01:00:00Z")
        val f = LeaveFixture { base + testScheduler.currentTime.milliseconds }
        f.policyCall = { AppResult.Success(f.policy.copy(validUntil = base + 1.seconds)) }
        val regular = f.balancesCall
        f.balancesCall = {
            delay(2.seconds)
            regular(it)
        }
        assertEquals(
            "client.company_policy_expired",
            assertIs<AppResult.Failed>(f.load()).failure.code,
        )
        assertEquals(0, f.requestReads)
        assertEquals(0, f.writes)
        val g = LeaveFixture()
        val start = g.clock.now() + 30.seconds
        g.policyCall = {
            AppResult.Success(
                g.policy.copy(maintenance = CompanyMaintenance(start, start + 60.seconds))
            )
        }
        assertIs<AppResult.Success<Unit>>(g.load())
        assertEquals(
            g.clock.now() + 1.hours,
            assertIs<AppResult.Success<LeaveOverview?>>(g.records.value).value!!.refreshAfter,
        )
    }
}
