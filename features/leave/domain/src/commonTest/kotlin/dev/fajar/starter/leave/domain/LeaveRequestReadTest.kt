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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class LeaveRequestReadTest {
    @Test
    fun renewingCredentialsDoesNotInvalidateTheStoredRequestReadOrRenewItsActionReview() = runTest {
        val f = LeaveRequestFixture()
        f.load()
        val saved = assertIs<AppResult.Success<LeaveRequestSnapshot?>>(f.records.value).value!!
        val renewed =
            f.base.session.copy(
                binding =
                    f.base.session.binding.copy(
                        expiresAtEpochMillis =
                            f.base.session.binding.expiresAtEpochMillis + 3_600_000
                    )
            )
        f.base.sessionState.value = AppResult.Success(renewed)
        assertEquals(
            saved,
            assertIs<AppResult.Success<LeaveRequestSnapshot?>>(
                    f.observe(f.base.workspace.id, f.selection.requestId).first()
                )
                .value,
        )
        val current =
            saved.access.copy(
                sessionExpiresAt =
                    Instant.fromEpochMilliseconds(renewed.binding.expiresAtEpochMillis)
            )
        assertIs<AppResult.Failed>(
            dev.fajar.starter.leave.domain.policies.validateLeaveActionSnapshot(
                current,
                saved,
                f.base.clock.now(),
            )
        )
    }

    @Test
    fun detailsOwnAnIndependentCompleteSnapshotAndNeedNoPeopleOrWorkforceRead() = runTest {
        val f = LeaveRequestFixture()
        f.base.load()
        val overview = f.base.records.value
        f.base.workspaceState.value =
            AppResult.Success(
                f.base.workspace.copy(
                    policy =
                        CompanyPolicySnapshot(
                            f.base.policy.copy(enabledModules = setOf(CompanyModule.LEAVE)),
                            f.base.clock.now(),
                        )
                )
            )
        assertIs<AppResult.Success<Unit>>(f.load())
        val saved = assertIs<AppResult.Success<LeaveRequestSnapshot?>>(f.records.value).value!!
        assertEquals(f.selection, saved.selection)
        assertEquals(f.details, saved.request)
        assertEquals(overview, f.base.records.value)
        assertEquals(1, f.reads)
        assertEquals(1, f.writes)
        assertEquals(f.base.workspace.admission.expiresAt, saved.refreshAfter)
    }

    @Test
    fun invalidReferencesCannotAcquireDataAndWrongResponseIdentityCannotPublish() = runTest {
        val f = LeaveRequestFixture()
        for (selection in
            listOf(
                LeaveRequestSelection("../other"),
                f.selection.copy(historyAfter = -1),
                f.selection.copy(historyAfter = 9_007_199_254_740_992L),
            )) assertIs<AppResult.Failed>(f.load(selection))
        assertEquals(0, f.base.policyReads)
        assertEquals(0, f.reads)
        f.requestCall = { AppResult.Success(f.details.copy(id = f.base.employee.id)) }
        assertEquals("client.invalid_response", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertEquals(0, f.writes)
    }

    @Test
    fun pendingGenerationAndRemoteResultsCannotReadWriteOrClearAReplacementCompany() = runTest {
        for (stage in listOf("generation", "policy", "request")) for (failed in
            listOf(false, true)) {
            val f = LeaveRequestFixture()
            f.load()
            val saved = f.records.value
            val release = CompletableDeferred<Unit>()
            val denied =
                AppResult.Failed(
                    Failure(FailureKind.AccessDenied, "Denied", code = "access_denied")
                )
            when (stage) {
                "generation" -> f.generationWait = { release.await() }
                "policy" -> f.base.policyCall = {
                        release.await()
                        if (failed) denied else AppResult.Success(f.base.policy)
                    }
                else -> f.requestCall = {
                        release.await()
                        if (failed) denied else AppResult.Success(f.details)
                    }
            }
            val pending = async { f.load() }
            runCurrent()
            f.base.workspaceState.value =
                AppResult.Success(f.base.workspace.copy(id = "replacement"))
            release.complete(Unit)
            assertEquals(
                "client.workspace_changed",
                assertIs<AppResult.Failed>(pending.await()).failure.code,
            )
            assertEquals(saved, f.records.value)
            assertEquals(0, f.clears)
            if (stage == "generation") assertEquals(1, f.base.policyReads)
        }
    }

    @Test
    fun authorityRejectionSurvivesFailedCleanupWhileOfflineFailureKeepsTheView() = runTest {
        val f = LeaveRequestFixture()
        f.load()
        val previous = f.records.value
        val offline = AppResult.Failed(Failure(FailureKind.Network, "Offline"))
        f.requestCall = { offline }
        assertSame(offline, f.load())
        assertEquals(previous, f.records.value)
        val unavailable =
            AppResult.Failed(
                Failure(FailureKind.Unavailable, "Unavailable", code = "leave_request_not_found")
            )
        f.requestCall = { unavailable }
        f.cleanupFails = true
        assertSame(unavailable, f.load())
        assertEquals(previous, f.records.value)
        f.cleanupFails = false
        assertSame(unavailable, f.load())
        assertEquals(AppResult.Success(null), f.records.value)
    }

    @Test
    fun cancellationRejectsLateTransportResultsAndReleasesTheNextAcquisition() = runTest {
        val f = LeaveRequestFixture()
        val release = CompletableDeferred<Unit>()
        f.requestCall = { selected ->
            if (selected.historyAfter == null) withContext(NonCancellable) { release.await() }
            AppResult.Success(f.details)
        }
        val pending = async { f.load() }
        runCurrent()
        pending.cancel()
        val next = async { f.load(f.selection.copy(historyAfter = 1)) }
        runCurrent()
        assertEquals(1, f.reads)
        release.complete(Unit)
        pending.join()
        assertIs<AppResult.Success<Unit>>(next.await())
        assertEquals(1, f.writes)
        assertEquals(
            1L,
            assertIs<AppResult.Success<LeaveRequestSnapshot?>>(f.records.value)
                .value!!
                .selection
                .historyAfter,
        )
    }

    @Test
    fun deadlinesAndConflictsDoNotRetryOrReportPublication() = runTest {
        val f = LeaveRequestFixture()
        var cancelled = false
        f.requestCall = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        assertEquals("client.leave_timeout", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertTrue(cancelled)
        assertEquals(1, f.reads)
        assertEquals(0, f.writes)
        val other = LeaveRequestFixture()
        other.rejectWrite = true
        assertEquals("client.leave_changed", assertIs<AppResult.Failed>(other.load()).failure.code)
    }

    @Test
    fun livePolicyBlocksTransportAndSeparatelyBoundsActionAuthorization() = runTest {
        val f = LeaveRequestFixture()
        f.base.policyCall = { AppResult.Success(f.base.policy.copy(enabledModules = emptySet())) }
        assertEquals("company_module_disabled", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertEquals(0, f.reads)
        val now = Instant.parse("2026-10-12T01:00:00Z")
        val g = LeaveRequestFixture { now + testScheduler.currentTime.milliseconds }
        val maintenance = CompanyMaintenance(now + 30.seconds, now + 60.seconds)
        g.base.policyCall = { AppResult.Success(g.base.policy.copy(maintenance = maintenance)) }
        g.load()
        val values = mutableListOf<AppResult<LeaveRequestSnapshot?>>()
        val observer =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                g.observe(g.base.workspace.id, g.selection.requestId).toList(values)
            }
        assertEquals(
            now + 1.hours,
            assertIs<AppResult.Success<LeaveRequestSnapshot?>>(values.last()).value!!.refreshAfter,
        )
        advanceTimeBy(30.seconds)
        runCurrent()
        assertIs<AppResult.Success<LeaveRequestSnapshot?>>(values.last())
        assertEquals(
            maintenance.startsAt,
            assertIs<AppResult.Success<LeaveRequestSnapshot?>>(values.last())
                .value!!
                .access
                .authorizationExpiresAt,
        )
        observer.cancelAndJoin()
        assertEquals(0, g.observers)
    }

    @Test
    fun observationNeverExposesAnotherRequestOrRetainsThePreviousSessionSubscription() = runTest {
        val f = LeaveRequestFixture()
        f.load()
        val values = mutableListOf<AppResult<LeaveRequestSnapshot?>>()
        val observer =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.base.workspace.id, f.base.employee.id).toList(values)
            }
        assertEquals(AppResult.Success(null), values.last())
        assertEquals(1, f.observers)
        f.base.sessionState.value = AppResult.Success(null)
        runCurrent()
        assertEquals("session_revoked", assertIs<AppResult.Failed>(values.last()).failure.code)
        assertEquals(0, f.observers)
        observer.cancelAndJoin()
    }
}
