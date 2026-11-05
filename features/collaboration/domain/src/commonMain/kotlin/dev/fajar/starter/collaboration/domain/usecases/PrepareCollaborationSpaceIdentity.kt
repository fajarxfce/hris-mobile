@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceIdentityCommandRepository
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/**
 * Retains one already-admitted selection intent. It sends no request and never replaces pending
 * work.
 */
class PrepareCollaborationSpaceIdentity(
    private val commands: CollaborationSpaceIdentityCommandRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        discovery: CollaborationSpaceDiscovery
    ): AppResult<CollaborationSpaceIdentityCommandRecord> =
        withTimeoutOrNull(2_000) {
            val initial = current(discovery)
            if (initial is AppResult.Failed) return@withTimeoutOrNull initial
            val access = discovery.owner.localDirectoryAccess()
            val previous = commands.current(access)
            val afterRead = current(discovery)
            if (afterRead is AppResult.Failed) return@withTimeoutOrNull afterRead
            if (previous is AppResult.Failed) return@withTimeoutOrNull previous
            (previous as AppResult.Success).value?.let {
                return@withTimeoutOrNull retained(access, it)
            }
            val command =
                CollaborationSpaceIdentityCommand(
                    Uuid.random().toString(),
                    discovery.owner.identityBinding(),
                    if (build.platform == AppPlatform.Android) CollaborationNativePlatform.ANDROID
                    else CollaborationNativePlatform.IOS,
                    build.number,
                    clock.now(),
                )
            if (!validCollaborationSpaceIdentityCommand(access, command))
                return@withTimeoutOrNull invalid()
            val inserted = commands.insert(access, command)
            val afterInsert = current(discovery)
            if (afterInsert is AppResult.Failed) return@withTimeoutOrNull afterInsert
            if (inserted is AppResult.Failed) return@withTimeoutOrNull inserted
            when ((inserted as AppResult.Success).value) {
                CollaborationSetupInsertion.STORED,
                CollaborationSetupInsertion.OCCUPIED -> Unit
                CollaborationSetupInsertion.CAPACITY_EXCEEDED ->
                    return@withTimeoutOrNull collaborationRecoveryFailure(
                        "client.command_queue_full",
                        FailureKind.Storage,
                    )
                CollaborationSetupInsertion.SCOPE_CHANGED ->
                    return@withTimeoutOrNull collaborationRecoveryFailure(
                        "client.session_changed",
                        FailureKind.Unauthorized,
                    )
            }
            val saved = commands.current(access)
            val after = current(discovery)
            if (after is AppResult.Failed) return@withTimeoutOrNull after
            when (saved) {
                is AppResult.Failed -> saved
                is AppResult.Success ->
                    saved.value?.let { retained(access, it) }
                        ?: collaborationRecoveryFailure(
                            "client.collaboration_command_changed",
                            FailureKind.Conflict,
                        )
            }
        }
            ?: collaborationRecoveryFailure(
                "client.collaboration_recovery_timeout",
                FailureKind.Timeout,
            )

    private fun retained(
        access: CollaborationSpaceLocalAccess,
        record: CollaborationSpaceIdentityCommandRecord,
    ) =
        if (validCollaborationSpaceIdentityRecord(access, record)) AppResult.Success(record)
        else invalid()

    private suspend fun current(discovery: CollaborationSpaceDiscovery): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return sessions.currentCollaborationSpaceDiscovery(discovery, server, build, clock)
    }

    private fun invalid() =
        collaborationRecoveryFailure("client.collaboration_invalid_command", FailureKind.Unexpected)
}
