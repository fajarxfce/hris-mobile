package dev.fajar.starter.collaboration.presentation.recipient

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceDirectoryResponseClosesRead
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.thread.invalidThreadProjection
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

/**
 * One route owns editing and local publications; use cases own permission, storage and delivery
 * policy.
 */
@KoinViewModel
class RecipientChatThreadViewModel(
    @InjectedParam private val access: CollaborationSpaceLocalAccess,
    @InjectedParam private val peerAccountId: String,
    private val observeSource: ObserveCollaborationSpaceRecipientProjection,
    private val observeDestination: ObserveCollaborationSpaceRecipientDestination,
    private val observeThread: ObserveCollaborationSpaceThread,
    private val observeProgress: ObserveCollaborationSpaceComposerProgress,
    private val edit: EditCollaborationSpaceRecipientDraft,
    private val submit: SubmitCollaborationSpaceRecipientComposer,
    private val readContext: ReadCollaborationSpaceThreadContext,
) :
    MviViewModel<RecipientChatThreadState, RecipientChatThreadEvent, RecipientChatThreadEffect>(
        RecipientChatThreadState()
    ) {
    private val requests = MutableStateFlow<Long?>(null)
    private var sequence = 0L
    private val originals =
        MutableStateFlow<List<CollaborationSpaceRecipientSubmission>>(emptyList())
    private var progress = emptyList<CollaborationSpaceRecipientMessageProgress>()
    private var editor: RecipientComposerEditingSession? = null
    private var editorGeneration = 0L
    private var copySequence = 0L
    private var pendingCopy: RecipientChatCopyRequest? = null
    private var departure: Job? = null
    private var departureId = 0L
    private var pendingDeparture: RecipientChatThreadEffect.Back? = null

    init {
        on<RecipientChatThreadEvent.Prepared>(::onPrepared)
        on<RecipientChatThreadEvent.BackRequested>(::onBackRequested)
        on<RecipientChatThreadEvent.RouteReleased>(::onRouteReleased)
        on<RecipientChatThreadEvent.Entered>(::onEntered)
        on<RecipientChatThreadEvent.Stopped>(::onStopped)
        on<RecipientChatThreadEvent.TextChanged>(::onTextChanged)
        on<RecipientChatThreadEvent.MessageSelected>(::onMessageSelected)
        on<RecipientChatThreadEvent.SelectionCleared>(::onSelectionCleared)
        on<RecipientChatThreadEvent.CopySelected>(::onCopySelected)
        on<RecipientChatThreadEvent.SendRequested>(::onSendRequested)
        on<RecipientChatThreadEvent.RetryRequested>(::onRetryRequested)
        observeRequests()
    }

    private fun onPrepared(event: RecipientChatThreadEvent.Prepared) {
        if (requests.value == null && state.value.restriction == null) requests.value = ++sequence
    }

    private fun onEntered(event: RecipientChatThreadEvent.Entered) {
        updateState { it.copy(visible = true) }
        if (
            requests.value == null ||
                state.value.sourceFailure != null ||
                state.value.destinationFailure != null ||
                state.value.historyFailure != null ||
                state.value.progressFailure != null
        )
            requests.value = ++sequence
    }

    private fun onStopped(event: RecipientChatThreadEvent.Stopped) {
        cancelDeparture()
        updateState { it.copy(visible = false, leaving = false) }
        // A retained route keeps local readers so account/admission changes can redact it.
    }

    private fun onRouteReleased(event: RecipientChatThreadEvent.RouteReleased) {
        cancelDeparture()
        updateState { it.copy(visible = false, leaving = false) }
    }

    private fun onBackRequested(event: RecipientChatThreadEvent.BackRequested) {
        if (!state.value.visible || state.value.leaving) return
        if (state.value.selecting) {
            onSelectionCleared(RecipientChatThreadEvent.SelectionCleared)
            return
        }
        pendingCopy = null
        val captured = RecipientChatThreadEffect.Back(++departureId)
        val generation = editorGeneration
        val current = editor
        pendingDeparture = captured
        updateState { it.copy(leaving = true) }
        departure =
            viewModelScope.launch {
                try {
                    val flushed = current?.flush() ?: true
                    ensureActive()
                    if (
                        pendingDeparture != captured ||
                            generation != editorGeneration ||
                            !state.value.visible
                    )
                        return@launch
                    if (flushed) emitEffect(captured)
                    else {
                        pendingDeparture = null
                        updateState { it.copy(leaving = false) }
                    }
                } finally {
                    if (departureId == captured.request) departure = null
                }
            }
    }

    /** The visible route confirms this exact effect immediately before calling navigation. */
    fun confirmBack(effect: RecipientChatThreadEffect.Back): Boolean {
        if (pendingDeparture != effect || !state.value.visible || !state.value.leaving) return false
        pendingDeparture = null
        return true
    }

    /** Cancels the navigation wait/effect without cancelling an ordinary retained editor write. */
    private fun cancelDeparture() {
        pendingDeparture = null
        onSelectionCleared(RecipientChatThreadEvent.SelectionCleared)
        departure?.cancel()
        departure = null
        discardPendingEffects()
    }

    private fun onMessageSelected(event: RecipientChatThreadEvent.MessageSelected) {
        val current = state.value
        if (
            !current.visible ||
                current.leaving ||
                current.restriction != null ||
                current.projectionFailure != null ||
                current.rows.none { it.identity == event.identity }
        )
            return
        pendingCopy = null
        val selected = current.selection
        when {
            event.identity in selected ->
                updateState {
                    it.copy(selection = selected - event.identity, selectionLimitReached = false)
                }
            selected.size < RECIPIENT_CHAT_SELECTION_LIMIT ->
                updateState {
                    it.copy(selection = selected + event.identity, selectionLimitReached = false)
                }
            else -> updateState { it.copy(selectionLimitReached = true) }
        }
    }

    private fun onSelectionCleared(event: RecipientChatThreadEvent.SelectionCleared) {
        pendingCopy = null
        updateState { it.copy(selection = emptySet(), selectionLimitReached = false) }
    }

    private fun onCopySelected(event: RecipientChatThreadEvent.CopySelected) {
        val current = state.value
        if (!current.canCopySelection) return
        val selected = current.selectedRows.sortedBy { it.position }
        val effect =
            RecipientChatThreadEffect.CopyMessages(
                ++copySequence,
                selected.joinToString("\n\n") { requireNotNull(it.bubble.text) },
            )
        onSelectionCleared(RecipientChatThreadEvent.SelectionCleared)
        pendingCopy = RecipientChatCopyRequest(effect, selected, editorGeneration)
        updateState { it.copy(actionFailure = null) }
        viewModelScope.launch { emitEffect(effect) }
    }

    /** The route checks current local ownership immediately before writing the clipboard. */
    suspend fun confirmCopy(effect: RecipientChatThreadEffect.CopyMessages): Boolean {
        val captured = pendingCopy ?: return false
        if (
            !viewModelScope.isActive ||
                captured.effect != effect ||
                !state.value.visible ||
                state.value.leaving
        )
            return false
        val context = readContext.recipient(access, peerAccountId)
        currentCoroutineContext().ensureActive()
        if (
            !viewModelScope.isActive ||
                pendingCopy != captured ||
                captured.generation != editorGeneration ||
                !state.value.visible ||
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
        val destination = (context as AppResult.Success).value
        val current = state.value
        return current.restriction == null &&
            current.projectionFailure == null &&
            captured.rows.all { requested ->
                current.rows.any { row ->
                    row.identity == requested.identity &&
                        row.bubble.kind == requested.bubble.kind &&
                        row.bubble.text == requested.bubble.text &&
                        recipientChatCopyMatchesContext(
                            access,
                            peerAccountId,
                            destination,
                            row,
                            current.messages,
                        )
                }
            }
    }

    private fun onTextChanged(event: RecipientChatThreadEvent.TextChanged) {
        if (state.value.canCompose) editor?.change(event.text)
    }

    private fun onSendRequested(event: RecipientChatThreadEvent.SendRequested) {
        if (state.value.canSend) editor?.send()
    }

    private fun onRetryRequested(event: RecipientChatThreadEvent.RetryRequested) {
        if (!state.value.visible || state.value.leaving) return
        if (state.value.restriction == null) editor?.retry()
        if (
            requests.value == null ||
                state.value.sourceFailure != null ||
                state.value.destinationFailure != null ||
                state.value.historyFailure != null ||
                state.value.progressFailure != null
        )
            requests.value = ++sequence
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeRequests() {
        viewModelScope.launch {
            requests
                .flatMapLatest { request ->
                    if (request == null) emptyFlow()
                    else
                        merge(
                                observeSource(access, peerAccountId).map {
                                    RecipientThreadReadResult.Source(request, it)
                                },
                                destinationReads(request),
                            )
                            .buffer(32)
                }
                .buffer(0)
                .collect { input ->
                    ensureActive()
                    if (input.request != requests.value) return@collect
                    when (input) {
                        is RecipientThreadReadResult.Source -> onSource(input.result)
                        is RecipientThreadReadResult.Destination -> onDestination(input.result)
                        is RecipientThreadReadResult.History ->
                            if (input.destination == state.value.destination)
                                onHistory(input.result)
                        is RecipientThreadReadResult.Progress ->
                            if (input.destination == state.value.destination)
                                onProgress(input.result)
                    }
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun destinationReads(request: Long): Flow<RecipientThreadReadResult> =
        observeDestination(access, peerAccountId)
            .transformLatest { result ->
                emit(RecipientThreadReadResult.Destination(request, result))
                val destination = (result as? AppResult.Success)?.value
                if (destination != null)
                    merge(
                            observeThread(access, destination.id).map {
                                RecipientThreadReadResult.History(request, destination, it)
                            },
                            observeProgress.recipients(access, destination, originals).map {
                                RecipientThreadReadResult.Progress(request, destination, it)
                            },
                        )
                        .collect { emit(it) }
            }
            .buffer(0)

    private fun onSource(result: AppResult<CollaborationSpaceRecipientProjection>) {
        when (result) {
            is AppResult.Failed -> {
                if (collaborationSpaceDirectoryResponseClosesRead(result.failure))
                    restrict(result.failure)
                else updateState { it.copy(loading = false, sourceFailure = result.failure) }
            }
            is AppResult.Success -> {
                updateState {
                    it.copy(
                        source = result.value,
                        loading = false,
                        sourceFailure = null,
                        restriction = null,
                    )
                }
                val current =
                    editor
                        ?: run {
                            val generation = ++editorGeneration
                            RecipientComposerEditingSession(
                                    viewModelScope,
                                    access,
                                    peerAccountId,
                                    save = { edit(it.access, it.expected, it.replacement) },
                                    submit = { submit(it.access, it.expected, it.original) },
                                    onState = {
                                        if (generation == editorGeneration) onEditorChanged(it)
                                    },
                                )
                                .also { editor = it }
                        }
                val alreadyBound = current.state.ready
                current.bind(
                    result.value.draft,
                    result.value.submissions.maxOfOrNull { it.submittedAt },
                )
                // Initial bind publishes editor state synchronously; a replay only refreshes source
                // facts.
                if (alreadyBound) renderMessages()
            }
        }
    }

    private fun onDestination(result: AppResult<CollaborationSpaceConversationMetadata?>) {
        when (result) {
            is AppResult.Failed -> {
                if (collaborationSpaceDirectoryResponseClosesRead(result.failure))
                    restrict(result.failure)
                else updateState { it.copy(destinationFailure = result.failure) }
            }
            is AppResult.Success -> {
                if (state.value.destination != null && result.value != state.value.destination) {
                    // The admitted association is immutable. Another target cannot reinterpret old
                    // evidence.
                    restrict(invalidThreadProjection().failure)
                    return
                }
                updateState { it.copy(destination = result.value, destinationFailure = null) }
                renderMessages()
            }
        }
    }

    private fun onHistory(result: AppResult<CollaborationSpaceThreadSnapshot>) {
        when (result) {
            is AppResult.Failed -> {
                if (collaborationSpaceDirectoryResponseClosesRead(result.failure))
                    restrict(result.failure)
                else updateState { it.copy(historyFailure = result.failure) }
            }
            is AppResult.Success -> {
                updateState { it.copy(thread = result.value, historyFailure = null) }
                renderMessages()
            }
        }
    }

    private fun onProgress(result: AppResult<List<CollaborationSpaceRecipientMessageProgress>>) {
        when (result) {
            is AppResult.Failed -> {
                if (collaborationSpaceDirectoryResponseClosesRead(result.failure))
                    restrict(result.failure)
                else updateState { it.copy(progressFailure = result.failure) }
            }
            is AppResult.Success -> {
                progress = result.value
                updateState { it.copy(progressFailure = null) }
                renderMessages()
            }
        }
    }

    private fun onEditorChanged(value: RecipientComposerEditingState) {
        val failure = value.failure
        if (failure != null && collaborationSpaceDirectoryResponseClosesRead(failure))
            restrict(failure)
        else {
            updateState { it.copy(editor = value) }
            renderMessages()
        }
    }

    private fun renderMessages() {
        if (state.value.restriction != null) return
        val current = state.value
        val presentation =
            projectRecipientChatPresentation(
                access,
                peerAccountId,
                current.destination,
                current.messages,
                current.source?.submissions.orEmpty(),
                current.editor.submissions,
                progress,
                current.thread,
            )
        if (presentation is AppResult.Failed) {
            updateState { it.copy(projectionFailure = presentation.failure) }
            return
        }
        val messages = (presentation as AppResult.Success).value
        val rendered =
            recipientChatThreadRows(
                access,
                peerAccountId,
                current.destination,
                current.thread,
                messages,
                current.rows,
            )
        if (rendered is AppResult.Failed) {
            updateState { it.copy(projectionFailure = rendered.failure) }
            return
        }
        updateState {
            it.copy(
                messages = messages,
                rows = (rendered as AppResult.Success).value,
                selection =
                    it.selection.intersect(rendered.value.map { row -> row.identity }.toSet()),
                projectionFailure = null,
            )
        }
        originals.value = messages.pending.map { it.original }
        // Only the coherent displayed projection may release durable provisional actor entries.
        editor?.represented(messages.represented.map { it.original }.toSet())
    }

    private fun restrict(failure: Failure) {
        cancelDeparture()
        requests.value = null
        originals.value = emptyList()
        progress = emptyList()
        editorGeneration++
        val old = editor
        editor = null
        old?.close()
        updateState {
            RecipientChatThreadState(visible = it.visible, loading = false, restriction = failure)
        }
    }

    override fun onCleared() {
        cancelDeparture()
        requests.value = null
        originals.value = emptyList()
        progress = emptyList()
        editorGeneration++
        editor?.close()
        editor = null
        updateState { RecipientChatThreadState(loading = false) }
        super.onCleared()
    }
}
