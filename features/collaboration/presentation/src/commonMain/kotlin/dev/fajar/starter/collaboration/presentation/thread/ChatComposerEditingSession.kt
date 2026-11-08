@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.presentation.thread

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
 * Route-owned editing state, confined to the parent's UI dispatcher. Serializes captured UI writes;
 * the supplied use cases own storage, admission, exact replay and delivery scheduling.
 * Closing/rebinding cancels this actor and fences late results. No repository or network access.
 */
internal class ChatComposerEditingSession(
    private val scope: CoroutineScope,
    private val conversationId: String,
    private val save: suspend (ChatComposerDraftWrite) -> AppResult<CollaborationDraft>,
    private val submit:
        suspend (ChatComposerSubmissionWrite) -> AppResult<CollaborationComposerSubmission>,
    private val onState: (ChatComposerEditingState) -> Unit,
    private val onSubmitted: () -> Unit = {},
    private val clock: Clock = Clock.System,
    private val revision: () -> String = { Uuid.random().toString() },
) {
    var state = ChatComposerEditingState()
        private set

    private val changes = MutableStateFlow(state)
    private var owner: CollaborationLocalAccess? = null
    private var stored: CollaborationDraft? = null
    private var edit: ChatComposerDraftWrite? = null
    private var actor: Job? = null
    private var writing = false
    private var epoch = 0L
    private var lastSubmittedAt: Instant? = null
    private val pending = ArrayDeque<CollaborationComposerSubmission>()
    private var submission: ChatComposerSubmissionWrite? = null

    fun bind(access: CollaborationLocalAccess, draft: CollaborationDraft?) {
        if (owner == access && state.ready) {
            // Only the confirmed successor of an untouched legacy frozen slot is adopted.
            if (state.legacyPending && draft?.submissionId == null) reset(access, draft)
            return
        }
        reset(access, draft)
    }

    fun reset(access: CollaborationLocalAccess, draft: CollaborationDraft?) {
        clear()
        owner = access
        stored = draft
        publish(
            ChatComposerEditingState(
                ready = true,
                text = draft?.text.orEmpty(),
                replyToEventId = draft?.replyToEventId,
                legacyPending = draft?.submissionId != null,
            )
        )
    }

    fun clear() {
        epoch++
        actor?.cancel()
        actor = null
        owner = null
        stored = null
        edit = null
        submission = null
        pending.clear()
        writing = false
        lastSubmittedAt = null
        publish(ChatComposerEditingState())
    }

    fun change(
        text: String = state.text,
        replyToEventId: String? = state.replyToEventId,
        immediate: Boolean = false,
    ) {
        if (!state.ready || state.legacyPending) return
        val bounded =
            text.take(16_384).let {
                if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
            }
        val remainingFailure =
            state.failure?.takeIf {
                it.code in
                    setOf(
                        "client.collaboration_draft_changed",
                        "client.collaboration_composer_changed",
                    )
            }
        publish(
            state.copy(
                text = bounded,
                replyToEventId = replyToEventId,
                failure = remainingFailure,
                submissions =
                    if (remainingFailure == null) state.submissions.map { it.copy(failure = null) }
                    else state.submissions,
            )
        )
        schedule(immediate = immediate)
    }

    /** Capture once, show immediately, and keep subsequent typing separate from this original. */
    fun send(): CollaborationComposerSubmission? {
        if (
            !state.ready ||
                state.legacyPending ||
                pending.size >= 16 ||
                state.submissions.size >= 128 ||
                !validCollaborationText(state.text)
        )
            return null
        val now = clock.now()
        // Preserve gesture order even when two callbacks share the clock's millisecond tick.
        val submittedAt = lastSubmittedAt?.let { maxOf(now, it + 1.milliseconds) } ?: now
        val original =
            CollaborationComposerSubmission(
                CollaborationDraft(
                    conversationId,
                    revision(),
                    state.text,
                    submittedAt,
                    replyToEventId = state.replyToEventId,
                ),
                revision(),
                submittedAt,
            )
        lastSubmittedAt = submittedAt
        pending.addLast(original)
        publish(
            state.copy(
                text = "",
                replyToEventId = null,
                submissions = state.submissions + ChatComposerPendingSubmission(original),
            )
        )
        schedule(immediate = true)
        return original
    }

    fun retry() {
        if (!state.ready || state.legacyPending) return
        publish(
            state.copy(
                failure = null,
                submissions = state.submissions.map { it.copy(failure = null) },
            )
        )
        schedule(immediate = true)
    }

    /** Called only after the same exact original is present in the screen's durable projection. */
    fun visible(originals: Set<CollaborationComposerSubmission>) {
        val remaining = state.submissions.filterNot { it.durable && it.original in originals }
        if (remaining != state.submissions) publish(state.copy(submissions = remaining))
    }

    /** Only an original never passed to submit can be discarded without durable cancellation. */
    fun discardUnsubmitted(original: CollaborationComposerSubmission): Boolean {
        if (submission?.original == original || original !in pending) return false
        pending.remove(original)
        publish(state.copy(submissions = state.submissions.filterNot { it.original == original }))
        schedule(immediate = true)
        return true
    }

    /** Wait for this exact local call, without blocking typing or waiting for network delivery. */
    suspend fun settled(original: CollaborationComposerSubmission): Boolean {
        val generation = epoch
        changes.first { value ->
            val pending = value.submissions.firstOrNull { it.original == original }
            epoch != generation || pending == null || pending.durable || pending.failure != null
        }
        return epoch == generation
    }

    suspend fun flush(): Boolean {
        if (!state.ready || state.legacyPending) return state.ready
        schedule(immediate = true)
        actor?.join()
        currentCoroutineContext().ensureActive()
        return state.failure == null && pending.isEmpty() && edit == null && matchesStoredEditor()
    }

    private fun schedule(immediate: Boolean) {
        if (owner == null || state.failure != null || state.legacyPending) return
        if (actor?.isActive == true) {
            if (!immediate || writing) return
            actor?.cancel() // Cancel only a debounce; an admitted write keeps its exact attempt.
        }
        val captured = requireNotNull(owner)
        val generation = epoch
        actor =
            scope.launch {
                if (!immediate) delay(250)
                writing = true
                try {
                    while (isActive && epoch == generation && owner == captured) {
                        if (state.failure != null) return@launch
                        val editing = edit
                        if (editing != null || (pending.isEmpty() && !matchesStoredEditor())) {
                            val request =
                                editing
                                    ?: ChatComposerDraftWrite(
                                            captured,
                                            stored,
                                            revision(),
                                            state.text,
                                            state.replyToEventId,
                                        )
                                        .also { edit = it }
                            publish(state.copy(saving = true))
                            val result = save(request)
                            ensureActive()
                            if (epoch != generation || owner != captured) return@launch
                            when (result) {
                                is AppResult.Failed -> {
                                    if (
                                        result.failure.kind == FailureKind.Validation &&
                                            result.failure.code ==
                                                "client.collaboration_invalid_message"
                                    )
                                        edit = null
                                    fail(result.failure)
                                    return@launch
                                }
                                is AppResult.Success -> {
                                    stored = result.value
                                    edit = null
                                }
                            }
                        } else if (pending.isNotEmpty()) {
                            val original = pending.first()
                            val request =
                                submission
                                    ?: ChatComposerSubmissionWrite(captured, stored, original)
                                        .also { submission = it }
                            publish(state.copy(saving = true))
                            val result = submit(request)
                            ensureActive()
                            if (epoch != generation || owner != captured) return@launch
                            when (result) {
                                is AppResult.Failed -> {
                                    fail(result.failure, original.id)
                                    return@launch
                                }
                                is AppResult.Success -> {
                                    stored = original.nextDraft
                                    pending.removeFirst()
                                    submission = null
                                    publish(
                                        state.copy(
                                            submissions =
                                                state.submissions.map {
                                                    if (it.original == original)
                                                        it.copy(durable = true)
                                                    else it
                                                }
                                        )
                                    )
                                    onSubmitted()
                                }
                            }
                        } else return@launch
                    }
                } finally {
                    if (epoch == generation && owner == captured) {
                        writing = false
                        publish(state.copy(saving = false))
                    }
                }
            }
    }

    private fun matchesStoredEditor() =
        state.text == stored?.text.orEmpty() && state.replyToEventId == stored?.replyToEventId

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

    private fun publish(value: ChatComposerEditingState) {
        if (state != value) {
            state = value
            changes.value = value
            onState(value)
        }
    }
}

internal data class ChatComposerEditingState(
    val ready: Boolean = false,
    val text: String = "",
    val replyToEventId: String? = null,
    val legacyPending: Boolean = false,
    val saving: Boolean = false,
    val failure: Failure? = null,
    val submissions: List<ChatComposerPendingSubmission> = emptyList(),
) {
    override fun toString() = "ChatComposerEditingState([redacted])"
}

internal data class ChatComposerPendingSubmission(
    val original: CollaborationComposerSubmission,
    val durable: Boolean = false,
    val failure: Failure? = null,
) {
    override fun toString() = "ChatComposerPendingSubmission([redacted])"
}

internal data class ChatComposerDraftWrite(
    val access: CollaborationLocalAccess,
    val expected: CollaborationDraft?,
    val revision: String,
    val text: String,
    val replyToEventId: String?,
) {
    override fun toString() = "ChatComposerDraftWrite([redacted])"
}

internal data class ChatComposerSubmissionWrite(
    val access: CollaborationLocalAccess,
    val expected: CollaborationDraft?,
    val original: CollaborationComposerSubmission,
) {
    override fun toString() = "ChatComposerSubmissionWrite([redacted])"
}
