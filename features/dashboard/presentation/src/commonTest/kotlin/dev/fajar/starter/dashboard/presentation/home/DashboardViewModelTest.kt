@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.dashboard.presentation.home

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.dashboard.domain.entities.Dashboard
import dev.fajar.starter.dashboard.domain.repositories.DashboardRepository
import dev.fajar.starter.dashboard.domain.usecases.LoadDashboard
import dev.fajar.starter.identity.domain.entities.User
import dev.fajar.starter.identity.domain.repositories.IdentityRepository
import dev.fajar.starter.identity.domain.usecases.ObserveUser
import dev.fajar.starter.identity.domain.usecases.SignOut
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
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
    fun newerRefreshWinsAndFailurePreservesContent() = runTest {
        val pending = mutableListOf<CompletableDeferred<AppResult<Dashboard>>>()
        val repository =
            object : DashboardRepository {
                override suspend fun load(): AppResult<Dashboard> {
                    val response = CompletableDeferred<AppResult<Dashboard>>()
                    pending += response
                    return response.await()
                }
            }
        val identity =
            object : IdentityRepository {
                override fun observeUser() = flowOf<User?>(null)

                override suspend fun signOut() = AppResult.Success(Unit)

                override suspend fun signIn(email: String, password: String): AppResult<User> =
                    error("unused")
            }
        val viewModel =
            DashboardViewModel(LoadDashboard(repository), ObserveUser(identity), SignOut(identity))
        store.put("dashboard", viewModel)
        runCurrent()
        pending[0].complete(AppResult.Success(Dashboard(8, 3, 5, emptyList())))
        runCurrent()
        viewModel.onEvent(DashboardEvent.RefreshRequested)
        runCurrent()
        viewModel.onEvent(DashboardEvent.RefreshRequested)
        runCurrent()
        pending[1].complete(AppResult.Success(Dashboard(10, 3, 5, emptyList())))
        pending[2].complete(AppResult.Success(Dashboard(12, 3, 5, emptyList())))
        runCurrent()
        assertEquals(12, viewModel.state.value.dashboard?.projects)
        viewModel.onEvent(DashboardEvent.RefreshRequested)
        runCurrent()
        pending[3].complete(AppResult.Failed(Failure(FailureKind.Network, "Offline")))
        runCurrent()
        assertEquals(12, viewModel.state.value.dashboard?.projects)
        assertEquals("Offline", viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
    }
}
