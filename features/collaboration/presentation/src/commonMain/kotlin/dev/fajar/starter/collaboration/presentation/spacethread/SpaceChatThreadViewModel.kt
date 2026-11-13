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
    private val readContext: ReadCollaborationSpaceThreadContext,
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
    private var copySequence = 0L
    private var pendingCopy: SpaceChatCopyRequest? = null
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
        on<SpaceChatThreadEvent.MessageSelected>(::onMessageSelected)
        on<SpaceChatThreadEvent.SelectionCleared>(::onSelectionCleared)
        on<SpaceChatThreadEvent.CopySelected>(::onCopySelected)
        on<SpaceChatThreadEvent.ReplySelected>(::onReplySelected)
        on<SpaceChatThreadEvent.ReplyCleared>(::onReplyCleared)
        on<SpaceChatThreadEvent.ActionFailureDismissed>(::onActionFailureDismissed)
        on<SpaceChatThreadEvent.LocalReadRetryRequested>(::onLocalReadRetryRequested)
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
        pendingCopy = null
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
                val rendered =
                    spaceChatThreadRows(
                        access,
                        conversationId,
                        current.thread,
                        messages,
                        current.rows,
                    )
                if (rendered is AppResult.Failed) {
                    updateState { it.copy(projectionFailure = rendered.failure) }
                    return
                }
                val rows = (rendered as AppResult.Success).value
                updateState {
                    it.copy(
                        messages = messages,
                        rows = rows,
                        projectionFailure = null,
                        selection = it.selection.intersect(rows.map { row -> row.identity }.toSet()),
                    )
                }
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
        if (state.value.canSend && editor.send() != null)
            viewModelScope.launch { emitEffect(SpaceChatThreadEffect.ScrollToNewest) }
    }

    private fun onDraftRetryRequested(event: SpaceChatThreadEvent.DraftRetryRequested) {
        if (state.value.restriction != null || state.value.leaving) return
        if (state.value.progressFailure != null) observeMessageProgress()
        if (!state.value.draftReady) observeEditing() else editor.retry()
    }

    private fun onBackRequested(event: SpaceChatThreadEvent.BackRequested) {
        if (state.value.selecting) {
            onSelectionCleared(SpaceChatThreadEvent.SelectionCleared)
            return
        }
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

    private fun onMessageSelected(event: SpaceChatThreadEvent.MessageSelected) {
        val current = state.value
        if (
            !visible ||
                current.leaving ||
                current.restriction != null ||
                current.projectionFailure != null ||
                current.rows.none { it.identity == event.identity }
        )
            return
        val selected = current.selection
        when {
            event.identity in selected ->
                updateState {
                    it.copy(selection = selected - event.identity, selectionLimitReached = false)
                }
            selected.size < SPACE_CHAT_SELECTION_LIMIT ->
                updateState {
                    it.copy(selection = selected + event.identity, selectionLimitReached = false)
                }
            else -> updateState { it.copy(selectionLimitReached = true) }
        }
    }

    private fun onSelectionCleared(event: SpaceChatThreadEvent.SelectionCleared) {
        updateState { it.copy(selection = emptySet(), selectionLimitReached = false) }
    }

    private fun onCopySelected(event: SpaceChatThreadEvent.CopySelected) {
        val current = state.value
        if (!visible || !current.canCopySelection) return
        val selected = current.selectedRows.sortedBy { it.position }
        val effect =
            SpaceChatThreadEffect.CopyMessages(
                ++copySequence,
                selected.joinToString("\n\n") { requireNotNull(it.bubble.text) },
            )
        pendingCopy = SpaceChatCopyRequest(effect, selected, generation)
        onSelectionCleared(SpaceChatThreadEvent.SelectionCleared)
        viewModelScope.launch { emitEffect(effect) }
    }

    /** The route verifies local ownership immediately before its platform clipboard write. */
    suspend fun confirmCopy(effect: SpaceChatThreadEffect.CopyMessages): Boolean {
        val requested = pendingCopy ?: return false
        if (
            !viewModelScope.isActive ||
                requested.effect != effect ||
                !visible ||
                state.value.leaving
        )
            return false
        val context = readContext(access, conversationId)
        currentCoroutineContext().ensureActive()
        if (
            !viewModelScope.isActive ||
                pendingCopy != requested ||
                requested.generation != generation ||
                !visible ||
                state.value.leaving
        )
            return false
        pendingCopy = null
        if (context is AppResult.Failed) {
            if (collaborationSpaceDirectoryResponseClosesRead(context.failure))
                restrict(context.failure)
            else updateState { it.copy(actionFailure = context.failure) }
            return false
        }
        val current = state.value
        return current.restriction == null &&
            current.projectionFailure == null &&
            requested.rows.all { captured ->
                current.rows.any {
                    it.identity == captured.identity &&
                        it.bubble.kind == captured.bubble.kind &&
                        it.bubble.text == captured.bubble.text
                }
            }
    }

    private fun onReplySelected(event: SpaceChatThreadEvent.ReplySelected) {
        val current = state.value
        if (!visible || !current.canReplySelection) return
        val target = current.selectedRows.single().bubble.eventId ?: return
        onSelectionCleared(SpaceChatThreadEvent.SelectionCleared)
        editor.change(replyToEventId = target, immediate = true)
        viewModelScope.launch { emitEffect(SpaceChatThreadEffect.FocusComposer) }
    }

    private fun onReplyCleared(event: SpaceChatThreadEvent.ReplyCleared) {
        if (state.value.canCompose) editor.change(replyToEventId = null, immediate = true)
    }

    private fun onLocalReadRetryRequested(event: SpaceChatThreadEvent.LocalReadRetryRequested) {
        val current = state.value
        if (!visible || current.leaving) return
        if (current.projectionFailure != null) {
            observeHistory()
            observeEditing()
            observeMessageProgress()
            return
        }
        if (current.restriction != null || current.historyFailure != null) observeHistory()
        if (current.restriction != null || current.composerReadFailure != null) observeEditing()
        if (current.restriction != null || current.progressFailure != null) observeMessageProgress()
    }

    private fun onActionFailureDismissed(event: SpaceChatThreadEvent.ActionFailureDismissed) {
        updateState { it.copy(actionFailure = null) }
    }

    private fun restrict(failure: Failure) {
        generation++
        pendingCopy = null
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
