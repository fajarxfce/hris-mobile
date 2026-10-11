package dev.fajar.starter.inbox.presentation.list

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.InboxCacheQuery
import dev.fajar.starter.inbox.domain.usecases.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class InboxViewModel(
    @InjectedParam private val workspaceId: String,
    private val observe: ObserveInbox,
    private val synchronize: SyncInbox,
) : MviViewModel<InboxState, InboxEvent, InboxEffect>(InboxState()) {
    private var observation: Job? = null
    private var refresh: Job? = null

    init {
        on<InboxEvent.Entered>(::onEntered)
        on<InboxEvent.Stopped>(::onStopped)
        on<InboxEvent.PageRequested>(::onPageRequested)
        on<InboxEvent.RefreshRequested>(::onRefreshRequested)
        on<InboxEvent.ItemSelected>(::onItemSelected)
        on<InboxEvent.BackRequested>(::onBackRequested)
        on<InboxEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: InboxEvent.Entered) {
        updateState { it.copy(navigating = false) }
        if (observation?.isActive == true) return
        onEvent(InboxEvent.PageRequested(state.value.afterCursor))
        onEvent(InboxEvent.RefreshRequested)
    }

    private fun onStopped(event: InboxEvent.Stopped) {
        observation?.cancel()
        observation = null
        refresh?.cancel()
        refresh = null
        updateState { it.copy(syncing = false) }
    }

    private fun onPageRequested(event: InboxEvent.PageRequested) {
        if (
            event.after != null &&
                event.after != state.value.afterCursor &&
                event.after != state.value.nextCursor
        )
            return
        val changed = event.after != state.value.afterCursor
        observation?.cancel()
        if (changed) {
            updateState {
                it.copy(
                    items = emptyList(),
                    afterCursor = event.after,
                    nextCursor = null,
                    loading = true,
                )
            }
            viewModelScope.launch { emitEffect(InboxEffect.ScrollToStart) }
        }
        observation =
            viewModelScope.launch {
                observe(workspaceId, InboxCacheQuery.Page(event.after)).collect { result ->
                    ensureActive()
                    when (result) {
                        is AppResult.Failed ->
                            updateState {
                                it.copy(
                                    items = emptyList(),
                                    hasSnapshot = false,
                                    nextCursor = null,
                                    loading = false,
                                    cacheFailure = result.failure,
                                )
                            }
                        is AppResult.Success ->
                            updateState {
                                it.copy(
                                    items = result.value.items,
                                    hasSnapshot = result.value.checkpoint != null,
                                    nextCursor = result.value.nextCursor,
                                    loading = false,
                                    cacheFailure = null,
                                )
                            }
                    }
                }
            }
    }

    private fun onRefreshRequested(event: InboxEvent.RefreshRequested) {
        if (refresh?.isActive == true) return
        updateState {
            it.copy(syncing = true, pending = false, syncFailure = null, needsVerification = false)
        }
        refresh =
            viewModelScope.launch {
                val result = synchronize(workspaceId)
                ensureActive()
                when (result) {
                    is AppResult.Failed ->
                        updateState {
                            it.copy(
                                syncing = false,
                                loading = false,
                                syncFailure = result.failure,
                                needsVerification = result.failure.code in inboxVerificationCodes,
                            )
                        }
                    is AppResult.Success ->
                        updateState {
                            it.copy(
                                syncing = false,
                                loading = false,
                                pending = !result.value.caughtUp,
                            )
                        }
                }
            }
    }

    private fun onItemSelected(event: InboxEvent.ItemSelected) {
        if (state.value.navigating || state.value.items.none { it.id == event.id }) return
        updateState { it.copy(navigating = true) }
        viewModelScope.launch { emitEffect(InboxEffect.OpenItem(event.id)) }
    }

    private fun onBackRequested(event: InboxEvent.BackRequested) {
        viewModelScope.launch { emitEffect(InboxEffect.Back) }
    }

    private fun onVerificationRequested(event: InboxEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(InboxEffect.VerifyAccount) }
    }
}

internal val inboxVerificationCodes =
    setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
