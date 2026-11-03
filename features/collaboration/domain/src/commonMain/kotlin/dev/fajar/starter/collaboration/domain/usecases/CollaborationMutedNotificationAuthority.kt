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

/** Shared policy; the two public operations separately own deadlines and mutation serialization. */
internal class CollaborationMutedNotificationAuthority(
    private val journal: CollaborationNotificationPublicationRepository,
    private val settings: CollaborationNotificationSettingsRepository,
    private val history: CollaborationHistoryRepository,
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
            access.sessionId != parent.sessionId ||
                access.audience != parent.audience ||
                access.accountId != parent.accountId
        )
            return notificationPublicationFailure(FailureKind.Unauthorized)
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

    suspend fun prepare(
        parent: CollaborationNativeParent,
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationMutedNotificationPreparation> {
        val inspected = source(parent, access, conversationId, roomId, eventId)
        if (inspected is AppResult.Failed) return inspected
        val ready = (inspected as AppResult.Success).value
        if (ready !is CollaborationMutedNotificationPreparation.Ready) return inspected
        val plan = ready.plan
        val loaded =
            checked(parent, access) {
                journal.read(parent.recoveryAccess(), access.companyId, conversationId)
            }
        if (loaded is AppResult.Failed) return loaded
        val previous = (loaded as AppResult.Success).value
        confirm(plan).let { if (it is AppResult.Failed) return it }
        if (previous != null) {
            if (!validCollaborationNotificationPublication(previous)) return conflict()
            val body = previous.body
            if (
                body.parent.accountId != parent.accountId ||
                    body.parent.audience != parent.audience ||
                    body.target.companyId != access.companyId ||
                    body.target.conversationId != conversationId ||
                    body.target.roomId != roomId
            )
                return conflict()
            if (body.target.eventId == plan.eventId) {
                if (body.contentHash != plan.contentHash || body.timestamp != plan.timestamp)
                    return conflict()
                if (!body.state.isActivePublication()) return suppressed()
            } else {
                val order =
                    compareValues(plan.timestamp, body.timestamp).takeIf { it != 0 }
                        ?: compareValues(plan.eventId, body.target.eventId)
                if (order <= 0) return suppressed()
            }
        }
        return AppResult.Success(
            CollaborationMutedNotificationPreparation.Ready(
                plan.copy(previous = previous?.cleanupReference())
            )
        )
    }

    suspend fun confirm(plan: CollaborationMutedNotificationPlan): AppResult<Unit> {
        val current =
            source(plan.parent, plan.access, plan.conversationId, plan.roomId, plan.eventId)
        if (current is AppResult.Failed) return current
        if (
            (current as AppResult.Success).value !=
                CollaborationMutedNotificationPreparation.Ready(plan.copy(previous = null))
        )
            return conflict()
        return AppResult.Success(Unit)
    }

    private suspend fun source(
        parent: CollaborationNativeParent,
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        eventId: String,
    ): AppResult<CollaborationMutedNotificationPreparation> {
        if (!validCollaborationReadSelection(conversationId, roomId, listOf(eventId), false))
            return notificationPublicationFailure(FailureKind.Validation)
        guard(parent, access).let { if (it is AppResult.Failed) return it }
        val listed = checked(parent, access) { lists.get(access, conversationId) }
        if (listed is AppResult.Failed) return listed
        val summary = (listed as AppResult.Success).value
        if (!ownsCollaborationReadConversation(access, conversationId, summary))
            return unavailable()
        val configured = checked(parent, access) { settings.read(access, conversationId) }
        if (configured is AppResult.Failed) return configured
        val preference = (configured as AppResult.Success).value
        if (!validCollaborationNotificationState(preference)) return unavailable()
        if (!preference.settings.isMutedAt(clock.now())) return conflict()
        val loaded =
            checked(parent, access) { history.getByEvent(access, conversationId, roomId, eventId) }
        if (loaded is AppResult.Failed) return loaded
        val raw = (loaded as AppResult.Success).value
        if (raw == null) {
            val fact =
                checked(parent, access) {
                    history.getEventFact(access, conversationId, roomId, eventId)
                }
            if (fact is AppResult.Failed) return fact
            val value = (fact as AppResult.Success).value
            if (value != null && !validCollaborationReadFact(value, eventId)) return unavailable()
            return if (value?.redacted == true) suppressed()
            else AppResult.Success(CollaborationMutedNotificationPreparation.PendingHistory)
        }
        if (raw.eventIds.size !in 1..128) return unavailable()
        val original = raw.copy(eventIds = raw.eventIds.toList())
        if (!validCollaborationHistoryMessage(original) || eventId !in original.eventIds)
            return unavailable()
        if (original.redacted) return suppressed()
        val repeated = checked(parent, access) { settings.read(access, conversationId) }
        if (repeated is AppResult.Failed) return repeated
        if (
            (repeated as AppResult.Success).value != preference ||
                !preference.settings.isMutedAt(clock.now())
        )
            return conflict()
        val found = checked(parent, access) { lists.get(access, conversationId) }
        if (found is AppResult.Failed) return found
        if ((found as AppResult.Success).value != summary) return conflict()
        return AppResult.Success(
            CollaborationMutedNotificationPreparation.Ready(
                CollaborationMutedNotificationPlan(
                    parent,
                    access,
                    conversationId,
                    roomId,
                    original.primaryEventId,
                    original.contentHash,
                    original.timestamp,
                    preference,
                )
            )
        )
    }

    suspend fun <T> checked(
        parent: CollaborationNativeParent,
        access: CollaborationLocalAccess,
        operation: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val result = operation()
        guard(parent, access).let { if (it is AppResult.Failed) return it }
        return result
    }

    private fun conflict() = notificationPublicationFailure(FailureKind.Conflict)

    private fun unavailable() = notificationPublicationFailure(FailureKind.Unavailable)

    private fun suppressed() =
        AppResult.Success(CollaborationMutedNotificationPreparation.AlreadySuppressed)
}

internal fun CollaborationNativeParent.recoveryAccess() =
    CollaborationRecoveryAccess(sessionId, audience, accountId)
