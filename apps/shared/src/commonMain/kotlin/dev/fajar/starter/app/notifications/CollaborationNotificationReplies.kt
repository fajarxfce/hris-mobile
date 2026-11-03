package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * Retains callback input before company admission, then reproduces the immutable command before
 * retiring that input. All submission/recovery work shares this application-owned, finite turn. The
 * native caller still owns its callback lifetime and must handle failure before retention.
 */
class CollaborationNotificationReplies(
    private val retain: RetainCollaborationNotificationReply,
    private val read: ReadCollaborationNotificationReply,
    private val complete: CompleteCollaborationNotificationReply,
    private val review: ReviewCollaborationNotificationReply,
    private val commands: CollaborationNotificationCommands,
    private val recover: RequestCollaborationRecovery,
    private val protect: ProtectCollaborationNotificationReply,
    private val transfer: TransferCollaborationNotificationInput,
) {
    private val execution = Mutex()
    private val submissions = Semaphore(8)

    /** Native callbacks protect input and request work without restoring an account or Matrix. */
    suspend fun capture(
        token: CollaborationNotificationActionToken,
        text: String,
    ): AppResult<CollaborationNotificationInputReference> {
        currentCoroutineContext().ensureActive()
        if (!submissions.tryAcquire()) return failure(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(8_000) {
                val stored = protect(token, text)
                if (stored is AppResult.Failed) return@withTimeoutOrNull stored
                val input = (stored as AppResult.Success).value
                // Retention survives a failed enqueue; startup and later wakes scan it again.
                withTimeoutOrNull(1_000) { recover() }
                currentCoroutineContext().ensureActive()
                AppResult.Success(input.reference)
            } ?: failure(FailureKind.Timeout)
        } finally {
            submissions.release()
        }
    }

    /** Transfer, cleanup and processing share the account-reply recovery turn. */
    suspend fun resumeProtected(
        reference: CollaborationNotificationInputReference
    ): AppResult<CollaborationNotificationReplyResult> {
        currentCoroutineContext().ensureActive()
        if (!execution.tryLock()) return failure(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(8_000) {
                when (val copied = transfer(reference)) {
                    is AppResult.Failed -> copied
                    is AppResult.Success ->
                        when (val value = copied.value) {
                            CollaborationNotificationInputTransfer.Absent ->
                                AppResult.Success(CollaborationNotificationReplyResult.Absent)
                            is CollaborationNotificationInputTransfer.Transferred ->
                                processRetained(value.replyId)
                        }
                }
            } ?: failure(FailureKind.Timeout)
        } finally {
            execution.unlock()
        }
    }

    /** At most eight admitted callbacks, including the active one; waiting shares the deadline. */
    suspend fun submit(
        token: CollaborationNotificationActionToken,
        text: String,
    ): AppResult<CollaborationNotificationReplyResult> {
        currentCoroutineContext().ensureActive()
        if (!submissions.tryAcquire()) return failure(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(8_000) {
                execution.withLock {
                    val stored = retain(token, text)
                    if (stored is AppResult.Failed) return@withLock stored
                    val input = (stored as AppResult.Success).value
                    // Startup/periodic recovery still discovers input if this request fails.
                    withTimeoutOrNull(1_000) { recover() }
                    currentCoroutineContext().ensureActive()
                    process(input)
                }
            } ?: failure(FailureKind.Timeout)
        } finally {
            submissions.release()
        }
    }

    /** A worker never accumulates waiting turns or bypasses queued callbacks. */
    suspend fun resume(id: String): AppResult<CollaborationNotificationReplyResult> {
        currentCoroutineContext().ensureActive()
        if (!execution.tryLock()) return failure(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(8_000) { processRetained(id) } ?: failure(FailureKind.Timeout)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun processRetained(
        id: String
    ): AppResult<CollaborationNotificationReplyResult> =
        when (val found = read(id)) {
            is AppResult.Failed -> found
            is AppResult.Success ->
                found.value?.let { process(it) }
                    ?: AppResult.Success(CollaborationNotificationReplyResult.Absent)
        }

    private suspend fun process(
        input: CollaborationNotificationReplyInput
    ): AppResult<CollaborationNotificationReplyResult> {
        when (val existing = complete(input)) {
            is AppResult.Failed -> return reviewFailure(input, existing)
            is AppResult.Success ->
                terminal(existing.value)?.let {
                    return it
                }
        }
        if (input.review != null) return needsReview(input)
        when (val checked = review(input)) {
            is AppResult.Failed -> return checked
            is AppResult.Success ->
                checked.value?.let {
                    return needsReview(it)
                }
        }
        // Neither a Pending completion nor a null review grants a send. Check that this exact
        // version is still retained, then the authenticated command path rechecks its authority.
        when (val found = read(input.id)) {
            is AppResult.Failed -> return found
            is AppResult.Success -> {
                val current =
                    found.value
                        ?: return AppResult.Success(CollaborationNotificationReplyResult.Absent)
                if (current != input) return failure(FailureKind.Conflict)
            }
        }
        val dispatched = commands(input.candidate.token, input.candidate.text)
        currentCoroutineContext().ensureActive()
        // Even a failed dispatch can have committed a command. Reproduce it; never clear input
        // merely because dispatch said success, and never send again to resolve uncertainty.
        return when (val finished = complete(input)) {
            is AppResult.Failed -> reviewFailure(input, finished)
            is AppResult.Success ->
                terminal(finished.value)
                    ?: reviewFailure(
                        input,
                        (dispatched as? AppResult.Failed) ?: failure(FailureKind.Conflict),
                    )
        }
    }

    private suspend fun reviewFailure(
        input: CollaborationNotificationReplyInput,
        original: AppResult.Failed,
    ): AppResult<CollaborationNotificationReplyResult> =
        when (val checked = review(input)) {
            is AppResult.Failed -> checked
            is AppResult.Success -> checked.value?.let { needsReview(it) } ?: original
        }

    private fun terminal(
        completion: CollaborationNotificationReplyCompletion
    ): AppResult<CollaborationNotificationReplyResult>? =
        when (completion) {
            CollaborationNotificationReplyCompletion.Pending -> null
            CollaborationNotificationReplyCompletion.Absent ->
                AppResult.Success(CollaborationNotificationReplyResult.Absent)
            is CollaborationNotificationReplyCompletion.Retired ->
                AppResult.Success(
                    CollaborationNotificationReplyResult.CommandRetained(completion.state)
                )
        }

    private fun needsReview(input: CollaborationNotificationReplyInput) =
        AppResult.Success(CollaborationNotificationReplyResult.NeedsReview(input.id))

    private fun failure(kind: FailureKind) =
        AppResult.Failed(
            Failure(
                kind,
                "The chat reply could not be completed.",
                code = "client.collaboration_notification_reply_flow_unavailable",
            )
        )
}
