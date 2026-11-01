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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Only observed registration/job receipts retire here. SDK installation owns gateway retirement.
 */
class ReconcileCollaborationSetupReceipt(
    private val commands: CollaborationSetupCommandRepository,
    private val setup: CollaborationSetupRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(workspaceId: String, commandId: String): AppResult<Boolean> {
        val owner =
            when (val captured = capture(workspaceId)) {
                is AppResult.Failed -> return captured
                is AppResult.Success -> captured.value
            }
        val stored = commands.current(owner)
        val admitted = continuation(workspaceId, owner, stored)
        if (admitted is AppResult.Failed) return admitted
        val record =
            when (stored) {
                is AppResult.Failed -> return stored
                is AppResult.Success -> stored.value
            }
        if (record == null || record.command.id != commandId) return AppResult.Success(false)
        if (record.command.binding != owner.setupBinding())
            return collaborationSetupFailure("client.collaboration_command_changed")
        if (
            record.outcome !is CollaborationSetupOutcome.Registered &&
                record.outcome !is CollaborationSetupOutcome.Retried
        )
            return AppResult.Success(false)
        val device = setup.read(owner)
        val current = continuation(workspaceId, owner, device)
        if (current is AppResult.Failed) return current
        if (device is AppResult.Failed) return device
        if (!collaborationSetupReceiptObserved(record, (device as AppResult.Success).value))
            return AppResult.Success(false)
        val removed = commands.remove(owner, record)
        val latest = continuation(workspaceId, owner, removed)
        return if (latest is AppResult.Failed) latest else removed
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
