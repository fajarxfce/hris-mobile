package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

class PrepareCollaborationMutedNotification(
    journal: CollaborationNotificationPublicationRepository,
    settings: CollaborationNotificationSettingsRepository,
    history: CollaborationHistoryRepository,
    lists: CollaborationListRepository,
    sessions: SessionRepository,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
) {
    private val authority =
        CollaborationMutedNotificationAuthority(
            journal,
            settings,
            history,
            lists,
            sessions,
            workspaces,
            server,
            build,
            clock,
        )

    suspend operator fun invoke(
        parent: CollaborationNativeParent,
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationMutedNotificationPreparation> {
        currentCoroutineContext().ensureActive()
        return withTimeoutOrNull(8_000) {
            authority.prepare(parent, access, conversationId, roomId, eventId)
        } ?: notificationPublicationFailure(FailureKind.Unavailable)
    }
}
