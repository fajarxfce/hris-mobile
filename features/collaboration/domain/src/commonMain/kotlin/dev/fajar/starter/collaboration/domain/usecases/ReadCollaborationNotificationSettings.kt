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

class ReadCollaborationNotificationSettings(
    private val settings: CollaborationNotificationSettingsRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        scope: CollaborationLocalScope,
        conversationId: String,
    ): AppResult<CollaborationNotificationSettingsState> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationMessageId(conversationId))
            return notificationSettingsFailure(FailureKind.Validation)
        return withTimeoutOrNull(10_000) {
            val captured = sessions.captureMessageAccess(scope, workspaces, server, build, clock)
            if (captured is AppResult.Failed) return@withTimeoutOrNull captured
            val access = (captured as AppResult.Success).value
            continuation(scope, access, conversationId).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            val loaded = settings.read(access, conversationId)
            continuation(scope, access, conversationId).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if (!validCollaborationNotificationState((loaded as AppResult.Success).value))
                return@withTimeoutOrNull notificationSettingsFailure(FailureKind.Conflict)
            loaded
        } ?: notificationSettingsFailure(FailureKind.Unavailable)
    }

    private suspend fun continuation(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        conversationId: String,
    ) =
        sessions.checkNotificationSettingsAccess(
            scope,
            access,
            conversationId,
            lists,
            workspaces,
            server,
            build,
            clock,
        )
}
