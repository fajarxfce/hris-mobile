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

/** A finite local conditional change. UI/OS actions retain their operation and expected version. */
class UpdateCollaborationNotificationSettings(
    private val settings: CollaborationNotificationSettingsRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        scope: CollaborationLocalScope,
        conversationId: String,
        change: CollaborationNotificationSettingsChange,
        expectedAccess: CollaborationLocalAccess? = null,
    ): AppResult<CollaborationNotificationSettingsState> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationMessageId(conversationId) ||
                !validCollaborationMessageId(change.operationId) ||
                !validCollaborationNotificationRevision(change.expected) ||
                !validCollaborationNotificationSettings(change.settings)
        )
            return notificationSettingsFailure(FailureKind.Validation)
        if (!execution.tryLock()) return notificationSettingsFailure(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(10_000) { update(scope, conversationId, change, expectedAccess) }
                ?: notificationSettingsFailure(FailureKind.Unavailable)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun update(
        scope: CollaborationLocalScope,
        conversationId: String,
        change: CollaborationNotificationSettingsChange,
        expectedAccess: CollaborationLocalAccess?,
    ): AppResult<CollaborationNotificationSettingsState> {
        val captured = sessions.captureMessageAccess(scope, workspaces, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val access = (captured as AppResult.Success).value
        if (expectedAccess != null && access != expectedAccess)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        continuation(scope, access, conversationId).let { if (it is AppResult.Failed) return it }
        val loaded = load(scope, access, conversationId)
        if (loaded is AppResult.Failed) return loaded
        val previous = (loaded as AppResult.Success).value
        if (previous.operationId == change.operationId)
            return if (previous.settings == change.settings) loaded
            else notificationSettingsFailure(FailureKind.Conflict)
        if (previous.version != change.expected)
            return notificationSettingsFailure(FailureKind.Conflict)
        val until = change.settings.mute as? CollaborationNotificationMute.Until
        if (until != null && until.at <= clock.now())
            return notificationSettingsFailure(FailureKind.Validation)
        val written = settings.update(access, conversationId, change)
        continuation(scope, access, conversationId).let { if (it is AppResult.Failed) return it }
        if (written is AppResult.Failed) return written
        if (!(written as AppResult.Success).value)
            return notificationSettingsFailure(FailureKind.Conflict)
        val current = load(scope, access, conversationId)
        if (current is AppResult.Failed) return current
        val state = (current as AppResult.Success).value
        return if (state.operationId == change.operationId && state.settings == change.settings)
            current
        else notificationSettingsFailure(FailureKind.Conflict)
    }

    private suspend fun load(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        conversationId: String,
    ): AppResult<CollaborationNotificationSettingsState> {
        val loaded = settings.read(access, conversationId)
        continuation(scope, access, conversationId).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        return if (validCollaborationNotificationState((loaded as AppResult.Success).value)) loaded
        else notificationSettingsFailure(FailureKind.Conflict)
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
