@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.app.bootstrap

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.app.navigation.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.AccountRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.onboarding.domain.repositories.OnboardingRepository
import dev.fajar.starter.onboarding.domain.usecases.LoadOnboarding
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.entities.WorkspaceAdmission
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.LeaveWorkspace
import dev.fajar.starter.workspace.domain.usecases.ObserveWorkspaceContext
import dev.fajar.starter.workspace.domain.usecases.RefreshWorkspace
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext

class AppViewModelTest {
    @Test
    fun renewingTheLeaseDoesNotStartARefreshLoopAndStoppingCancelsPendingWork() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        val identity = TestSessions(testSession())
        val selected = testWorkspace()
        val workspaces = TestWorkspaces(selected)
        var calls = 0
        var release: CompletableDeferred<Unit>? = null
        val accounts =
            object : AccountRepository {
                override suspend fun load(sessionId: String): AppResult<AccountSnapshot> {
                    calls++
                    release?.let { gate -> withContext(NonCancellable) { gate.await() } }
                    return AppResult.Success(
                        AccountSnapshot(
                            testSession().user,
                            listOf(selected.company),
                            emptySet(),
                            SessionAssurance(false, false, false, null, null),
                            false,
                        )
                    )
                }
            }
        try {
            val vm = bootstrapViewModel(identity, workspaces, accounts)
            owner.put("app", vm)
            runCurrent()
            assertEquals(0, calls)
            vm.onEvent(AppEvent.ForegroundEntered)
            runCurrent()
            assertEquals(1, calls)
            repeat(3) { runCurrent() }
            assertEquals(1, calls)
            val accepted = (vm.state.value.stage as AppStage.SignedIn).workspace
            release = CompletableDeferred()
            vm.onEvent(AppEvent.ForegroundLeft)
            vm.onEvent(AppEvent.ForegroundEntered)
            runCurrent()
            assertEquals(2, calls)
            vm.onEvent(AppEvent.ForegroundLeft)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(accepted, (vm.state.value.stage as AppStage.SignedIn).workspace)
            assertNull(vm.state.value.accessFailure)
        } finally {
            owner.clear()
            release?.complete(Unit)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedRevocationStorageKeepsProtectedRoutesBlockedUntilRevalidationRecovers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        val identity = TestSessions(testSession())
        val selected = testWorkspace()
        val workspaces = TestWorkspaces(selected)
        val storage = Failure(FailureKind.Storage, "fixture", code = "fixture.storage")
        workspaces.casFailure = storage
        var failure = Failure(FailureKind.AccessDenied, "fixture", code = "company_access_denied")
        val accounts =
            object : AccountRepository {
                override suspend fun load(sessionId: String): AppResult<AccountSnapshot> =
                    AppResult.Failed(failure)
            }
        try {
            val vm = bootstrapViewModel(identity, workspaces, accounts)
            owner.put("app", vm)
            runCurrent()
            vm.onEvent(AppEvent.ForegroundEntered)
            runCurrent()
            assertEquals(storage, vm.state.value.accessFailure)
            failure = Failure(FailureKind.Network, "offline")
            vm.onEvent(AppEvent.BootstrapRequested)
            runCurrent()
            assertEquals(storage, vm.state.value.accessFailure)
            workspaces.casFailure = null
            failure = Failure(FailureKind.AccessDenied, "fixture", code = "company_access_denied")
            vm.onEvent(AppEvent.BootstrapRequested)
            runCurrent()
            assertNull(vm.state.value.accessFailure)
            assertNull((vm.state.value.stage as AppStage.SignedIn).workspace)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pendingLinkSurvivesOnboardingAndLoginAndIgnoresAnOlderAcknowledgement() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        val identity = TestSessions()
        var complete = false
        val onboarding =
            object : OnboardingRepository {
                override suspend fun isComplete() = AppResult.Success(complete)

                override suspend fun complete() = AppResult.Success(Unit).also { complete = true }
            }
        try {
            val vm =
                AppViewModel(
                    LoadOnboarding(onboarding),
                    ObserveWorkspaceContext(identity, TestWorkspaces(), fixtureBuild),
                    dev.fajar.starter.identity.domain.usecases.RestoreSession(
                        identity,
                        server =
                            dev.fajar.starter.identity.domain.entities.IdentityServer(
                                "https://example.invalid/",
                                "Test device",
                            ),
                    ),
                    ResolveAppLink(AppEnvironment.Dev),
                    RefreshWorkspace(
                        identity,
                        object : AccountRepository {
                            override suspend fun load(
                                sessionId: String
                            ): AppResult<AccountSnapshot> = error("No workspace is selected.")
                        },
                        TestWorkspaces(),
                    ),
                    LeaveWorkspace(identity, TestWorkspaces()),
                )
            owner.put("app", vm)
            vm.onEvent(AppEvent.LinkReceived("hrismobile-dev://app/inbox"))
            runCurrent()
            assertEquals(AppStage.Onboarding, vm.state.value.stage)
            assertEquals(AppLink.Inbox, vm.state.value.pendingLink)
            complete = true
            vm.onEvent(AppEvent.BootstrapRequested)
            runCurrent()
            assertEquals(AppStage.SignedOut, vm.state.value.stage)
            assertEquals(AppLink.Inbox, vm.state.value.pendingLink)
            identity.value.value = AppResult.Success(testSession())
            runCurrent()
            assertEquals(AppStage.SignedIn("session-a"), vm.state.value.stage)
            identity.value.value = AppResult.Success(testSession().copy(id = "session-b"))
            runCurrent()
            assertEquals(AppStage.SignedIn("session-b"), vm.state.value.stage)
            vm.onEvent(AppEvent.LinkReceived("hrismobile-dev://app/activity"))
            vm.onEvent(AppEvent.LinkHandled(AppLink.Inbox))
            runCurrent()
            assertEquals(AppLink.Activity, vm.state.value.pendingLink)
            vm.onEvent(AppEvent.LinkHandled(AppLink.Activity))
            runCurrent()
            assertNull(vm.state.value.pendingLink)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }
}

internal class TestSessions(initial: Session? = null) : SessionRepository {
    override val persistent = false
    val value = MutableStateFlow<AppResult<Session?>>(AppResult.Success(initial))

    override fun observe() = value

    override suspend fun current() = value.value

    override suspend fun clear(sessionId: String): AppResult<Boolean> {
        val session =
            when (val result = current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        return if (session?.id == sessionId) compareAndSet(session, null)
        else AppResult.Success(false)
    }

    override suspend fun compareAndSet(expected: Session?, updated: Session?): AppResult<Boolean> {
        val current = value.value
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != expected) return AppResult.Success(false)
        value.value = AppResult.Success(updated)
        return AppResult.Success(true)
    }
}

internal fun testSession() =
    Session(
        "session-a",
        User("1", "Alex", "demo@example.com"),
        SessionTokens("access", "refresh", Long.MAX_VALUE),
        binding =
            dev.fajar.starter.identity.domain.entities.SessionBinding(
                "11111111-1111-4111-8111-111111111111",
                "https://example.invalid/",
                Long.MAX_VALUE,
            ),
    )

internal class TestWorkspaces(initial: Workspace? = null, private val clock: Clock = Clock.System) :
    WorkspaceRepository {
    override suspend fun clientPolicy(sessionId: String, companyId: String) =
        AppResult.Success(fixturePolicy(clock.now()))

    private val selection = MutableStateFlow<AppResult<Workspace?>>(AppResult.Success(initial))
    var casFailure: Failure? = null

    override fun observe(sessionId: String) = selection

    override suspend fun current(sessionId: String) = selection.value

    override suspend fun compareAndSet(
        sessionId: String,
        expected: Workspace?,
        updated: Workspace?,
    ): AppResult<Boolean> =
        casFailure?.let { AppResult.Failed(it) }
            ?: AppResult.Success(
                selection.compareAndSet(AppResult.Success(expected), AppResult.Success(updated))
            )

    override suspend fun permissions(sessionId: String, companyId: String) =
        AppResult.Success(emptySet<String>())
}

internal fun testWorkspace(now: kotlin.time.Instant = Clock.System.now()): Workspace {
    val session = testSession()
    return Workspace(
        "scope",
        session.id,
        session.binding.audience,
        CompanyMembership("company", "Company", "CO", "Asia/Jakarta"),
        emptySet(),
        WorkspaceAdmission(session.user.id, session.binding.serverId, now, now + 1.hours),
        policy = CompanyPolicySnapshot(fixturePolicy(now), now),
    )
}

internal fun bootstrapViewModel(
    identity: SessionRepository,
    workspaces: WorkspaceRepository,
    accounts: AccountRepository,
    clock: Clock = Clock.System,
) =
    AppViewModel(
        LoadOnboarding(
            object : OnboardingRepository {
                override suspend fun isComplete() = AppResult.Success(true)

                override suspend fun complete() = AppResult.Success(Unit)
            }
        ),
        ObserveWorkspaceContext(identity, workspaces, fixtureBuild, clock),
        dev.fajar.starter.identity.domain.usecases.RestoreSession(
            identity,
            IdentityServer("https://example.invalid/", "Fixture"),
        ),
        ResolveAppLink(AppEnvironment.Dev),
        RefreshWorkspace(identity, accounts, workspaces, clock),
        LeaveWorkspace(identity, workspaces),
    )

private val fixtureBuild = AppBuild(AppPlatform.Android, 1, "1.0.0", null)

private fun fixturePolicy(now: kotlin.time.Instant = Clock.System.now()) =
    now.let {
        CompanyPolicy(
            null,
            CompanyModule.entries.toSet(),
            CompanyMinimumBuilds(0, 0, 0),
            null,
            false,
            it,
            it + 60.seconds,
        )
    }
