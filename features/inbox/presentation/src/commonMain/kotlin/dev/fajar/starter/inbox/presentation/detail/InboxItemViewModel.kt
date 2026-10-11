package dev.fajar.starter.inbox.presentation.detail

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.InboxCacheQuery
import dev.fajar.starter.inbox.domain.usecases.*
import dev.fajar.starter.inbox.presentation.list.inboxVerificationCodes
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class InboxItemViewModel(
    @InjectedParam private val target: InboxItemTarget,
    private val observe: ObserveInbox,
    private val synchronize: SyncInbox,
) : MviViewModel<InboxItemState, InboxItemEvent, InboxItemEffect>(InboxItemState()) {
    private var observation: Job? = null
    private var refresh: Job? = null

    init {
        on<InboxItemEvent.Entered>(::onEntered)
        on<InboxItemEvent.Stopped>(::onStopped)
        on<InboxItemEvent.RefreshRequested>(::onRefreshRequested)
        on<InboxItemEvent.BackRequested>(::onBackRequested)
        on<InboxItemEvent.VerificationRequested>(::onVerificationRequested)
    }

    private fun onEntered(event: InboxItemEvent.Entered) {
        if (observation?.isActive == true) return
        observation =
            viewModelScope.launch {
                observe(target.workspaceId, InboxCacheQuery.Item(target.itemId)).collect { result ->
                    ensureActive()
                    when (result) {
                        is AppResult.Failed ->
                            updateState {
                                it.copy(
                                    item = null,
                                    hasSnapshot = false,
                                    loading = false,
                                    cacheFailure = result.failure,
                                )
                            }
                        is AppResult.Success ->
                            updateState {
                                it.copy(
                                    item = result.value.items.singleOrNull(),
                                    cacheFailure = null,
                                    hasSnapshot = result.value.checkpoint != null,
                                    loading = false,
                                )
                            }
                    }
                }
            }
        onEvent(InboxItemEvent.RefreshRequested)
    }

    private fun onStopped(event: InboxItemEvent.Stopped) {
        observation?.cancel()
        observation = null
        refresh?.cancel()
        refresh = null
        updateState { it.copy(syncing = false) }
    }

    private fun onRefreshRequested(event: InboxItemEvent.RefreshRequested) {
        if (refresh?.isActive == true) return
        updateState {
            it.copy(syncing = true, pending = false, syncFailure = null, needsVerification = false)
        }
        refresh =
            viewModelScope.launch {
                val result = synchronize(target.workspaceId)
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

    private fun onBackRequested(event: InboxItemEvent.BackRequested) {
        viewModelScope.launch { emitEffect(InboxItemEffect.Back) }
    }

    private fun onVerificationRequested(event: InboxItemEvent.VerificationRequested) {
        if (state.value.needsVerification)
            viewModelScope.launch { emitEffect(InboxItemEffect.VerifyAccount) }
    }
}
