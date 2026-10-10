package dev.fajar.starter.security.presentation.settings

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.security.domain.lock.usecases.*
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class LockSettingsViewModel(
    private val observe: ObserveAppLock,
    private val check: CheckDeviceAuthentication,
    private val configure: SetAppLockEnabled,
) : MviViewModel<LockSettingsState, LockSettingsEvent, Nothing>(LockSettingsState()) {
    private var configuration: Job? = null

    init {
        on<LockSettingsEvent.EnabledChanged>(::onEnabledChanged)
        on<LockSettingsEvent.Deactivated>(::onDeactivated)
        viewModelScope.launch {
            when (val result = check()) {
                is AppResult.Success -> updateState { it.copy(available = result.value) }
                is AppResult.Failed -> updateState { it.copy(failure = result.failure) }
            }
        }
        viewModelScope.launch {
            observe().collect { result ->
                when (result) {
                    is AppResult.Success -> updateState { it.copy(enabled = result.value.enabled) }
                    is AppResult.Failed -> updateState { it.copy(failure = result.failure) }
                }
            }
        }
    }

    private fun onEnabledChanged(event: LockSettingsEvent.EnabledChanged) {
        if (configuration?.isActive == true || !state.value.available) return
        updateState { it.copy(saving = true, failure = null) }
        configuration =
            viewModelScope.launch {
                try {
                    when (val result = configure(event.enabled)) {
                        is AppResult.Success -> Unit
                        is AppResult.Failed -> updateState { it.copy(failure = result.failure) }
                    }
                } finally {
                    updateState { it.copy(saving = false) }
                }
            }
    }

    private fun onDeactivated(event: LockSettingsEvent.Deactivated) {
        configuration?.cancel()
    }
}
