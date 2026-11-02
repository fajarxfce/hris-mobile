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

/**
 * Projects one retained encrypted original, not an untrusted push body or an SDK display result.
 */
class PrepareCollaborationMessageNotification(
    private val settings: CollaborationNotificationSettingsRepository,
    private val reads: CollaborationReadStateRepository,
    private val history: CollaborationHistoryRepository,
    private val identities: CollaborationIdentityRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        scope: CollaborationLocalScope.Captured,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationNotificationProjection> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationReadSelection(conversationId, roomId, listOf(eventId), false))
            return invalid()
        return withTimeoutOrNull(10_000) {
            val captured = sessions.captureMessageAccess(scope, workspaces, server, build, clock)
            if (captured is AppResult.Failed) return@withTimeoutOrNull captured
            val access = (captured as AppResult.Success).value
            continuation(scope, access).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            prepare(scope, access, conversationId, roomId, eventId)
        } ?: notificationSettingsFailure(FailureKind.Unavailable)
    }

    private suspend fun prepare(
        scope: CollaborationLocalScope.Captured,
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationNotificationProjection> {
        val found = checked(scope, access) { lists.get(access, conversationId) }
        if (found is AppResult.Failed) return found
        val summary = (found as AppResult.Success).value
        if (!ownsCollaborationReadConversation(access, conversationId, summary)) return invalid()
        val configured = checked(scope, access) { settings.read(access, conversationId) }
        if (configured is AppResult.Failed) return configured
        val preference = (configured as AppResult.Success).value
        if (!validCollaborationNotificationState(preference)) return invalid()
        if (preference.settings.isMutedAt(clock.now()))
            return suppressed(CollaborationNotificationSuppression.Muted)
        val own = checked(scope, access) { identities.current(access) }
        if (own is AppResult.Failed) return own
        val self = (own as AppResult.Success).value ?: return pending()
        if (!validCollaborationHistorySender(self.matrixUserId)) return invalid()
        val loaded =
            checked(scope, access) { history.getByEvent(access, conversationId, roomId, eventId) }
        if (loaded is AppResult.Failed) return loaded
        val stored =
            (loaded as AppResult.Success).value
                ?: return missingOriginal(scope, access, conversationId, roomId, eventId)
        if (stored.eventIds.size !in 1..128) return invalid()
        val original = stored.copy(eventIds = stored.eventIds.toList())
        if (!validCollaborationHistoryMessage(original) || eventId !in original.eventIds)
            return invalid()
        if (original.redacted) return suppressed(CollaborationNotificationSuppression.Redacted)
        if (original.sender == self.matrixUserId)
            return suppressed(CollaborationNotificationSuppression.OwnMessage)
        return finish(scope, access, requireNotNull(summary), roomId, original, preference)
    }

    private suspend fun missingOriginal(
        scope: CollaborationLocalScope.Captured,
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationNotificationProjection> {
        val loaded =
            checked(scope, access) { history.getEventFact(access, conversationId, roomId, eventId) }
        if (loaded is AppResult.Failed) return loaded
        val fact = (loaded as AppResult.Success).value
        if (fact != null && !validCollaborationReadFact(fact, eventId)) return invalid()
        return if (fact?.redacted == true) suppressed(CollaborationNotificationSuppression.Redacted)
        else pending()
    }

    private suspend fun finish(
        scope: CollaborationLocalScope.Captured,
        access: CollaborationLocalAccess,
        summary: CollaborationDirectSummary,
        roomId: String,
        original: CollaborationHistoryMessage,
        preference: CollaborationNotificationSettingsState,
    ): AppResult<CollaborationNotificationProjection> {
        val conversationId = summary.conversation.id
        val marked =
            checked(scope, access) { reads.read(access, conversationId, roomId, original.eventIds) }
        if (marked is AppResult.Failed) return marked
        val read =
            snapshotCollaborationReadState((marked as AppResult.Success).value, original.eventIds)
                ?: return invalid()
        if (read.readEventIds.isNotEmpty())
            return suppressed(CollaborationNotificationSuppression.Read)
        val current =
            checked(scope, access) {
                history.getByEvent(access, conversationId, roomId, original.primaryEventId)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != original) return changed()
        val repeated = checked(scope, access) { settings.read(access, conversationId) }
        if (repeated is AppResult.Failed) return repeated
        if ((repeated as AppResult.Success).value != preference) return changed()
        val preview = preference.settings.preview
        return AppResult.Success(
            CollaborationNotificationProjection.Ready(
                CollaborationMessageNotification(
                    access,
                    conversationId,
                    roomId,
                    original.primaryEventId,
                    original.contentHash,
                    preference.version,
                    read.version,
                    preview,
                    if (preview == CollaborationNotificationPreview.Hidden) null
                    else collaborationNotificationPreviewText(summary.peer.displayName, 160),
                    if (preview == CollaborationNotificationPreview.Message)
                        collaborationNotificationPreviewText(requireNotNull(original.text), 400)
                    else null,
                )
            )
        )
    }

    private suspend fun <T> checked(
        scope: CollaborationLocalScope.Captured,
        access: CollaborationLocalAccess,
        operation: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val result = operation()
        continuation(scope, access).let { if (it is AppResult.Failed) return it }
        return result
    }

    private suspend fun continuation(
        scope: CollaborationLocalScope.Captured,
        access: CollaborationLocalAccess,
    ) = sessions.checkReadStateContinuation(scope, access, lists, workspaces, server, build, clock)

    private fun invalid() =
        collaborationSetupFailure(
            "client.collaboration_notification_unavailable",
            FailureKind.Unavailable,
        )

    private fun changed() = notificationSettingsFailure(FailureKind.Conflict)

    private fun pending() = AppResult.Success(CollaborationNotificationProjection.PendingHistory)

    private fun suppressed(reason: CollaborationNotificationSuppression) =
        AppResult.Success(CollaborationNotificationProjection.Suppressed(reason))
}
