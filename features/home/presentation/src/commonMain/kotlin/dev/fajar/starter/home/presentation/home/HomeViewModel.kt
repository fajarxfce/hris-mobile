package dev.fajar.starter.home.presentation.home

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.home.presentation.catalog.homeServices
import dev.fajar.starter.identity.domain.usecases.ObserveSessionIdentity
import dev.fajar.starter.identity.domain.usecases.SignOutNativeSession
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.usecases.LeaveWorkspace
import dev.fajar.starter.workspace.domain.usecases.ObserveWorkspace
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class HomeViewModel(
    @InjectedParam workspace: Workspace,
    @InjectedParam initialTab: HomeTab,
    private val observeIdentity: ObserveSessionIdentity,
    private val leaveWorkspace: LeaveWorkspace,
    private val signOut: SignOutNativeSession,
    private val observeWorkspace: ObserveWorkspace,
) :
    MviViewModel<HomeState, HomeEvent, HomeEffect>(
        HomeState(workspace, tab = initialTab, services = homeServices(workspace))
    ) {
    init {
        on<HomeEvent.TabSelected>(::onTabSelected)
        on<HomeEvent.BackRequested>(::onBackRequested)
        on<HomeEvent.AllServicesRequested>(::onAllServicesRequested)
        on<HomeEvent.CompanyChangeRequested>(::onCompanyChangeRequested)
        on<HomeEvent.SettingsRequested>(::onSettingsRequested)
        on<HomeEvent.ServiceSelected>(::onServiceSelected)
        on<HomeEvent.SessionsRequested>(::onSessionsRequested)
        on<HomeEvent.SignOutRequested>(::onSignOutRequested)
        viewModelScope.launch {
            observeWorkspace().collect { result ->
                val selected =
                    (result as? AppResult.Success)?.value?.takeIf { it.id == workspace.id }
                updateState {
                    it.copy(workspace = selected ?: it.workspace, services = homeServices(selected))
                }
            }
        }
        viewModelScope.launch {
            observeIdentity().collect { result ->
                when (result) {
                    is AppResult.Failed -> updateState { it.copy(failure = result.failure) }
                    is AppResult.Success ->
                        updateState {
                            it.copy(
                                user =
                                    result.value
                                        ?.takeIf { identity ->
                                            identity.sessionId == workspace.sessionId
                                        }
                                        ?.user
                            )
                        }
                }
            }
        }
    }

    private fun onTabSelected(event: HomeEvent.TabSelected) {
        if (state.value.working) return
        updateState { it.copy(tab = event.tab) }
    }

    private fun onBackRequested(event: HomeEvent.BackRequested) {
        if (state.value.working) return
        updateState { it.copy(tab = HomeTab.Home) }
    }

    private fun onAllServicesRequested(event: HomeEvent.AllServicesRequested) {
        if (state.value.working) return
        viewModelScope.launch { emitEffect(HomeEffect.OpenAllServices) }
    }

    private fun onSettingsRequested(event: HomeEvent.SettingsRequested) {
        if (state.value.working) return
        viewModelScope.launch { emitEffect(HomeEffect.OpenSettings) }
    }

    private fun onServiceSelected(event: HomeEvent.ServiceSelected) {
        if (
            state.value.working ||
                event.service !in state.value.services.account &&
                    state.value.services.sections.none { event.service in it.items }
        )
            return
        viewModelScope.launch { emitEffect(HomeEffect.OpenService(event.service)) }
    }

    private fun onSessionsRequested(event: HomeEvent.SessionsRequested) {
        if (state.value.working) return
        viewModelScope.launch { emitEffect(HomeEffect.OpenSessions) }
    }

    private fun onCompanyChangeRequested(event: HomeEvent.CompanyChangeRequested) {
        if (state.value.working) return
        updateState { it.copy(operation = HomeOperation.ChangingCompany, failure = null) }
        viewModelScope.launch {
            when (val result = leaveWorkspace(state.value.workspace.id)) {
                is AppResult.Failed ->
                    updateState { it.copy(operation = null, failure = result.failure) }
                is AppResult.Success -> Unit
            }
        }
    }

    private fun onSignOutRequested(event: HomeEvent.SignOutRequested) {
        if (state.value.working) return
        updateState { it.copy(operation = HomeOperation.SigningOut, failure = null) }
        viewModelScope.launch {
            when (val result = signOut(state.value.workspace.sessionId)) {
                is AppResult.Failed ->
                    updateState { it.copy(operation = null, failure = result.failure) }
                is AppResult.Success -> Unit
            }
        }
    }
}
