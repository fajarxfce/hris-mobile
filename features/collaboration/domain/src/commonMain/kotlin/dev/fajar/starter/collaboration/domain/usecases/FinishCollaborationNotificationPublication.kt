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
 * Records an exact submission outcome or removes owned preview content, never authorizes display.
 */
class FinishCollaborationNotificationPublication(
    private val journal: CollaborationNotificationPublicationRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        expected: CollaborationNotificationPublication,
        state: CollaborationNotificationPublicationState,
    ): AppResult<CollaborationNotificationPublicationSettlement> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationNotificationPublication(expected) ||
                state == CollaborationNotificationPublicationState.Prepared ||
                state == CollaborationNotificationPublicationState.Dismissing
        )
            return notificationPublicationFailure(FailureKind.Validation)
        if (!execution.tryLock()) return notificationPublicationFailure(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(8_000) { finish(expected, state) }
                ?: notificationPublicationFailure(FailureKind.Unavailable)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun finish(
        expected: CollaborationNotificationPublication,
        state: CollaborationNotificationPublicationState,
    ): AppResult<CollaborationNotificationPublicationSettlement> {
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        val body = expected.body
        // Deleting owned data does not require a company content grant or the old native family.
        if (
            body.parent.audience != parent.audience ||
                body.parent.accountId != parent.accountId ||
                (state == CollaborationNotificationPublicationState.Published &&
                    body.parent != parent)
        )
            return notificationPublicationFailure(FailureKind.Unauthorized)
        if (
            state == CollaborationNotificationPublicationState.Published &&
                !body.state.isActivePublication()
        )
            return notificationPublicationFailure(FailureKind.Conflict)
        val access =
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        val found = journal.read(access, body.target.companyId, body.target.conversationId)
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        if (found is AppResult.Failed) return found
        val current = (found as AppResult.Success).value
        if (current != null && !validCollaborationNotificationPublication(current))
            return notificationPublicationFailure(FailureKind.Unavailable)
        if (current != expected)
            return AppResult.Success(CollaborationNotificationPublicationSettlement.Contended)
        if (body.state == state || !body.state.isActivePublication())
            return AppResult.Success(receipt(expected))
        val desired =
            body.copy(
                state = state,
                message =
                    body.message.takeIf {
                        state == CollaborationNotificationPublicationState.Published
                    },
                actions =
                    body.actions.takeIf {
                        state == CollaborationNotificationPublicationState.Published
                    },
            )
        val saved = journal.replace(access, expected, desired)
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
                        stored.id != expected.id ||
                        stored.version.generation != expected.version.generation ||
                        stored.version.revision <= expected.version.revision
                )
                    notificationPublicationFailure(FailureKind.Conflict)
                else AppResult.Success(receipt(stored))
            }
        }
    }

    private fun receipt(value: CollaborationNotificationPublication) =
        CollaborationNotificationPublicationSettlement.Settled(
            value.id,
            value.version,
            value.body.target.notificationId,
            value.body.target.renderId,
            value.body.state,
        )
}
