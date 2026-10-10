package dev.fajar.starter.auth.presentation.sessions

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.usecases.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class SessionsViewModel(
    @InjectedParam private val sessionId: String,
    private val loadSessions: LoadDeviceSessions,
    private val revokeSession: RevokeDeviceSession,
    private val signOut: SignOutNativeSession,
) : MviViewModel<SessionsState, SessionsEvent, SessionsEffect>(SessionsState()) {
    init {
        on<SessionsEvent.RefreshRequested>(::onRefreshRequested)
        on<SessionsEvent.RevocationRequested>(::onRevocationRequested)
        on<SessionsEvent.RevocationConfirmed>(::onRevocationConfirmed)
        on<SessionsEvent.RevocationCancelled>(::onRevocationCancelled)
        on<SessionsEvent.SignOutRequested>(::onSignOutRequested)
        on<SessionsEvent.BackRequested>(::onBackRequested)
    }

    private fun onRefreshRequested(event: SessionsEvent.RefreshRequested) {
        if (state.value.working) return
        updateState { SessionsState(working = true) }
        viewModelScope.launch {
            val result = loadSessions(sessionId)
            ensureActive()
            when (result) {
                is AppResult.Failed ->
                    updateState { it.copy(working = false, failure = result.failure) }
                is AppResult.Success ->
                    updateState { it.copy(working = false, directory = result.value) }
            }
        }
    }

    private fun onRevocationRequested(event: SessionsEvent.RevocationRequested) {
        val snapshot = state.value
        val directory = snapshot.directory ?: return
        if (
            snapshot.working ||
                event.id == directory.currentId ||
                directory.items.none { it.id == event.id }
        )
            return
        updateState { it.copy(confirmingId = event.id, failure = null) }
    }

    private fun onRevocationConfirmed(event: SessionsEvent.RevocationConfirmed) {
        val snapshot = state.value
        val remoteId = snapshot.confirmingId ?: return
        if (snapshot.working) return
        updateState { it.copy(working = true, failure = null) }
        viewModelScope.launch {
            val result = revokeSession(sessionId, remoteId)
            ensureActive()
            when (result) {
                is AppResult.Failed ->
                    updateState { it.copy(working = false, failure = result.failure) }
                is AppResult.Success -> {
                    updateState { it.copy(working = false, confirmingId = null) }
                    onEvent(SessionsEvent.RefreshRequested)
                }
            }
        }
    }

    private fun onRevocationCancelled(event: SessionsEvent.RevocationCancelled) {
        if (!state.value.working) updateState { it.copy(confirmingId = null, failure = null) }
    }

    private fun onSignOutRequested(event: SessionsEvent.SignOutRequested) {
        if (state.value.working) return
        updateState { it.copy(working = true, failure = null) }
        viewModelScope.launch {
            when (val result = signOut(sessionId)) {
                is AppResult.Failed ->
                    updateState { it.copy(working = false, failure = result.failure) }
                is AppResult.Success -> updateState { SessionsState() }
            }
        }
    }

    private fun onBackRequested(event: SessionsEvent.BackRequested) {
        viewModelScope.launch { emitEffect(SessionsEffect.Back) }
    }
}
