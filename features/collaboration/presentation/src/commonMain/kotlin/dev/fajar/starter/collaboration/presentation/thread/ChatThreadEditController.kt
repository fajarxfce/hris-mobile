@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationContentResponseClosesRead
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationEdit
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationEditCandidate
import dev.fajar.starter.common.result.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/** Route-owned editor buffer; never writes or replaces the ordinary message draft. */
internal class ChatThreadEditController(
    private val scope: CoroutineScope,
    private val workspaceId: String,
    private val conversationId: String,
    private val read: ReadCollaborationEditCandidate,
    private val queue: QueueCollaborationEdit,
    private val onState: (ChatEditState?) -> Unit,
    private val onStarted: () -> Unit,
    private val onQueued: () -> Unit,
    private val onFailure: (Failure) -> Unit,
    private val onRestricted: (Failure) -> Unit,
) {
    private var owner: CollaborationLocalAccess? = null
    private var current: ChatEditState? = null
    private var operationId: String? = null
    private var job: Job? = null
    private var generation = 0L

    fun open(
        access: CollaborationLocalAccess,
        roomId: String,
        reference: CollaborationMessageReference,
    ) {
        if (current != null || job?.isActive == true) return
        owner = access
        val token = ++generation
        job =
            scope.launch {
                val result = read(workspaceId, access, conversationId, roomId, reference)
                currentCoroutineContext().ensureActive()
                if (token != generation || owner != access) return@launch
                job = null
                when (result) {
                    is AppResult.Success -> {
                        publish(ChatEditState(result.value, result.value.text))
                        onStarted()
                    }
                    is AppResult.Failed -> fail(result.failure)
                }
            }
    }

    fun change(text: String) {
        val state = current ?: return
        if (state.busy || state.locked) return
        publish(state.copy(text = text, failure = null))
    }

    fun cancelPreparation() {
        if (current == null) close()
    }

    fun save() {
        val state = current ?: return
        val access = owner ?: return
        if (!state.canSave || job?.isActive == true) return
        val id = operationId ?: Uuid.random().toString().also { operationId = it }
        val token = ++generation
        publish(state.copy(busy = true, locked = true, failure = null))
        job =
            scope.launch {
                val result =
                    queue(workspaceId, access, conversationId, id, state.candidate, state.text)
                currentCoroutineContext().ensureActive()
                if (token != generation || owner != access) return@launch
                job = null
                when (result) {
                    is AppResult.Success -> {
                        close()
                        onQueued()
                    }
                    is AppResult.Failed -> fail(result.failure)
                }
            }
    }

    /** Stop screen work without cancelling any command already accepted by local storage. */
    fun pause() {
        ++generation
        job?.cancel()
        job = null
        current?.let { publish(it.copy(busy = false)) }
    }

    fun close() {
        ++generation
        job?.cancel()
        job = null
        owner = null
        operationId = null
        current = null
        onState(null)
    }

    private fun fail(failure: Failure) {
        if (collaborationContentResponseClosesRead(failure)) {
            close()
            onRestricted(failure)
            return
        }
        val state = current
        if (state == null) onFailure(failure)
        else publish(state.copy(busy = false, failure = failure))
    }

    private fun publish(value: ChatEditState) {
        current = value
        onState(value)
    }
}
