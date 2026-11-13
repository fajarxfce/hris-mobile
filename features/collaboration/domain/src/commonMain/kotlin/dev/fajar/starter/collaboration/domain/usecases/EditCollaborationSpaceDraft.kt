package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** An immutable local edit. The caller captures revision, time and content before suspension. */
class EditCollaborationSpaceDraft(
    private val drafts: CollaborationSpaceDraftRepository,
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationDraft?,
        replacement: CollaborationDraft,
    ): AppResult<CollaborationDraft> =
        if (!validCollaborationSpaceDraftChange(access, expected, replacement)) invalidDraft()
        else Editing(access, expected, replacement).execute()

    /** The caller's coroutine owns this denial latch; concurrent edits never share it. */
    private inner class Editing(
        val access: CollaborationSpaceLocalAccess,
        val expected: CollaborationDraft?,
        val replacement: CollaborationDraft,
    ) {
        var denial: Failure? = null

        suspend fun execute(): AppResult<CollaborationDraft> {
            admitted().let { if (it is AppResult.Failed) return it }
            val read = drafts.get(access, replacement.conversationId)
            admitted().let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            val current = (read as AppResult.Success).value
            if (
                current != null &&
                    (!validCollaborationDraft(current) ||
                        current.conversationId != replacement.conversationId)
            )
                return collaborationRecoveryFailure(
                    "client.collaboration_invalid_response",
                    FailureKind.Unexpected,
                )
            if (current == replacement) return AppResult.Success(replacement)
            if (current != expected) return changedDraft()
            val canonical = metadata.getById(access, replacement.conversationId)
            admitted().let { if (it is AppResult.Failed) return it }
            if (canonical is AppResult.Failed) return canonical
            val conversation = (canonical as AppResult.Success).value
            if (
                conversation == null ||
                    conversation.id != replacement.conversationId ||
                    !validCollaborationSpaceConversationMetadata(access, conversation)
            )
                return collaborationRecoveryFailure(
                    "client.collaboration_conversation_unavailable",
                    FailureKind.Unavailable,
                )
            currentCoroutineContext().ensureActive()
            val written = drafts.replace(access, expected, replacement)
            admitted().let { if (it is AppResult.Failed) return it }
            val interrupted = (written as? AppResult.Failed)?.failure
            if (
                interrupted != null &&
                    interrupted.kind !in setOf(FailureKind.Storage, FailureKind.Conflict)
            )
                return AppResult.Failed(interrupted)
            // Confirm actual retained state, including a committed write with a lost reply.
            val saved = drafts.get(access, replacement.conversationId)
            admitted().let { if (it is AppResult.Failed) return it }
            if (saved is AppResult.Failed) return interrupted?.let { AppResult.Failed(it) } ?: saved
            if ((saved as AppResult.Success).value == replacement)
                return AppResult.Success(replacement)
            return interrupted?.let { AppResult.Failed(it) } ?: changedDraft()
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
