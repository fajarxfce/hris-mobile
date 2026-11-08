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
import kotlinx.coroutines.withTimeoutOrNull

/** One immutable local gesture; it never creates a room, queues native content or sends HTTP. */
class SubmitCollaborationComposer(
    private val outbox: CollaborationComposerOutboxRepository,
    private val messages: CollaborationMessageRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val schedules: SyncScheduleRepository,
) {
    suspend operator fun invoke(
        workspaceId: String,
        expected: CollaborationDraft?,
        submission: CollaborationComposerSubmission,
        expectedAccess: CollaborationLocalAccess,
    ): AppResult<CollaborationComposerSubmission> {
        if (!validCollaborationComposerHandoff(submission, expected)) return invalidDraft()
        val captured = sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        if (access != expectedAccess)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        val original = collaborationComposerIntent(access, submission)
        val command = messages.get(access, submission.id)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (command is AppResult.Failed) return command
        (command as AppResult.Success).value?.let {
            if (it.intent != original) return changedDraft()
            // Exact active or authenticated archived proof survives retirement of the outbox.
            return if (
                it.state is CollaborationMessageState.Sent ||
                    it.state == CollaborationMessageState.Cancelled
            )
                AppResult.Success(submission)
            else wake(workspaceId, access, submission)
        }
        val retained = outbox.get(access, submission.id)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (retained is AppResult.Failed) return retained
        (retained as AppResult.Success).value?.let {
            return if (it == submission) wake(workspaceId, access, submission) else changedDraft()
        }
        val conversation = lists.get(access, submission.original.conversationId)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (conversation is AppResult.Failed) return conversation
        if (
            !ownsDraftConversation(
                access,
                submission.original.conversationId,
                (conversation as AppResult.Success).value,
            )
        )
            return collaborationSetupFailure(
                "client.collaboration_conversation_unavailable",
                FailureKind.Unavailable,
            )
        val handedOff = outbox.handoff(access, submission, expected)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        val interrupted = (handedOff as? AppResult.Failed)?.failure
        if (
            interrupted != null &&
                interrupted.kind !in setOf(FailureKind.Storage, FailureKind.Conflict)
        )
            return AppResult.Failed(interrupted)
        when ((handedOff as? AppResult.Success)?.value) {
            CollaborationComposerHandoffResult.STORED,
            CollaborationComposerHandoffResult.RETAINED ->
                return wake(workspaceId, access, submission)
            CollaborationComposerHandoffResult.CAPACITY_EXCEEDED ->
                return collaborationSetupFailure("client.command_queue_full", FailureKind.Storage)
            CollaborationComposerHandoffResult.CHANGED,
            null -> Unit
        }
        // A lost commit reply or concurrent recovery is resolved by exact retained facts only.
        val saved = outbox.get(access, submission.id)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (saved is AppResult.Failed)
            return if (interrupted != null) AppResult.Failed(interrupted) else saved
        (saved as AppResult.Success).value?.let {
            return if (it == submission) wake(workspaceId, access, submission) else changedDraft()
        }
        val recovered = messages.get(access, submission.id)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (recovered is AppResult.Failed) return recovered
        val record =
            (recovered as AppResult.Success).value
                ?: return if (interrupted != null) AppResult.Failed(interrupted) else changedDraft()
        if (record.intent != original) return changedDraft()
        return if (
            record.state is CollaborationMessageState.Sent ||
                record.state == CollaborationMessageState.Cancelled
        )
            AppResult.Success(submission)
        else wake(workspaceId, access, submission)
    }

    private suspend fun wake(
        workspaceId: String,
        access: CollaborationLocalAccess,
        submission: CollaborationComposerSubmission,
    ): AppResult<CollaborationComposerSubmission> {
        // The durable original is authoritative; a failed scheduling hint cannot undo its commit.
        withTimeoutOrNull(1_500) { schedules.request(QueueCollaborationText.DELIVERY_KEY) }
        val current = guard(workspaceId, access)
        return if (current is AppResult.Failed) current else AppResult.Success(submission)
    }

    private suspend fun guard(workspaceId: String, access: CollaborationLocalAccess) =
        sessions.checkDraftContinuation(
            workspaceId,
            access,
            lists,
            workspaces,
            server,
            build,
            clock,
        )
}
