package dev.fajar.starter.collaboration.presentation.recipient

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceLocalAccess
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceDirectoryResponseClosesRead
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

/** Local composer presentation. A future recipient thread supplies durable message projection. */
@KoinViewModel
class RecipientComposerViewModel(
    @InjectedParam private val access: CollaborationSpaceLocalAccess,
    @InjectedParam private val peerAccountId: String,
    private val observe: ObserveCollaborationSpaceRecipientDraft,
    private val edit: EditCollaborationSpaceRecipientDraft,
    private val submit: SubmitCollaborationSpaceRecipientComposer,
) :
    MviViewModel<RecipientComposerState, RecipientComposerEvent, Nothing>(
        RecipientComposerState()
    ) {
    private val readRequests = MutableStateFlow<Long?>(null)
    private var readSequence = 0L
    private var editor: RecipientComposerEditingSession? = null

    init {
        on<RecipientComposerEvent.Entered>(::onEntered)
        on<RecipientComposerEvent.Stopped>(::onStopped)
        on<RecipientComposerEvent.TextChanged>(::onTextChanged)
        on<RecipientComposerEvent.SendRequested>(::onSendRequested)
        on<RecipientComposerEvent.RetryRequested>(::onRetryRequested)
        observeDraftRequests()
    }

    private fun onEntered(event: RecipientComposerEvent.Entered) {
        updateState { it.copy(visible = true) }
        if (
            readRequests.value == null ||
                state.value.readFailure != null ||
                state.value.restriction != null
        )
            readRequests.value = ++readSequence
    }

    private fun onStopped(event: RecipientComposerEvent.Stopped) {
        updateState { it.copy(visible = false) }
        // The retained route keeps its local reader to redact on changed account/Group admission.
    }

    private fun onTextChanged(event: RecipientComposerEvent.TextChanged) {
        if (state.value.visible && state.value.restriction == null) editor?.change(event.text)
    }

    private fun onSendRequested(event: RecipientComposerEvent.SendRequested) {
        if (state.value.visible && state.value.restriction == null) editor?.send()
    }

    private fun onRetryRequested(event: RecipientComposerEvent.RetryRequested) {
        if (!state.value.visible) return
        if (state.value.restriction == null) editor?.retry()
        if (
            readRequests.value == null ||
                state.value.readFailure != null ||
                state.value.restriction != null
        )
            readRequests.value = ++readSequence
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDraftRequests() {
        viewModelScope.launch {
            readRequests
                .flatMapLatest { request ->
                    // A single switch owns cancellation and joining, including rapid re-entry.
                    if (request == null) emptyFlow()
                    else observe(access, peerAccountId).map { request to it }
                }
                .buffer(0)
                .collect { (request, result) ->
                    ensureActive()
                    if (request != readRequests.value) return@collect
                    when (result) {
                        is AppResult.Failed -> {
                            if (collaborationSpaceDirectoryResponseClosesRead(result.failure)) {
                                editor?.close()
                                editor = null
                                updateState {
                                    it.copy(
                                        editor = RecipientComposerEditingState(),
                                        readFailure = result.failure,
                                        restriction = result.failure,
                                    )
                                }
                            } else updateState { it.copy(readFailure = result.failure) }
                        }
                        is AppResult.Success -> {
                            val current =
                                editor
                                    ?: RecipientComposerEditingSession(
                                            viewModelScope,
                                            access,
                                            peerAccountId,
                                            save = { edit(it.access, it.expected, it.replacement) },
                                            submit = {
                                                submit(it.access, it.expected, it.original)
                                            },
                                            onState = ::onEditorChanged,
                                        )
                                        .also { editor = it }
                            updateState { it.copy(readFailure = null, restriction = null) }
                            current.bind(result.value)
                        }
                    }
                }
        }
    }

    private fun onEditorChanged(value: RecipientComposerEditingState) {
        val failure = value.failure
        if (failure != null && collaborationSpaceDirectoryResponseClosesRead(failure)) {
            // This denial came from a separate write lifetime. Its older reader cannot reopen UI.
            readRequests.value = null
            editor?.close()
            editor = null
            updateState { it.copy(editor = RecipientComposerEditingState(), restriction = failure) }
        } else updateState { it.copy(editor = value) }
    }

    override fun onCleared() {
        editor?.close()
        editor = null
        super.onCleared()
    }
}
