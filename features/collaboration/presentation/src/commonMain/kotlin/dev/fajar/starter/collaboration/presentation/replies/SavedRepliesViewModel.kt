@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.presentation.replies

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class SavedRepliesViewModel(
    @InjectedParam private val workspaceId: String,
    @InjectedParam private val replyId: String,
    private val observe: ObserveCollaborationSavedReplies,
    private val discard: DiscardCollaborationNotificationReply,
    private val saveDraft: SaveCollaborationReplyDraft,
    private val confirm: ConfirmCollaborationReply,
    private val resume: ResumeCollaborationReviewedReply,
) :
    MviViewModel<SavedRepliesState, SavedRepliesEvent, SavedRepliesEffect>(
        SavedRepliesState(replyId)
    ) {
    private var visible = false
    private var observation: Job? = null
    private var work: Job? = null
    private var draftWork: Job? = null
    private var pendingLeave: SavedRepliesEffect? = null

    init {
        on<SavedRepliesEvent.DraftChanged>(::draftChanged)
        on<SavedRepliesEvent.SaveDraftRequested> { persistDraft() }
        on<SavedRepliesEvent.SendRequested>(::send)
        on<SavedRepliesEvent.DiscardEditRequested> {
            requestDiscard(
                SavedRepliesEvent.DiscardRequested,
                CollaborationReplyDiscardMode.EditedDraftOnly,
            )
        }
        on<SavedRepliesEvent.LeaveConfirmed> { pendingLeave?.let { leave(it) } }
        on<SavedRepliesEvent.LeaveDismissed> {
            pendingLeave = null
            updateState { it.copy(leaveConfirmation = false) }
        }
        on<SavedRepliesEvent.Entered>(::entered)
        on<SavedRepliesEvent.Stopped>(::stopped)
        on<SavedRepliesEvent.RefreshRequested>(::refresh)
        on<SavedRepliesEvent.BackRequested>(::back)
        on<SavedRepliesEvent.ReplySelected>(::selected)
        on<SavedRepliesEvent.OpenChatRequested>(::openChat)
        on<SavedRepliesEvent.DiscardRequested> { requestDiscard(it) }
        on<SavedRepliesEvent.DiscardDismissed>(::dismissDiscard)
        on<SavedRepliesEvent.DiscardConfirmed>(::confirmDiscard)
    }

    private fun entered(event: SavedRepliesEvent.Entered) {
        if (visible) return
        visible = true
        restart()
    }

    private fun restart() {
        val previous = observation
        val previousWork = work
        val previousDraft = draftWork
        previousDraft?.cancel()
        pendingLeave = null
        previous?.cancel()
        previousWork?.cancel()
        updateState { SavedRepliesState(replyId) }
        observation =
            viewModelScope.launch {
                previous?.join()
                previousWork?.join()
                previousDraft?.join()
                ensureActive()
                observe(workspaceId).collect { result ->
                    ensureActive()
                    when (result) {
                        is AppResult.Failed -> {
                            // Clear private content before awaiting an obsolete mutation's cleanup.
                            updateState {
                                SavedRepliesState(
                                    replyId,
                                    loading = false,
                                    failure = result.failure,
                                )
                            }
                            pendingLeave = null
                            draftWork?.cancel()
                            work?.cancelAndJoin()
                            draftWork?.join()
                        }
                        is AppResult.Success -> {
                            val previousSnapshot = state.value.snapshot
                            if (
                                previousSnapshot != null &&
                                    (previousSnapshot.access != result.value.access ||
                                        previousSnapshot.parent != result.value.parent)
                            ) {
                                updateState { SavedRepliesState(replyId) }
                                pendingLeave = null
                                draftWork?.cancel()
                                work?.cancelAndJoin()
                                draftWork?.join()
                                ensureActive()
                            }
                            updateState {
                                it.copy(
                                    snapshot = result.value,
                                    draft =
                                        result.value.items
                                            .find { row -> row.input.id == replyId }
                                            ?.input
                                            ?.let { input ->
                                                input.resolution?.text
                                                    ?: it.draft
                                                    ?: input.draft
                                                    ?: input.candidate.text
                                            },
                                    loading = false,
                                    failure = null,
                                    confirmation =
                                        it.confirmation &&
                                            result.value.items.any { row ->
                                                row.input.id == replyId
                                            },
                                )
                            }
                        }
                    }
                }
            }
    }

    private fun stopped(event: SavedRepliesEvent.Stopped) {
        visible = false
        observation?.cancel()
        work?.cancel()
        draftWork?.cancel()
        pendingLeave = null
        updateState { SavedRepliesState(replyId) }
    }

    private fun refresh(event: SavedRepliesEvent.RefreshRequested) {
        if (visible && !state.value.busy && !state.value.unsavedDraft) restart()
    }

    private fun back(event: SavedRepliesEvent.BackRequested) {
        if (!visible || state.value.busy) return
        if (state.value.confirmation) {
            updateState { it.copy(confirmation = false) }
        } else if (state.value.leaveConfirmation) {
            pendingLeave = null
            updateState { it.copy(leaveConfirmation = false) }
        } else requestLeave(SavedRepliesEffect.Back)
    }

    private fun selected(event: SavedRepliesEvent.ReplySelected) {
        val current = state.value
        if (
            !visible ||
                current.loading ||
                current.failure != null ||
                current.busy ||
                current.snapshot?.items?.none { it.input.id == event.id } != false
        )
            return
        leave(SavedRepliesEffect.OpenReply(event.id))
    }

    private fun openChat(event: SavedRepliesEvent.OpenChatRequested) {
        val current = state.value
        val selected = current.selected ?: return
        if (
            !visible ||
                current.loading ||
                current.failure != null ||
                current.busy ||
                selected.conversationName == null
        )
            return
        requestLeave(
            SavedRepliesEffect.OpenChat(selected.input.candidate.action.target.conversationId)
        )
    }

    private fun requestLeave(effect: SavedRepliesEffect) {
        if (state.value.busy) return
        if (state.value.unsavedDraft) {
            pendingLeave = effect
            updateState { it.copy(leaveConfirmation = true) }
        } else leave(effect)
    }

    private fun leave(effect: SavedRepliesEffect) {
        val oldObservation = observation
        val oldWork = work
        val oldDraft = draftWork
        stopped(SavedRepliesEvent.Stopped)
        work =
            viewModelScope.launch {
                oldObservation?.join()
                oldWork?.join()
                oldDraft?.join()
                ensureActive()
                emitEffect(effect)
            }
    }

    private fun requestDiscard(
        event: SavedRepliesEvent.DiscardRequested,
        mode: CollaborationReplyDiscardMode = CollaborationReplyDiscardMode.All,
    ) {
        val current = state.value
        if (
            !visible ||
                current.loading ||
                current.busy ||
                current.failure != null ||
                current.selected == null
        )
            return
        updateState {
            it.copy(confirmation = true, discardMode = mode, actionFailure = null, outcome = null)
        }
    }

    private fun dismissDiscard(event: SavedRepliesEvent.DiscardDismissed) {
        if (!state.value.busy) updateState { it.copy(confirmation = false) }
    }

    private fun confirmDiscard(event: SavedRepliesEvent.DiscardConfirmed) {
        val current = state.value
        val input = current.selected?.input ?: return
        val access = current.snapshot?.access ?: return
        if (
            !visible ||
                !current.confirmation ||
                current.busy ||
                current.loading ||
                current.failure != null
        )
            return
        val parent = current.snapshot.parent
        val previous = work
        val previousDraft = draftWork
        previousDraft?.cancel()
        updateState {
            it.copy(busy = true, actionFailure = null, outcome = null, savingDraft = false)
        }
        work =
            viewModelScope.launch {
                previous?.join()
                ensureActive()
                previousDraft?.join()
                ensureActive()
                // A just-finished autosave may have advanced the exact input version.
                val latest = state.value.selected?.input
                if (latest == null) {
                    if (owns(access, parent))
                        updateState {
                            it.copy(
                                busy = false,
                                confirmation = false,
                                outcome = CollaborationReplyDiscard.Absent,
                            )
                        }
                    return@launch
                }
                val result = discard(workspaceId, access, latest, current.discardMode, parent)
                ensureActive()
                if (!owns(access, parent)) return@launch
                when (result) {
                    is AppResult.Failed ->
                        updateState { it.copy(busy = false, actionFailure = result.failure) }
                    is AppResult.Success ->
                        updateState {
                            it.copy(
                                busy = false,
                                confirmation = false,
                                actionFailure = null,
                                outcome = result.value,
                            )
                        }
                }
            }
    }

    private fun owns(access: CollaborationLocalAccess, parent: CollaborationNativeParent?) =
        visible &&
            state.value.failure == null &&
            state.value.snapshot?.let { it.access == access && it.parent == parent } == true

    private fun draftChanged(event: SavedRepliesEvent.DraftChanged) {
        if (!visible || !state.value.editable || !validCollaborationReplyDraft(event.text)) return
        updateState {
            it.copy(
                draft = event.text,
                draftFailure = null,
                actionFailure = null,
                sendOutcome = null,
            )
        }
        persistDraft()
    }

    private fun persistDraft() {
        val current = state.value
        val input = current.selected?.input ?: return
        val access = current.snapshot?.access ?: return
        val parent = current.snapshot.parent ?: return
        val text = current.draft ?: return
        if (!visible || !current.editable) return
        val previous = draftWork
        previous?.cancel()
        updateState { it.copy(savingDraft = true, draftFailure = null) }
        draftWork =
            viewModelScope.launch {
                try {
                    previous?.join()
                    ensureActive()
                    val result = saveDraft(workspaceId, access, parent, input, text)
                    ensureActive()
                    if (!owns(access, parent) || state.value.draft != text) return@launch
                    when (result) {
                        is AppResult.Failed ->
                            updateState {
                                it.copy(savingDraft = false, draftFailure = result.failure)
                            }
                        is AppResult.Success ->
                            updateState {
                                it.copy(
                                    savingDraft = false,
                                    draftFailure = null,
                                    snapshot =
                                        it.snapshot?.copy(
                                            items =
                                                it.snapshot.items.map { row ->
                                                    if (row.input.id == input.id)
                                                        row.copy(input = result.value)
                                                    else row
                                                }
                                        ),
                                )
                            }
                    }
                } finally {
                    // A cancelled successor must still join its predecessor before a third edit
                    // starts.
                    withContext(NonCancellable) { previous?.join() }
                }
            }
    }

    private fun send(event: SavedRepliesEvent.SendRequested) {
        val current = state.value
        val input = current.selected?.input ?: return
        val access = current.snapshot?.access ?: return
        val parent = current.snapshot.parent ?: return
        val text = input.resolution?.text ?: current.draft ?: input.draft ?: input.candidate.text
        if (
            !visible ||
                current.busy ||
                current.loading ||
                current.failure != null ||
                current.confirmation ||
                (input.review == null && input.resolution == null) ||
                !validCollaborationText(text)
        )
            return
        val operationId =
            input.resolution?.operationId ?: current.pendingSendId ?: Uuid.random().toString()
        val previous = work
        val previousDraft = draftWork
        previousDraft?.cancel()
        updateState {
            it.copy(
                busy = true,
                savingDraft = false,
                draftFailure = null,
                pendingSendId = operationId,
                actionFailure = null,
                sendOutcome = null,
                outcome = null,
            )
        }
        work =
            viewModelScope.launch {
                previous?.join()
                previousDraft?.join()
                ensureActive()
                val confirmed = confirm(workspaceId, access, parent, input, operationId, text)
                ensureActive()
                if (!owns(access, parent)) return@launch
                val result =
                    when (confirmed) {
                        is AppResult.Failed -> confirmed
                        is AppResult.Success -> resume(confirmed.value)
                    }
                ensureActive()
                if (!owns(access, parent)) return@launch
                when (result) {
                    is AppResult.Failed ->
                        updateState { it.copy(busy = false, actionFailure = result.failure) }
                    is AppResult.Success ->
                        updateState {
                            it.copy(
                                busy = false,
                                sendOutcome = result.value,
                                pendingSendId = null,
                                draft =
                                    if (
                                        result.value is CollaborationReviewedReplyResult.NeedsReview
                                    )
                                        it.draft
                                    else null,
                            )
                        }
                }
            }
    }
}
