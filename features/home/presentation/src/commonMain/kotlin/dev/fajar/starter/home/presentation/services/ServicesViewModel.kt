package dev.fajar.starter.home.presentation.services

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.home.presentation.catalog.homeServices
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.workspace.domain.usecases.ObserveWorkspace
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ServicesViewModel(@InjectedParam workspaceId: String, observeWorkspace: ObserveWorkspace) :
    MviViewModel<ServicesState, ServicesEvent, ServicesEffect>(ServicesState()) {
    init {
        on<ServicesEvent.ServiceSelected>(::onServiceSelected)
        on<ServicesEvent.BackRequested>(::onBackRequested)
        viewModelScope.launch {
            observeWorkspace().collect { result ->
                when (result) {
                    is AppResult.Failed ->
                        updateState { ServicesState(loading = false, failure = result.failure) }
                    is AppResult.Success -> {
                        val workspace = result.value?.takeIf { it.id == workspaceId }
                        updateState {
                            ServicesState(
                                companyName = workspace?.company?.name,
                                services = homeServices(workspace),
                                loading = false,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun onServiceSelected(event: ServicesEvent.ServiceSelected) {
        if (state.value.services.sections.none { event.service in it.items }) return
        viewModelScope.launch { emitEffect(ServicesEffect.OpenService(event.service)) }
    }

    private fun onBackRequested(event: ServicesEvent.BackRequested) {
        viewModelScope.launch { emitEffect(ServicesEffect.Back) }
    }
}
