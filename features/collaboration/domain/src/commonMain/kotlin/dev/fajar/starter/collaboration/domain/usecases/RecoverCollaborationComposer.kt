package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Materialize at most eight local originals; never acquire an SDK, send HTTP or clear the editor.
 */
class RecoverCollaborationComposer(
    private val outbox: CollaborationComposerOutboxRepository,
    private val cursors: CollaborationComposerRecoveryCursorRepository,
    private val messages: CollaborationMessageRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val schedules: SyncScheduleRepository,
) {
    /** True means another bounded pass is needed; held originals remain available for review. */
    suspend operator fun invoke(): AppResult<Boolean> {
        val identity = sessions.current()
        currentCoroutineContext().ensureActive()
        if (identity is AppResult.Failed) return identity
        val session = (identity as AppResult.Success).value ?: return AppResult.Success(false)
        val selected = workspaces.current(session.id)
        currentCoroutineContext().ensureActive()
        if (selected is AppResult.Failed) return selected
        val workspace = (selected as AppResult.Success).value ?: return AppResult.Success(false)
        val scope = CollaborationLocalScope.Selected(workspace.id)
        val captured = sessions.captureMessageAccess(scope, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        if (
            access.sessionId != session.id ||
                access.accountId != session.user.id ||
                access.companyId != workspace.company.id
        )
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        return recover(scope, access)
    }

    suspend operator fun invoke(scope: CollaborationLocalScope): AppResult<Boolean> {
        val captured = sessions.captureMessageAccess(scope, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        return recover(scope, (captured as AppResult.Success).value)
    }

    /** Explicitly revisit only this retained original, including one held by automatic recovery. */
    suspend fun retry(
        workspaceId: String,
        access: CollaborationLocalAccess,
        original: CollaborationComposerSubmission,
    ): AppResult<CollaborationMessageRecord> {
        if (!validCollaborationComposerSubmission(original)) return invalidDraft()
        val scope = CollaborationLocalScope.Selected(workspaceId)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        val recovered = materialize(scope, access, original)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (recovered is AppResult.Failed) return recovered
        val read = messages.get(access, original.id)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val record =
            (read as AppResult.Success).value
                ?: return collaborationSetupFailure(
                    "client.collaboration_composer_unavailable",
                    FailureKind.Unavailable,
                )
        if (record.intent != collaborationComposerIntent(access, original)) return changedDraft()
        if (
            record.state !is CollaborationMessageState.Sent &&
                record.state != CollaborationMessageState.Cancelled
        ) {
            withTimeoutOrNull(1_500) { schedules.request(QueueCollaborationText.DELIVERY_KEY) }
            guard(scope, access).let { if (it is AppResult.Failed) return it }
        }
        return AppResult.Success(record)
    }

    private suspend fun recover(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
    ): AppResult<Boolean> {
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        val saved = cursors.current(access)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (saved is AppResult.Failed) return saved
        var cursor = (saved as AppResult.Success).value
        if (
            cursor != null &&
                (!validCollaborationMessageId(cursor.generation) ||
                    !validCollaborationMessageId(cursor.afterSubmissionId))
        )
            return changedDraft()
        val found = page(scope, access, cursor?.afterSubmissionId)
        if (found is AppResult.Failed) return found
        var failure: Failure? = null
        for (original in (found as AppResult.Success).value.submissions) {
            val advanced = cursors.advance(access, cursor, original.id)
            guard(scope, access).let { if (it is AppResult.Failed) return it }
            if (advanced is AppResult.Failed) return advanced
            cursor = (advanced as AppResult.Success).value ?: return changedDraft()
            if (
                !validCollaborationMessageId(cursor.generation) ||
                    cursor.afterSubmissionId != original.id
            )
                return changedDraft()
            val recovered = materialize(scope, access, original)
            guard(scope, access).let { if (it is AppResult.Failed) return it }
            if (recovered is AppResult.Failed) failure = failure ?: recovered.failure
        }
        // Advance before each original: an unavailable entry cannot pin a page or starve a room.
        val remaining = page(scope, access, cursor?.afterSubmissionId)
        if (remaining is AppResult.Failed) return remaining
        return failure?.let { AppResult.Failed(it) }
            ?: AppResult.Success((remaining as AppResult.Success).value.submissions.isNotEmpty())
    }

    private suspend fun page(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        after: String?,
    ): AppResult<CollaborationComposerSubmissionPage> {
        var position = after
        var read = outbox.pending(access, position)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        if ((read as AppResult.Success).value.submissions.isEmpty() && position != null) {
            position = null
            read = outbox.pending(access)
            guard(scope, access).let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
        }
        val value = (read as AppResult.Success).value
        if (
            value.submissions.size > 8 ||
                value.submissions.any {
                    !validCollaborationComposerSubmission(it) ||
                        (position != null && it.id <= position)
                } ||
                value.submissions.map { it.id } !=
                    value.submissions.map { it.id }.distinct().sorted() ||
                (value.next != null &&
                    (value.submissions.size != 8 || value.next != value.submissions.last().id))
        )
            return changedDraft()
        return AppResult.Success(value)
    }

    private suspend fun materialize(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        original: CollaborationComposerSubmission,
    ): AppResult<Unit> {
        val retained = outbox.get(access, original.id)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (retained is AppResult.Failed) return retained
        // A competing recovery may have retired the entry. Absence grants no new insertion.
        val value = (retained as AppResult.Success).value ?: return AppResult.Success(Unit)
        if (value != original) return changedDraft()
        val intent = collaborationComposerIntent(access, original)
        val existing = messages.get(access, original.id)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (existing is AppResult.Failed) return existing
        (existing as AppResult.Success).value?.let {
            return if (it.intent == intent) retire(scope, access, original)
            else hold(scope, access, original)
        }
        val conversation = lists.get(access, original.original.conversationId)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (conversation is AppResult.Failed) return conversation
        if (
            !ownsDraftConversation(
                access,
                original.original.conversationId,
                (conversation as AppResult.Success).value,
            )
        ) {
            // Recheck the command after missing metadata: a racing original may already exist.
            val raced = messages.get(access, original.id)
            guard(scope, access).let { if (it is AppResult.Failed) return it }
            if (raced is AppResult.Failed) return raced
            if ((raced as AppResult.Success).value?.intent == intent)
                return retire(scope, access, original)
            return hold(scope, access, original)
        }
        val inserted = messages.insert(access, intent)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        val interrupted = (inserted as? AppResult.Failed)?.failure
        if (
            interrupted != null &&
                interrupted.kind !in setOf(FailureKind.Storage, FailureKind.Conflict)
        )
            return AppResult.Failed(interrupted)
        when ((inserted as? AppResult.Success)?.value) {
            CollaborationMessageInsertion.CAPACITY_EXCEEDED ->
                return collaborationSetupFailure("client.command_queue_full", FailureKind.Storage)
            CollaborationMessageInsertion.SCOPE_CHANGED ->
                return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
            CollaborationMessageInsertion.STORED,
            null -> Unit
        }
        // Only exact active or authenticated archived command evidence authorizes retirement.
        val confirmed = messages.get(access, original.id)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (confirmed is AppResult.Failed) return confirmed
        val record =
            (confirmed as AppResult.Success).value
                ?: return interrupted?.let { AppResult.Failed(it) } ?: changedDraft()
        return if (record.intent == intent) retire(scope, access, original)
        else hold(scope, access, original)
    }

    private suspend fun retire(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        original: CollaborationComposerSubmission,
    ): AppResult<Unit> {
        val removed = outbox.remove(access, original)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (removed is AppResult.Failed) return removed
        if ((removed as AppResult.Success).value) return AppResult.Success(Unit)
        val remaining = outbox.get(access, original.id)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (remaining is AppResult.Failed) return remaining
        return if ((remaining as AppResult.Success).value == null) AppResult.Success(Unit)
        else changedDraft()
    }

    private suspend fun hold(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        original: CollaborationComposerSubmission,
    ): AppResult<Unit> {
        val held = outbox.hold(access, original)
        guard(scope, access).let { if (it is AppResult.Failed) return it }
        if (held is AppResult.Failed) return held
        return if ((held as AppResult.Success).value) AppResult.Success(Unit) else changedDraft()
    }

    private suspend fun guard(scope: CollaborationLocalScope, access: CollaborationLocalAccess) =
        sessions.checkDraftContinuation(scope, access, lists, workspaces, server, build, clock)
}
