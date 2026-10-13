package dev.fajar.starter.inbox.presentation.detail

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.inboxFailureInvalidatesCache
import dev.fajar.starter.inbox.domain.usecases.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class InboxItemViewModel(
    @InjectedParam private val target: InboxItemTarget,
    private val observe: ObserveInbox,
    private val synchronize: SyncInbox,
    private val observeAction: ObserveInboxAction,
    private val prepareAction: PrepareInboxAction,
    private val replayAction: ReplayInboxAction,
    private val reviewAction: ReviewInboxAction,
) : MviViewModel<InboxItemState, InboxItemEvent, InboxItemEffect>(InboxItemState()) {
    private var contentObservation: Job? = null
    private var actionObservation: Job? = null
    private var refresh: Job? = null
    private var action: Job? = null

    init {
        on<InboxItemEvent.Entered>(::onEntered)
        on<InboxItemEvent.Stopped>(::onStopped)
        on<InboxItemEvent.RefreshRequested>(::onRefreshRequested)
        on<InboxItemEvent.BackRequested>(::onBackRequested)
        on<InboxItemEvent.VerificationRequested>(::onVerificationRequested)
        on<InboxItemEvent.ReadRequested>(::onReadRequested)
        on<InboxItemEvent.AcknowledgementRequested>(::onAcknowledgementRequested)
        on<InboxItemEvent.AcknowledgementConfirmed>(::onAcknowledgementConfirmed)
        on<InboxItemEvent.AcknowledgementCancelled>(::onAcknowledgementCancelled)
        on<InboxItemEvent.RetryActionRequested>(::onRetryActionRequested)
        on<InboxItemEvent.ReviewActionRequested>(::onReviewActionRequested)
    }

    private fun onEntered(event: InboxItemEvent.Entered) {
        if (contentObservation?.isActive != true) observeContent()
        if (actionObservation?.isActive != true) observeCommand()
        onEvent(InboxItemEvent.RefreshRequested)
    }

    private fun onStopped(event: InboxItemEvent.Stopped) {
        contentObservation?.cancel()
        contentObservation = null
        actionObservation?.cancel()
        actionObservation = null
        refresh?.cancel()
        refresh = null
        action?.cancel()
        action = null
        updateState { it.copy(syncing = false, working = false, confirmationVersion = null) }
    }

    private fun observeContent() {
        if (state.value.readRestricted) return
        contentObservation?.cancel()
        contentObservation =
            viewModelScope.launch {
                observe(target.workspaceId, InboxCacheQuery.Item(target.itemId)).collect { result ->
                    ensureActive()
                    if (state.value.readRestricted) return@collect
                    when (result) {
                        is AppResult.Failed ->
                            updateState {
                                it.copy(
                                    item = null,
                                    hasSnapshot = false,
                                    loading = false,
                                    cacheFailure = result.failure,
                                    confirmationVersion = null,
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
                    if (state.value.readRestricted) contentObservation?.cancel()
                }
            }
    }

    private fun observeCommand() {
        actionObservation?.cancel()
        actionObservation =
            viewModelScope.launch {
                observeAction(target.workspaceId, target.itemId).collect { result ->
                    ensureActive()
                    when (result) {
                        is AppResult.Failed ->
                            updateState {
                                it.copy(
                                    command = null,
                                    actionsReady = false,
                                    commandReadFailure = result.failure,
                                )
                            }
                        is AppResult.Success ->
                            updateState {
                                it.copy(
                                    command = result.value,
                                    actionsReady = true,
                                    commandReadFailure = null,
                                )
                            }
                    }
                }
            }
    }

    private fun onRefreshRequested(event: InboxItemEvent.RefreshRequested) {
        if (refresh?.isActive == true || action?.isActive == true) return
        updateState { it.copy(actionFailure = null) }
        if (state.value.cacheFailure != null) observeContent()
        if (state.value.commandReadFailure != null) observeCommand()
        refresh =
            viewModelScope.launch {
                if (refreshContent()) {
                    val retained = state.value.command
                    if (retained?.outcome is InboxCommandOutcome.Confirmed)
                        reviewCompletedAction(retained.command.id)
                }
            }
    }

    private fun onReadRequested(event: InboxItemEvent.ReadRequested) {
        if (!state.value.canRead) return
        val version = state.value.item?.version ?: return
        prepareAndSend(InboxAction.READ, version)
    }

    private fun onAcknowledgementRequested(event: InboxItemEvent.AcknowledgementRequested) {
        if (!state.value.canAcknowledge) return
        updateState { it.copy(confirmationVersion = it.item?.version, actionFailure = null) }
    }

    private fun onAcknowledgementConfirmed(event: InboxItemEvent.AcknowledgementConfirmed) {
        val version = state.value.confirmationVersion ?: return
        if (action?.isActive == true) return
        updateState { it.copy(confirmationVersion = null) }
        prepareAndSend(InboxAction.ACKNOWLEDGE, version)
    }

    private fun onAcknowledgementCancelled(event: InboxItemEvent.AcknowledgementCancelled) {
        updateState { it.copy(confirmationVersion = null) }
    }

    private fun prepareAndSend(method: InboxAction, version: Long) {
        if (action?.isActive == true) return
        refresh?.cancel()
        refresh = null
        updateState { it.copy(working = true, syncing = false, actionFailure = null) }
        action =
            viewModelScope.launch {
                try {
                    val prepared = prepareAction(target.workspaceId, target.itemId, version, method)
                    ensureActive()
                    when (prepared) {
                        is AppResult.Failed ->
                            updateState { it.copy(actionFailure = prepared.failure) }
                        is AppResult.Success -> sendAndRefresh(prepared.value.command.id)
                    }
                } finally {
                    if (isActive) updateState { it.copy(working = false) }
                }
            }
    }

    private fun onRetryActionRequested(event: InboxItemEvent.RetryActionRequested) {
        val retained = state.value.command ?: return
        if (action?.isActive == true || retained.outcome is InboxCommandOutcome.Confirmed) return
        refresh?.cancel()
        refresh = null
        updateState { it.copy(working = true, syncing = false, actionFailure = null) }
        action =
            viewModelScope.launch {
                try {
                    sendAndRefresh(retained.command.id)
                } finally {
                    if (isActive) updateState { it.copy(working = false) }
                }
            }
    }

    private suspend fun sendAndRefresh(id: String) {
        val result = replayAction(target.workspaceId, id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Failed -> updateState { it.copy(actionFailure = result.failure) }
            is AppResult.Success ->
                if (result.value.outcome is InboxCommandOutcome.Confirmed && refreshContent())
                    reviewCompletedAction(id)
        }
    }

    private fun onReviewActionRequested(event: InboxItemEvent.ReviewActionRequested) {
        val retained = state.value.command ?: return
        if (
            action?.isActive == true ||
                (retained.outcome !is InboxCommandOutcome.Rejected &&
                    retained.outcome !is InboxCommandOutcome.Confirmed)
        )
            return
        refresh?.cancel()
        refresh = null
        updateState { it.copy(working = true, syncing = false, actionFailure = null) }
        action =
            viewModelScope.launch {
                try {
                    if (refreshContent()) reviewCompletedAction(retained.command.id)
                } finally {
                    if (isActive) updateState { it.copy(working = false) }
                }
            }
    }

    private suspend fun refreshContent(): Boolean {
        updateState {
            it.copy(
                syncing = true,
                pending = false,
                cacheFailure =
                    it.syncFailure?.takeIf(::inboxFailureInvalidatesCache) ?: it.cacheFailure,
                syncFailure = null,
            )
        }
        val result = synchronize(target.workspaceId)
        currentCoroutineContext().ensureActive()
        return when (result) {
            is AppResult.Failed -> {
                val restricted =
                    state.value.readRestricted || inboxFailureInvalidatesCache(result.failure)
                if (restricted) contentObservation?.cancel()
                updateState {
                    it.copy(
                        syncing = false,
                        loading = false,
                        syncFailure = result.failure,
                        item = if (restricted) null else it.item,
                        hasSnapshot = !restricted && it.hasSnapshot,
                        confirmationVersion = if (restricted) null else it.confirmationVersion,
                    )
                }
                false
            }
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        syncing = false,
                        loading = false,
                        pending = !result.value.caughtUp,
                        cacheFailure = if (result.value.caughtUp) null else it.cacheFailure,
                    )
                }
                if (result.value.caughtUp && contentObservation?.isActive != true) observeContent()
                result.value.caughtUp
            }
        }
    }

    private suspend fun reviewCompletedAction(id: String) {
        val result = reviewAction(target.workspaceId, id)
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Success -> updateState { it.copy(actionFailure = null) }
            is AppResult.Failed ->
                if (result.failure.code == "client.inbox_updates_pending")
                    updateState { it.copy(pending = true) }
                else updateState { it.copy(actionFailure = result.failure) }
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
