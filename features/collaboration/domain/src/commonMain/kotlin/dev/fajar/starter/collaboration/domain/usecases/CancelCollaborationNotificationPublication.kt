package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationPublicationRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/**
 * Retires an exact owned watermark without returning its preview or authorizing a native action.
 */
class CancelCollaborationNotificationPublication(
    private val journal: CollaborationNotificationPublicationRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        parent: CollaborationNativeParent,
        expected: CollaborationNotificationPublicationReference,
    ): AppResult<CollaborationNotificationPublicationSettlement> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationNotificationReference(expected))
            return notificationPublicationFailure(FailureKind.Validation)
        if (
            expected.parent.accountId != parent.accountId ||
                expected.parent.audience != parent.audience
        )
            return notificationPublicationFailure(FailureKind.Unauthorized)
        if (!execution.tryLock()) return notificationPublicationFailure(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(8_000) { cancel(parent, expected) }
                ?: notificationPublicationFailure(FailureKind.Unavailable)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun cancel(
        parent: CollaborationNativeParent,
        expected: CollaborationNotificationPublicationReference,
    ): AppResult<CollaborationNotificationPublicationSettlement> {
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        val access =
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        val found = journal.read(access, expected.target.companyId, expected.target.conversationId)
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        if (found is AppResult.Failed) return found
        val current = (found as AppResult.Success).value
        if (current != null && !validCollaborationNotificationPublication(current))
            return notificationPublicationFailure(FailureKind.Unavailable)
        if (current?.cleanupReference() != expected)
            return AppResult.Success(CollaborationNotificationPublicationSettlement.Contended)
        requireNotNull(current)
        if (!current.body.state.isActivePublication())
            return AppResult.Success(current.cleanupReceipt())
        val desired =
            current.body.copy(
                state = CollaborationNotificationPublicationState.Cancelled,
                message = null,
                actions = null,
            )
        val saved = journal.replace(access, current, desired)
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        if (saved is AppResult.Failed) return saved
        return when (val write = (saved as AppResult.Success).value) {
            CollaborationNotificationPublicationWrite.Contended ->
                AppResult.Success(CollaborationNotificationPublicationSettlement.Contended)
            CollaborationNotificationPublicationWrite.CapacityExceeded ->
                notificationPublicationFailure(FailureKind.Storage)
            is CollaborationNotificationPublicationWrite.Stored -> {
                val stored = write.publication
                if (
                    !validCollaborationNotificationPublication(stored) ||
                        stored.body != desired ||
                        stored.id != current.id ||
                        stored.version.generation != current.version.generation ||
                        stored.version.revision <= current.version.revision
                )
                    notificationPublicationFailure(FailureKind.Conflict)
                else AppResult.Success(stored.cleanupReceipt())
            }
        }
    }
}
