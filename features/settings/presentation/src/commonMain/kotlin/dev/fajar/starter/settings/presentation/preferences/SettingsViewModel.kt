package dev.fajar.starter.settings.presentation.preferences

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.settings.domain.usecases.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class SettingsViewModel(
    private val observe: ObserveLanguage,
    private val setLanguage: SetLanguage,
    private val observeAccess: dev.fajar.starter.security.domain.access.usecases.ObserveAccess,
    private val refreshAccess: dev.fajar.starter.security.domain.access.usecases.RefreshAccess,
) : MviViewModel<SettingsState, SettingsEvent, SettingsEffect>(SettingsState()) {
    private var observation: Job? = null

    init {
        on<SettingsEvent.AccessRefreshRequested>(::onAccessRefreshRequested)
        on<SettingsEvent.BackRequested>(::onBackRequested)
        on<SettingsEvent.LanguageSelected>(::onLanguageSelected)
        on<SettingsEvent.ReloadRequested>(::onReloadRequested)
        viewModelScope.launch {
            observeAccess().collect { result ->
                when (result) {
                    is AppResult.Success ->
                        updateState {
                            it.copy(
                                roles = result.value?.roles.orEmpty(),
                                permissions = result.value?.permissions.orEmpty(),
                            )
                        }
                    is AppResult.Failed ->
                        updateState {
                            it.copy(
                                roles = emptySet(),
                                permissions = emptySet(),
                                accessError = result.failure,
                            )
                        }
                }
            }
        }
        onEvent(SettingsEvent.AccessRefreshRequested)
        onEvent(SettingsEvent.ReloadRequested)
    }

    private fun onAccessRefreshRequested(event: SettingsEvent.AccessRefreshRequested) {
        if (state.value.loadingAccess) return
        updateState { it.copy(loadingAccess = true, accessError = null) }
        viewModelScope.launch {
            when (val result = refreshAccess()) {
                is AppResult.Success -> updateState { it.copy(loadingAccess = false) }
                is AppResult.Failed ->
                    updateState { it.copy(loadingAccess = false, accessError = result.failure) }
            }
        }
    }

    private fun onBackRequested(event: SettingsEvent.BackRequested) {
        viewModelScope.launch { emitEffect(SettingsEffect.Back) }
    }

    private fun onReloadRequested(event: SettingsEvent.ReloadRequested) {
        observation?.cancel()
        observation =
            viewModelScope.launch {
                observe().collect { result ->
                    when (result) {
                        is AppResult.Success ->
                            updateState { it.copy(language = result.value, error = null) }
                        is AppResult.Failed -> updateState { it.copy(error = result.failure) }
                    }
                }
            }
    }

    private fun onLanguageSelected(event: SettingsEvent.LanguageSelected) {
        if (state.value.saving) return
        updateState { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val result = setLanguage(event.language)) {
                is AppResult.Success -> {
                    updateState { it.copy(saving = false) }
                    onEvent(SettingsEvent.ReloadRequested)
                }
                is AppResult.Failed ->
                    updateState { it.copy(saving = false, error = result.failure) }
            }
        }
    }
}
