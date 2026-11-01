@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationContentResponseClosesRead
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.policies.validCollaborationText
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.presentation.mvi.MviViewModel
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
    private val observeHistory: ObserveCollaborationHistory,
    private val observeMessages: ObserveCollaborationMessages,
    private val queue: QueueCollaborationText,
    private val requestDelivery: RequestCollaborationDelivery,
    private val loadSetup: LoadCollaborationSetup,
    private val ensureRuntime: EnsureCollaborationRuntime,
    private val identity: ReadCollaborationIdentity,
    private val read: ReadCollaborationConversations,
    private val control: ReadCollaborationControl,
    private val prepare: PrepareCollaborationConversation,
    private val enter: EnterCollaborationConversation,
    private val acquireHistory: AcquireCollaborationHistory,
    private val reopenHistory: ReopenCollaborationHistory,
    acquireProviderHistory: AcquireCollaborationProviderHistory,
    readHistoryReferences: ReadCollaborationHistoryReferences,
    reconcileHistoryReferences: ReconcileCollaborationHistoryReferences,
    private val observeDraft: ObserveCollaborationDraft,
    private val editDraft: EditCollaborationDraft,
    private val prepareDraftSend: PrepareCollaborationDraftSend,
    private val confirmDraftSend: ConfirmCollaborationDraftSend,
) : MviViewModel<ChatThreadState, ChatThreadEvent, ChatThreadEffect>(ChatThreadState()) {
    private var observation: Job? = null
    private var connection: Job? = null
    private var submission: Job? = null
    private var visible = false
    private var access: CollaborationLocalAccess? = null
    private var context: CollaborationThreadContext? = null
    private var pending = emptyList<CollaborationMessageRecord>()
    private var history = emptyList<CollaborationHistoryMessage>()
    private var timeline = emptyList<CollaborationTimelineItem>()
    private var source: CollaborationMessageDevice? = null
    private var next: String? = null
    private var previous: String? = null
    private val window = MutableStateFlow(ChatThreadLocalWindow())
    private var displayedWindow = ChatThreadLocalWindow()
    private var historyLoading = true
    private var scrollToLatest = false
    private var localDraft: CollaborationDraft? = null
    private var preparingDraft: CollaborationDraft? = null
    private var draftWrite: ChatDraftWrite? = null
    private var localSave: Job? = null
    private var departure: Job? = null
    private var departureEffect: ChatThreadEffect? = null
    private var pulse: Channel<Unit>? = null
    private var wakeRequested = false
    private var olderRequested = false
    private var olderPosition = ChatThreadHistoryPosition()
    private var olderScanned = false
    private var olderKey: CollaborationHistoryTraversal? = null
    private var olderRecoveryAnchor: String? = null
    private val olderHistory =
        ChatThreadHistoryPager(
            acquireProviderHistory,
            acquireHistory,
            readHistoryReferences,
            reconcileHistoryReferences,
        )

    init {
        on<ChatThreadEvent.Entered>(::onEntered)
        on<ChatThreadEvent.Stopped>(::onStopped)
        on<ChatThreadEvent.BackRequested>(::onBackRequested)
        on<ChatThreadEvent.SetupRequested>(::onSetupRequested)
        on<ChatThreadEvent.RefreshRequested>(::onRefreshRequested)
        on<ChatThreadEvent.OlderRequested>(::onOlderRequested)
        on<ChatThreadEvent.NewerRequested>(::onNewerRequested)
        on<ChatThreadEvent.LatestRequested>(::onLatestRequested)
        on<ChatThreadEvent.DraftChanged>(::onDraftChanged)
        on<ChatThreadEvent.SendRequested>(::onSendRequested)
        on<ChatThreadEvent.DraftRetryRequested>(::onDraftRetryRequested)
        on<ChatThreadEvent.DraftDialogDismissed>(::onDraftDialogDismissed)
        on<ChatThreadEvent.DraftDialogConfirmed>(::onDraftDialogConfirmed)
    }

    private fun onEntered(event: ChatThreadEvent.Entered) {
        if (visible) return
        visible = true
        if (observation?.isActive != true) observeLocal() else if (context != null) connect()
    }

    private fun observeLocal() {
        observation?.cancel()
        observation =
            viewModelScope.launch {
                observeAccess(workspaceId).collectLatest { result ->
                    ensureActive()
                    if (result is AppResult.Failed) {
                        restrict(result.failure)
                        return@collectLatest
                    }
                    val owned = (result as AppResult.Success).value
                    if (access != owned) clearScope()
                    access = owned
                    val loaded = readContext(workspaceId, conversationId)
                    ensureActive()
                    if (loaded is AppResult.Failed) {
                        restrict(loaded.failure)
                        return@collectLatest
                    }
                    val value = (loaded as AppResult.Success).value
                    if (value.owner.localListAccess() != owned) {
                        restrict(changedThread())
                        return@collectLatest
                    }
                    context = value.value
                    updateState {
                        it.copy(title = value.value.summary.peer.displayName, localFailure = null)
                    }
                    if (visible) connect()
                    coroutineScope {
                        launch { observeComposer(owned) }
                        observePages(owned)
                    }
                }
            }
    }

    private suspend fun observePages(owned: CollaborationLocalAccess) {
        val pages =
            window
                .flatMapLatest { selected ->
                    observeHistory(
                            workspaceId,
                            conversationId,
                            selected.after,
                            150,
                            selected.before,
                            50,
                        )
                        .map { selected to it }
                }
                .buffer(0)
        combine(pages, observeMessages(workspaceId, conversationId)) { loaded, messages ->
                Triple(loaded.first, loaded.second, messages)
            }
            .collect { (selected, loaded, messages) ->
                currentCoroutineContext().ensureActive()
                if (access != owned || selected != window.value) return@collect
                val failed = loaded as? AppResult.Failed ?: messages as? AppResult.Failed
                if (failed != null) {
                    restrict(failed.failure)
                    return@collect
                }
                val page = (loaded as AppResult.Success).value
                // Once reverse traversal reaches the head, observe the live head instead of keeping
                // a historical upper boundary. Empty windows after replacement recover the same
                // way.
                if (
                    (selected.before != null && page.previous == null) ||
                        (!selected.latest && page.messages.isEmpty())
                ) {
                    selectWindow(ChatThreadLocalWindow())
                    return@collect
                }
                history = page.messages
                pending = (messages as AppResult.Success).value
                next = page.next
                previous = page.previous
                displayedWindow = selected
                historyLoading = false
                project()
                if (scrollToLatest && selected.latest) {
                    scrollToLatest = false
                    emitEffect(ChatThreadEffect.ScrollToNewest)
                }
            }
    }

    private fun clearScope() {
        connection?.cancel()
        connection = null
        submission?.cancel()
        submission = null
        pulse = null
        wakeRequested = false
        olderRequested = false
        olderPosition = ChatThreadHistoryPosition()
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
        localDraft = null
        preparingDraft = null
        draftWrite = null
        updateState { ChatThreadState() }
    }

    private fun restrict(failure: Failure) {
        clearScope()
        updateState { ChatThreadState(loading = false, localFailure = failure) }
    }

    private fun project() {
        val owned = access ?: return
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
                )
        ) {
            is AppResult.Failed -> {
                updateState {
                    it.copy(rows = emptyList(), loading = false, localFailure = rows.failure)
                }
            }
            is AppResult.Success ->
                updateState {
                    it.copy(
                        rows = rows.value,
                        loading = historyLoading,
                        hasOlder = next != null || (source != null && !olderScanned),
                        hasNewer = previous != null,
                        browsingHistory = !displayedWindow.latest,
                        localFailure = null,
                    )
                }
        }
    }

    private fun onStopped(event: ChatThreadEvent.Stopped) {
        visible = false
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
        leaveThread(ChatThreadEffect.Back)
    }

    private fun onSetupRequested(event: ChatThreadEvent.SetupRequested) {
        leaveThread(ChatThreadEffect.Setup)
    }

    private fun onRefreshRequested(event: ChatThreadEvent.RefreshRequested) {
        if (
            !visible ||
                state.value.connection == ChatThreadConnection.CONNECTING ||
                state.value.syncing ||
                state.value.loadingOlder
        )
            return
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
                olderPosition = ChatThreadHistoryPosition()
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

    private suspend fun observeComposer(owned: CollaborationLocalAccess) {
        observeDraft(workspaceId, conversationId, owned).collect { result ->
            currentCoroutineContext().ensureActive()
            if (access != owned) return@collect
            when (result) {
                is AppResult.Failed -> reportDraftFailure(result.failure)
                is AppResult.Success ->
                    if (!state.value.draftReady) {
                        localDraft = result.value
                        updateState {
                            it.copy(
                                draftReady = true,
                                draft = result.value?.text.orEmpty(),
                                submissionPending = result.value?.submissionId != null,
                                draftFailure = null,
                            )
                        }
                    }
            }
            // Subsequent values are invalidations, not editor replacements. Only acknowledged
            // CAS results or an explicit reload replace text; delayed emissions cannot undo input.
        }
    }

    private fun onDraftChanged(event: ChatThreadEvent.DraftChanged) {
        if (!visible || !state.value.canCompose) return
        val bounded =
            event.text.take(16_384).let {
                if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
            }
        updateState {
            it.copy(
                draft = bounded,
                draftFailure =
                    it.draftFailure?.takeIf { failure ->
                        failure.code == "client.collaboration_draft_changed"
                    },
            )
        }
        persistDraft()
    }

    /** One writer, one immutable uncertain mutation, and the most recent waiting text. */
    private fun persistDraft(immediate: Boolean = false) {
        if (
            !state.value.draftReady ||
                state.value.localFailure != null ||
                state.value.draftFailure != null ||
                localDraft?.submissionId != null ||
                preparingDraft != null
        )
            return
        if (localSave?.isActive == true) {
            if (!immediate || state.value.draftSaving) return
            localSave?.cancel() // Only the debounce; never cancel an admitted write to replace it.
        }
        val owned = access ?: return
        localSave =
            viewModelScope.launch {
                if (!immediate) delay(250)
                while (isActive && access == owned) {
                    if (state.value.localFailure != null || state.value.draftFailure != null)
                        return@launch
                    if (draftWrite == null && state.value.draft == localDraft?.text.orEmpty())
                        return@launch
                    val mutation =
                        draftWrite
                            ?: ChatDraftWrite(
                                localDraft,
                                Uuid.random().toString(),
                                state.value.draft,
                            )
                    draftWrite = mutation
                    updateState { it.copy(draftSaving = true) }
                    val result =
                        editDraft(
                            workspaceId,
                            conversationId,
                            mutation.expected,
                            mutation.revision,
                            mutation.text,
                            owned,
                        )
                    ensureActive()
                    if (access != owned) return@launch
                    when (result) {
                        is AppResult.Failed -> {
                            reportDraftFailure(result.failure)
                            return@launch
                        }
                        is AppResult.Success -> {
                            localDraft = result.value
                            draftWrite = null
                            updateState { it.copy(draftSaving = false, draftFailure = null) }
                        }
                    }
                }
            }
    }

    private suspend fun flushDraft(): Boolean {
        persistDraft(immediate = true)
        localSave?.join()
        currentCoroutineContext().ensureActive()
        return state.value.draftReady &&
            state.value.localFailure == null &&
            state.value.draftFailure == null &&
            draftWrite == null &&
            state.value.draft == localDraft?.text.orEmpty()
    }

    private fun reportDraftFailure(failure: Failure) {
        // Input validation runs before I/O; this rejected body can safely be corrected.
        if (
            failure.kind == FailureKind.Validation &&
                failure.code == "client.collaboration_invalid_message"
        )
            draftWrite = null
        if (collaborationContentResponseClosesRead(failure)) {
            restrict(failure)
        } else updateState { it.copy(draftSaving = false, saving = false, draftFailure = failure) }
    }

    private fun onDraftRetryRequested(event: ChatThreadEvent.DraftRetryRequested) {
        if (!visible || state.value.saving || state.value.draftSaving || state.value.leaving) return
        if (state.value.draftFailure?.code == "client.collaboration_draft_changed") {
            updateState { it.copy(draftDialog = ChatDraftDialog.RELOAD) }
            return
        }
        updateState { it.copy(draftFailure = null) }
        if (!state.value.draftReady) observeLocal() else persistDraft(immediate = true)
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
                                localDraft = read.value
                                preparingDraft = null
                                draftWrite = null
                                updateState {
                                    it.copy(
                                        draft = read.value?.text.orEmpty(),
                                        draftReady = true,
                                        submissionPending = read.value?.submissionId != null,
                                        saving = false,
                                        draftSaving = false,
                                        draftFailure = null,
                                    )
                                }
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
        if (!visible || !state.value.canSend || submission?.isActive == true) return
        val owned = access ?: return
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
        if (
            !state.value.submissionPending &&
                state.value.draftFailure?.code == "client.collaboration_draft_changed"
        )
            return
        updateState { it.copy(saving = true, draftFailure = null, failure = null) }
        submission =
            viewModelScope.launch {
                if (!state.value.submissionPending && !flushDraft()) {
                    updateState { it.copy(saving = false) }
                    return@launch
                }
                ensureActive()
                if (access != owned) return@launch
                val expected =
                    preparingDraft
                        ?: localDraft
                        ?: run {
                            updateState { it.copy(saving = false) }
                            return@launch
                        }
                preparingDraft = expected
                updateState { it.copy(submissionPending = true) }
                val frozen =
                    if (expected.submissionId != null) AppResult.Success(expected)
                    else prepareDraftSend(workspaceId, expected, owned)
                ensureActive()
                if (access != owned) return@launch
                if (frozen is AppResult.Failed) {
                    reportDraftFailure(frozen.failure)
                    return@launch
                }
                localDraft = (frozen as AppResult.Success).value
                preparingDraft = null
                val draft = frozen.value
                val result =
                    queue(
                        workspaceId,
                        conversationId,
                        requireNotNull(draft.submissionId),
                        draft.text,
                        owned,
                    )
                ensureActive()
                if (access != owned) return@launch
                if (result is AppResult.Failed) {
                    if (collaborationContentResponseClosesRead(result.failure))
                        restrict(result.failure)
                    else updateState { it.copy(saving = false, failure = result.failure) }
                    return@launch
                }
                val confirmed = confirmDraftSend(workspaceId, draft, owned)
                ensureActive()
                if (access != owned) return@launch
                when (confirmed) {
                    is AppResult.Failed -> reportDraftFailure(confirmed.failure)
                    is AppResult.Success -> {
                        localDraft = confirmed.value
                        draftWrite = null
                        updateState {
                            it.copy(
                                draft = confirmed.value?.text.orEmpty(),
                                submissionPending = confirmed.value?.submissionId != null,
                                saving = false,
                                draftFailure = null,
                            )
                        }
                        showLatest()
                        if (pulse != null) requestSync(wake = false) else connect()
                    }
                }
            }
    }

    private fun requestSync(wake: Boolean = true) {
        wakeRequested = wakeRequested || wake
        pulse?.trySend(Unit)
    }

    private fun connect(retryRoom: Boolean = false, recoverHistory: Boolean = false) {
        if (!visible || context == null || connection?.isActive == true) return
        val owned = access ?: return
        updateState { it.copy(connection = ChatThreadConnection.CONNECTING, failure = null) }
        connection =
            viewModelScope.launch {
                val setup = loadSetup(workspaceId)
                ensureActive()
                if (access != owned) return@launch
                if (setup is AppResult.Failed) {
                    connectionFailed(setup.failure)
                    return@launch
                }
                val device = (setup as AppResult.Success).value.device
                if (device == null || device.state != CollaborationDeviceState.ACTIVE) {
                    updateState { it.copy(connection = ChatThreadConnection.SETUP_REQUIRED) }
                    return@launch
                }
                val restored = ensureRuntime(workspaceId, device.id)
                ensureActive()
                if (restored is AppResult.Failed) {
                    connectionFailed(restored.failure)
                    return@launch
                }
                if (!(restored as AppResult.Success).value.value) {
                    updateState { it.copy(connection = ChatThreadConnection.SETUP_REQUIRED) }
                    return@launch
                }
                val self = identity.acquire(workspaceId, device.id)
                ensureActive()
                if (self is AppResult.Failed) {
                    connectionFailed(self.failure)
                    return@launch
                }
                context =
                    context?.copy(self = (self as AppResult.Success).value.value ?: context?.self)
                project()
                val ready = prepareRoom(device, retryRoom)
                ensureActive()
                when (ready) {
                    is AppResult.Failed -> connectionFailed(ready.failure)
                    is AppResult.Success ->
                        if (ready.value) {
                            if (recoverHistory && !restoreHistory(device.id)) return@launch
                            ensureActive()
                            if (access == owned) observeTimeline(owned, device.id)
                        } else updateState { it.copy(connection = ChatThreadConnection.WAITING) }
                }
            }
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

    private suspend fun prepareRoom(
        device: CollaborationDeviceSetup,
        retry: Boolean,
    ): AppResult<Boolean> {
        val retained = control.room(workspaceId, conversationId)
        currentCoroutineContext().ensureActive()
        if (retained is AppResult.Failed) return retained
        val command = (retained as AppResult.Success).value.value
        val status = read.room(workspaceId, conversationId)
        currentCoroutineContext().ensureActive()
        if (status is AppResult.Failed) return status
        val room = (status as AppResult.Success).value.value
        if (command == null && room.state == CollaborationDirectRoomState.READY)
            return AppResult.Success(true)
        val records = observeMessages(workspaceId, conversationId).first()
        currentCoroutineContext().ensureActive()
        if (records is AppResult.Failed) return records
        val hasPending =
            (records as AppResult.Success).value.any { it.state !is CollaborationMessageState.Sent }
        if (
            command == null &&
                hasPending &&
                retry &&
                room.retryable &&
                room.state in
                    setOf(
                        CollaborationDirectRoomState.FAILED,
                        CollaborationDirectRoomState.CANCELLED,
                    )
        ) {
            // A failed provisioning attempt requires explicit user intent. The worker sends it.
            val prepared =
                prepare(
                    workspaceId,
                    CollaborationConversationAction.RequestRoom(
                        conversationId,
                        device.id,
                        device.version,
                        room.version,
                    ),
                )
            currentCoroutineContext().ensureActive()
            if (prepared is AppResult.Failed) return prepared
        }
        if (hasPending || command != null) {
            val requested = requestDelivery(workspaceId)
            currentCoroutineContext().ensureActive()
            if (requested is AppResult.Failed) return requested
        }
        if (!hasPending) return AppResult.Success(room.state == CollaborationDirectRoomState.READY)
        repeat(12) { attempt ->
            if (attempt > 0) delay(2_000)
            val loaded = read.room(workspaceId, conversationId)
            currentCoroutineContext().ensureActive()
            if (loaded is AppResult.Failed) return loaded
            val current = (loaded as AppResult.Success).value.value
            if (current.state == CollaborationDirectRoomState.READY) return AppResult.Success(true)
            if (
                current.state !in
                    setOf(
                        CollaborationDirectRoomState.NOT_REQUESTED,
                        CollaborationDirectRoomState.QUEUED,
                        CollaborationDirectRoomState.RUNNING,
                    )
            )
                return AppResult.Success(false)
            updateState { it.copy(connection = ChatThreadConnection.WAITING) }
        }
        return AppResult.Success(false)
    }

    private suspend fun observeTimeline(owned: CollaborationLocalAccess, deviceId: String) {
        val acquired = enter(workspaceId, deviceId, conversationId)
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
                olderPosition = ChatThreadHistoryPosition()
                olderScanned = false
                olderRecoveryAnchor = null
                olderKey = key
                updateState { it.copy(historyIncomplete = false, historyFailure = null) }
            }
            updateState { it.copy(connection = ChatThreadConnection.CONNECTED) }
            project()
            coroutineScope {
                val signals = Channel<Unit>(Channel.CONFLATED)
                pulse = signals
                wakeRequested = false
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
                                        timeline = result.value.toList()
                                        project()
                                        signals.trySend(Unit)
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
        }
    }

    private suspend fun loadOlder(device: CollaborationMessageDevice): AppResult<Unit> {
        val key = CollaborationHistoryTraversal(conversationId, device.roomId, device.deviceId)
        val result = olderHistory.load(workspaceId, key, olderPosition)
        currentCoroutineContext().ensureActive()
        if (result is AppResult.Failed) {
            olderRecoveryAnchor = olderHistory.recoveryAnchor
            return result
        }
        val batch = (result as AppResult.Success).value
        olderRecoveryAnchor = null
        olderPosition = batch.position
        olderScanned = batch.scanned
        updateState { it.copy(historyIncomplete = it.historyIncomplete || batch.unresolved) }
        return AppResult.Success(Unit)
    }

    private suspend fun synchronize(deviceId: String, wake: Boolean): AppResult<Unit> {
        val acquired = acquireHistory(workspaceId, deviceId, conversationId)
        currentCoroutineContext().ensureActive()
        if (acquired is AppResult.Failed) return acquired
        // Original history belongs to the local projection. The application worker owns delivery,
        // receipt reconciliation and retirement even after this screen is disposed.
        if (wake || pending.isNotEmpty()) return requestDelivery(workspaceId)
        return AppResult.Success(Unit)
    }

    private fun connectionFailed(failure: Failure) {
        timeline = emptyList()
        source = null
        updateState {
            it.copy(
                connection = ChatThreadConnection.OFFLINE,
                syncing = false,
                loadingOlder = false,
                failure = failure,
            )
        }
        project()
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
private data class ChatDraftWrite(
    val expected: CollaborationDraft?,
    val revision: String,
    val text: String,
) {
    override fun toString() = "ChatDraftWrite([redacted])"
}
