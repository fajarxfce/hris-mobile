package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationContentResponseClosesRead
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*

/** Route-owned local selection and publication. Its coroutine scope belongs to the ViewModel. */
internal class ChatThreadForwardController(
    private val scope: CoroutineScope,
    private val workspaceId: String,
    private val targets: ReadCollaborationForwardTargets,
    private val prepare: PrepareCollaborationForward,
    private val queue: QueueCollaborationForward,
    private val onState: (ChatForwardState?) -> Unit,
    private val onRestricted: (Failure) -> Unit,
    private val onForwarded: () -> Unit,
) {
    private var owner: CollaborationLocalAccess? = null
    private var selection: CollaborationForwardSelection? = null
    private var prepared: CollaborationForwardBatch? = null
    private var current: ChatForwardState? = null
    private var job: Job? = null
    private var generation = 0L

    fun open(
        access: CollaborationLocalAccess,
        conversation: String,
        room: String,
        messages: List<CollaborationForwardSource>,
    ) {
        close()
        if (messages.size !in 1..COLLABORATION_FORWARD_MESSAGE_LIMIT) return
        owner = access
        selection =
            CollaborationForwardSelection(conversation, room, messages.toList(), emptyList())
        publish(
            ChatForwardState(
                messages.size,
                minOf(
                    COLLABORATION_FORWARD_TARGET_LIMIT,
                    COLLABORATION_FORWARD_BATCH_LIMIT / messages.size,
                ),
            )
        )
        load()
    }

    fun load() {
        val access = owner ?: return
        val state = current ?: return
        if (state.busy || state.locked) return
        job?.cancel()
        val token = ++generation
        publish(state.copy(loading = true, failure = null))
        job =
            scope.launch {
                val result = targets(workspaceId, access)
                currentCoroutineContext().ensureActive()
                if (token != generation) return@launch
                when (result) {
                    is AppResult.Success -> {
                        val ordered =
                            result.value.sortedWith(
                                compareBy<CollaborationDirectSummary> {
                                        it.peer.displayName.lowercase()
                                    }
                                    .thenBy { it.conversation.id }
                            )
                        publish(
                            current!!.copy(
                                targets = ordered,
                                selectedIds =
                                    current!!.selectedIds.filter { id ->
                                        ordered.any { it.conversation.id == id }
                                    },
                                loading = false,
                            )
                        )
                    }
                    is AppResult.Failed -> fail(result.failure)
                }
            }
    }

    fun search(value: String) {
        val state = current ?: return
        publish(state.copy(query = value.take(120)))
    }

    fun select(id: String) {
        val state = current ?: return
        if (state.busy || state.locked || state.targets.none { it.conversation.id == id }) return
        val ids =
            if (id in state.selectedIds) state.selectedIds - id
            else if (state.selectedIds.size < state.targetLimit) state.selectedIds + id else return
        publish(state.copy(selectedIds = ids, failure = null))
    }

    fun confirm() {
        val state = current ?: return
        if (!state.canConfirm) return
        val access = owner ?: return
        val selected = selection?.copy(destinationIds = state.selectedIds.toList()) ?: return
        job?.cancel()
        val token = ++generation
        publish(state.copy(busy = true, failure = null))
        job =
            scope.launch {
                if (prepared == null) {
                    val result = prepare(workspaceId, access, selected)
                    currentCoroutineContext().ensureActive()
                    if (token != generation) return@launch
                    when (result) {
                        is AppResult.Failed -> {
                            fail(result.failure)
                            return@launch
                        }
                        is AppResult.Success -> {
                            prepared = result.value
                            publish(current!!.copy(locked = true))
                        }
                    }
                }
                val result = queue(workspaceId, requireNotNull(prepared))
                currentCoroutineContext().ensureActive()
                if (token != generation) return@launch
                when (result) {
                    is AppResult.Failed -> fail(result.failure)
                    is AppResult.Success -> {
                        job = null
                        close()
                        onForwarded()
                    }
                }
            }
    }

    fun pause() {
        job?.cancel()
        job = null
        generation++
        current?.let { publish(it.copy(loading = false, busy = false)) }
    }

    fun resume() {
        val state = current ?: return
        if (state.targets.isEmpty() && !state.locked && !state.busy) load()
    }

    fun close() {
        generation++
        job?.cancel()
        job = null
        owner = null
        selection = null
        prepared = null
        publish(null)
    }

    private fun fail(failure: Failure) {
        if (collaborationContentResponseClosesRead(failure)) {
            close()
            onRestricted(failure)
        } else current?.let { publish(it.copy(loading = false, busy = false, failure = failure)) }
    }

    private fun publish(value: ChatForwardState?) {
        current = value
        onState(value)
    }
}
