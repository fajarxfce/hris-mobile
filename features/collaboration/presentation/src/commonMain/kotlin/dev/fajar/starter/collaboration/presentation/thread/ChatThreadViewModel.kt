@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.viewModelScope
import dev.fajar.starter.collaboration.domain.entities.*
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
    private val cursors = MutableStateFlow(listOf<String?>(null))
    private var reservedId: String? = null
    private var reservedText: String? = null
    private var pulse: Channel<Unit>? = null
    private var wakeRequested = false

    init {
        on<ChatThreadEvent.Entered>(::onEntered)
        on<ChatThreadEvent.Stopped>(::onStopped)
        on<ChatThreadEvent.BackRequested>(::onBackRequested)
        on<ChatThreadEvent.SetupRequested>(::onSetupRequested)
        on<ChatThreadEvent.RefreshRequested>(::onRefreshRequested)
        on<ChatThreadEvent.OlderRequested>(::onOlderRequested)
        on<ChatThreadEvent.DraftChanged>(::onDraftChanged)
        on<ChatThreadEvent.SendRequested>(::onSendRequested)
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
                    observePages(owned)
                }
            }
    }

    private suspend fun observePages(owned: CollaborationLocalAccess) {
        val pages =
            cursors.flatMapLatest { selected ->
                combine(selected.map { observeHistory(workspaceId, conversationId, it) }) {
                    it.toList()
                }
            }
        combine(pages, observeMessages(workspaceId, conversationId)) { loaded, messages ->
                loaded to messages
            }
            .collect { (loaded, messages) ->
                currentCoroutineContext().ensureActive()
                if (access != owned) return@collect
                val failed =
                    loaded.filterIsInstance<AppResult.Failed>().firstOrNull()
                        ?: messages as? AppResult.Failed
                if (failed != null) {
                    restrict(failed.failure)
                    return@collect
                }
                val values = loaded.map { (it as AppResult.Success).value }
                history = values.flatMap { it.messages }.distinctBy { it.primaryEventId }
                pending = (messages as AppResult.Success).value
                next = values.last().next
                project()
            }
    }

    private fun clearScope() {
        connection?.cancel()
        connection = null
        submission?.cancel()
        submission = null
        pulse = null
        wakeRequested = false
        access = null
        context = null
        pending = emptyList()
        history = emptyList()
        timeline = emptyList()
        source = null
        next = null
        cursors.value = listOf(null)
        reservedId = null
        reservedText = null
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
                        loading = false,
                        hasOlder = next != null && cursors.value.size < 10,
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
        updateState {
            it.copy(connection = ChatThreadConnection.OFFLINE, syncing = false, saving = false)
        }
        project()
        // Owned local observers stay alive to clear hidden content after account or admission loss.
    }

    private fun onBackRequested(event: ChatThreadEvent.BackRequested) {
        if (!visible) return
        onStopped(ChatThreadEvent.Stopped)
        viewModelScope.launch { emitEffect(ChatThreadEffect.Back) }
    }

    private fun onSetupRequested(event: ChatThreadEvent.SetupRequested) {
        if (!visible) return
        onStopped(ChatThreadEvent.Stopped)
        viewModelScope.launch { emitEffect(ChatThreadEffect.Setup) }
    }

    private fun onRefreshRequested(event: ChatThreadEvent.RefreshRequested) {
        if (
            !visible ||
                state.value.connection == ChatThreadConnection.CONNECTING ||
                state.value.syncing
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
        } else if (state.value.failure?.code == "client.collaboration_history_focus_capacity") {
            connection?.cancel()
            connection = null
            pulse = null
            timeline = emptyList()
            source = null
            project()
            connect(retryRoom = true, recoverHistory = true)
        } else if (pulse != null) requestSync() else connect(retryRoom = true)
    }

    private fun onOlderRequested(event: ChatThreadEvent.OlderRequested) {
        if (
            !visible ||
                state.value.localFailure != null ||
                state.value.loading ||
                cursors.value.size >= 10
        )
            return
        val after = next ?: return
        if (after in cursors.value) return
        updateState { it.copy(loading = true) }
        cursors.value += after
    }

    private fun onDraftChanged(event: ChatThreadEvent.DraftChanged) {
        if (!visible || !state.value.canCompose) return
        val bounded =
            event.text.take(16_384).let {
                if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
            }
        updateState { it.copy(draft = bounded) }
    }

    private fun onSendRequested(event: ChatThreadEvent.SendRequested) {
        if (!visible || !state.value.canSend || submission?.isActive == true) return
        val owned = access ?: return
        val id = reservedId ?: Uuid.random().toString()
        val text = reservedText ?: state.value.draft
        if (!validCollaborationText(text)) {
            updateState {
                it.copy(
                    failure =
                        Failure(
                            FailureKind.Validation,
                            "Check the message length and characters.",
                            code = "client.collaboration_invalid_message",
                        )
                )
            }
            return
        }
        reservedId = id
        reservedText = text
        updateState { it.copy(submissionPending = true, saving = true, failure = null) }
        submission =
            viewModelScope.launch {
                val result = queue(workspaceId, conversationId, id, text)
                ensureActive()
                if (access != owned) return@launch
                when (result) {
                    is AppResult.Failed ->
                        updateState { it.copy(saving = false, failure = result.failure) }
                    is AppResult.Success -> {
                        reservedId = null
                        reservedText = null
                        updateState {
                            it.copy(draft = "", submissionPending = false, saving = false)
                        }
                        emitEffect(ChatThreadEffect.ScrollToNewest)
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
        val result = reopenHistory(workspaceId, deviceId, conversationId)
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
            updateState { it.copy(connection = ChatThreadConnection.CONNECTED) }
            coroutineScope {
                val signals = Channel<Unit>(Channel.CONFLATED)
                pulse = signals
                wakeRequested = false
                signals.trySend(Unit)
                val worker = launch {
                    for (signal in signals) {
                        val wake = wakeRequested
                        wakeRequested = false
                        if (!wake) delay(400)
                        updateState { it.copy(syncing = true) }
                        val result = synchronize(deviceId, wake)
                        ensureActive()
                        updateState {
                            it.copy(
                                syncing = false,
                                failure = (result as? AppResult.Failed)?.failure,
                            )
                        }
                    }
                }
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
            it.copy(connection = ChatThreadConnection.OFFLINE, syncing = false, failure = failure)
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
