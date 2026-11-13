package dev.fajar.starter.collaboration.presentation.spacethread

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceDirectoryResponseClosesRead
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.thread.ChatComposerEditingSession
import dev.fajar.starter.collaboration.presentation.thread.ChatComposerEditingState
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

/** The route owns presentation and its serial editor; use cases own local admission and storage. */
@KoinViewModel
class SpaceChatThreadViewModel(
    @InjectedParam private val access: CollaborationSpaceLocalAccess,
    @InjectedParam private val conversationId: String,
    private val observeThread: ObserveCollaborationSpaceThread,
    private val observeComposer: ObserveCollaborationSpaceComposer,
    private val observeProgress: ObserveCollaborationSpaceComposerProgress,
    private val editDraft: EditCollaborationSpaceDraft,
    private val submitComposer: SubmitCollaborationSpaceComposer,
) :
    MviViewModel<SpaceChatThreadState, SpaceChatThreadEvent, SpaceChatThreadEffect>(
        SpaceChatThreadState()
    ) {
    private var visible = false
    private var generation = 0L
    private var historyObservation: Job? = null
    private var composerObservation: Job? = null
    private var progressObservation: Job? = null
    private val originals = MutableStateFlow<List<CollaborationComposerSubmission>>(emptyList())
    private var progress = emptyList<CollaborationSpaceComposerMessageProgress>()
    private var departure: Job? = null
    private val editor =
        ChatComposerEditingSession<CollaborationSpaceLocalAccess>(
            viewModelScope,
            conversationId,
            save = { write ->
                editDraft(
                    write.access,
                    write.expected,
                    CollaborationDraft(
                        conversationId,
                        write.revision,
                        write.text,
                        write.savedAt,
                        replyToEventId = write.replyToEventId,
                    ),
                )
            },
            submit = { write -> submitComposer(write.access, write.expected, write.original) },
            onState = ::onEditorChanged,
        )

    init {
        on<SpaceChatThreadEvent.Prepared>(::onPrepared)
        on<SpaceChatThreadEvent.Entered>(::onEntered)
        on<SpaceChatThreadEvent.Stopped>(::onStopped)
        on<SpaceChatThreadEvent.DraftChanged>(::onDraftChanged)
        on<SpaceChatThreadEvent.SendRequested>(::onSendRequested)
        on<SpaceChatThreadEvent.DraftRetryRequested>(::onDraftRetryRequested)
        on<SpaceChatThreadEvent.BackRequested>(::onBackRequested)
    }

    private fun onPrepared(event: SpaceChatThreadEvent.Prepared) {
        if (state.value.restriction != null) return
        if (progressObservation?.isActive != true) observeMessageProgress()
        if (historyObservation?.isActive != true) observeHistory()
        if (composerObservation?.isActive != true) observeEditing()
    }

    private fun onEntered(event: SpaceChatThreadEvent.Entered) {
        if (visible) return
        visible = true
        updateState { it.copy(leaving = false) }
        // Explicit re-entry may reopen only after a newly admitted local publication.
        // Keep any confirmed restriction visible while those new readers are pending.
        if (progressObservation?.isActive != true || state.value.progressFailure != null)
            observeMessageProgress()
        if (historyObservation?.isActive != true || state.value.historyFailure != null)
            observeHistory()
        if (composerObservation?.isActive != true || state.value.composerReadFailure != null)
            observeEditing()
    }

    private fun onStopped(event: SpaceChatThreadEvent.Stopped) {
        visible = false
        // Retained routes keep their owned local readers to redact on logout or Group denial.
        // The bounded navigation store owns disposal; no transport work is started by this model.
    }

    private fun observeHistory() {
        historyObservation?.cancel()
        val captured = generation
        historyObservation =
            viewModelScope.launch {
                observeThread(access, conversationId).collect { result ->
                    ensureActive()
                    if (captured != generation) return@collect
                    when (result) {
                        is AppResult.Failed -> {
                            if (collaborationSpaceDirectoryResponseClosesRead(result.failure))
                                restrict(result.failure)
                            else
                                updateState {
                                    it.copy(loading = false, historyFailure = result.failure)
                                }
                        }
                        is AppResult.Success -> {
                            updateState {
                                it.copy(
                                    thread = result.value,
                                    loading = false,
                                    historyFailure = null,
                                    restriction = null,
                                )
                            }
                            renderComposer()
                        }
                    }
                }
            }
    }

    private fun observeEditing() {
        composerObservation?.cancel()
        val captured = generation
        composerObservation =
            viewModelScope.launch {
                observeComposer(access, conversationId).collect { result ->
                    ensureActive()
                    if (captured != generation) return@collect
                    when (result) {
                        is AppResult.Failed -> {
                            if (collaborationSpaceDirectoryResponseClosesRead(result.failure))
                                restrict(result.failure)
                            else updateState { it.copy(composerReadFailure = result.failure) }
                        }
                        is AppResult.Success -> {
                            updateState {
                                it.copy(
                                    composer = result.value,
                                    composerReadFailure = null,
                                    restriction = null,
                                )
                            }
                            editor.bind(access, result.value.draft)
                            renderComposer()
                        }
                    }
                }
            }
    }

    private fun observeMessageProgress() {
        progressObservation?.cancel()
        val captured = generation
        progressObservation =
            viewModelScope.launch {
                observeProgress(access, originals).collect { result ->
                    ensureActive()
                    if (captured != generation) return@collect
                    when (result) {
                        is AppResult.Failed -> {
                            if (collaborationSpaceDirectoryResponseClosesRead(result.failure))
                                restrict(result.failure)
                            else updateState { it.copy(progressFailure = result.failure) }
                        }
                        is AppResult.Success -> {
                            progress = result.value
                            updateState { it.copy(progressFailure = null) }
                            renderComposer()
                        }
                    }
                }
            }
    }

    private fun renderComposer() {
        val current = state.value
        if (current.restriction != null) return
        when (
            val projected =
                projectSpaceChatComposer(
                    access,
                    conversationId,
                    current.messages,
                    current.composer?.submissions.orEmpty(),
                    current.provisional,
                    progress,
                    current.thread,
                )
        ) {
            is AppResult.Failed -> updateState { it.copy(projectionFailure = projected.failure) }
            is AppResult.Success -> {
                val messages = projected.value
                updateState { it.copy(messages = messages, projectionFailure = null) }
                originals.value = messages.pending.map { it.original }.sortedBy { it.id }
                // The immutable screen projection owns these exact rows before releasing the actor.
                editor.visible(
                    (messages.pending + messages.represented)
                        .filter { it.durable }
                        .map { it.original }
                        .toSet()
                )
            }
        }
    }

    private fun onEditorChanged(value: ChatComposerEditingState) {
        if (value.failure?.let(::collaborationSpaceDirectoryResponseClosesRead) == true) {
            restrict(requireNotNull(value.failure))
            return
        }
        updateState {
            it.copy(
                draft = value.text,
                replyToEventId = value.replyToEventId,
                draftReady = value.ready,
                draftSaving = value.saving,
                draftFailure = value.failure,
                legacyPending = value.legacyPending,
                provisional =
                    value.submissions.map { pending ->
                        SpaceChatPendingSubmission(
                            pending.original,
                            pending.durable,
                            pending.failure,
                        )
                    },
            )
        }
        renderComposer()
    }

    private fun onDraftChanged(event: SpaceChatThreadEvent.DraftChanged) {
        if (state.value.canCompose) editor.change(event.text)
    }

    private fun onSendRequested(event: SpaceChatThreadEvent.SendRequested) {
        if (state.value.canSend) editor.send()
    }

    private fun onDraftRetryRequested(event: SpaceChatThreadEvent.DraftRetryRequested) {
        if (state.value.restriction != null || state.value.leaving) return
        if (state.value.progressFailure != null) observeMessageProgress()
        if (!state.value.draftReady) observeEditing() else editor.retry()
    }

    private fun onBackRequested(event: SpaceChatThreadEvent.BackRequested) {
        if (departure?.isActive == true || state.value.leaving) return
        val captured = generation
        departure =
            viewModelScope.launch {
                updateState { it.copy(leaving = true) }
                val settled = !editor.state.ready || editor.flush()
                ensureActive()
                if (captured != generation) return@launch
                if (settled) emitEffect(SpaceChatThreadEffect.Back)
                else updateState { it.copy(leaving = false) }
            }
    }

    private fun restrict(failure: Failure) {
        generation++
        historyObservation?.cancel()
        composerObservation?.cancel()
        progressObservation?.cancel()
        progressObservation = null
        originals.value = emptyList()
        progress = emptyList()
        departure?.cancel()
        historyObservation = null
        composerObservation = null
        updateState { SpaceChatThreadState(loading = false, restriction = failure) }
        editor.clear()
    }
}
