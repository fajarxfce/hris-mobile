@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

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
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** The native owner removes the exact previous active render BEFORE this conditional write. */
class CommitCollaborationMutedNotification(
    private val journal: CollaborationNotificationPublicationRepository,
    settings: CollaborationNotificationSettingsRepository,
    history: CollaborationHistoryRepository,
    lists: CollaborationListRepository,
    sessions: SessionRepository,
    workspaces: WorkspaceRepository,
    server: IdentityServer,
    build: AppBuild,
    clock: Clock,
) {
    private val execution = Mutex()
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

    suspend operator fun invoke(plan: CollaborationMutedNotificationPlan): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        if (!execution.tryLock()) return notificationPublicationFailure(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(8_000) { commit(plan) }
                ?: notificationPublicationFailure(FailureKind.Unavailable)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun commit(plan: CollaborationMutedNotificationPlan): AppResult<Unit> {
        val inspected =
            authority.prepare(
                plan.parent,
                plan.access,
                plan.conversationId,
                plan.roomId,
                plan.eventId,
            )
        if (inspected is AppResult.Failed) return inspected
        if (
            (inspected as AppResult.Success).value !=
                CollaborationMutedNotificationPreparation.Ready(plan)
        )
            return notificationPublicationFailure(FailureKind.Conflict)
        val loaded =
            authority.checked(plan.parent, plan.access) {
                journal.read(
                    plan.parent.recoveryAccess(),
                    plan.access.companyId,
                    plan.conversationId,
                )
            }
        if (loaded is AppResult.Failed) return loaded
        val previous = (loaded as AppResult.Success).value
        if (previous?.cleanupReference() != plan.previous)
            return notificationPublicationFailure(FailureKind.Conflict)
        authority.confirm(plan).let { if (it is AppResult.Failed) return it }
        val body =
            CollaborationNotificationPublicationBody(
                plan.parent,
                CollaborationNotificationActionTarget(
                    plan.access.companyId,
                    plan.conversationId,
                    plan.roomId,
                    plan.eventId,
                    previous?.body?.target?.notificationId ?: Uuid.random().toString(),
                    Uuid.random().toString(),
                ),
                plan.contentHash,
                plan.timestamp,
                CollaborationNotificationPublicationState.Cancelled,
            )
        val saved =
            authority.checked(plan.parent, plan.access) {
                journal.replace(plan.parent.recoveryAccess(), previous, body)
            }
        if (saved is AppResult.Failed) return saved
        return when (val write = (saved as AppResult.Success).value) {
            CollaborationNotificationPublicationWrite.Contended ->
                notificationPublicationFailure(FailureKind.Conflict)
            CollaborationNotificationPublicationWrite.CapacityExceeded ->
                notificationPublicationFailure(FailureKind.Storage)
            is CollaborationNotificationPublicationWrite.Stored -> {
                val stored = write.publication
                if (
                    !validCollaborationNotificationPublication(stored) ||
                        stored.body != body ||
                        (previous != null &&
                            (stored.id != previous.id ||
                                stored.version.generation != previous.version.generation ||
                                stored.version.revision <= previous.version.revision))
                )
                    return notificationPublicationFailure(FailureKind.Conflict)
                authority.confirm(plan)
            }
        }
    }
}
