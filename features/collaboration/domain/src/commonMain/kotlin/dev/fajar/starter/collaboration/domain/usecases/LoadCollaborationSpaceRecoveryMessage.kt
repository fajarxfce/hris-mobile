package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Resolves one claimed local original and canonical conversation, without preparing transport. */
class LoadCollaborationSpaceRecoveryMessage(
    private val messages: CollaborationSpaceMessageRepository,
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        snapshot: CollaborationSpaceRecoverySnapshot
    ): AppResult<CollaborationSpaceRecoveryMessage?> =
        withTimeoutOrNull(5_000) {
            val owner = snapshot.owner
            val before = admitted(owner)
            if (before is AppResult.Failed) return@withTimeoutOrNull before
            val progress = snapshot.progress ?: return@withTimeoutOrNull changed()
            if (!validCollaborationSpaceRecoveryProgress(owner, progress))
                return@withTimeoutOrNull changed()
            val claim = progress.sweep?.messages?.pending ?: return@withTimeoutOrNull changed()
            val access = owner.localDirectoryAccess()
            val found = messages.get(access, claim.position.messageId)
            val captured =
                (found as? AppResult.Success)?.value?.let {
                    collaborationSpaceMessageInventory(access, listOf(it))?.single()
                }
            val afterRecord = admitted(owner)
            if (afterRecord is AppResult.Failed) return@withTimeoutOrNull afterRecord
            if (found is AppResult.Failed) return@withTimeoutOrNull found
            if ((found as AppResult.Success).value == null)
                return@withTimeoutOrNull AppResult.Success(null)
            val record = captured ?: return@withTimeoutOrNull changed()
            if (
                record.intent.id != claim.position.messageId ||
                    record.intent.createdAt != claim.position.createdAt ||
                    record.intent.conversationId != claim.conversationId
            )
                return@withTimeoutOrNull changed()
            if (
                record.state is CollaborationMessageState.Sent ||
                    record.state == CollaborationMessageState.Cancelled
            )
                return@withTimeoutOrNull AppResult.Success(null)
            val canonical = metadata.getById(access, claim.conversationId)
            val afterMetadata = admitted(owner)
            if (afterMetadata is AppResult.Failed) return@withTimeoutOrNull afterMetadata
            if (canonical is AppResult.Failed) return@withTimeoutOrNull canonical
            val retained =
                (canonical as AppResult.Success).value ?: return@withTimeoutOrNull changed()
            if (
                !validCollaborationSpaceConversationMetadata(access, retained) ||
                    retained.id != claim.conversationId
            )
                return@withTimeoutOrNull changed()
            val conversation =
                CollaborationSpaceConversation(
                    owner,
                    retained.id,
                    retained.firstAccountId,
                    retained.secondAccountId,
                    retained.createdBy,
                    retained.createdAt,
                    retained.version,
                )
            AppResult.Success(CollaborationSpaceRecoveryMessage(conversation, record))
        }
            ?: collaborationRecoveryFailure(
                "client.collaboration_space_recovery_message_timeout",
                FailureKind.Timeout,
            )

    private suspend fun admitted(owner: CollaborationSpaceOwner): AppResult<Unit> {
        val local = sessions.guardSpaceRecoveryOwner(server, owner)
        if (local is AppResult.Failed) return local
        val read = directory.admission(owner.localDirectoryAccess())
        currentCoroutineContext().ensureActive()
        if (read is AppResult.Failed) return read
        val value = (read as AppResult.Success).value
        if (!validCollaborationSpaceDirectoryAdmission(value)) return changed()
        value.denial?.let {
            return AppResult.Failed(it)
        }
        return sessions.guardSpaceRecoveryOwner(server, owner)
    }

    private fun changed() =
        collaborationRecoveryFailure(
            "client.collaboration_space_recovery_message_changed",
            FailureKind.Conflict,
        )
}
