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

/** Local editing before canonical setup. It never prepares or sends a message. */
class EditCollaborationSpaceRecipientDraft(
    private val drafts: CollaborationSpaceRecipientDraftRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceRecipientDraft?,
        replacement: CollaborationSpaceRecipientDraft,
    ): AppResult<CollaborationSpaceRecipientDraft> =
        if (!validCollaborationSpaceRecipientDraftChange(access, expected, replacement))
            invalidDraft()
        else Editing(access, expected, replacement).execute()

    /** One captured edit owns its admission latch through exact local readback. */
    private inner class Editing(
        val access: CollaborationSpaceLocalAccess,
        val expected: CollaborationSpaceRecipientDraft?,
        val replacement: CollaborationSpaceRecipientDraft,
    ) {
        var denial: Failure? = null

        suspend fun execute(): AppResult<CollaborationSpaceRecipientDraft> {
            admitted().let { if (it is AppResult.Failed) return it }
            val read = drafts.get(access, replacement.peerAccountId)
            admitted().let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            val current = (read as AppResult.Success).value
            if (
                current != null &&
                    (!validCollaborationSpaceRecipientDraft(current) ||
                        current.peerAccountId != replacement.peerAccountId)
            )
                return collaborationRecoveryFailure(
                    "client.collaboration_invalid_response",
                    FailureKind.Unexpected,
                )
            if (current == replacement) return AppResult.Success(replacement)
            if (current != expected) return changedDraft()
            // A first working copy requires published contact facts. Existing owned text remains
            // editable after contact disappearance; this grants no permission to send to that peer.
            if (current == null) {
                val contact = directory.observeContact(access, replacement.peerAccountId).first()
                admitted().let { if (it is AppResult.Failed) return it }
                if (contact is AppResult.Failed) return contact
                val peer = (contact as AppResult.Success).value
                if (peer == null)
                    return collaborationRecoveryFailure(
                        "client.collaboration_conversation_unavailable",
                        FailureKind.Unavailable,
                    )
                if (
                    peer.accountId != replacement.peerAccountId ||
                        !validCollaborationSpaceContact(peer, access.accountId)
                )
                    return collaborationRecoveryFailure(
                        "client.collaboration_invalid_response",
                        FailureKind.Unexpected,
                    )
            }
            currentCoroutineContext().ensureActive()
            val written = drafts.replace(access, expected, replacement)
            admitted().let { if (it is AppResult.Failed) return it }
            val interrupted = (written as? AppResult.Failed)?.failure
            if (
                interrupted != null &&
                    interrupted.kind !in setOf(FailureKind.Storage, FailureKind.Conflict)
            )
                return AppResult.Failed(interrupted)
            val saved = drafts.get(access, replacement.peerAccountId)
            admitted().let { if (it is AppResult.Failed) return it }
            if (saved is AppResult.Failed) return interrupted?.let { AppResult.Failed(it) } ?: saved
            return if ((saved as AppResult.Success).value == replacement)
                AppResult.Success(replacement)
            else interrupted?.let { AppResult.Failed(it) } ?: changedDraft()
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
