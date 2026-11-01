package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationGatewayMatchesCommand
import dev.fajar.starter.collaboration.domain.policies.collaborationSetupFailure
import dev.fajar.starter.collaboration.domain.policies.retainCollaborationCommandFailure
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application owner serializes explicit attempts. It never schedules a retry loop. */
class ReplayCollaborationSetup(
    private val commands: CollaborationSetupCommandRepository,
    private val setup: CollaborationSetupRepository,
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
    ): AppResult<CollaborationSetupCommandRecord> =
        execution.withLock {
            val owner =
                when (val captured = capture(workspaceId)) {
                    is AppResult.Failed -> return@withLock captured
                    is AppResult.Success -> captured.value
                }
            val stored = commands.current(owner)
            val admitted = continuation(workspaceId, owner, stored)
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
            if (record.command.id != commandId || record.command.binding != owner.setupBinding())
                return@withLock collaborationSetupFailure("client.collaboration_command_changed")
            if (
                record.command.clientBuild != build.number ||
                    record.command.platform.name != build.platform.name.uppercase()
            )
                return@withLock collaborationSetupFailure("client.collaboration_build_changed")
            if (
                record.outcome != CollaborationSetupOutcome.Pending &&
                    record.outcome !is CollaborationSetupOutcome.Unconfirmed
            )
                return@withLock AppResult.Success(record)
            if (record.attempts >= 1_000)
                return@withLock collaborationSetupFailure("client.collaboration_attempt_limit")
            deliver(workspaceId, owner, record)
        }

    private suspend fun deliver(
        workspaceId: String,
        owner: CollaborationOwner,
        original: CollaborationSetupCommandRecord,
    ): AppResult<CollaborationSetupCommandRecord> {
        val uncertain =
            CollaborationSetupOutcome.Unconfirmed(
                (original.outcome as? CollaborationSetupOutcome.Unconfirmed)?.failure
            )
        val sending =
            original.copy(
                revision = original.revision + 1,
                attempts = original.attempts + 1,
                outcome = uncertain,
            )
        val previousFailure = (original.outcome as? CollaborationSetupOutcome.Unconfirmed)?.failure
        val preparing =
            when (val saved = commands.update(owner, original, uncertain, sending.attempts)) {
                is AppResult.Failed ->
                    AppResult.Failed(
                        retainCollaborationCommandFailure(previousFailure, saved.failure)
                    )
                is AppResult.Success -> saved
            }
        val preparedOwner =
            continuation(
                workspaceId,
                owner,
                if (preparing is AppResult.Failed) preparing
                else previousFailure?.let { AppResult.Failed(it) },
            )
        if (preparedOwner is AppResult.Failed) return preparedOwner
        when (val saved = preparing) {
            is AppResult.Failed -> return saved
            is AppResult.Success ->
                if (!saved.value)
                    return collaborationSetupFailure("client.collaboration_command_changed")
        }
        val admitted =
            continuation(workspaceId, owner, previousFailure?.let { AppResult.Failed(it) })
        if (admitted is AppResult.Failed) return admitted
        val result =
            when (val delivered = send(owner, original.command)) {
                is AppResult.Failed ->
                    AppResult.Failed(
                        retainCollaborationCommandFailure(
                            (original.outcome as? CollaborationSetupOutcome.Unconfirmed)?.failure,
                            delivered.failure,
                        )
                    )
                is AppResult.Success -> delivered
            }
        val current = continuation(workspaceId, owner, result)
        if (current is AppResult.Failed) return current
        val outcome =
            when (result) {
                is AppResult.Failed -> CollaborationSetupOutcome.Unconfirmed(result.failure)
                is AppResult.Success -> result.value
            }
        if (
            outcome is CollaborationSetupOutcome.Bootstrapped &&
                !collaborationGatewayMatchesCommand(owner, original.command, outcome.gateway)
        )
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        val saved = commands.update(owner, sending, outcome, sending.attempts)
        val completion =
            if (saved is AppResult.Failed)
                AppResult.Failed(
                    retainCollaborationCommandFailure(
                        (result as? AppResult.Failed)?.failure,
                        saved.failure,
                    )
                )
            else result
        val latest = continuation(workspaceId, owner, completion)
        if (latest is AppResult.Failed) return latest
        // Confirmed access failures remain authoritative even when outcome persistence fails.
        if (completion is AppResult.Failed) return completion
        return when (saved) {
            is AppResult.Failed -> saved
            is AppResult.Success ->
                if (saved.value)
                    AppResult.Success(
                        sending.copy(revision = sending.revision + 1, outcome = outcome)
                    )
                else collaborationSetupFailure("client.collaboration_command_changed")
        }
    }

    private suspend fun send(
        owner: CollaborationOwner,
        command: CollaborationSetupCommand,
    ): AppResult<CollaborationSetupOutcome> =
        when (val action = command.action) {
            is CollaborationSetupAction.Register ->
                when (
                    val result =
                        setup.register(
                            owner,
                            CollaborationRegistration(command.id, command.platform, action.label),
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(CollaborationSetupOutcome.Registered(result.value))
                }
            is CollaborationSetupAction.Retry ->
                when (
                    val result =
                        setup.retry(
                            owner,
                            CollaborationProvisioningRetry(
                                command.id,
                                action.deviceId,
                                action.deviceVersion,
                                action.provisioningVersion,
                            ),
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(CollaborationSetupOutcome.Retried(result.value))
                }
            is CollaborationSetupAction.Bootstrap ->
                when (
                    val result =
                        setup.bootstrap(
                            owner,
                            CollaborationBootstrap(
                                command.id,
                                action.deviceId,
                                action.deviceVersion,
                                action.gatewayVersion,
                            ),
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(CollaborationSetupOutcome.Bootstrapped(result.value))
                }
        }

    private suspend fun capture(workspaceId: String) =
        sessions.captureCollaborationOwner(workspaceId, workspaces, server, build, clock)

    private suspend fun continuation(
        workspaceId: String,
        owner: CollaborationOwner,
        received: AppResult<*>?,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        return requireCollaborationOwner(capture(workspaceId), owner, received)
    }
}
