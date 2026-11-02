package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSetupFailure
import dev.fajar.starter.collaboration.domain.policies.retainCollaborationCommandFailure
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application owner serializes explicit attempts. It never schedules a retry loop. */
class ReplayCollaborationConversation(
    private val commands: CollaborationConversationCommandRepository,
    private val conversations: CollaborationConversationRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val lists: CollaborationListRepository,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        commandId: String,
    ): AppResult<CollaborationConversationCommandRecord> =
        invoke(CollaborationOperationScope.Selected(workspaceId), commandId)

    suspend operator fun invoke(
        scope: CollaborationOperationScope,
        commandId: String,
    ): AppResult<CollaborationConversationCommandRecord> =
        execution.withLock {
            val owner =
                when (val captured = capture(scope)) {
                    is AppResult.Failed -> return@withLock captured
                    is AppResult.Success -> captured.value
                }
            continuation(scope, owner, null).let { if (it is AppResult.Failed) return@withLock it }
            val stored = commands.get(owner, commandId)
            val admitted = continuation(scope, owner, stored)
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
            if (!collaborationControlDeviceMatches(scope, record.command.action))
                return@withLock collaborationSetupFailure(
                    "client.collaboration_invalid_command",
                    FailureKind.Validation,
                )
            if (record.outcome is CollaborationConversationOutcome.Confirmed)
                return@withLock AppResult.Success(record)
            if (record.attempts >= 1_000)
                return@withLock collaborationSetupFailure("client.collaboration_attempt_limit")
            deliver(scope, owner, record)
        }

    private suspend fun deliver(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        original: CollaborationConversationCommandRecord,
    ): AppResult<CollaborationConversationCommandRecord> {
        val uncertain =
            CollaborationConversationOutcome.Unconfirmed(
                (original.outcome as? CollaborationConversationOutcome.Unconfirmed)?.failure
            )
        val sending =
            original.copy(
                revision = original.revision + 1,
                attempts = original.attempts + 1,
                outcome = uncertain,
            )
        val previousFailure =
            (original.outcome as? CollaborationConversationOutcome.Unconfirmed)?.failure
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
                scope,
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
        val admitted = continuation(scope, owner, previousFailure?.let { AppResult.Failed(it) })
        if (admitted is AppResult.Failed) return admitted
        val response = send(owner, original.command)
        val result =
            when (val delivered = response) {
                is AppResult.Failed ->
                    AppResult.Failed(
                        retainCollaborationCommandFailure(
                            (original.outcome as? CollaborationConversationOutcome.Unconfirmed)
                                ?.failure,
                            delivered.failure,
                        )
                    )
                is AppResult.Success -> delivered
            }
        val current = continuation(scope, owner, result, response)
        if (current is AppResult.Failed) return current
        val outcome =
            when (result) {
                is AppResult.Failed -> CollaborationConversationOutcome.Unconfirmed(result.failure)
                is AppResult.Success -> result.value
            }
        if (outcome is CollaborationConversationOutcome.Confirmed) {
            val action = original.command.action
            if (
                outcome.receipt.version !in 0..999_999 ||
                    (action is CollaborationConversationAction.Open &&
                        outcome.receipt.version != 0L) ||
                    (action is CollaborationConversationAction.RequestRoom &&
                        outcome.receipt.id != action.conversationId)
            )
                return collaborationSetupFailure("client.collaboration_receipt_changed")
        }
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
        val latest = continuation(scope, owner, completion)
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
        command: CollaborationConversationCommand,
    ): AppResult<CollaborationConversationOutcome> {
        val result =
            when (val action = command.action) {
                is CollaborationConversationAction.Open ->
                    conversations.open(
                        owner,
                        CollaborationOpenDirect(command.id, action.peerAccountId),
                    )
                is CollaborationConversationAction.RequestRoom ->
                    conversations.requestRoom(
                        owner,
                        CollaborationRequestDirectRoom(
                            command.id,
                            action.conversationId,
                            action.deviceId,
                            action.deviceVersion,
                            action.provisioningVersion,
                        ),
                    )
            }
        return when (result) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                AppResult.Success(CollaborationConversationOutcome.Confirmed(result.value))
        }
    }

    private suspend fun capture(scope: CollaborationOperationScope) =
        sessions.captureCollaborationOwner(scope, workspaces, server, build, clock)

    private suspend fun continuation(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        received: AppResult<*>?,
        response: AppResult<*>? = null,
    ): AppResult<Unit> =
        sessions.continueCollaborationControl(
            scope,
            owner,
            received,
            response,
            lists,
            workspaces,
            server,
            build,
            clock,
        )
}
