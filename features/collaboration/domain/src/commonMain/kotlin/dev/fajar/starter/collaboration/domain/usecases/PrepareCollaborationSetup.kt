@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class PrepareCollaborationSetup(
    private val commands: CollaborationSetupCommandRepository,
    private val setup: CollaborationSetupRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        action: CollaborationSetupAction,
    ): AppResult<CollaborationSetupCommandRecord> {
        val owner =
            when (val captured = capture(workspaceId)) {
                is AppResult.Failed -> return captured
                is AppResult.Success -> captured.value
            }
        val stored = commands.current(owner)
        val admitted = continuation(workspaceId, owner)
        if (admitted is AppResult.Failed) return admitted
        val existing =
            when (stored) {
                is AppResult.Failed -> return stored
                is AppResult.Success -> stored.value
            }
        if (existing != null) return retained(existing, action, owner)
        val device = setup.read(owner)
        val refreshed = continuation(workspaceId, owner)
        if (refreshed is AppResult.Failed) return refreshed
        if (device is AppResult.Failed) return device
        val platform =
            if (build.platform == AppPlatform.Android) CollaborationNativePlatform.ANDROID
            else CollaborationNativePlatform.IOS
        val valid =
            validateCollaborationSetupAction(
                action,
                (device as AppResult.Success).value,
                platform,
                clock.now(),
            )
        if (valid is AppResult.Failed) return valid
        val command =
            CollaborationSetupCommand(
                Uuid.random().toString(),
                owner.setupBinding(),
                platform,
                build.number,
                clock.now(),
                action,
            )
        currentCoroutineContext().ensureActive()
        val inserted = commands.insert(owner, command)
        val insertedOwner = continuation(workspaceId, owner)
        if (insertedOwner is AppResult.Failed) return insertedOwner
        when (inserted) {
            is AppResult.Failed -> return inserted
            is AppResult.Success ->
                when (inserted.value) {
                    CollaborationSetupInsertion.STORED,
                    CollaborationSetupInsertion.OCCUPIED -> Unit
                    CollaborationSetupInsertion.CAPACITY_EXCEEDED ->
                        return collaborationSetupFailure(
                            "client.command_queue_full",
                            FailureKind.Storage,
                        )
                    CollaborationSetupInsertion.SCOPE_CHANGED ->
                        return collaborationSetupFailure(
                            "client.session_changed",
                            FailureKind.Unauthorized,
                        )
                }
        }
        val saved = commands.current(owner)
        val current = continuation(workspaceId, owner)
        if (current is AppResult.Failed) return current
        return when (saved) {
            is AppResult.Failed -> saved
            is AppResult.Success ->
                saved.value?.let { retained(it, action, owner) }
                    ?: collaborationSetupFailure("client.collaboration_command_changed")
        }
    }

    private fun retained(
        record: CollaborationSetupCommandRecord,
        action: CollaborationSetupAction,
        owner: CollaborationOwner,
    ): AppResult<CollaborationSetupCommandRecord> =
        if (
            record.command.binding == owner.setupBinding() &&
                record.command.action == action &&
                record.command.clientBuild == build.number &&
                record.command.platform.name == build.platform.name.uppercase()
        )
            AppResult.Success(record)
        else collaborationSetupFailure("client.collaboration_setup_pending")

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
