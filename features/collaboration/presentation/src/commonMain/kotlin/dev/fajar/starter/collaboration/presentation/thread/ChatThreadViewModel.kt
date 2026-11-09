@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationContentResponseClosesRead
import dev.fajar.starter.collaboration.domain.policies.collaborationTimelineRequiresHistory
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.policies.validCollaborationCancellation
import dev.fajar.starter.collaboration.domain.policies.validCollaborationText
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.chatRequiresReconnect
import dev.fajar.starter.collaboration.presentation.replies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.*
import dev.fajar.starter.presentation.mvi.MviViewModel
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

@KoinViewModel
class ChatThreadViewModel(
    @InjectedParam private val workspaceId: String,
    @InjectedParam private val conversationId: String,
    private val observeAccess: ObserveCollaborationAccess,
    private val readContext: ReadCollaborationThreadContext,
    private val observeThread: ObserveCollaborationThread,
    private val requestDelivery: RequestCollaborationDelivery,
    private val loadSetup: LoadCollaborationSetup,
    private val ensureRuntime: EnsureCollaborationRuntime,
    private val identity: ReadCollaborationIdentity,
    private val prepareRoom: PrepareCollaborationRoom,
    private val enter: EnterCollaborationConversation,
    private val acquireHistory: AcquireCollaborationHistory,
    private val reopenHistory: ReopenCollaborationHistory,
    private val acquireOlderHistory: AcquireOlderCollaborationHistory,
    private val readHistoryEnd: ReadCollaborationHistoryEnd,
    private val observeDraft: ObserveCollaborationDraft,
    private val editDraft: EditCollaborationDraft,
    private val submitComposer: SubmitCollaborationComposer,
    private val observeComposerProgress: ObserveCollaborationComposerProgress,
    private val cancelComposer: CancelCollaborationComposer,
    private val recoverComposer: RecoverCollaborationComposer,
    private val cancelText: CancelCollaborationText,
    readState: ReadCollaborationReadState,
    markRead: MarkCollaborationMessagesRead,
    requestRecovery: RequestCollaborationRecovery,
    private val observeHidden: ObserveCollaborationHiddenMessages,
    private val deleteForMe: DeleteCollaborationMessagesForMe,
    private val observeReplies: ObserveCollaborationSavedReplies,
    private val observeQuotes: ObserveCollaborationQuotes,
    forwardTargets: ReadCollaborationForwardTargets,
    prepareForward: PrepareCollaborationForward,
    queueForward: QueueCollaborationForward,
    readEditCandidate: ReadCollaborationEditCandidate,
    queueEdit: QueueCollaborationEdit,
    private val readEditOutcomes: ReadCollaborationEditOutcomes,
    private val retireMessages: RetireCollaborationMessages,
) : MviViewModel<ChatThreadState, ChatThreadEvent, ChatThreadEffect>(ChatThreadState()) {
    private val forwarding =
        ChatThreadForwardController(
            viewModelScope,
            workspaceId,
            forwardTargets,
            prepareForward,
            queueForward,
            onState = { value -> updateState { it.copy(forwarding = value) } },
            onRestricted = ::restrict,
            onForwarded = { clearSelection() },
        )
    private val editing =
        ChatThreadEditController(
            viewModelScope,
            workspaceId,
            conversationId,
            readEditCandidate,
            queueEdit,
            onState = { value -> updateState { it.copy(editing = value) } },
            onStarted = {
                clearSelection()
                updateState { it.copy(editFailure = null) }
                viewModelScope.launch { emitEffect(ChatThreadEffect.FocusComposer) }
            },
            onQueued = {
                wakeRequested = true
                pulse?.trySend(Unit)
            },
            onFailure = { failure -> updateState { it.copy(editFailure = failure) } },
            onRestricted = ::restrict,
        )
    private var editAcknowledgement: Job? = null
    private var editOutcomes = emptyList<CollaborationEditOutcome>()
    private val projection = MutableStateFlow<ChatVisibilityProjection?>(null)
    private val quoteRequests = MutableStateFlow<ChatQuoteRequest?>(null)
    private var deletionSelection: ChatDeletionSelection? = null
    private var deletionJob: Job? = null
    private var observation: Job? = null
    private var connection: Job? = null
    private var reconnection: Job? = null
    private var reconnectAttempt = 0
    private var submission: Job? = null
    private var cancellationJob: Job? = null
    private var cancellationSelection: ChatCancellationSelection? = null
    private var visible = false
    private var access: CollaborationLocalAccess? = null
    private var context: CollaborationThreadContext? = null
    private var pending = emptyList<CollaborationMessageRecord>()
    private var history = emptyList<CollaborationHistoryMessage>()
    private var timeline = emptyList<CollaborationTimelineItem>()
    private var source: CollaborationMessageDevice? = null
    private var historyRoomId: String? = null
    private var viewportKeys = emptyList<String>()
    private val readTracker =
        ChatThreadReadTracker(
            viewModelScope,
            readState,
            markRead,
            requestRecovery,
            onFailure = { if (collaborationContentResponseClosesRead(it)) restrict(it) },
        )
    private var next: String? = null
    private var previous: String? = null
    private val window = MutableStateFlow(ChatThreadLocalWindow())
    private var displayedWindow = ChatThreadLocalWindow()

    internal val canRetainAfterNavigation: Boolean
        get() = context != null && window.value.latest && displayedWindow.latest

    private var historyLoading = true
    private var scrollToLatest = false
    private var localSave: Job? = null
    private val composerOriginals = MutableStateFlow(emptyList<CollaborationComposerSubmission>())
    private var composerProgress = emptyList<CollaborationComposerMessageProgress>()
    private var watchedComposerOriginals = emptyList<CollaborationComposerSubmission>()
    private var composerRetry: Job? = null
    private var composerProgressReady = false
    private val composer =
        ChatComposerEditingSession(
            viewModelScope,
            conversationId,
            save = { write ->
                editDraft(
                    workspaceId,
                    conversationId,
                    write.expected,
                    write.revision,
                    write.text,
                    write.access,
                    write.replyToEventId,
                )
            },
            submit = { write ->
                withContext(OperationTrace.create()) {
                    measureAppResult(PerformanceOperation.CommandQueue) {
                        submitComposer(workspaceId, write.expected, write.original, write.access)
                    }
                }
            },
            onSubmitted = { if (visible && pulse == null) connect() },
            onState = ::onComposerStateChanged,
        )
    private var departure: Job? = null
    private var departureEffect: ChatThreadEffect? = null
    private var pulse: Channel<Unit>? = null
    private var wakeRequested = false
    private var olderRequested = false
    private var olderPosition = CollaborationOlderHistoryPosition()
    private var olderScanned = false
    private var olderKey: CollaborationHistoryTraversal? = null
    private var olderRecoveryAnchor: String? = null

    init {
        on<ChatThreadEvent.UnsentReplySelected> {
            if (
                visible &&
                    !state.value.selecting &&
                    state.value.localFailure == null &&
                    state.value.unsentReplies.any { reply -> reply.id == it.id }
            ) {
                leaveThread(ChatThreadEffect.OpenReply(it.id))
            }
        }
        on<ChatThreadEvent.LocalReadRetryRequested> { if (visible) observeLocal() }
        on<ChatThreadEvent.Entered>(::onEntered)
        on<ChatThreadEvent.LocalPrepared> {
            if (!visible && observation?.isActive != true) observeLocal(it.accessHint)
        }
        on<ChatThreadEvent.Stopped>(::onStopped)
        on<ChatThreadEvent.RouteReleased> {
            onStopped(ChatThreadEvent.Stopped)
            departure?.cancel()
            departure = null
            departureEffect = null
            editing.close()
            forwarding.close()
            clearSelection()
            dismissDeletion()
            scrollToLatest = false
            discardPendingEffects()
            updateState { it.copy(draftDialog = null, leaving = false) }
        }
        on<ChatThreadEvent.ViewportChanged>(::onViewportChanged)
        on<ChatThreadEvent.BackRequested>(::onBackRequested)
        on<ChatThreadEvent.SetupRequested>(::onSetupRequested)
        on<ChatThreadEvent.RefreshRequested>(::onRefreshRequested)
        on<ChatThreadEvent.OlderRequested>(::onOlderRequested)
        on<ChatThreadEvent.NewerRequested>(::onNewerRequested)
        on<ChatThreadEvent.LatestRequested>(::onLatestRequested)
        on<ChatThreadEvent.DraftChanged>(::onDraftChanged)
        on<ChatThreadEvent.SendRequested>(::onSendRequested)
        on<ChatThreadEvent.DraftRetryRequested>(::onDraftRetryRequested)
        on<ChatThreadEvent.MessageRetryRequested>(::onMessageRetryRequested)
        on<ChatThreadEvent.DraftDialogDismissed>(::onDraftDialogDismissed)
        on<ChatThreadEvent.DraftDialogConfirmed>(::onDraftDialogConfirmed)
        on<ChatThreadEvent.CancelRequested>(::onCancelRequested)
        on<ChatThreadEvent.CancellationConfirmed>(::onCancellationConfirmed)
        on<ChatThreadEvent.CancellationDismissed>(::onCancellationDismissed)
        on<ChatThreadEvent.MessageSelected>(::onMessageSelected)
        on<ChatThreadEvent.SelectionCleared> { clearSelection() }
        on<ChatThreadEvent.SelectionMenuOpened> {
            if (visible && state.value.selecting) updateState { it.copy(selectionMenuOpen = true) }
        }
        on<ChatThreadEvent.SelectionMenuDismissed> {
            updateState { it.copy(selectionMenuOpen = false) }
        }
        on<ChatThreadEvent.EditSelected> { openEditing() }
        on<ChatThreadEvent.EditChanged> { if (visible) editing.change(it.text) }
        on<ChatThreadEvent.EditSaveRequested> {
            if (visible && state.value.canEditInput) editing.save()
        }
        on<ChatThreadEvent.EditCancelled> {
            editing.close()
            updateState { it.copy(editFailure = null) }
        }
        on<ChatThreadEvent.EditFailureDismissed> { updateState { it.copy(editFailure = null) } }
        on<ChatThreadEvent.FailedEditsDismissRequested> { dismissFailedEdits() }
        on<ChatThreadEvent.CopyFailedEdits> { copyFailedEdits() }
        on<ChatThreadEvent.CopySelected>(::onCopySelected)
        on<ChatThreadEvent.ForwardSelected> { openForwarding() }
        on<ChatThreadEvent.ForwardDismissed> { forwarding.close() }
        on<ChatThreadEvent.ForwardConfirmed> { if (visible) forwarding.confirm() }
        on<ChatThreadEvent.ForwardTargetsRetryRequested> { if (visible) forwarding.load() }
        on<ChatThreadEvent.ForwardSearchChanged> { forwarding.search(it.query) }
        on<ChatThreadEvent.ForwardTargetSelected> { if (visible) forwarding.select(it.id) }
        on<ChatThreadEvent.ReplySelected>(::onReplySelected)
        on<ChatThreadEvent.ReplyCleared>(::onReplyCleared)
        on<ChatThreadEvent.DeleteSelected>(::onDeleteSelected)
        on<ChatThreadEvent.DeletionConfirmed>(::onDeletionConfirmed)
        on<ChatThreadEvent.DeletionDismissed> { dismissDeletion() }
    }

    private fun onEntered(event: ChatThreadEvent.Entered) {
        if (visible) return
        visible = true
        forwarding.resume()
        reconnectAttempt = 0
        if (observation?.isActive != true || (context == null && state.value.localFailure != null))
            observeLocal(event.accessHint)
        else if (context != null) connect()
    }

    private fun observeLocal(accessHint: CollaborationLocalAccess? = null) {
        observation?.cancel()
        observation =
            viewModelScope.launch {
                observeAccess(workspaceId)
                    // This only chooses the private read scope. The thread use case checks the
                    // captured session and fresh workspace/admission before publishing a snapshot.
                    .onStart { accessHint?.let { emit(AppResult.Success(it)) } }
                    .distinctUntilChanged()
                    .collectLatest { result ->
                        ensureActive()
                        if (result is AppResult.Failed) {
                            restrict(result.failure)
                            return@collectLatest
                        }
                        val owned = (result as AppResult.Success).value
                        if (access != owned) clearScope()
                        access = owned
                        coroutineScope {
                            val firstPage = CompletableDeferred<Unit>()
                            launch {
                                firstPage.await()
                                observeComposer(owned)
                            }
                            launch {
                                firstPage.await()
                                observeUnsentReplies(owned)
                            }
                            launch { observeVisibleRows(owned) }
                            launch { observeQuotePreviews(owned) }
                            observePages(owned, firstPage)
                        }
                    }
            }
    }

    private suspend fun observeUnsentReplies(owned: CollaborationLocalAccess) {
        observeReplies(workspaceId).collect { result ->
            currentCoroutineContext().ensureActive()
            if (access != owned) return@collect
            when (result) {
                is AppResult.Failed ->
                    updateState {
                        it.copy(unsentReplies = emptyList(), unsentFailure = result.failure)
                    }
                is AppResult.Success ->
                    if (result.value.access == owned)
                        updateState {
                            it.copy(
                                unsentReplies =
                                    result.value.items
                                        .filter { reply ->
                                            reply.needsAttention &&
                                                reply.input.candidate.action.target
                                                    .conversationId == conversationId
                                        }
                                        .sortedByDescending { reply -> reply.input.sequence }
                                        .map { reply -> reply.unsentDisplay() },
                                unsentFailure = null,
                            )
                        }
            }
        }
    }

    private suspend fun observePages(
        owned: CollaborationLocalAccess,
        firstPage: CompletableDeferred<Unit>,
    ) {
        window
            .flatMapLatest { selected ->
                observeThread(
                        workspaceId,
                        owned,
                        conversationId,
                        CollaborationHistoryWindow(selected.after, selected.before, 150, 50),
                    )
                    .map { selected to it }
            }
            .buffer(0)
            .collectLatest { (selected, loaded) ->
                currentCoroutineContext().ensureActive()
                if (access != owned || selected != window.value) return@collectLatest
                if (loaded is AppResult.Failed) {
                    restrict(loaded.failure)
                    return@collectLatest
                }
                val value = (loaded as AppResult.Success).value
                if (value.owner.localListAccess() != owned) {
                    restrict(changedThread())
                    return@collectLatest
                }
                val snapshot = value.value
                val page = snapshot.history
                // Once reverse traversal reaches the head, observe the live head instead of keeping
                // a historical upper boundary. Empty windows after replacement recover the same
                // way.
                if (
                    (selected.before != null && page.previous == null) ||
                        (!selected.latest && page.messages.isEmpty())
                ) {
                    selectWindow(ChatThreadLocalWindow())
                    return@collectLatest
                }
                val commands = snapshot.commands
                val outcomes =
                    readEditOutcomes(
                        workspaceId,
                        owned,
                        conversationId,
                        page.roomId,
                        commands,
                        page.messages,
                    )
                currentCoroutineContext().ensureActive()
                if (access != owned || selected != window.value) return@collectLatest
                if (
                    outcomes is AppResult.Failed &&
                        collaborationContentResponseClosesRead(outcomes.failure)
                ) {
                    restrict(outcomes.failure)
                    return@collectLatest
                }
                context = snapshot.context.copy(self = context?.self ?: snapshot.context.self)
                updateState {
                    it.copy(
                        title = snapshot.context.summary.peer.displayName,
                        recipientUnavailable = snapshot.recipientUnavailable,
                        notificationOwner = value.owner,
                        localFailure = null,
                        readViewportToken = it.readViewportToken ?: Uuid.random().toString(),
                    )
                }
                editOutcomes = (outcomes as? AppResult.Success)?.value.orEmpty()
                if (outcomes is AppResult.Failed)
                    updateState { it.copy(editFailure = outcomes.failure) }
                if (historyRoomId != page.roomId) readTracker.clear()
                historyRoomId = page.roomId
                history = page.messages
                val newlyQueued =
                    commands.any { command -> pending.none { it.intent.id == command.intent.id } }
                pending = commands
                val represented =
                    composerProgress.visibleComposerOriginals(commands, page.messages, page.roomId)
                watchedComposerOriginals = watchedComposerOriginals.filterNot { it in represented }
                composer.visible(represented)
                onComposerStateChanged(composer.state)
                next = page.next
                previous = page.previous
                displayedWindow = selected
                historyLoading = false
                project()
                firstPage.complete(Unit)
                if (
                    visible &&
                        reconnection?.isActive != true &&
                        (connection == null ||
                            (newlyQueued &&
                                state.value.connection == ChatThreadConnection.WAITING &&
                                connection?.isActive != true))
                )
                    connect()
                if (scrollToLatest && selected.latest) {
                    scrollToLatest = false
                    emitEffect(ChatThreadEffect.ScrollToNewest)
                }
            }
    }

    private fun clearScope() {
        editing.close()
        editAcknowledgement?.cancel()
        editAcknowledgement = null
        editOutcomes = emptyList()
        updateState { it.copy(selectionMenuOpen = false, recipientUnavailable = false) }
        forwarding.close()
        projection.value = null
        quoteRequests.value = null
        dismissDeletion()
        readTracker.clear()
        viewportKeys = emptyList()
        historyRoomId = null
        dismissCancellation()
        reconnection?.cancel()
        reconnection = null
        reconnectAttempt = 0
        connection?.cancel()
        connection = null
        submission?.cancel()
        submission = null
        pulse = null
        wakeRequested = false
        olderRequested = false
        olderPosition = CollaborationOlderHistoryPosition()
        olderScanned = false
        olderKey = null
        olderRecoveryAnchor = null
        access = null
        context = null
        pending = emptyList()
        history = emptyList()
        timeline = emptyList()
        source = null
        next = null
        previous = null
        window.value = ChatThreadLocalWindow()
        displayedWindow = ChatThreadLocalWindow()
        historyLoading = true
        scrollToLatest = false
        localSave?.cancel()
        localSave = null
        departure?.cancel()
        departure = null
        departureEffect = null
        composerProgress = emptyList()
        watchedComposerOriginals = emptyList()
        composerProgressReady = false
        composerRetry?.cancel()
        composerRetry = null
        composer.clear()
        updateState { ChatThreadState() }
    }

    private fun restrict(failure: Failure) {
        clearScope()
        updateState { ChatThreadState(loading = false, localFailure = failure) }
    }

    private fun project() {
        val owned = access ?: return
        val selectedCancellation = cancellationSelection
        val dialog = state.value.cancellationDialog
        if (
            selectedCancellation != null &&
                dialog != null &&
                !dialog.busy &&
                dialog.failure == null &&
                (if (selectedCancellation.original != null) {
                    val record =
                        composerProgress
                            .firstOrNull { it.original == selectedCancellation.original }
                            ?.record
                            ?: pending.firstOrNull {
                                it.intent.id == selectedCancellation.original.id
                            }
                    record != null && !validCollaborationCancellation(record)
                } else selectedCancellation.record !in pending)
        ) {
            updateState { it.copy(cancellationDialog = dialog.copy(unavailable = true)) }
        }
        when (
            val rows =
                chatThreadRows(
                    owned.messageOwner(),
                    conversationId,
                    context?.self,
                    source,
                    history,
                    pending,
                    timeline,
                    includeUnmatched = displayedWindow.latest,
                    editOutcomes = editOutcomes,
                    historyRoomId = historyRoomId,
                    composer = composerProgress,
                    provisional = composer.state.submissions,
                )
        ) {
            is AppResult.Failed -> {
                projection.value = null
                updateState {
                    it.copy(rows = emptyList(), loading = false, localFailure = rows.failure)
                }
            }
            is AppResult.Success -> {
                val candidate =
                    ChatVisibilityProjection(
                        owned,
                        historyRoomId
                            ?: source?.roomId
                            ?: pending
                                .mapNotNull {
                                    (it.state as? CollaborationMessageState.Sent)?.device?.roomId
                                }
                                .distinct()
                                .singleOrNull(),
                        rows.value,
                        historyLoading,
                        next != null || (source != null && !olderScanned),
                        previous != null,
                        !displayedWindow.latest,
                    )
                projection.value = candidate
                if (candidate.rows.none { !it.visibilityKnown && it.reference != null })
                    publishRows(candidate, emptySet())
            }
        }
        updateReadViewport()
    }

    private suspend fun observeVisibleRows(owned: CollaborationLocalAccess) {
        projection
            .flatMapLatest { candidate ->
                if (candidate == null) return@flatMapLatest emptyFlow()
                val references =
                    candidate.rows
                        .filter { !it.visibilityKnown }
                        .mapNotNull { it.reference }
                        .distinct()
                when {
                    candidate.access != owned || references.isEmpty() -> emptyFlow()
                    candidate.roomId == null ->
                        flowOf(candidate to AppResult.Failed(changedThread()))
                    else ->
                        observeHidden(
                                workspaceId,
                                owned,
                                conversationId,
                                candidate.roomId,
                                references,
                            )
                            .map { candidate to it }
                }
            }
            .buffer(0)
            .collect { (candidate, result) ->
                currentCoroutineContext().ensureActive()
                if (access != owned || projection.value != candidate) return@collect
                when (result) {
                    is AppResult.Success -> publishRows(candidate, result.value)
                    is AppResult.Failed -> {
                        if (collaborationContentResponseClosesRead(result.failure))
                            restrict(result.failure)
                        else
                            updateState {
                                it.copy(
                                    rows = emptyList(),
                                    loading = false,
                                    localFailure = result.failure,
                                )
                            }
                    }
                }
            }
    }

    private fun publishRows(
        candidate: ChatVisibilityProjection,
        hidden: Set<CollaborationMessageReference>,
    ) {
        if (access != candidate.access || projection.value != candidate) return
        val rows = candidate.rows.filter { it.reference !in hidden }
        updateState { current ->
            val selected =
                current.selectedMessages
                    .map { old -> rows.firstOrNull { sameChatMessage(old, it) } ?: old }
                    .distinctBy { it.key }
            current.copy(
                rows = rows,
                selfSender = context?.self?.matrixUserId,
                loading = candidate.loading,
                hasOlder = candidate.hasOlder,
                hasNewer = candidate.hasNewer,
                browsingHistory = candidate.browsingHistory,
                localFailure = null,
                selectedMessages = selected,
            )
        }
        updateReadViewport()
        requestQuotePreviews()
    }

    private fun onMessageSelected(event: ChatThreadEvent.MessageSelected) {
        if (state.value.editing != null) return
        editing.cancelPreparation()
        val current = state.value
        if (
            !visible ||
                current.leaving ||
                current.localFailure != null ||
                current.deletionDialog != null ||
                current.cancellationDialog != null ||
                current.draftDialog != null
        )
            return
        val row = current.rows.singleOrNull { it.key == event.key } ?: return
        updateState {
            it.copy(selectionTime = Clock.System.now().toEpochMilliseconds(), editFailure = null)
        }
        val old = current.selectedMessages
        val existing = old.firstOrNull { sameChatMessage(it, row) }
        if (existing != null)
            updateState {
                it.copy(
                    selectedMessages = old - existing,
                    selecting = old.size > 1,
                    selectionLimitReached = false,
                )
            }
        else if (old.size < COLLABORATION_LOCAL_DELETE_LIMIT)
            updateState {
                it.copy(
                    selectedMessages = old + row,
                    selecting = true,
                    selectionLimitReached = false,
                )
            }
        else updateState { it.copy(selectionLimitReached = true) }
    }

    private fun clearSelection() {
        editing.cancelPreparation()
        updateState { it.copy(selectionMenuOpen = false) }
        updateState {
            it.copy(
                selectedMessages = emptyList(),
                selecting = false,
                selectionLimitReached = false,
            )
        }
    }

    private fun onReplySelected(event: ChatThreadEvent.ReplySelected) {
        val current = state.value
        if (!visible || !current.canReplySelection) return
        val target = current.selectedMessages.single().reference?.eventId ?: return
        updateState {
            it.copy(
                draftReplyToEventId = target,
                selecting = false,
                selectedMessages = emptyList(),
                selectionLimitReached = false,
            )
        }
        requestQuotePreviews()
        composer.change(replyToEventId = target, immediate = true)
        viewModelScope.launch { emitEffect(ChatThreadEffect.FocusComposer) }
    }

    private fun onReplyCleared(event: ChatThreadEvent.ReplyCleared) {
        if (!visible || !state.value.canCompose) return
        composer.change(replyToEventId = null, immediate = true)
        requestQuotePreviews()
    }

    private fun requestQuotePreviews() {
        val current = state.value
        val owned = access
        val room = projection.value?.roomId ?: historyRoomId
        if (owned == null || room == null || current.localFailure != null) {
            quoteRequests.value = null
            updateState { it.copy(quotes = emptyList()) }
            return
        }
        val rows =
            if (viewportKeys.isEmpty()) current.rows.take(20)
            else current.rows.filter { it.key in viewportKeys }
        val targets =
            (listOfNotNull(current.draftReplyToEventId) + rows.mapNotNull { it.replyToEventId })
                .distinct()
                .take(128)
        if (targets.isEmpty()) {
            quoteRequests.value = null
            updateState { it.copy(quotes = emptyList()) }
        } else {
            quoteRequests.value = ChatQuoteRequest(owned, room, targets)
            updateState { it.copy(quotes = it.quotes.filter { quote -> quote.eventId in targets }) }
        }
    }

    private suspend fun observeQuotePreviews(owned: CollaborationLocalAccess) {
        quoteRequests
            .flatMapLatest { request ->
                if (request == null || request.access != owned) emptyFlow()
                else
                    observeQuotes(
                            workspaceId,
                            owned,
                            conversationId,
                            request.roomId,
                            request.eventIds,
                        )
                        .map { request to it }
            }
            .buffer(0)
            .collect { (request, result) ->
                currentCoroutineContext().ensureActive()
                if (access != owned || quoteRequests.value != request) return@collect
                when (result) {
                    is AppResult.Success -> updateState { it.copy(quotes = result.value) }
                    is AppResult.Failed ->
                        if (collaborationContentResponseClosesRead(result.failure))
                            restrict(result.failure)
                        else updateState { it.copy(quotes = emptyList()) }
                }
            }
    }

    private fun onCopySelected(event: ChatThreadEvent.CopySelected) {
        val current = state.value
        if (!current.canCopySelection) return
        val text =
            current.selectedMessages
                .sortedWith(compareBy<ChatMessageRow> { it.timestamp }.thenBy { it.key })
                .joinToString("\n\n") { it.text.orEmpty() }
        copyText(text)
    }

    private fun copyFailedEdits() {
        val current = state.value
        if (!current.canCopyFailedEdits) return
        copyText(current.selectedMessages.single().failedEditText ?: return)
    }

    private fun copyText(text: String) {
        val current = state.value
        val owned = access ?: return
        val token = current.readViewportToken ?: return
        if (!visible || current.localFailure != null || current.deletionDialog != null) return
        viewModelScope.launch { emitEffect(ChatThreadEffect.CopyMessages(owned, token, text)) }
        clearSelection()
    }

    private fun openEditing() {
        val current = state.value
        val owned = access ?: return
        val room = historyRoomId ?: return
        if (!visible || !current.canEditSelection) return
        val reference = current.selectedMessages.single().reference ?: return
        updateState { it.copy(selectionMenuOpen = false, editFailure = null) }
        editing.open(owned, room, reference)
    }

    private fun dismissFailedEdits() {
        val current = state.value
        val owned = access ?: return
        val room = historyRoomId ?: return
        if (!visible || !current.canDismissFailedEdits || editAcknowledgement?.isActive == true)
            return
        val row = current.selectedMessages.single()
        val reference = row.reference ?: return
        val ids = row.failedEditIds.toList()
        if (ids.size !in 1..128) return
        updateState {
            it.copy(editAcknowledging = true, selectionMenuOpen = false, editFailure = null)
        }
        editAcknowledgement =
            viewModelScope.launch {
                for (batch in ids.chunked(32)) {
                    val result =
                        retireMessages(
                            workspaceId,
                            conversationId,
                            CollaborationEditAcknowledgement(room, reference.eventId, batch),
                            owned,
                        )
                    currentCoroutineContext().ensureActive()
                    if (access != owned || !visible) return@launch
                    if (result is AppResult.Failed) {
                        if (collaborationContentResponseClosesRead(result.failure))
                            restrict(result.failure)
                        else
                            updateState {
                                it.copy(editAcknowledging = false, editFailure = result.failure)
                            }
                        return@launch
                    }
                }
                updateState { it.copy(editAcknowledging = false, editFailure = null) }
                if (state.value.selectedMessages.singleOrNull()?.reference == reference)
                    clearSelection()
            }
    }

    private fun openForwarding() {
        val current = state.value
        val owned = access ?: return
        val room = historyRoomId ?: source?.roomId ?: return
        if (
            !visible ||
                current.leaving ||
                current.localFailure != null ||
                current.deletionDialog != null ||
                current.forwarding != null ||
                !current.canForwardSelection
        )
            return
        val messages =
            current.selectedMessages
                .sortedWith(compareBy<ChatMessageRow> { it.timestamp }.thenBy { it.key })
                .map {
                    CollaborationForwardSource(
                        requireNotNull(it.reference),
                        requireNotNull(it.text),
                    )
                }
        viewportKeys = emptyList()
        readTracker.clear()
        forwarding.open(owned, conversationId, room, messages)
    }

    /** The route checks fresh ownership immediately before writing to the platform clipboard. */
    suspend fun confirmCopy(effect: ChatThreadEffect.CopyMessages): Boolean {
        if (!visible || effect.owner != access || effect.token != state.value.readViewportToken)
            return false
        val result = withTimeoutOrNull(5_000) { readContext(workspaceId, conversationId) }
        currentCoroutineContext().ensureActive()
        return result is AppResult.Success &&
            result.value.owner.localListAccess() == effect.owner &&
            visible &&
            access == effect.owner &&
            state.value.readViewportToken == effect.token &&
            state.value.localFailure == null
    }

    private fun onDeleteSelected(event: ChatThreadEvent.DeleteSelected) {
        val owned = access ?: return
        val room = projection.value?.roomId ?: return
        val current = state.value
        if (
            !visible ||
                current.leaving ||
                current.localFailure != null ||
                current.deletionDialog != null ||
                !current.canDeleteSelection
        )
            return
        val references = current.selectedMessages.mapNotNull { it.reference }.distinct()
        deletionSelection = ChatDeletionSelection(owned, room, references)
        updateState { it.copy(deletionDialog = ChatDeletionDialog(references.size)) }
    }

    private fun onDeletionConfirmed(event: ChatThreadEvent.DeletionConfirmed) {
        val selected = deletionSelection ?: return
        val dialog = state.value.deletionDialog ?: return
        if (
            !visible || dialog.busy || access != selected.access || state.value.localFailure != null
        )
            return
        updateState { it.copy(deletionDialog = dialog.copy(busy = true, failure = null)) }
        deletionJob =
            viewModelScope.launch {
                val result =
                    deleteForMe(
                        workspaceId,
                        selected.access,
                        conversationId,
                        selected.roomId,
                        selected.messages,
                    )
                ensureActive()
                if (selected !== deletionSelection || access != selected.access) return@launch
                deletionJob = null
                when (result) {
                    is AppResult.Success -> {
                        deletionSelection = null
                        updateState {
                            it.copy(
                                deletionDialog = null,
                                selectedMessages = emptyList(),
                                selecting = false,
                                selectionLimitReached = false,
                            )
                        }
                    }
                    is AppResult.Failed ->
                        if (collaborationContentResponseClosesRead(result.failure))
                            restrict(result.failure)
                        else
                            updateState {
                                it.copy(deletionDialog = dialog.copy(failure = result.failure))
                            }
                }
            }
    }

    private fun dismissDeletion() {
        deletionJob?.cancel()
        deletionJob = null
        deletionSelection = null
        updateState { it.copy(deletionDialog = null) }
    }

    private fun onViewportChanged(event: ChatThreadEvent.ViewportChanged) {
        if (event.token == null || event.token != state.value.readViewportToken) return
        viewportKeys = if (event.rowKeys.size <= 128) event.rowKeys.toList() else emptyList()
        updateReadViewport()
        requestQuotePreviews()
    }

    private fun updateReadViewport(retry: Boolean = false) {
        val current = state.value
        val owned = access
        val room = historyRoomId
        val admitted =
            visible &&
                owned != null &&
                room != null &&
                current.localFailure == null &&
                !current.leaving &&
                current.forwarding == null &&
                current.draftDialog == null &&
                current.cancellationDialog == null
        val selected =
            if (!admitted) emptyList()
            else
                current.rows
                    .filter {
                        it.key in viewportKeys &&
                            it.ownership == ChatMessageOwnership.COLLEAGUE &&
                            it.kind in
                                setOf(
                                    CollaborationMessageKind.TEXT,
                                    CollaborationMessageKind.REDACTED,
                                ) &&
                            history.any { original -> original.primaryEventId == it.eventId }
                    }
                    .mapNotNull { it.eventId }
                    .distinct()
        readTracker.update(
            if (selected.isEmpty() || owned == null || room == null) null
            else ChatThreadReadSelection(owned, conversationId, room, selected),
            retry,
        )
    }

    private fun onStopped(event: ChatThreadEvent.Stopped) {
        editing.pause()
        editAcknowledgement?.cancel()
        editAcknowledgement = null
        updateState { it.copy(editAcknowledging = false) }
        forwarding.pause()
        visible = false
        viewportKeys = emptyList()
        readTracker.update(null)
        dismissCancellation()
        reconnection?.cancel()
        reconnection = null
        reconnectAttempt = 0
        connection?.cancel()
        connection = null
        submission?.cancel()
        submission = null
        pulse = null
        timeline = emptyList()
        source = null
        olderRequested = false
        updateState {
            it.copy(
                connection = ChatThreadConnection.OFFLINE,
                syncing = false,
                saving = false,
                loadingOlder = false,
            )
        }
        project()
        // Owned local observers stay alive to clear hidden content after account or admission loss.
    }

    private fun onBackRequested(event: ChatThreadEvent.BackRequested) {
        if (state.value.editing != null) {
            editing.close()
            updateState { it.copy(editFailure = null) }
            return
        }
        if (state.value.forwarding != null) {
            forwarding.close()
            return
        }
        if (state.value.deletionDialog != null) {
            dismissDeletion()
            return
        }
        if (state.value.cancellationDialog != null) {
            dismissCancellation()
            return
        }
        if (state.value.selecting) {
            clearSelection()
            return
        }
        leaveThread(ChatThreadEffect.Back)
    }

    private fun onSetupRequested(event: ChatThreadEvent.SetupRequested) {
        dismissDeletion()
        clearSelection()
        dismissCancellation()
        leaveThread(ChatThreadEffect.Setup)
    }

    private fun onCancelRequested(event: ChatThreadEvent.CancelRequested) {
        val owned = access ?: return
        val current = state.value
        if (
            !visible ||
                current.leaving ||
                current.localFailure != null ||
                current.saving ||
                current.draftDialog != null ||
                current.cancellationDialog != null
        )
            return
        val row = current.rows.singleOrNull { it.localId == event.id && it.canCancel } ?: return
        val original =
            row.composerSubmission
                ?: composerProgress.firstOrNull { it.original.id == event.id }?.original
        if (original != null) {
            cancellationSelection = ChatCancellationSelection(owned, original = original)
            updateState {
                it.copy(cancellationDialog = ChatCancellationDialog(original.original.text))
            }
        } else {
            val record = pending.singleOrNull { it.intent.id == event.id } ?: return
            if (
                record.intent.owner != owned.messageOwner() ||
                    !validCollaborationCancellation(record)
            )
                return
            cancellationSelection = ChatCancellationSelection(owned, record)
            updateState { it.copy(cancellationDialog = ChatCancellationDialog(record.intent.text)) }
        }
    }

    private fun onCancellationConfirmed(event: ChatThreadEvent.CancellationConfirmed) {
        val selected = cancellationSelection ?: return
        val dialog = state.value.cancellationDialog ?: return
        if (
            !visible ||
                dialog.busy ||
                dialog.unavailable ||
                state.value.localFailure != null ||
                selected.access != access
        )
            return
        updateState { it.copy(cancellationDialog = dialog.copy(busy = true, failure = null)) }
        cancellationJob =
            viewModelScope.launch {
                val result =
                    withTimeoutOrNull(5_000) {
                        val original = selected.original
                        if (original == null)
                            cancelText(
                                workspaceId,
                                selected.access,
                                requireNotNull(selected.record),
                            )
                        else if (composer.discardUnsubmitted(original)) AppResult.Success(Unit)
                        else if (!composer.settled(original)) AppResult.Failed(changedThread())
                        else cancelComposer(workspaceId, selected.access, original)
                    }
                        ?: AppResult.Failed(
                            Failure(
                                FailureKind.Unavailable,
                                "Cancellation is unconfirmed.",
                                code = "client.collaboration_cancellation_pending",
                            )
                        )
                ensureActive()
                if (selected !== cancellationSelection || selected.access != access) return@launch
                cancellationJob = null
                when (result) {
                    is AppResult.Success -> {
                        if (selected.original != null) composer.retry()
                        cancellationSelection = null
                        clearSelection()
                        updateState { it.copy(cancellationDialog = null) }
                    }
                    is AppResult.Failed -> {
                        if (collaborationContentResponseClosesRead(result.failure))
                            restrict(result.failure)
                        else
                            updateState {
                                it.copy(
                                    cancellationDialog =
                                        dialog.copy(
                                            busy = false,
                                            failure = result.failure,
                                            unavailable =
                                                result.failure.code ==
                                                    "client.collaboration_cancellation_unavailable",
                                        )
                                )
                            }
                    }
                }
            }
    }

    private fun onCancellationDismissed(event: ChatThreadEvent.CancellationDismissed) {
        dismissCancellation()
    }

    private fun dismissCancellation() {
        cancellationJob?.cancel()
        cancellationJob = null
        cancellationSelection = null
        updateState { it.copy(cancellationDialog = null) }
    }

    override fun onCleared() {
        forwarding.close()
        readTracker.clear()
        dismissCancellation()
        super.onCleared()
    }

    private fun onRefreshRequested(event: ChatThreadEvent.RefreshRequested) {
        if (visible) updateReadViewport(retry = true)
        if (
            !visible ||
                state.value.connection == ChatThreadConnection.CONNECTING ||
                state.value.syncing ||
                state.value.loadingOlder
        )
            return
        val localFailure = state.value.localFailure
        if (localFailure != null && !collaborationContentResponseClosesRead(localFailure)) {
            observeLocal()
            return
        }
        if (access == null || context == null) {
            connection?.cancel()
            connection =
                viewModelScope.launch {
                    val result = loadSetup(workspaceId)
                    ensureActive()
                    if (result is AppResult.Failed)
                        updateState { it.copy(failure = result.failure) }
                    else observeLocal()
                }
        } else if (
            (state.value.historyFailure ?: state.value.failure)?.code ==
                "client.collaboration_history_focus_capacity"
        ) {
            connection?.cancel()
            connection = null
            pulse = null
            timeline = emptyList()
            source = null
            project()
            connect(retryRoom = true, recoverHistory = true)
        } else {
            if (state.value.historyIncomplete || state.value.historyFailure != null) {
                olderPosition = CollaborationOlderHistoryPosition()
                olderScanned = false
                olderRequested = true
                updateState { it.copy(historyIncomplete = false, historyFailure = null) }
            }
            if (pulse != null) requestSync() else connect(retryRoom = true)
        }
    }

    private fun onOlderRequested(event: ChatThreadEvent.OlderRequested) {
        if (!visible || state.value.localFailure != null || state.value.loading) return
        if (state.value.loadingOlder || olderRequested) return
        val after = next
        if (after != null) {
            selectWindow(ChatThreadLocalWindow(after = after))
        } else if (source != null && pulse != null && !olderScanned) {
            olderRequested = true
            updateState { it.copy(loadingOlder = true, historyFailure = null) }
            pulse?.trySend(Unit)
        }
    }

    private fun onNewerRequested(event: ChatThreadEvent.NewerRequested) {
        if (
            !visible ||
                state.value.localFailure != null ||
                state.value.loading ||
                state.value.loadingOlder
        )
            return
        previous?.let { selectWindow(ChatThreadLocalWindow(before = it)) }
    }

    private fun onLatestRequested(event: ChatThreadEvent.LatestRequested) {
        if (!visible || state.value.localFailure != null) return
        showLatest()
    }

    private fun showLatest() {
        if (window.value.latest && displayedWindow.latest && !historyLoading) {
            viewModelScope.launch { emitEffect(ChatThreadEffect.ScrollToNewest) }
        } else {
            scrollToLatest = true
            selectWindow(ChatThreadLocalWindow())
        }
    }

    private fun selectWindow(selected: ChatThreadLocalWindow) {
        if (window.value == selected) return
        historyLoading = true
        updateState { it.copy(loading = true) }
        window.value = selected
    }

    private fun onComposerStateChanged(value: ChatComposerEditingState) {
        val represented = composerProgress.visibleComposerOriginals(pending, history, historyRoomId)
        if (value.submissions.any { it.durable && it.original in represented }) {
            composer.visible(represented)
            return
        }

        // Route-owned continuity across independently emitted outbox/command snapshots.
        // Keep the current gestures and the newest bounded local window under observation.
        val captured = value.submissions.map { it.original }
        watchedComposerOriginals =
            watchedComposerOriginals
                .filterNot { it in represented || it in captured }
                .sortedByDescending { it.submittedAt }
                .take(128 - captured.size)
        composerOriginals.value = (captured + watchedComposerOriginals).sortedBy { it.id }
        updateState {
            it.copy(
                draftReady = value.ready,
                draft = value.text,
                draftReplyToEventId = value.replyToEventId,
                submissionPending = value.legacyPending,
                draftSaving = value.saving,
                draftFailure = value.failure,
                composerCanSend =
                    composerProgressReady &&
                        value.submissions.count { pending -> !pending.durable } < 16 &&
                        composerOriginals.value.size < 128,
            )
        }
        if (value.failure?.let(::collaborationContentResponseClosesRead) == true)
            restrict(requireNotNull(value.failure))
        else {
            project()
            requestQuotePreviews()
        }
    }

    private suspend fun observeComposer(owned: CollaborationLocalAccess) = coroutineScope {
        launch {
            observeComposerProgress(workspaceId, owned, composerOriginals).collect { result ->
                ensureActive()
                if (access != owned) return@collect
                when (result) {
                    is AppResult.Failed -> reportDraftFailure(result.failure)
                    is AppResult.Success -> {
                        composerProgress =
                            result.value.filter {
                                it.original.original.conversationId == conversationId
                            }
                        composerProgressReady = true
                        watchedComposerOriginals =
                            (watchedComposerOriginals + composerProgress.map { it.original })
                                .distinct()
                        onComposerStateChanged(composer.state)
                    }
                }
            }
        }
        observeDraft(workspaceId, conversationId, owned).collect { result ->
            ensureActive()
            if (access != owned) return@collect
            when (result) {
                is AppResult.Failed -> reportDraftFailure(result.failure)
                is AppResult.Success -> composer.bind(owned, result.value)
            }
            requestQuotePreviews()
        }
    }

    private fun onDraftChanged(event: ChatThreadEvent.DraftChanged) {
        if (visible && state.value.canCompose) composer.change(event.text)
    }

    private suspend fun flushDraft(): Boolean =
        measureOperation(
            PerformanceOperation.DraftPersistence,
            classify = { if (it) PerformanceOutcome.Succeeded else PerformanceOutcome.Failed },
        ) {
            state.value.localFailure == null && composer.flush()
        }

    private fun reportDraftFailure(failure: Failure) {
        if (collaborationContentResponseClosesRead(failure)) {
            restrict(failure)
        } else updateState { it.copy(draftSaving = false, saving = false, draftFailure = failure) }
    }

    private fun onDraftRetryRequested(event: ChatThreadEvent.DraftRetryRequested) {
        if (state.value.cancellationDialog != null) return
        if (!visible || state.value.saving || state.value.draftSaving || state.value.leaving) return
        if (state.value.draftFailure?.code == "client.collaboration_draft_changed") {
            updateState { it.copy(draftDialog = ChatDraftDialog.RELOAD) }
            return
        }
        updateState { it.copy(draftFailure = null) }
        if (!state.value.draftReady) observeLocal() else composer.retry()
    }

    private fun onMessageRetryRequested(event: ChatThreadEvent.MessageRetryRequested) {
        val owned = access ?: return
        if (!visible || state.value.localFailure != null || composerRetry?.isActive == true) return
        val original =
            state.value.rows
                .singleOrNull {
                    it.localId == event.id && it.delivery == CollaborationMessageDelivery.FAILED
                }
                ?.composerSubmission ?: return
        clearSelection()
        if (composer.state.submissions.any { it.original == original && !it.durable }) {
            composer.retry()
            return
        }
        composerRetry =
            viewModelScope.launch {
                val result = recoverComposer.retry(workspaceId, owned, original)
                ensureActive()
                if (access != owned) return@launch
                if (
                    result is AppResult.Failed &&
                        collaborationContentResponseClosesRead(result.failure)
                ) {
                    restrict(result.failure)
                } else if (result is AppResult.Success && visible && pulse == null) connect()
            }
    }

    private fun onDraftDialogDismissed(event: ChatThreadEvent.DraftDialogDismissed) {
        updateState { it.copy(draftDialog = null) }
        departureEffect = null
    }

    private fun onDraftDialogConfirmed(event: ChatThreadEvent.DraftDialogConfirmed) {
        if (
            !visible ||
                state.value.saving ||
                (state.value.draftSaving && state.value.draftDialog != ChatDraftDialog.LEAVE)
        )
            return
        when (state.value.draftDialog) {
            ChatDraftDialog.LEAVE -> {
                val effect = departureEffect ?: return
                departureEffect = null
                updateState { it.copy(draftDialog = null) }
                onStopped(ChatThreadEvent.Stopped)
                viewModelScope.launch { emitEffect(effect) }
            }
            ChatDraftDialog.RELOAD -> {
                val owned = access ?: return
                localSave =
                    viewModelScope.launch {
                        updateState {
                            it.copy(draftSaving = true, saving = true, draftDialog = null)
                        }
                        val read = observeDraft(workspaceId, conversationId, owned).first()
                        ensureActive()
                        if (access != owned) return@launch
                        when (read) {
                            is AppResult.Failed -> reportDraftFailure(read.failure)
                            is AppResult.Success -> {
                                composer.reset(owned, read.value)
                                updateState { it.copy(saving = false) }
                                requestQuotePreviews()
                            }
                        }
                    }
            }
            null -> Unit
        }
    }

    private fun leaveThread(effect: ChatThreadEffect) {
        if (!visible || departure?.isActive == true) return
        val owned = access
        departure =
            viewModelScope.launch {
                updateState { it.copy(leaving = true) }
                readTracker.update(null)
                val saved =
                    if (state.value.submissionPending || !state.value.draftReady) true
                    else withTimeoutOrNull(1_500) { flushDraft() } ?: false
                ensureActive()
                if (access != owned) return@launch
                updateState { it.copy(leaving = false) }
                if (!saved) {
                    departureEffect = effect
                    updateState { it.copy(draftDialog = ChatDraftDialog.LEAVE) }
                    return@launch
                }
                onStopped(ChatThreadEvent.Stopped)
                emitEffect(effect)
            }
    }

    private fun onSendRequested(event: ChatThreadEvent.SendRequested) {
        if (!visible || !state.value.canSend) return
        if (state.value.submissionPending) {
            if (submission?.isActive == true) return
            submission = viewModelScope.launch { requestDelivery(workspaceId) }
            return
        }
        if (!validCollaborationText(state.value.draft)) {
            reportDraftFailure(
                Failure(
                    FailureKind.Validation,
                    "Check the message length and characters.",
                    code = "client.collaboration_invalid_message",
                )
            )
            return
        }
        if (composer.send() == null) return
        showLatest()
    }

    private fun requestSync(wake: Boolean = true) {
        wakeRequested = wakeRequested || wake
        pulse?.trySend(Unit)
    }

    private fun connect(retryRoom: Boolean = false, recoverHistory: Boolean = false) {
        if (!visible || context == null || connection?.isActive == true) return
        val owned = access ?: return
        reconnection?.cancel()
        reconnection = null
        updateState { it.copy(connection = ChatThreadConnection.CONNECTING) }
        connection =
            viewModelScope.launch {
                var deviceId: String? = null
                val prepared =
                    withTimeoutOrNull(30_000) {
                        deviceId = prepareConnection(owned, retryRoom, recoverHistory)
                        true
                    }
                ensureActive()
                if (prepared == null)
                    connectionFailed(
                        Failure(
                            FailureKind.Timeout,
                            "Chat connection timed out.",
                            code = "client.collaboration_connection_timeout",
                        )
                    )
                else if (state.value.connection == ChatThreadConnection.CONNECTING) {
                    val ready = deviceId ?: return@launch
                    if (access == owned) observeTimeline(owned, ready)
                }
            }
    }

    private suspend fun prepareConnection(
        owned: CollaborationLocalAccess,
        retryRoom: Boolean,
        recoverHistory: Boolean,
    ): String? {
        val setup = loadSetup(workspaceId)
        currentCoroutineContext().ensureActive()
        if (access != owned) return null
        if (setup is AppResult.Failed) {
            connectionFailed(setup.failure)
            return null
        }
        val device = (setup as AppResult.Success).value.device
        if (device == null || device.state != CollaborationDeviceState.ACTIVE) {
            updateState { it.copy(connection = ChatThreadConnection.SETUP_REQUIRED) }
            return null
        }
        val restored = ensureRuntime(workspaceId, device.id)
        currentCoroutineContext().ensureActive()
        if (restored is AppResult.Failed) {
            connectionFailed(restored.failure)
            return null
        }
        if (!(restored as AppResult.Success).value.value) {
            updateState { it.copy(connection = ChatThreadConnection.SETUP_REQUIRED) }
            return null
        }
        val self = identity.acquire(workspaceId, device.id)
        currentCoroutineContext().ensureActive()
        if (self is AppResult.Failed) {
            connectionFailed(self.failure)
            return null
        }
        context = context?.copy(self = (self as AppResult.Success).value.value ?: context?.self)
        project()
        val ready =
            prepareRoom(workspaceId, owned, conversationId, device.id, device.version, retryRoom)
        currentCoroutineContext().ensureActive()
        when (ready) {
            is AppResult.Failed -> connectionFailed(ready.failure)
            is AppResult.Success ->
                if (ready.value == CollaborationRoomReadiness.READY) {
                    if (recoverHistory && !restoreHistory(device.id)) return null
                    currentCoroutineContext().ensureActive()
                    if (access == owned) {
                        updateState { it.copy(connection = ChatThreadConnection.CONNECTING) }
                        return device.id
                    }
                } else updateState { it.copy(connection = ChatThreadConnection.WAITING) }
        }
        return null
    }

    private suspend fun restoreHistory(deviceId: String): Boolean {
        val result = reopenHistory(workspaceId, deviceId, conversationId, olderRecoveryAnchor)
        currentCoroutineContext().ensureActive()
        if (result is AppResult.Failed) {
            connectionFailed(result.failure)
            return false
        }
        if (
            (result as AppResult.Success).value.value ==
                CollaborationHistoryRuntimePreparation.SetupRequired
        ) {
            updateState { it.copy(connection = ChatThreadConnection.SETUP_REQUIRED) }
            return false
        }
        if (olderRecoveryAnchor != null) olderRequested = true
        olderRecoveryAnchor = null
        return true
    }

    private suspend fun observeTimeline(owned: CollaborationLocalAccess, deviceId: String) {
        val acquired =
            withTimeoutOrNull(30_000) { enter(workspaceId, deviceId, conversationId) }
                ?: AppResult.Failed(
                    Failure(
                        FailureKind.Timeout,
                        "Chat connection timed out.",
                        code = "client.collaboration_connection_timeout",
                    )
                )
        val child = (acquired as? AppResult.Success)?.value
        try {
            currentCoroutineContext().ensureActive()
            if (access != owned) return
            if (acquired is AppResult.Failed) {
                connectionFailed(acquired.failure)
                return
            }
            val lease = requireNotNull(child)
            source = lease.device
            val key =
                CollaborationHistoryTraversal(
                    conversationId,
                    lease.device.roomId,
                    lease.device.deviceId,
                )
            if (olderKey != key) {
                olderPosition = CollaborationOlderHistoryPosition()
                olderScanned = false
                olderRecoveryAnchor = null
                olderKey = key
                updateState { it.copy(historyIncomplete = false, historyFailure = null) }
                val restored = readHistoryEnd(workspaceId, key)
                currentCoroutineContext().ensureActive()
                if (access != owned) return
                when (restored) {
                    is AppResult.Success -> olderScanned = restored.value
                    is AppResult.Failed -> {
                        if (collaborationContentResponseClosesRead(restored.failure)) {
                            restrict(restored.failure)
                            return
                        }
                        updateState { it.copy(historyFailure = restored.failure) }
                    }
                }
            }
            updateState {
                it.copy(connection = ChatThreadConnection.CONNECTED, failure = null, syncing = true)
            }
            project()
            coroutineScope {
                val signals = Channel<Unit>(Channel.CONFLATED)
                pulse = signals
                // Timeline acquisition owns history observation, not delivery recovery. The
                // application worker recovers retained originals independently of this route;
                // reconnecting after admission renewal cannot restart blocked work.
                signals.trySend(Unit)
                val worker = launch { consumeHistorySignals(signals, lease.device) }
                try {
                    lease.updates
                        .takeWhile { result ->
                            ensureActive()
                            when (result) {
                                is AppResult.Failed -> {
                                    connectionFailed(result.failure)
                                    false
                                }
                                is AppResult.Success -> {
                                    if (timeline != result.value) {
                                        val historyChanged =
                                            collaborationTimelineRequiresHistory(
                                                timeline,
                                                result.value,
                                            )
                                        timeline = result.value.toList()
                                        project()
                                        if (historyChanged) signals.trySend(Unit)
                                    }
                                    true
                                }
                            }
                        }
                        .collect()
                } finally {
                    signals.close()
                    if (pulse === signals) pulse = null
                    worker.cancelAndJoin()
                }
            }
            currentCoroutineContext().ensureActive()
            if (access == owned && state.value.connection == ChatThreadConnection.CONNECTED)
                connectionFailed(
                    Failure(
                        FailureKind.Unavailable,
                        "Reconnect to this conversation.",
                        code = "client.collaboration_timeline_stopped",
                    )
                )
        } finally {
            withContext(NonCancellable) { child?.close() }
        }
    }

    private suspend fun consumeHistorySignals(
        signals: Channel<Unit>,
        device: CollaborationMessageDevice,
    ) {
        for (signal in signals) {
            val older = olderRequested
            olderRequested = false
            val wake = wakeRequested
            wakeRequested = false
            if (!wake && !older) delay(400)
            updateState { it.copy(syncing = true, loadingOlder = older || olderRequested) }
            val result = if (older) loadOlder(device) else synchronize(device.deviceId, wake)
            currentCoroutineContext().ensureActive()
            updateState {
                if (older)
                    it.copy(
                        syncing = false,
                        loadingOlder = olderRequested,
                        historyFailure = (result as? AppResult.Failed)?.failure,
                    )
                else
                    it.copy(
                        syncing = false,
                        loadingOlder = olderRequested,
                        failure = (result as? AppResult.Failed)?.failure,
                    )
            }
            project()
            if (result is AppResult.Failed && chatRequiresReconnect(result.failure)) {
                connectionFailed(result.failure)
                return
            }
            if (!older) {
                if (result is AppResult.Failed) scheduleReconnect(result.failure, syncOnly = true)
                else {
                    reconnectAttempt = 0
                    reconnection?.cancel()
                    reconnection = null
                }
            }
        }
    }

    private suspend fun loadOlder(device: CollaborationMessageDevice): AppResult<Unit> {
        val key = CollaborationHistoryTraversal(conversationId, device.roomId, device.deviceId)
        olderRecoveryAnchor = null
        val result = acquireOlderHistory(workspaceId, key, olderPosition)
        currentCoroutineContext().ensureActive()
        if (result is AppResult.Failed) return result
        val batch = (result as AppResult.Success).value
        if (batch is CollaborationOlderHistoryOutcome.ReopenRequired) {
            olderRecoveryAnchor = batch.anchorEventId
            return AppResult.Failed(batch.failure)
        }
        batch as CollaborationOlderHistoryOutcome.Loaded
        olderRecoveryAnchor = null
        olderPosition = batch.position
        olderScanned = batch.exhausted
        updateState { it.copy(historyIncomplete = it.historyIncomplete || batch.unresolved) }
        return AppResult.Success(Unit)
    }

    private suspend fun synchronize(deviceId: String, wake: Boolean): AppResult<Unit> {
        val acquired = acquireHistory(workspaceId, deviceId, conversationId)
        currentCoroutineContext().ensureActive()
        if (acquired is AppResult.Failed) return acquired
        // Original history belongs to the local projection. The application worker owns delivery,
        // receipt reconciliation and retirement even after this screen is disposed.
        if (wake) return requestDelivery(workspaceId)
        return AppResult.Success(Unit)
    }

    private fun connectionFailed(failure: Failure) {
        reconnection?.cancel()
        reconnection = null
        updateState {
            it.copy(
                connection =
                    if (chatRequiresReconnect(failure)) ChatThreadConnection.SETUP_REQUIRED
                    else ChatThreadConnection.OFFLINE,
                syncing = false,
                loadingOlder = false,
                failure = failure,
            )
        }
        project()
        scheduleReconnect(failure)
    }

    private fun scheduleReconnect(failure: Failure, syncOnly: Boolean = false) {
        if (!visible || !chatCanReconnect(failure)) {
            reconnection?.cancel()
            reconnection = null
            return
        }
        if (reconnection?.isActive == true) return
        val owned = access ?: return
        val delayMillis = chatReconnectDelay(reconnectAttempt, failure)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
        val previous = connection
        reconnection =
            viewModelScope.launch {
                delay(delayMillis)
                if (!syncOnly) previous?.join()
                ensureActive()
                if (!visible || access != owned || state.value.localFailure != null) return@launch
                reconnection = null
                if (syncOnly && pulse != null) requestSync(wake = false)
                else {
                    connection = null
                    connect()
                }
            }
    }

    private fun changedThread() =
        Failure(
            FailureKind.Unauthorized,
            "Open this conversation again.",
            code = "client.session_changed",
        )
}

/** Only the active window is retained; no growing back stack of local or provider cursors. */
private data class ChatThreadLocalWindow(val after: String? = null, val before: String? = null) {
    val latest: Boolean
        get() = after == null && before == null

    override fun toString() = "ChatThreadLocalWindow([redacted])"
}

/** At most one uncertain write is retained alongside the latest visible input. */
