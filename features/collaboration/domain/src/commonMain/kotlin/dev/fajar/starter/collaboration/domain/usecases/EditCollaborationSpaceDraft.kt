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
    private val inbox: CollaborationSpaceInboxRepository,
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
            repeat(3) {
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
                if (current != expected && current != replacement) return changedDraft()
                val indexRead = inbox.conversation(access, replacement.conversationId)
                admitted().let { if (it is AppResult.Failed) return it }
                if (indexRead is AppResult.Failed) return indexRead
                val captured =
                    captureCollaborationSpaceInboxConversation(
                        replacement.conversationId,
                        (indexRead as AppResult.Success).value,
                    )
                if (captured is AppResult.Failed) return captured
                val indexed = (captured as AppResult.Success).value
                // An exact coherent replay remains local even when canonical metadata is absent.
                if (current == replacement && indexed.draft?.value == replacement)
                    return AppResult.Success(replacement)
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
                val prepared =
                    collaborationSpaceInboxDraftPublication(conversation, indexed, replacement)
                if (prepared is AppResult.Failed) return prepared
                currentCoroutineContext().ensureActive()
                val written =
                    drafts.replace(
                        access,
                        expected,
                        replacement,
                        (prepared as AppResult.Success).value,
                    )
                admitted().let { if (it is AppResult.Failed) return it }
                val interrupted = (written as? AppResult.Failed)?.failure
                if (
                    interrupted != null &&
                        interrupted.kind !in setOf(FailureKind.Storage, FailureKind.Conflict)
                )
                    return AppResult.Failed(interrupted)
                val saved = drafts.get(access, replacement.conversationId)
                admitted().let { if (it is AppResult.Failed) return it }
                if (saved is AppResult.Failed)
                    return interrupted?.let { AppResult.Failed(it) } ?: saved
                val retained = (saved as AppResult.Success).value
                if (retained != replacement) {
                    if (interrupted != null) return AppResult.Failed(interrupted)
                    if (retained != expected) return changedDraft()
                    return@repeat
                }
                val savedIndex = inbox.conversation(access, replacement.conversationId)
                admitted().let { if (it is AppResult.Failed) return it }
                if (savedIndex is AppResult.Failed)
                    return interrupted?.let { AppResult.Failed(it) } ?: savedIndex
                val confirmed =
                    captureCollaborationSpaceInboxConversation(
                        replacement.conversationId,
                        (savedIndex as AppResult.Success).value,
                    )
                if (confirmed is AppResult.Failed)
                    return interrupted?.let { AppResult.Failed(it) } ?: confirmed
                if ((confirmed as AppResult.Success).value.draft?.value == replacement)
                    return AppResult.Success(replacement)
                if (interrupted != null) return AppResult.Failed(interrupted)
            }
            return changedDraft()
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
