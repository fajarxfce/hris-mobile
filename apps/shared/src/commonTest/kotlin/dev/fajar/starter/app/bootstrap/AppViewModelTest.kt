@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.app.bootstrap

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.app.navigation.*
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.usecases.ObserveUser
import dev.fajar.starter.onboarding.domain.repositories.OnboardingRepository
import dev.fajar.starter.onboarding.domain.usecases.LoadOnboarding
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

class AppViewModelTest {
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
                    ObserveUser(identity),
                    dev.fajar.starter.identity.domain.usecases.RestoreSession(identity),
                    ResolveAppLink(AppEnvironment.Dev),
                )
            owner.put("app", vm)
            vm.onEvent(AppEvent.LinkReceived("fluentstarter-dev://app/inbox"))
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
            assertEquals(AppStage.SignedIn, vm.state.value.stage)
            vm.onEvent(AppEvent.LinkReceived("fluentstarter-dev://app/activity"))
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

private class TestSessions(initial: Session? = null) : SessionRepository {
    override val persistent = false
    val value = MutableStateFlow<AppResult<Session?>>(AppResult.Success(initial))

    override fun observe() = value

    override suspend fun current() = value.value

    override suspend fun compareAndSet(expected: Session?, updated: Session?): AppResult<Boolean> {
        val current = value.value
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != expected) return AppResult.Success(false)
        value.value = AppResult.Success(updated)
        return AppResult.Success(true)
    }
}

private fun testSession() =
    Session(
        "session-a",
        User("1", "Alex", "demo@example.com"),
        SessionTokens("access", "refresh", Long.MAX_VALUE),
    )
