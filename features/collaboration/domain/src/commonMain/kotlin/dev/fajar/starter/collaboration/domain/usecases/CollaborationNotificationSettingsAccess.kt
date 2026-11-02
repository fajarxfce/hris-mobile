package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock

/** Current local owner, retained denial and acquired direct-conversation binding; no network. */
internal suspend fun SessionRepository.checkNotificationSettingsAccess(
    scope: CollaborationLocalScope,
    access: CollaborationLocalAccess,
    conversationId: String,
    lists: CollaborationListRepository,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
): AppResult<Unit> {
    checkReadStateContinuation(scope, access, lists, workspaces, server, build, clock).let {
        if (it is AppResult.Failed) return it
    }
    val found = lists.get(access, conversationId)
    checkReadStateContinuation(scope, access, lists, workspaces, server, build, clock).let {
        if (it is AppResult.Failed) return it
    }
    if (found is AppResult.Failed) return found
    if (
        !ownsCollaborationReadConversation(
            access,
            conversationId,
            (found as AppResult.Success).value,
        )
    )
        return notificationSettingsFailure(FailureKind.Unavailable)
    return AppResult.Success(Unit)
}

internal fun notificationSettingsFailure(kind: FailureKind) =
    collaborationSetupFailure(
        when (kind) {
            FailureKind.Validation -> "client.collaboration_invalid_notification_settings"
            FailureKind.Conflict -> "client.collaboration_notification_settings_changed"
            else -> "client.collaboration_notification_settings_unavailable"
        },
        kind,
    )
