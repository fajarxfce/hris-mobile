package dev.fajar.starter.home.presentation.home

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.usecases.ObserveSessionIdentity
import dev.fajar.starter.identity.domain.usecases.SignOutNativeSession
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.usecases.LeaveWorkspace
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
) : MviViewModel<HomeState, HomeEvent, HomeEffect>(HomeState(workspace, tab = initialTab)) {
    init {
        on<HomeEvent.TabSelected>(::onTabSelected)
        on<HomeEvent.CompanyChangeRequested>(::onCompanyChangeRequested)
        on<HomeEvent.SettingsRequested>(::onSettingsRequested)
        on<HomeEvent.ProfileRequested>(::onProfileRequested)
        on<HomeEvent.SessionsRequested>(::onSessionsRequested)
        on<HomeEvent.SignOutRequested>(::onSignOutRequested)
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
        updateState { it.copy(tab = event.tab) }
    }

    private fun onSettingsRequested(event: HomeEvent.SettingsRequested) {
        viewModelScope.launch { emitEffect(HomeEffect.OpenSettings) }
    }

    private fun onProfileRequested(event: HomeEvent.ProfileRequested) {
        viewModelScope.launch { emitEffect(HomeEffect.OpenProfile) }
    }

    private fun onSessionsRequested(event: HomeEvent.SessionsRequested) {
        viewModelScope.launch { emitEffect(HomeEffect.OpenSessions) }
    }

    private fun onCompanyChangeRequested(event: HomeEvent.CompanyChangeRequested) {
        if (state.value.working) return
        updateState { it.copy(working = true) }
        viewModelScope.launch {
            when (val result = leaveWorkspace(state.value.workspace.id)) {
                is AppResult.Failed ->
                    updateState { it.copy(working = false, failure = result.failure) }
                is AppResult.Success -> updateState { it.copy(working = false) }
            }
        }
    }

    private fun onSignOutRequested(event: HomeEvent.SignOutRequested) {
        if (state.value.working) return
        updateState { it.copy(working = true) }
        viewModelScope.launch {
            when (val result = signOut(state.value.workspace.sessionId)) {
                is AppResult.Failed ->
                    updateState { it.copy(working = false, failure = result.failure) }
                is AppResult.Success -> updateState { it.copy(working = false, user = null) }
            }
        }
    }
}
