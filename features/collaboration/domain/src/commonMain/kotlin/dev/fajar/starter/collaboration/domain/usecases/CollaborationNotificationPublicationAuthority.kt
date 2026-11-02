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

/** Internal issuance/revalidation implementation; callers own the finite operation and OS lease. */
internal class CollaborationNotificationPublicationAuthority(
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
    suspend fun guard(
        parent: CollaborationNativeParent,
        access: CollaborationLocalAccess,
    ): AppResult<Unit> {
        if (
            access !=
                CollaborationLocalAccess(
                    parent.sessionId,
                    parent.audience,
                    parent.accountId,
                    access.companyId,
                )
        )
            return notificationPublicationFailure(FailureKind.Unauthorized)
        // Retained company denial wins even if another pending operation also failed.
        sessions
            .checkReadStateContinuation(
                CollaborationLocalScope.Captured(access, parent.nativeSessionId),
                access,
                lists,
                workspaces,
                server,
                build,
                clock,
            )
            .let { if (it is AppResult.Failed) return it }
        return sessions.guardPushParent(server, build, parent)
    }

    suspend fun inspect(
        parent: CollaborationNativeParent,
        message: CollaborationMessageNotification,
    ): AppResult<CollaborationHistoryMessage> {
        guard(parent, message.access).let { if (it is AppResult.Failed) return it }
        val found = checked(parent, message) { lists.get(message.access, message.conversationId) }
        if (found is AppResult.Failed) return found
        val summary = (found as AppResult.Success).value
        if (!ownsCollaborationReadConversation(message.access, message.conversationId, summary))
            return notificationPublicationFailure(FailureKind.Unavailable)
        val configured =
            checked(parent, message) { settings.read(message.access, message.conversationId) }
        if (configured is AppResult.Failed) return configured
        val preference = (configured as AppResult.Success).value
        if (!validCollaborationNotificationState(preference))
            return notificationPublicationFailure(FailureKind.Unavailable)
        if (preference.settings.isMutedAt(clock.now()))
            return notificationPublicationFailure(FailureKind.Conflict)
        val identity = checked(parent, message) { identities.current(message.access) }
        if (identity is AppResult.Failed) return identity
        val self =
            (identity as AppResult.Success).value
                ?: return notificationPublicationFailure(FailureKind.Unavailable)
        if (!validCollaborationHistorySender(self.matrixUserId))
            return notificationPublicationFailure(FailureKind.Unavailable)
        val loaded =
            checked(parent, message) {
                history.getByEvent(
                    message.access,
                    message.conversationId,
                    message.roomId,
                    message.eventId,
                )
            }
        if (loaded is AppResult.Failed) return loaded
        val raw =
            (loaded as AppResult.Success).value
                ?: return notificationPublicationFailure(FailureKind.Conflict)
        if (raw.eventIds.size !in 1..128)
            return notificationPublicationFailure(FailureKind.Unavailable)
        val original = raw.copy(eventIds = raw.eventIds.toList())
        if (!validCollaborationHistoryMessage(original))
            return notificationPublicationFailure(FailureKind.Unavailable)
        if (
            original.primaryEventId != message.eventId ||
                original.redacted ||
                original.sender == self.matrixUserId
        )
            return notificationPublicationFailure(FailureKind.Conflict)
        val marked =
            checked(parent, message) {
                reads.read(
                    message.access,
                    message.conversationId,
                    message.roomId,
                    original.eventIds,
                )
            }
        if (marked is AppResult.Failed) return marked
        val read =
            snapshotCollaborationReadState((marked as AppResult.Success).value, original.eventIds)
                ?: return notificationPublicationFailure(FailureKind.Unavailable)
        if (read.readEventIds.isNotEmpty())
            return notificationPublicationFailure(FailureKind.Conflict)
        val expected =
            collaborationNotificationMessage(
                message.access,
                requireNotNull(summary),
                message.roomId,
                original,
                preference,
                read,
            )
        if (expected != message) return notificationPublicationFailure(FailureKind.Conflict)
        return confirm(parent, message, original, preference, read, summary, self)
    }

    private suspend fun confirm(
        parent: CollaborationNativeParent,
        message: CollaborationMessageNotification,
        original: CollaborationHistoryMessage,
        preference: CollaborationNotificationSettingsState,
        read: CollaborationReadState,
        summary: CollaborationDirectSummary,
        self: CollaborationSelfIdentity,
    ): AppResult<CollaborationHistoryMessage> {
        val current =
            checked(parent, message) {
                history.getByEvent(
                    message.access,
                    message.conversationId,
                    message.roomId,
                    message.eventId,
                )
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != original)
            return notificationPublicationFailure(FailureKind.Conflict)
        val repeated =
            checked(parent, message) { settings.read(message.access, message.conversationId) }
        if (repeated is AppResult.Failed) return repeated
        if (
            (repeated as AppResult.Success).value != preference ||
                preference.settings.isMutedAt(clock.now())
        )
            return notificationPublicationFailure(FailureKind.Conflict)
        val listed = checked(parent, message) { lists.get(message.access, message.conversationId) }
        if (listed is AppResult.Failed) return listed
        if ((listed as AppResult.Success).value != summary)
            return notificationPublicationFailure(FailureKind.Conflict)
        val identity = checked(parent, message) { identities.current(message.access) }
        if (identity is AppResult.Failed) return identity
        if ((identity as AppResult.Success).value != self)
            return notificationPublicationFailure(FailureKind.Conflict)
        val marked =
            checked(parent, message) {
                reads.read(
                    message.access,
                    message.conversationId,
                    message.roomId,
                    original.eventIds,
                )
            }
        if (marked is AppResult.Failed) return marked
        val latest =
            snapshotCollaborationReadState((marked as AppResult.Success).value, original.eventIds)
                ?: return notificationPublicationFailure(FailureKind.Unavailable)
        if (latest != read) return notificationPublicationFailure(FailureKind.Conflict)
        return AppResult.Success(original)
    }

    suspend fun <T> checked(
        parent: CollaborationNativeParent,
        message: CollaborationMessageNotification,
        operation: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val result = operation()
        guard(parent, message.access).let { if (it is AppResult.Failed) return it }
        return result
    }
}

internal fun notificationPublicationFailure(kind: FailureKind) =
    collaborationSetupFailure("client.collaboration_notification_unavailable", kind)
