@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.app.bootstrap

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.AccountRepository
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class AppLocalWorkspaceTest {
    @Test
    fun admittedColdStartOpensWithoutWaitingForAnUnreachableServer() = runTest {
        val f = RecoveryFixture(this, expired = false)
        try {
            f.load = { awaitCancellation() }
            f.vm.onEvent(AppEvent.ForegroundEntered)
            runCurrent()
            assertEquals(f.selected, assertIs<AppStage.SignedIn>(f.vm.state.value.stage).workspace)
            assertTrue(f.vm.state.value.refreshingWorkspace)
            assertEquals(1, f.calls)
            advanceTimeBy(30_001)
            runCurrent()
            assertFalse(f.vm.state.value.refreshingWorkspace)
            assertEquals(FailureKind.Timeout, f.vm.state.value.workspaceRefreshFailure?.kind)
            assertEquals(f.selected, assertIs<AppStage.SignedIn>(f.vm.state.value.stage).workspace)
        } finally {
            f.close()
        }
    }

    @Test
    fun staleColdStartKeepsCompanyDataWithoutWaitingForTheNetworkOrPolling() = runTest {
        val f = RecoveryFixture(this)
        try {
            runCurrent()
            assertEquals(0, f.calls)
            assertEquals(AppStage.SignedIn(f.session.id, f.selected), f.vm.state.value.stage)
            assertNotNull(f.vm.state.value.companyPolicy)
            f.vm.onEvent(AppEvent.ForegroundEntered)
            runCurrent()
            assertEquals(1, f.calls)
            assertEquals(f.offline, f.vm.state.value.workspaceRefreshFailure)
            assertFalse(f.vm.state.value.refreshingWorkspace)
            advanceTimeBy(10.minutes)
            runCurrent()
            assertEquals(1, f.calls)
            assertEquals(f.selected, assertIs<AppStage.SignedIn>(f.vm.state.value.stage).workspace)
            assertEquals(AppResult.Success(f.selected), f.workspaces.current(f.session.id))
        } finally {
            f.close()
        }
    }

    @Test
    fun localReadsRemainOpenDuringRefreshAndSuccessfulRenewalDoesNotLoop() = runTest {
        val f = RecoveryFixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            f.vm.onEvent(AppEvent.ForegroundEntered)
            runCurrent()
            f.load = {
                release.await()
                AppResult.Success(f.account)
            }
            f.vm.onEvent(AppEvent.WorkspaceRefreshRequested)
            runCurrent()
            f.vm.onEvent(AppEvent.WorkspaceRefreshRequested)
            runCurrent()
            assertEquals(2, f.calls)
            assertTrue(f.vm.state.value.refreshingWorkspace)
            assertEquals(f.selected, assertIs<AppStage.SignedIn>(f.vm.state.value.stage).workspace)
            release.complete(Unit)
            runCurrent()
            val admitted = assertIs<AppStage.SignedIn>(f.vm.state.value.stage)
            assertEquals(f.selected.id, admitted.workspace?.id)
            assertNull(f.vm.state.value.accessFailure)
            assertNull(f.vm.state.value.workspaceRefreshFailure)
            assertFalse(f.vm.state.value.refreshingWorkspace)
            repeat(3) { runCurrent() }
            assertEquals(2, f.calls)
        } finally {
            release.cancel()
            f.close()
        }
    }

    @Test
    fun stalePolicyRequestsOneRefreshWithoutExpiringLocalReads() = runTest {
        val f = RecoveryFixture(this, expired = false)
        try {
            f.vm.onEvent(AppEvent.ForegroundEntered)
            runCurrent()
            assertEquals(1, f.calls)
            advanceTimeBy(60_001)
            runCurrent()
            assertEquals(2, f.calls)
            advanceTimeBy(1.hours)
            runCurrent()
            assertEquals(2, f.calls)
            assertEquals(AppStage.SignedIn(f.session.id, f.selected), f.vm.state.value.stage)
            advanceTimeBy(72.hours)
            runCurrent()
            assertEquals(2, f.calls)
            assertEquals(f.selected, assertIs<AppStage.SignedIn>(f.vm.state.value.stage).workspace)
        } finally {
            f.close()
        }
    }

    @Test
    fun failedInvalidationAndLaterOfflineRetryCannotReopenRetainedCiphertext() = runTest {
        val f = RecoveryFixture(this)
        val storage = Failure(FailureKind.Storage, "fixture", code = "fixture.storage")
        try {
            f.workspaces.casFailure = storage
            f.load = {
                AppResult.Failed(
                    Failure(FailureKind.AccessDenied, "fixture", code = "company_access_denied")
                )
            }
            f.vm.onEvent(AppEvent.ForegroundEntered)
            runCurrent()
            assertEquals(storage, f.vm.state.value.accessFailure)
            f.load = { AppResult.Failed(f.offline) }
            f.vm.onEvent(AppEvent.BootstrapRequested)
            runCurrent()
            assertEquals(storage, f.vm.state.value.accessFailure)
            assertEquals(f.selected, assertIs<AppStage.SignedIn>(f.vm.state.value.stage).workspace)
            assertEquals(AppResult.Success(f.selected), f.workspaces.current(f.session.id))
            f.workspaces.casFailure = null
            f.load = { AppResult.Success(f.account) }
            f.vm.onEvent(AppEvent.BootstrapRequested)
            runCurrent()
            assertNull(f.vm.state.value.accessFailure)
            assertNotNull(assertIs<AppStage.SignedIn>(f.vm.state.value.stage).workspace)
        } finally {
            f.close()
        }
    }

    @Test
    fun backgroundAndDisposalCancelRecoveryBeforeALateSuccessOrDenialCanChangeStorage() = runTest {
        for (denied in listOf(false, true)) {
            val f = RecoveryFixture(this)
            val release = CompletableDeferred<Unit>()
            try {
                f.load = {
                    withContext(NonCancellable) { release.await() }
                    if (denied)
                        AppResult.Failed(
                            Failure(
                                FailureKind.AccessDenied,
                                "fixture",
                                code = "company_access_denied",
                            )
                        )
                    else AppResult.Success(f.account)
                }
                f.vm.onEvent(AppEvent.ForegroundEntered)
                runCurrent()
                assertEquals(1, f.calls)
                f.vm.onEvent(AppEvent.ForegroundLeft)
                runCurrent()
                assertFalse(f.vm.state.value.refreshingWorkspace)
                f.owner.clear()
                release.complete(Unit)
                runCurrent()
                assertEquals(AppResult.Success(f.selected), f.workspaces.current(f.session.id))
                assertNull(f.vm.state.value.accessFailure)
            } finally {
                release.complete(Unit)
                f.close()
            }
        }
    }

    @Test
    fun anAccountChangeDiscardsLocalSelectionAndRejectsALateRefreshResult() = runTest {
        val f = RecoveryFixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            f.load = {
                withContext(NonCancellable) { release.await() }
                AppResult.Success(f.account)
            }
            f.vm.onEvent(AppEvent.ForegroundEntered)
            runCurrent()
            f.sessions.value.value = AppResult.Success(f.session.copy(id = "replacement"))
            runCurrent()
            assertEquals(AppStage.SignedIn("replacement"), f.vm.state.value.stage)
            release.complete(Unit)
            runCurrent()
            assertEquals(AppStage.SignedIn("replacement"), f.vm.state.value.stage)
            assertEquals(1, f.calls)
            assertEquals(AppResult.Success(f.selected), f.workspaces.current(f.session.id))
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    @Test
    fun explicitlyLeavingAStaleCompanyIsLocalAndDoesNotFetchAnotherCompany() = runTest {
        val f = RecoveryFixture(this)
        try {
            f.vm.onEvent(AppEvent.ForegroundEntered)
            runCurrent()
            f.vm.onEvent(AppEvent.CompanyChangeRequested)
            runCurrent()
            assertEquals(AppStage.SignedIn(f.session.id), f.vm.state.value.stage)
            assertEquals(AppResult.Success(null), f.workspaces.current(f.session.id))
            assertFalse(f.vm.state.value.changingWorkspace)
            assertEquals(1, f.calls)
        } finally {
            f.close()
        }
    }
}

