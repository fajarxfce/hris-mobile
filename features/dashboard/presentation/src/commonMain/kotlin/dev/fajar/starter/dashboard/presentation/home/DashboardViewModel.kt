package dev.fajar.starter.dashboard.presentation.home

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.dashboard.domain.usecases.LoadDashboard
import dev.fajar.starter.identity.domain.usecases.ObserveUser
import dev.fajar.starter.identity.domain.usecases.SignOut
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class DashboardViewModel(
    private val loadDashboard: LoadDashboard,
    private val observeUser: ObserveUser,
    private val signOut: SignOut,
) : MviViewModel<DashboardState, DashboardEvent, Nothing>(DashboardState()) {
    private var refreshJob: Job? = null

    init {
        on<DashboardEvent.TabSelected>(::onTabSelected)
        on<DashboardEvent.RefreshRequested>(::onRefreshRequested)
        on<DashboardEvent.SignOutRequested>(::onSignOutRequested)
        viewModelScope.launch {
            observeUser().collect { user -> updateState { it.copy(user = user) } }
        }
        onEvent(DashboardEvent.RefreshRequested)
    }

    private fun onTabSelected(event: DashboardEvent.TabSelected) {
        updateState { it.copy(tab = event.tab) }
    }

    private fun onRefreshRequested(event: DashboardEvent.RefreshRequested) {
        refreshJob?.cancel()
        updateState { it.copy(loading = true, error = null) }
        refreshJob =
            viewModelScope.launch {
                when (val result = loadDashboard()) {
                    is AppResult.Success ->
                        updateState { it.copy(dashboard = result.value, loading = false) }
                    is AppResult.Failed ->
                        updateState { it.copy(error = result.failure.message, loading = false) }
                }
            }
    }

    private fun onSignOutRequested(event: DashboardEvent.SignOutRequested) {
        if (state.value.signingOut) return
        updateState { it.copy(signingOut = true, error = null) }
        viewModelScope.launch {
            when (val result = signOut()) {
                is AppResult.Success -> updateState { it.copy(signingOut = false) }
                is AppResult.Failed ->
                    updateState { it.copy(signingOut = false, error = result.failure.message) }
            }
        }
    }
}
