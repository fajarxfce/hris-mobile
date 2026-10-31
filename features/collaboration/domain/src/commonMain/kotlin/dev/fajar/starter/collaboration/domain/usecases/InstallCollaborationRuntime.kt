package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Transfers confirmed bootstrap facts into protected SDK storage before retiring the command. The
 * returned lease stays paused; installation does not grant live delivery authority.
 */
class InstallCollaborationRuntime(
    private val commands: CollaborationSetupCommandRepository,
    private val runtimes: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        commandId: String,
    ): AppResult<CollaborationRuntimeLease> =
        execution.withLock {
            val owner =
                when (val captured = capture(workspaceId)) {
                    is AppResult.Failed -> return@withLock captured
                    is AppResult.Success -> captured.value
                }
            val stored = commands.current(owner)
            val admitted = continuation(workspaceId, owner)
            if (admitted is AppResult.Failed) return@withLock admitted
            val record =
                when (stored) {
                    is AppResult.Failed -> return@withLock stored
                    is AppResult.Success ->
                        stored.value
                            ?: return@withLock collaborationSetupFailure(
                                "client.collaboration_command_changed"
                            )
                }
            val valid = validate(owner, record, commandId)
            if (valid is AppResult.Failed) return@withLock valid
            install(workspaceId, owner, record)
        }

    private suspend fun install(
        workspaceId: String,
        owner: CollaborationOwner,
        record: CollaborationSetupCommandRecord,
    ): AppResult<CollaborationRuntimeLease> {
        var lease: CollaborationRuntimeLease? = null
        var transferred = false
        try {
            val installed = runtimes.install(owner, record)
            // Own a late successful resource before any suspending admission check.
            if (installed is AppResult.Success) lease = installed.value
            val admitted = continuation(workspaceId, owner)
            if (admitted is AppResult.Failed) return admitted
            if (installed is AppResult.Failed) return installed
            val runtime = checkNotNull(lease)
            val deviceId = (record.command.action as CollaborationSetupAction.Bootstrap).deviceId
            if (runtime.owner != owner || runtime.deviceId != deviceId)
                return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
            if (runtime.installedOperationId != record.command.id)
                return collaborationSetupFailure("client.collaboration_command_changed")
            runtime.credentialFailures.value?.let {
                return AppResult.Failed(it)
            }

            val retired = commands.remove(owner, record)
            val latest = continuation(workspaceId, owner)
            if (latest is AppResult.Failed) return latest
            when (retired) {
                is AppResult.Failed -> return retired
                is AppResult.Success ->
                    if (!retired.value)
                        return collaborationSetupFailure("client.collaboration_command_changed")
            }
            // A write failure during retirement cannot publish a ready resource.
            runtime.credentialFailures.value?.let {
                return AppResult.Failed(it)
            }
            currentCoroutineContext().ensureActive()
            transferred = true
            return AppResult.Success(runtime)
        } finally {
            if (!transferred) withContext(NonCancellable) { lease?.close() }
        }
    }

    private fun validate(
        owner: CollaborationOwner,
        record: CollaborationSetupCommandRecord,
        commandId: String,
    ): AppResult<Unit> {
        val command = record.command
        if (command.id != commandId || command.binding != owner.setupBinding())
            return collaborationSetupFailure("client.collaboration_command_changed")
        if (
            command.clientBuild != build.number ||
                command.platform.name != build.platform.name.uppercase()
        )
            return collaborationSetupFailure("client.collaboration_build_changed")
        val outcome =
            record.outcome as? CollaborationSetupOutcome.Bootstrapped
                ?: return collaborationSetupFailure("client.collaboration_setup_pending")
        if (
            record.attempts <= 0 ||
                !collaborationGatewayMatchesCommand(owner, command, outcome.gateway)
        )
            return collaborationSetupFailure("client.collaboration_command_changed")
        return AppResult.Success(Unit)
    }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)

    private suspend fun continuation(
        workspaceId: String,
        owner: CollaborationOwner,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return requireCollaborationOwner(capture(workspaceId), owner)
    }
}
