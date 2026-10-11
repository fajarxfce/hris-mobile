@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.WorkCalendarSnapshot
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveWorkCalendarTest {
    @Test
    fun observationHidesContentOnScopeOrModuleLossAndReleasesCollectorsOnDisposal() = runTest {
        val f = WorkCalendarFixture()
        f.load()
        val values = mutableListOf<AppResult<WorkCalendarSnapshot?>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id).collect { values.add(it) }
            }
        assertIs<AppResult.Success<WorkCalendarSnapshot?>>(values.last())
        assertEquals(1, f.observers)
        f.workspaceState.value = AppResult.Success(f.workspace.copy(permissions = emptySet()))
        runCurrent()
        assertEquals("access_denied", assertIs<AppResult.Failed>(values.last()).failure.code)
        assertEquals(0, f.observers)
        f.workspaceState.value =
            AppResult.Success(
                f.workspace.copy(
                    policy =
                        f.workspace.policy!!.copy(
                            policy = f.policy.copy(enabledModules = emptySet())
                        )
                )
            )
        runCurrent()
        assertEquals(
            "company_module_disabled",
            assertIs<AppResult.Failed>(values.last()).failure.code,
        )
        f.workspaceState.value = AppResult.Success(f.workspace)
        runCurrent()
        assertIs<AppResult.Success<WorkCalendarSnapshot?>>(values.last())
        f.sessionState.value = AppResult.Success(f.session.copy(id = "replacement"))
        runCurrent()
        assertEquals(
            "client.session_changed",
            assertIs<AppResult.Failed>(values.last()).failure.code,
        )
        assertEquals(0, f.observers)
        f.sessionState.value = AppResult.Success(f.session)
        runCurrent()
        assertEquals(1, f.observers)
        reader.cancelAndJoin()
        assertEquals(0, f.observers)
        val count = values.size
        f.records.value = AppResult.Success(null)
        runCurrent()
        assertEquals(count, values.size)
    }

    @Test
    fun aRenewedWorkspaceCannotExtendTheAgeOfAnExistingSnapshot() = runTest {
        val base = Instant.parse("2026-10-12T01:00:00Z")
        val f = WorkCalendarFixture { base + testScheduler.currentTime.milliseconds }
        f.load()
        val values = mutableListOf<AppResult<WorkCalendarSnapshot?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.observe(f.workspace.id).collect { values.add(it) }
        }
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
        runCurrent()
        assertIs<AppResult.Success<WorkCalendarSnapshot?>>(values.last())
        advanceTimeBy(30.minutes)
        runCurrent()
        assertEquals(
            "client.calendar_cache_expired",
            assertIs<AppResult.Failed>(values.last()).failure.code,
        )
    }

    @Test
    fun knownMaintenanceEndsTheReadLeaseWithoutWaitingForAnotherDatabaseEvent() = runTest {
        val base = Instant.parse("2026-10-12T01:00:00Z")
        val f = WorkCalendarFixture { base + testScheduler.currentTime.milliseconds }
        val policy =
            f.policy.copy(maintenance = CompanyMaintenance(base + 10.seconds, base + 60.seconds))
        f.policyCall = { AppResult.Success(policy) }
        f.workspaceState.value =
            AppResult.Success(f.workspace.copy(policy = CompanyPolicySnapshot(policy, base)))
        f.load()
        val values = mutableListOf<AppResult<WorkCalendarSnapshot?>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.observe(f.workspace.id).collect { values.add(it) }
        }
        assertIs<AppResult.Success<WorkCalendarSnapshot?>>(values.last())
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals("company_maintenance", assertIs<AppResult.Failed>(values.last()).failure.code)
    }

    @Test
    fun aSnapshotFromAnotherScopeAndClockRollbackAreNeverPublished() = runTest {
        var now = Instant.parse("2026-10-12T01:00:00Z")
        val f = WorkCalendarFixture { now }
        f.load()
        val saved = assertIs<AppResult.Success<WorkCalendarSnapshot?>>(f.records.value).value!!
        f.records.value =
            AppResult.Success(saved.copy(access = saved.access.copy(companyId = "other-company")))
        assertEquals(
            "client.calendar_scope_changed",
            assertIs<AppResult.Failed>(f.observe(f.workspace.id).first()).failure.code,
        )
        f.records.value = AppResult.Success(saved.copy(fetchedAt = now + 1.seconds))
        assertEquals(
            "client.calendar_cache_expired",
            assertIs<AppResult.Failed>(f.observe(f.workspace.id).first()).failure.code,
        )
        now -= 1.seconds
        assertEquals(
            "client.workspace_expired",
            assertIs<AppResult.Failed>(f.observe(f.workspace.id).first()).failure.code,
        )
    }
}
