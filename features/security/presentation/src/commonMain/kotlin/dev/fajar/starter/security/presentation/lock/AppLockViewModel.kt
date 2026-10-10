package dev.fajar.starter.security.presentation.lock

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.presentation.mvi.MviViewModel
import dev.fajar.starter.security.domain.lock.usecases.*
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class AppLockViewModel(
    private val observeLock: ObserveAppLock,
    private val unlock: UnlockApp,
    private val lock: LockApp,
    private val interaction: RecordAppInteraction,
    private val reset: ResetLockedSession,
) : MviViewModel<AppLockState, AppLockEvent, Nothing>(AppLockState()) {
    private var observation: Job? = null
    private var authentication: Job? = null
    private var activity: Job? = null
    private var locking: Job? = null

    init {
        on<AppLockEvent.Foregrounded>(::onForegrounded)
        on<AppLockEvent.Backgrounded>(::onBackgrounded)
        on<AppLockEvent.UnlockRequested>(::onUnlockRequested)
        on<AppLockEvent.AccountSignInRequested>(::onAccountSignInRequested)
        on<AppLockEvent.InteractionReceived>(::onInteractionReceived)
    }

    private fun onForegrounded(event: AppLockEvent.Foregrounded) {
        if (observation?.isActive == true) return
        observation =
            viewModelScope.launch {
                locking?.join()
                observeLock().collect { result ->
                    when (result) {
                        is AppResult.Failed ->
                            updateState {
                                it.copy(loading = false, locked = true, failure = result.failure)
                            }
                        is AppResult.Success -> {
                            if (result.value.sessionId != state.value.sessionId) {
                                authentication?.cancel()
                                activity?.cancel()
                            }
                            updateState {
                                it.copy(
                                    loading = false,
                                    enabled = result.value.enabled,
                                    sessionId = result.value.sessionId,
                                    locked = result.value.locked,
                                    failure = null,
                                )
                            }
                        }
                    }
                }
            }
    }

    private fun onBackgrounded(event: AppLockEvent.Backgrounded) {
        observation?.cancel()
        authentication?.cancel()
        activity?.cancel()
        updateState {
            it.copy(
                locked = it.locked || (it.enabled && it.sessionId != null),
                authenticating = false,
            )
        }
        locking = viewModelScope.launch { lock() }
    }

    private fun onUnlockRequested(event: AppLockEvent.UnlockRequested) {
        if (authentication?.isActive == true || state.value.resetting) return
        updateState { it.copy(authenticating = true, failure = null) }
        authentication =
            viewModelScope.launch {
                try {
                    when (val result = unlock()) {
                        is AppResult.Failed -> updateState { it.copy(failure = result.failure) }
                        is AppResult.Success -> Unit
                    }
                } finally {
                    updateState { it.copy(authenticating = false) }
                }
            }
    }

    private fun onAccountSignInRequested(event: AppLockEvent.AccountSignInRequested) {
        if (state.value.resetting) return
        authentication?.cancel()
        activity?.cancel()
        updateState { it.copy(resetting = true, failure = null) }
        viewModelScope.launch {
            try {
                when (val result = reset()) {
                    is AppResult.Failed -> updateState { it.copy(failure = result.failure) }
                    is AppResult.Success -> Unit
                }
            } finally {
                updateState { it.copy(resetting = false) }
            }
        }
    }

    private fun onInteractionReceived(event: AppLockEvent.InteractionReceived) {
        if (!state.value.enabled || state.value.locked || activity?.isActive == true) return
        activity = viewModelScope.launch { interaction() }
    }
}
