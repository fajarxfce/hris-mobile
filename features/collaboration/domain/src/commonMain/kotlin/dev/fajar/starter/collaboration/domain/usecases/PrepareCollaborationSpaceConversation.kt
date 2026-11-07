@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Finite local preparation. No HTTP, SDK, worker scheduling or uncertain intent replacement. */
class PrepareCollaborationSpaceConversation(
    private val commands: CollaborationSpaceConversationCommandRepository,
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        peerAccountId: String,
        discovery: CollaborationSpaceDiscovery? = null,
    ): AppResult<CollaborationSpaceOpenPreparation> =
        withTimeoutOrNull(5_000) {
            val initial = owned(access)
            if (initial is AppResult.Failed) return@withTimeoutOrNull initial
            if (
                !listOf(access.accountId, access.spaceId, peerAccountId)
                    .all(::validCollaborationSpaceConversationId) ||
                    peerAccountId == access.accountId
            )
                return@withTimeoutOrNull invalid()
            val previous = existing(access, peerAccountId)
            if (previous is AppResult.Failed) return@withTimeoutOrNull previous
            (previous as AppResult.Success).value?.let {
                return@withTimeoutOrNull AppResult.Success(it)
            }
            val restriction = directory.admission(access)
            val afterAdmission = owned(access)
            if (afterAdmission is AppResult.Failed) return@withTimeoutOrNull afterAdmission
            if (restriction is AppResult.Failed) return@withTimeoutOrNull restriction
            val gate = (restriction as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(gate)) return@withTimeoutOrNull invalid()
            gate.denial?.let {
                return@withTimeoutOrNull AppResult.Failed(it)
            }
            if (discovery == null)
                return@withTimeoutOrNull collaborationRecoveryFailure(
                    "client.collaboration_revalidation_required",
                    FailureKind.Unavailable,
                )
            if (discovery.owner.localDirectoryAccess() != access) return@withTimeoutOrNull invalid()
            val beforeWrite =
                sessions.currentCollaborationSpaceDiscovery(discovery, server, build, clock)
            if (beforeWrite is AppResult.Failed) return@withTimeoutOrNull beforeWrite
            val command =
                CollaborationSpaceConversationCommand.Open(
                    Uuid.random().toString(),
                    discovery.owner.identityBinding(),
                    peerAccountId,
                    clock.now(),
                )
            if (!validCollaborationSpaceConversationCommand(access, command))
                return@withTimeoutOrNull invalid()
            insertAndReconcile(access, command)
        }
            ?: collaborationRecoveryFailure(
                "client.collaboration_recovery_timeout",
                FailureKind.Timeout,
            )

    private suspend fun insertAndReconcile(
        access: CollaborationSpaceLocalAccess,
        command: CollaborationSpaceConversationCommand.Open,
    ): AppResult<CollaborationSpaceOpenPreparation> {
        val inserted = commands.insert(access, command)
        val afterInsert = owned(access)
        if (afterInsert is AppResult.Failed) return afterInsert
        // A lost local write response is reconciled by its exact original ID, without retry.
        val saved = commands.find(access, command.id)
        val afterRead = owned(access)
        if (afterRead is AppResult.Failed) return afterRead
        if (saved is AppResult.Failed) return saved
        (saved as AppResult.Success).value?.let { record ->
            return if (
                record.command == command &&
                    validCollaborationSpaceConversationCommandRecord(access, record)
            )
                AppResult.Success(CollaborationSpaceOpenPreparation.Original(record))
            else invalid()
        }
        if (inserted is AppResult.Failed) return inserted
        return when ((inserted as AppResult.Success).value) {
            CollaborationSetupInsertion.OCCUPIED ->
                when (val winner = existing(access, command.peerAccountId)) {
                    is AppResult.Failed -> winner
                    is AppResult.Success ->
                        winner.value?.let { AppResult.Success(it) } ?: conflict()
                }
            CollaborationSetupInsertion.CAPACITY_EXCEEDED ->
                collaborationRecoveryFailure("client.command_queue_full", FailureKind.Storage)
            CollaborationSetupInsertion.SCOPE_CHANGED ->
                collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
            CollaborationSetupInsertion.STORED -> conflict()
        }
    }

    private suspend fun existing(
        access: CollaborationSpaceLocalAccess,
        peerAccountId: String,
    ): AppResult<CollaborationSpaceOpenPreparation?> {
        val active = commands.observeAll(access).first()
        val records = (active as? AppResult.Success)?.value?.takeIf { it.size <= 128 }?.toList()
        val afterCommands = owned(access)
        if (afterCommands is AppResult.Failed) return afterCommands
        if (active is AppResult.Failed) return active
        if (
            records == null || !validCollaborationSpaceConversationCommandInventory(access, records)
        )
            return invalid()
        records
            .firstOrNull {
                it.command is CollaborationSpaceConversationCommand.Open &&
                    it.command.peerAccountId == peerAccountId
            }
            ?.let {
                return AppResult.Success(CollaborationSpaceOpenPreparation.Original(it))
            }
        val canonical = metadata.findByPeer(access, peerAccountId)
        val afterMetadata = owned(access)
        if (afterMetadata is AppResult.Failed) return afterMetadata
        if (canonical is AppResult.Failed) return canonical
        val value = (canonical as AppResult.Success).value ?: return AppResult.Success(null)
        if (
            !validCollaborationSpaceConversationMetadata(access, value) ||
                peerAccountId !in listOf(value.firstAccountId, value.secondAccountId)
        )
            return invalid()
        return AppResult.Success(CollaborationSpaceOpenPreparation.Canonical(value.id))
    }

    private suspend fun owned(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationSpaceLocalAccess> {
        currentCoroutineContext().ensureActive()
        val session = sessions.current()
        currentCoroutineContext().ensureActive()
        return when (session) {
            is AppResult.Failed -> session
            is AppResult.Success ->
                localCollaborationSpaceAccess(access, session.value, server.audience)
        }
    }

    private fun invalid() =
        collaborationRecoveryFailure("client.collaboration_invalid_command", FailureKind.Unexpected)

    private fun conflict() =
        collaborationRecoveryFailure("client.collaboration_command_changed", FailureKind.Conflict)
}
