package dev.fajar.starter.collaboration.presentation.setup

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.chatRequiresReconnect
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ChatSetupViewModel(
    @InjectedParam private val workspaceId: String,
    private val observeAccess: ObserveCollaborationAccess,
    private val readControl: ReadCollaborationControl,
    private val activate: ActivateCollaborationDevice,
) : MviViewModel<ChatSetupState, ChatSetupEvent, ChatSetupEffect>(ChatSetupState()) {
    private var observation: Job? = null
    private var work: Job? = null
    private var visible = false
    private var accessObserved = false
    private var workRevision = 0L

    init {
        on<ChatSetupEvent.Entered>(::onEntered)
        on<ChatSetupEvent.Stopped>(::onStopped)
        on<ChatSetupEvent.BackRequested>(::onBackRequested)
        on<ChatSetupEvent.RefreshRequested>(::onRefreshRequested)
        on<ChatSetupEvent.ContinueRequested>(::onContinueRequested)
        on<ChatSetupEvent.OpenChatRequested>(::onOpenChatRequested)
    }

    private fun onEntered(event: ChatSetupEvent.Entered) {
        if (visible) return
        visible = true
        if (observation?.isActive == true) {
            if (accessObserved) runSetup(CollaborationActivationMode.INSPECT)
            return
        }
        observation =
            viewModelScope.launch {
                var first = true
                observeAccess(workspaceId).collect { result ->
                    ensureActive()
                    accessObserved = true
                    when (result) {
                        is AppResult.Failed -> {
                            workRevision++
                            work?.cancel()
                            work = null
                            updateState {
                                ChatSetupState(
                                    phase = ChatSetupPhase.UNAVAILABLE,
                                    loading = false,
                                    localFailure = result.failure,
                                )
                            }
                        }
                        is AppResult.Success -> updateState { it.copy(localFailure = null) }
                    }
                    if (first) {
                        first = false
                        if (visible) runSetup(CollaborationActivationMode.INSPECT)
                    }
                }
            }
    }

    private fun onStopped(event: ChatSetupEvent.Stopped) {
        visible = false
        workRevision++
        work?.cancel()
        work = null
        updateState { it.copy(loading = false, working = false) }
    }

    private fun onBackRequested(event: ChatSetupEvent.BackRequested) {
        if (!visible) return
        onStopped(ChatSetupEvent.Stopped)
        viewModelScope.launch { emitEffect(ChatSetupEffect.Back) }
    }

    private fun onRefreshRequested(event: ChatSetupEvent.RefreshRequested) {
        if (!visible || work?.isActive == true) return
        runSetup(CollaborationActivationMode.INSPECT)
    }

    private fun onContinueRequested(event: ChatSetupEvent.ContinueRequested) {
        val current = state.value
        if (
            !visible ||
                !accessObserved ||
                current.localFailure != null ||
                current.loading ||
                current.working ||
                work?.isActive == true
        )
            return
        if (
            current.phase !in
                setOf(
                    ChatSetupPhase.REGISTER,
                    ChatSetupPhase.RETRY_PROVISIONING,
                    ChatSetupPhase.BOOTSTRAP,
                    ChatSetupPhase.RECONNECT,
                    ChatSetupPhase.RESUME,
                )
        )
            return
        runSetup(
            if (current.phase == ChatSetupPhase.RESUME) CollaborationActivationMode.RESUME
            else CollaborationActivationMode.CONTINUE
        )
    }

    private fun runSetup(mode: CollaborationActivationMode) {
        val revision = ++workRevision
        work?.cancel()
        updateState {
            it.copy(
                loading = mode == CollaborationActivationMode.INSPECT,
                working = mode != CollaborationActivationMode.INSPECT,
                failure = null,
                readyDeviceId = null,
            )
        }
        work =
            viewModelScope.launch {
                try {
                    val local = readControl.setup(workspaceId)
                    ensureActive()
                    if (local is AppResult.Success) showRetained(local.value.value)
                    activate(workspaceId, mode).collect { result ->
                        ensureActive()
                        when (result) {
                            is AppResult.Failed -> failed(result.failure)
                            is AppResult.Success ->
                                updateState { it.withActivation(result.value, mode) }
                        }
                    }
                } finally {
                    if (revision == workRevision)
                        updateState { it.copy(loading = false, working = false) }
                }
            }
    }

    private fun showRetained(record: CollaborationSetupCommandRecord?) {
        if (record == null) return
        updateState {
            it.copy(
                phase = ChatSetupPhase.RESUME,
                retainedOperation = record.command.action.setupOperation(),
                readyDeviceId = null,
            )
        }
    }

    private fun onOpenChatRequested(event: ChatSetupEvent.OpenChatRequested) {
        val current = state.value
        if (
            !visible ||
                current.localFailure != null ||
                current.loading ||
                current.working ||
                current.phase != ChatSetupPhase.READY
        )
            return
        val id = current.readyDeviceId ?: return
        onStopped(ChatSetupEvent.Stopped)
        viewModelScope.launch { emitEffect(ChatSetupEffect.Ready(id)) }
    }

    private fun failed(failure: Failure) {
        updateState {
            it.copy(
                phase =
                    when {
                        chatRequiresReconnect(failure) && it.phase == ChatSetupPhase.BOOTSTRAP ->
                            ChatSetupPhase.RECONNECT
                        it.phase == ChatSetupPhase.READY -> ChatSetupPhase.UNAVAILABLE
                        else -> it.phase
                    },
                loading = false,
                working = false,
                readyDeviceId = null,
                failure = failure,
            )
        }
    }
}
