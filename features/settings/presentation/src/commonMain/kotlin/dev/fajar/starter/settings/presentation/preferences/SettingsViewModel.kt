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
) : MviViewModel<SettingsState, SettingsEvent, SettingsEffect>(SettingsState()) {
    private var observation: Job? = null

    init {
        on<SettingsEvent.BackRequested>(::onBackRequested)
        on<SettingsEvent.LanguageSelected>(::onLanguageSelected)
        on<SettingsEvent.ReloadRequested>(::onReloadRequested)
        onEvent(SettingsEvent.ReloadRequested)
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
