@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.dashboard.presentation.home

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.result.*
import dev.fajar.starter.dashboard.domain.config.DashboardFlags
import dev.fajar.starter.dashboard.domain.entities.*
import dev.fajar.starter.dashboard.domain.repositories.DashboardRepository
import dev.fajar.starter.dashboard.domain.usecases.*
import dev.fajar.starter.featureflags.domain.entities.FlagSnapshot
import dev.fajar.starter.featureflags.domain.repositories.FeatureFlagRepository
import dev.fajar.starter.featureflags.domain.usecases.ObserveFeatureFlag
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.usecases.ObserveUser
import dev.fajar.starter.identity.domain.usecases.SignOut
import dev.fajar.starter.security.domain.access.entities.*
import dev.fajar.starter.security.domain.access.repositories.AccessRepository
import dev.fajar.starter.security.domain.access.usecases.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

class DashboardViewModelTest {
    private val store = ViewModelStore()

    @BeforeTest
    fun before() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun after() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun cachedDataAppearsBeforeRefreshAndFailureDoesNotEraseIt() = runTest {
        val pending = mutableListOf<CompletableDeferred<AppResult<Unit>>>()
        val cached =
            MutableStateFlow<AppResult<Dashboard?>>(
                AppResult.Success(Dashboard(8, 3, 5, emptyList()))
            )
        val repository =
            object : DashboardRepository {
                override fun observe(sessionId: String) = cached

                override suspend fun loadNextPage(sessionId: String) = AppResult.Success(Unit)

                override suspend fun refresh(sessionId: String): AppResult<Unit> {
                    val response = CompletableDeferred<AppResult<Unit>>()
                    pending += response
                    return response.await()
                }

                override suspend fun pendingChanges(sessionId: String, limit: Int) =
                    AppResult.Success(emptyList<ActivityChange>())

                override suspend fun push(sessionId: String, change: ActivityChange) =
                    error("unused")

                override suspend fun acknowledge(sessionId: String, operationId: String) =
                    error("unused")

                override suspend fun setSaved(
                    sessionId: String,
                    activityId: String,
                    saved: Boolean,
                ) = AppResult.Success(Unit)
            }
        val identity = TestSessions(testSession())
        val scheduler =
            object : SyncScheduleRepository {
                override suspend fun request(key: String) = AppResult.Success(Unit)
            }
        val flagValues =
            MutableStateFlow<AppResult<FlagSnapshot>>(AppResult.Success(FlagSnapshot()))
        val flags =
            object : FeatureFlagRepository {
                override fun observe() = flagValues

                override suspend fun snapshot() = flagValues.value

                override suspend fun refresh(fetchedAtEpochMillis: Long) = error("unused")

                override suspend fun setOverride(key: String, value: Boolean?) = error("unused")
            }
        val viewModel =
            DashboardViewModel(
                ObserveDashboard(repository, TestSessions(testSession())),
                SyncDashboard(repository, TestSessions(testSession())),
                LoadNextActivityPage(repository, TestSessions(testSession())),
                SetActivitySaved(
                    repository,
                    scheduler,
                    flags,
                    AppEnvironment.Dev,
                    TestSessions(testSession()),
                    GrantedAccess(),
                ),
                ObserveUser(identity),
                SignOut(identity),
                ObserveFeatureFlag(flags, AppEnvironment.Dev),
                ObservePermission(identity, GrantedAccess()),
                RefreshAccess(identity, GrantedAccess()),
                DashboardTab.Overview,
            )
        store.put("dashboard", viewModel)
        runCurrent()
        assertEquals(8, viewModel.state.value.dashboard?.projects)
        assertTrue(viewModel.state.value.savingAvailable)
        flagValues.value =
            AppResult.Success(FlagSnapshot(mapOf(DashboardFlags.SavedActivities.key to "false")))
        runCurrent()
        assertFalse(viewModel.state.value.savingAvailable)
        viewModel.onEvent(DashboardEvent.RefreshRequested)
        runCurrent()
        pending[0].complete(AppResult.Success(Unit))
        pending[1].complete(AppResult.Failed(Failure(FailureKind.Network, "Offline")))
        runCurrent()
        assertEquals(8, viewModel.state.value.dashboard?.projects)
        assertEquals("Offline", viewModel.state.value.error?.message)
        assertFalse(viewModel.state.value.loading)
        viewModel.onEvent(DashboardEvent.RefreshRequested)
        runCurrent()
        store.clear()
        flagValues.value = AppResult.Success(FlagSnapshot())
        pending[2].complete(AppResult.Success(Unit))
        runCurrent()
        assertNull(viewModel.state.value.error?.message)
        assertFalse(viewModel.state.value.savingAvailable)
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

private class GrantedAccess : AccessRepository {
    override fun observe(sessionId: String) = flowOf(AppResult.Success(grants(sessionId)))

    override suspend fun cached(sessionId: String) = AppResult.Success(grants(sessionId))

    override suspend fun refresh(sessionId: String) = AppResult.Success(Unit)

    override suspend fun invalidate(sessionId: String) = AppResult.Success(Unit)

    private fun grants(id: String) =
        AccessSnapshot(id, setOf("editor"), setOf(Permission.SaveActivity), Long.MAX_VALUE)
}
