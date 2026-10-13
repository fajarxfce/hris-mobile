@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workspace.domain

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import dev.fajar.starter.workspace.domain.usecases.ObserveWorkspaceContext
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CompanyAvailabilityTest {
    private val now = Instant.parse("2026-10-11T00:00:00Z")
    private val build = AppBuild(AppPlatform.Android, 1, "1.0.0", null)

    @Test
    fun freshnessUsesTheServerIntervalAndConsumesRequestLatencyWithoutExtendingOfflineAdmission() {
        val snapshot = CompanyPolicySnapshot(testCompanyPolicy(now + 10.hours), now)
        assertTrue(companyPolicyIsFresh(snapshot, now + 59.seconds))
        assertFalse(companyPolicyIsFresh(snapshot, now + 60.seconds))
        assertFalse(companyPolicyIsFresh(snapshot, now - 1.milliseconds))
        assertEquals(
            "client.company_policy_expired",
            assertIs<AppResult.Failed>(
                    admitCompanyOperation(
                        snapshot,
                        build,
                        CompanyModule.COMMUNICATIONS,
                        now + 60.seconds,
                    )
                )
                .failure
                .code,
        )
        assertEquals(
            CompanyAvailability.Available,
            companyAvailability(snapshot, build, now + 30.minutes),
        )
    }

    @Test
    fun minimumBuildsArePlatformSpecificAndDesktopCannotImpersonateAMobileBuild() {
        val policy = testCompanyPolicy(now).copy(minimumBuilds = CompanyMinimumBuilds(2, 0, 3))
        val snapshot = CompanyPolicySnapshot(policy, now)
        assertEquals(
            "client_update_required",
            assertIs<AppResult.Failed>(
                    admitCompanyOperation(snapshot, build, CompanyModule.PEOPLE, now)
                )
                .failure
                .code,
        )
        assertEquals(
            CompanyAvailability.Available,
            companyAvailability(snapshot, build.copy(platform = AppPlatform.Ios), now),
        )
        assertEquals(
            CompanyAvailability.UpdateRequired(3),
            companyAvailability(snapshot, build.copy(platform = AppPlatform.Web), now),
        )
        assertEquals(
            CompanyAvailability.ClientMetadataRequired,
            companyAvailability(
                snapshot,
                build.copy(platform = AppPlatform.Desktop, number = 999),
                now,
            ),
        )
    }

    @Test
    fun scheduledMaintenancePausesAtItsBoundaryAndRequiresLiveConfirmationToReopen() {
        val window = CompanyMaintenance(now + 30.seconds, now + 10.minutes)
        val snapshot =
            CompanyPolicySnapshot(
                testCompanyPolicy(now).copy(maintenance = window, validUntil = window.startsAt),
                now,
            )
        assertEquals(
            CompanyAvailability.Available,
            companyAvailability(snapshot, build, now + 29.seconds),
        )
        assertIs<CompanyAvailability.Maintenance>(
            companyAvailability(snapshot, build, window.startsAt)
        )
        assertIs<CompanyAvailability.Maintenance>(
            companyAvailability(snapshot, build, window.endsAt + 1.minutes)
        )
        val confirmed =
            CompanyPolicySnapshot(
                testCompanyPolicy(window.endsAt).copy(maintenance = window),
                window.endsAt,
            )
        assertEquals(
            CompanyAvailability.Available,
            companyAvailability(confirmed, build, window.endsAt),
        )
    }

    @Test
    fun flagChangesDoNotDiscardCompanyAdmissionOrChangeItsScope() = runTest {
        val f = AdmissionFixture()
        val blocked =
            testCompanyPolicy(f.clock.now())
                .copy(enabledModules = emptySet(), minimumBuilds = CompanyMinimumBuilds(2, 0, 0))
        f.policyCall = { AppResult.Success(blocked) }
        assertEquals(SyncResult.Complete, f.refresh())
        val selected = assertIs<AppResult.Success<Workspace?>>(f.selected.value).value!!
        assertEquals(f.workspace.id, selected.id)
        assertEquals(blocked, selected.policy!!.policy)
        f.policyCall = {
            AppResult.Failed(
                Failure(FailureKind.Service, "maintenance", code = "company_maintenance")
            )
        }
        assertIs<SyncResult.Retry>(f.refresh())
        assertEquals(selected, assertIs<AppResult.Success<Workspace?>>(f.selected.value).value)
    }

    @Test
    fun contextEmitsOneFreshnessBoundaryAndKeepsLocalDataWithoutPolling() = runTest {
        val f = AdmissionFixture { now + testScheduler.currentTime.milliseconds }
        f.selected.value =
            AppResult.Success(
                f.workspace.copy(policy = CompanyPolicySnapshot(testCompanyPolicy(now), now))
            )
        val states = mutableListOf<AppResult<WorkspaceContext?>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ObserveWorkspaceContext(f.sessions, f.workspaces, build, f.clock)().collect {
                    states.add(it)
                }
            }
        assertFalse(
            assertIs<AppResult.Success<WorkspaceContext?>>(states.last()).value!!.policy!!.stale
        )
        advanceTimeBy(60.seconds + 1.milliseconds)
        runCurrent()
        assertEquals(2, states.size)
        val stale = assertIs<AppResult.Success<WorkspaceContext?>>(states.last()).value!!
        assertTrue(stale.policy!!.stale)
        assertEquals(f.workspace.id, stale.workspace!!.id)
        advanceTimeBy(2.minutes)
        assertEquals(2, states.size)
        advanceTimeBy(1.hours)
        runCurrent()
        val staleAgain = assertIs<AppResult.Success<WorkspaceContext?>>(states.last()).value!!
        assertEquals(stale.workspace, staleAgain.workspace)
        assertTrue(staleAgain.policy!!.stale)
        advanceTimeBy(72.hours)
        runCurrent()
        assertEquals(2, states.size)
        reader.cancelAndJoin()
    }

    @Test
    fun coldExpiredAuthorizationRetainsLocalSelectionButNeverAnotherOwner() = runTest {
        val f = AdmissionFixture()
        f.selected.value =
            AppResult.Success(
                f.workspace.copy(
                    admission =
                        f.workspace.admission.copy(
                            checkedAt = f.clock.now() - 2.hours,
                            expiresAt = f.clock.now() - 1.hours,
                        )
                )
            )
        val context =
            assertIs<AppResult.Success<WorkspaceContext?>>(
                    ObserveWorkspaceContext(f.sessions, f.workspaces, build, f.clock)().first()
                )
                .value!!
        assertEquals(f.selected.value.let { (it as AppResult.Success).value }, context.workspace)
        assertNotNull(context.policy)

        for (identity in
            listOf(
                f.session.copy(id = "replacement"),
                f.session.copy(binding = f.session.binding.copy(serverId = "another-family")),
                f.session.copy(
                    binding = f.session.binding.copy(audience = "https://another.invalid/")
                ),
            )) {
            f.identity.value = AppResult.Success(identity)
            val unselected =
                assertIs<AppResult.Success<WorkspaceContext?>>(
                        ObserveWorkspaceContext(f.sessions, f.workspaces, build, f.clock)().first()
                    )
                    .value!!
            assertNull(unselected.workspace)
            assertNull(unselected.policy)
        }
    }

    @Test
    fun expiredNetworkCredentialsDoNotExpireLocallyOwnedSelection() = runTest {
        val f = AdmissionFixture { now + testScheduler.currentTime.milliseconds }
        f.identity.value =
            AppResult.Success(
                f.session.copy(
                    binding =
                        f.session.binding.copy(
                            expiresAtEpochMillis = (now + 1.minutes).toEpochMilliseconds()
                        )
                )
            )
        f.selected.value =
            AppResult.Success(
                f.workspace.copy(
                    admission = f.workspace.admission.copy(expiresAt = now + 1.minutes),
                    policy = CompanyPolicySnapshot(testCompanyPolicy(now), now),
                )
            )
        val states = mutableListOf<AppResult<WorkspaceContext?>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ObserveWorkspaceContext(f.sessions, f.workspaces, build, f.clock)().toList(states)
            }
        assertNotNull(
            assertIs<AppResult.Success<WorkspaceContext?>>(states.last()).value!!.workspace
        )
        advanceTimeBy(60_001)
        runCurrent()
        val ended = assertIs<AppResult.Success<WorkspaceContext?>>(states.last()).value!!
        assertEquals(f.workspace.id, ended.workspace?.id)
        assertEquals(CompanyAvailability.Available, ended.policy?.availability)
        val refreshed = assertIs<SyncResult.Blocked>(f.refresh())
        assertEquals("client.authentication_required", refreshed.failure.code)
        assertNotNull(assertIs<AppResult.Success<Workspace?>>(f.selected.value).value)
        reader.cancelAndJoin()
    }

    @Test
    fun fractionalServerDeadlinesStillPublishAStalePolicyEvent() = runTest {
        val f = AdmissionFixture { now + testScheduler.currentTime.milliseconds }
        f.selected.value =
            AppResult.Success(
                f.workspace.copy(
                    policy =
                        CompanyPolicySnapshot(
                            testCompanyPolicy(now)
                                .copy(validUntil = now + 1.seconds + 500.microseconds),
                            now,
                        )
                )
            )
        val states = mutableListOf<AppResult<WorkspaceContext?>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ObserveWorkspaceContext(f.sessions, f.workspaces, build, f.clock)().collect {
                    states.add(it)
                }
            }
        advanceTimeBy(1001)
        runCurrent()
        assertTrue(
            assertIs<AppResult.Success<WorkspaceContext?>>(states.last()).value!!.policy!!.stale
        )
        reader.cancelAndJoin()
    }

    @Test
    fun scheduledMaintenanceStillClosesAnAlreadyStaleWorkspaceWithoutAnotherStorageEvent() =
        runTest {
            val f = AdmissionFixture { now + testScheduler.currentTime.milliseconds }
            val window = CompanyMaintenance(now + 2.minutes, now + 10.minutes)
            f.selected.value =
                AppResult.Success(
                    f.workspace.copy(
                        policy =
                            CompanyPolicySnapshot(
                                testCompanyPolicy(now).copy(maintenance = window),
                                now,
                            )
                    )
                )
            val states = mutableListOf<AppResult<WorkspaceContext?>>()
            val reader =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    ObserveWorkspaceContext(f.sessions, f.workspaces, build, f.clock)().collect {
                        states.add(it)
                    }
                }
            advanceTimeBy(60.seconds + 1.milliseconds)
            runCurrent()
            val stale =
                assertIs<AppResult.Success<WorkspaceContext?>>(states.last()).value!!.policy!!
            assertTrue(stale.stale)
            assertEquals(CompanyAvailability.Available, stale.availability)
            advanceTimeBy(60.seconds)
            runCurrent()
            assertEquals(3, states.size)
            assertIs<CompanyAvailability.Maintenance>(
                assertIs<AppResult.Success<WorkspaceContext?>>(states.last())
                    .value!!
                    .policy!!
                    .availability
            )
            advanceTimeBy(10.minutes)
            runCurrent()
            assertEquals(3, states.size)
            reader.cancelAndJoin()
        }

    @Test
    fun contextNeverPairsANewPolicyWithThePreviousCompanyAndStopsOnSessionReplacement() = runTest {
        val f = AdmissionFixture()
        val states = mutableListOf<AppResult<WorkspaceContext?>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ObserveWorkspaceContext(f.sessions, f.workspaces, build, f.clock)().collect {
                    states.add(it)
                }
            }
        val selected =
            f.workspace.copy(
                id = "other",
                company = f.workspace.company.copy(id = "another-company"),
                policy =
                    CompanyPolicySnapshot(
                        testCompanyPolicy(f.clock.now())
                            .copy(minimumBuilds = CompanyMinimumBuilds(9, 0, 0)),
                        f.clock.now(),
                    ),
            )
        f.selected.value = AppResult.Success(selected)
        runCurrent()
        val context = assertIs<AppResult.Success<WorkspaceContext?>>(states.last()).value!!
        assertEquals(selected, context.workspace)
        assertEquals(selected.id, context.policy!!.workspaceId)
        assertEquals(CompanyAvailability.UpdateRequired(9), context.policy.availability)
        f.identity.value = AppResult.Success(f.session.copy(id = "replacement"))
        runCurrent()
        assertNull(assertIs<AppResult.Success<WorkspaceContext?>>(states.last()).value!!.workspace)
        reader.cancelAndJoin()
    }

    @Test
    fun cancellationDuringAnUncooperativePolicyLookupCannotRenewOrClearTheOldSnapshot() = runTest {
        val f = AdmissionFixture()
        val release = CompletableDeferred<Unit>()
        f.policyCall = {
            withContext(NonCancellable) { release.await() }
            AppResult.Success(testCompanyPolicy(f.clock.now()))
        }
        val pending = launch { f.refresh() }
        runCurrent()
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertEquals(AppResult.Success(f.workspace), f.selected.value)
    }
}