private class RecoveryFixture(scope: TestScope, expired: Boolean = true) {
    init {
        Dispatchers.setMain(StandardTestDispatcher(scope.testScheduler))
    }

    val clock =
        object : Clock {
            override fun now() =
                Instant.parse("2026-10-13T08:00:00Z") + scope.testScheduler.currentTime.milliseconds
        }
    val session = testSession()
    val selected = testWorkspace(clock.now() - if (expired) 2.hours else 0.hours)
    val sessions = TestSessions(session)
    val workspaces = TestWorkspaces(selected, clock)
    val offline = Failure(FailureKind.Network, "fixture.offline")
    val account =
        AccountSnapshot(
            session.user,
            listOf(selected.company),
            emptySet(),
            SessionAssurance(false, false, false, null, null),
            false,
        )
    var calls = 0
    var load: suspend () -> AppResult<AccountSnapshot> = { AppResult.Failed(offline) }
    val vm =
        bootstrapViewModel(
            sessions,
            workspaces,
            object : AccountRepository {
                override suspend fun load(sessionId: String): AppResult<AccountSnapshot> {
                    calls++
                    return load()
                }
            },
            clock,
        )
    val owner = ViewModelStore().also { it.put("app", vm) }

    fun close() {
        owner.clear()
        Dispatchers.resetMain()
    }
}
