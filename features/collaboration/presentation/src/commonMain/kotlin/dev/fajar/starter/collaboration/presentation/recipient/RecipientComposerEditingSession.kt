@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationText
import dev.fajar.starter.common.result.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * One route's serial UI writer, confined to its parent's UI dispatcher. Recipient identity never
 * becomes a conversation ID. Supplied use cases own admission, storage and exact readback.
 */
internal class RecipientComposerEditingSession(
    private val scope: CoroutineScope,
    private val access: CollaborationSpaceLocalAccess,
    private val peerAccountId: String,
    private val save:
        suspend (RecipientComposerDraftWrite) -> AppResult<CollaborationSpaceRecipientDraft>,
    private val submit:
        suspend (RecipientComposerSubmissionWrite) -> AppResult<
                CollaborationSpaceRecipientSubmission
            >,
    private val onState: (RecipientComposerEditingState) -> Unit,
    private val clock: Clock = Clock.System,
    private val revision: () -> String = { Uuid.random().toString() },
) {
    var state = RecipientComposerEditingState()
        private set

    private val changes = MutableStateFlow(state)
    private var closed = false
    private var stored: CollaborationSpaceRecipientDraft? = null
    private var edit: RecipientComposerDraftWrite? = null
    private var submission: RecipientComposerSubmissionWrite? = null
    private val pending = ArrayDeque<CollaborationSpaceRecipientSubmission>()
    private var actor: Job? = null
    private var writing = false
    private var lastSubmittedAt: Instant? = null

    /** Replayed observations cannot replace text or gestures already owned by this editor. */
    fun bind(draft: CollaborationSpaceRecipientDraft?, latestSubmittedAt: Instant? = null) {
        if (closed || state.ready) return
        require(draft == null || draft.peerAccountId == peerAccountId)
        stored = draft
        lastSubmittedAt = latestSubmittedAt
        publish(RecipientComposerEditingState(ready = true, text = draft?.text.orEmpty()))
    }

    fun close() {
        if (closed) return
        closed = true
        actor?.cancel()
        actor = null
        stored = null
        edit = null
        submission = null
        pending.clear()
        writing = false
        publish(RecipientComposerEditingState())
    }

    fun change(text: String) {
        if (closed || !state.ready) return
        val bounded =
            text.take(16_384).let {
                if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
            }
        if (bounded == state.text) return
        publish(state.copy(text = bounded))
        schedule(immediate = false)
    }

    /** Capture synchronously; neither a local write nor network acknowledgement blocks typing. */
    fun send(): CollaborationSpaceRecipientSubmission? {
        if (
            closed ||
                !state.ready ||
                pending.size >= 16 ||
                state.submissions.size >= 128 ||
                !validCollaborationText(state.text)
        )
            return null
        val now = clock.now()
        val time = lastSubmittedAt?.let { maxOf(now, it + 1.milliseconds) } ?: now
        val original =
            CollaborationSpaceRecipientSubmission(
                CollaborationSpaceRecipientDraft(peerAccountId, revision(), state.text, time),
                revision(),
                time,
            )
        lastSubmittedAt = time
        pending.addLast(original)
        publish(
            state.copy(
                text = "",
                submissions = state.submissions + RecipientComposerPendingSubmission(original),
            )
        )
        schedule(immediate = true)
        return original
    }

    /** Failed captured writes stay idle until this explicit action; typing alone never retries. */
    fun retry() {
        if (closed || !state.ready) return
        publish(
            state.copy(
                failure = null,
                submissions = state.submissions.map { it.copy(failure = null) },
            )
        )
        schedule(immediate = true)
    }

    fun represented(originals: Set<CollaborationSpaceRecipientSubmission>) {
        if (closed) return
        val remaining = state.submissions.filterNot { it.durable && it.original in originals }
        if (remaining != state.submissions) publish(state.copy(submissions = remaining))
    }

    /** Once submit was called, only a durable cancellation use case may remove that original. */
    fun discardUnsubmitted(original: CollaborationSpaceRecipientSubmission): Boolean {
        if (closed || submission?.original == original || original !in pending) return false
        pending.remove(original)
        publish(state.copy(submissions = state.submissions.filterNot { it.original == original }))
        schedule(immediate = true)
        return true
    }

    suspend fun settled(original: CollaborationSpaceRecipientSubmission): Boolean {
        changes.first { value ->
            val item = value.submissions.firstOrNull { it.original == original }
            closed || item == null || item.durable || item.failure != null
        }
        return !closed
    }

    suspend fun flush(): Boolean {
        if (closed || !state.ready) return false
        schedule(immediate = true)
        actor?.join()
        currentCoroutineContext().ensureActive()
        return !closed &&
            state.failure == null &&
            pending.isEmpty() &&
            edit == null &&
            state.text == stored?.text.orEmpty()
    }

    private fun schedule(immediate: Boolean) {
        if (closed || !state.ready || state.failure != null) return
        if (actor?.isActive == true) {
            if (!immediate || writing) return
            actor?.cancel() // Only the debounce can be superseded; an in-flight write is retained.
        }
        actor =
            scope.launch {
                if (!immediate) delay(250)
                writing = true
                try {
                    while (isActive && !closed && state.failure == null) {
                        val progressed =
                            when {
                                edit != null ||
                                    (pending.isEmpty() && state.text != stored?.text.orEmpty()) ->
                                    saveCapturedDraft()
                                pending.isNotEmpty() -> submitCapturedOriginal()
                                else -> false
                            }
                        if (!progressed) return@launch
                    }
                } finally {
                    if (!closed) {
                        writing = false
                        publish(state.copy(saving = false))
                    }
                }
            }
    }

    private suspend fun saveCapturedDraft(): Boolean {
        val request =
            edit
                ?: RecipientComposerDraftWrite(
                        access,
                        stored,
                        CollaborationSpaceRecipientDraft(
                            peerAccountId,
                            revision(),
                            state.text,
                            clock.now(),
                        ),
                    )
                    .also { edit = it }
        publish(state.copy(saving = true))
        val result = save(request)
        currentCoroutineContext().ensureActive()
        if (closed) return false
        return when (result) {
            is AppResult.Failed -> {
                fail(result.failure)
                false
            }
            is AppResult.Success -> {
                if (result.value != request.replacement) {
                    fail(invalidResponse())
                    false
                } else {
                    stored = result.value
                    edit = null
                    true
                }
            }
        }
    }

    private suspend fun submitCapturedOriginal(): Boolean {
        val original = pending.first()
        val request =
            submission
                ?: RecipientComposerSubmissionWrite(access, stored, original).also {
                    submission = it
                }
        publish(state.copy(saving = true))
        val result = submit(request)
        currentCoroutineContext().ensureActive()
        if (closed) return false
        return when (result) {
            is AppResult.Failed -> {
                fail(result.failure, original.id)
                false
            }
            is AppResult.Success -> {
                if (result.value != original) {
                    fail(invalidResponse(), original.id)
                    false
                } else {
                    stored = original.nextDraft
                    pending.removeFirst()
                    submission = null
                    publish(
                        state.copy(
                            submissions =
                                state.submissions.map {
                                    if (it.original == original) it.copy(durable = true) else it
                                }
                        )
                    )
                    true
                }
            }
        }
    }

    private fun fail(failure: Failure, originalId: String? = pending.firstOrNull()?.id) {
        publish(
            state.copy(
                failure = failure,
                submissions =
                    state.submissions.map {
                        if (it.original.id == originalId) it.copy(failure = failure) else it
                    },
            )
        )
    }

    private fun publish(value: RecipientComposerEditingState) {
        if (state != value) {
            state = value
            changes.value = value
            if (!closed && scope.isActive) onState(value)
        }
    }

    private fun invalidResponse() =
        Failure(
            FailureKind.Unexpected,
            "The message could not be saved.",
            code = "client.collaboration_invalid_response",
        )
}
