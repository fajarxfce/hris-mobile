package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** Retain one exact peer-addressed Send locally; canonical setup and delivery are separate. */
class SubmitCollaborationSpaceRecipientComposer(
    private val outbox: CollaborationSpaceRecipientOutboxRepository,
    private val drafts: CollaborationSpaceRecipientDraftRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceRecipientDraft?,
        submission: CollaborationSpaceRecipientSubmission,
    ): AppResult<CollaborationSpaceRecipientSubmission> =
        if (!validCollaborationSpaceRecipientHandoff(access, expected, submission)) invalidDraft()
        else Submission(access, expected, submission).execute()

    /** One invocation owns its captured original and admission latch through readback. */
    private inner class Submission(
        val access: CollaborationSpaceLocalAccess,
        val expected: CollaborationSpaceRecipientDraft?,
        val submission: CollaborationSpaceRecipientSubmission,
    ) {
        var denial: Failure? = null

        suspend fun execute(): AppResult<CollaborationSpaceRecipientSubmission> {
            admitted().let { if (it is AppResult.Failed) return it }
            val existing = outbox.get(access, submission.id)
            admitted().let { if (it is AppResult.Failed) return it }
            if (existing is AppResult.Failed) return existing
            (existing as AppResult.Success).value?.let {
                return if (it == submission) AppResult.Success(submission) else changedDraft()
            }
            val editing = drafts.get(access, submission.original.peerAccountId)
            admitted().let { if (it is AppResult.Failed) return it }
            if (editing is AppResult.Failed) return editing
            if ((editing as AppResult.Success).value != expected) return changedDraft()
            val contact =
                directory.observeContact(access, submission.original.peerAccountId).first()
            admitted().let { if (it is AppResult.Failed) return it }
            if (contact is AppResult.Failed) return contact
            val peer =
                (contact as AppResult.Success).value
                    ?: return collaborationRecoveryFailure(
                        "client.collaboration_conversation_unavailable",
                        FailureKind.Unavailable,
                    )
            if (
                peer.accountId != submission.original.peerAccountId ||
                    !validCollaborationSpaceContact(peer, access.accountId)
            )
                return collaborationRecoveryFailure(
                    "client.collaboration_invalid_response",
                    FailureKind.Unexpected,
                )
            currentCoroutineContext().ensureActive()
            val written = outbox.handoff(access, expected, submission)
            admitted().let { if (it is AppResult.Failed) return it }
            val interrupted = (written as? AppResult.Failed)?.failure
            if (
                interrupted != null &&
                    interrupted.kind !in setOf(FailureKind.Storage, FailureKind.Conflict)
            )
                return AppResult.Failed(interrupted)
            val confirmed = outbox.get(access, submission.id)
            admitted().let { if (it is AppResult.Failed) return it }
            if (confirmed is AppResult.Failed)
                return interrupted?.let { AppResult.Failed(it) } ?: confirmed
            val saved = (confirmed as AppResult.Success).value
            if (saved == submission) return AppResult.Success(submission)
            if (saved != null) return changedDraft()
            if (interrupted != null) return AppResult.Failed(interrupted)
            return if (
                (written as AppResult.Success).value ==
                    CollaborationComposerHandoffResult.CAPACITY_EXCEEDED
            )
                collaborationRecoveryFailure("client.command_queue_full", FailureKind.Storage)
            else changedDraft()
        }

        private suspend fun admitted(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val identity = sessions.current()
            currentCoroutineContext().ensureActive()
            if (identity is AppResult.Failed) return AppResult.Failed(denial ?: identity.failure)
            val owned =
                localCollaborationSpaceAccess(
                    access,
                    (identity as AppResult.Success).value,
                    server.audience,
                )
            if (owned is AppResult.Failed) return AppResult.Failed(denial ?: owned.failure)
            val read = directory.admission(access)
            currentCoroutineContext().ensureActive()
            if (read is AppResult.Failed) return AppResult.Failed(denial ?: read.failure)
            val gate = (read as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(gate))
                return denial?.let { AppResult.Failed(it) }
                    ?: collaborationRecoveryFailure(
                        "client.collaboration_invalid_response",
                        FailureKind.Unexpected,
                    )
            denial = denial ?: gate.denial
            val latest = sessions.current()
            currentCoroutineContext().ensureActive()
            denial?.let {
                return AppResult.Failed(it)
            }
            if (latest is AppResult.Failed) return latest
            val current =
                localCollaborationSpaceAccess(
                    access,
                    (latest as AppResult.Success).value,
                    server.audience,
                )
            return if (current is AppResult.Failed) current else AppResult.Success(Unit)
        }
    }
}
