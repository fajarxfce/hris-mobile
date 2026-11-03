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
 * Point-in-time local validation. The native publisher still owns serialized submission/cleanup.
 */
class ValidateCollaborationNotificationPublication(
    private val journal: CollaborationNotificationPublicationRepository,
    private val actions: CollaborationNotificationActionRepository,
    settings: CollaborationNotificationSettingsRepository,
    reads: CollaborationReadStateRepository,
    history: CollaborationHistoryRepository,
    identities: CollaborationIdentityRepository,
    lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    clock: Clock,
) {
    private val authority =
        CollaborationNotificationPublicationAuthority(
            settings,
            reads,
            history,
            identities,
            lists,
            sessions,
            workspaces,
            server,
            build,
            clock,
        )

    suspend operator fun invoke(expected: CollaborationNotificationPublication): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationNotificationPublication(expected) ||
                !expected.body.state.isActivePublication()
        )
            return notificationPublicationFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) { validate(expected, expected) }
            ?: notificationPublicationFailure(FailureKind.Unavailable)
    }

    /** Rechecks the original source after an exact inactive write; never authorizes display. */
    suspend fun afterCancellation(
        original: CollaborationNotificationPublication,
        receipt: CollaborationNotificationPublicationSettlement.Settled,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationNotificationPublication(original) ||
                !original.body.state.isActivePublication() ||
                receipt.id != original.id ||
                receipt.notificationId != original.body.target.notificationId ||
                receipt.renderId != original.body.target.renderId ||
                receipt.state != CollaborationNotificationPublicationState.Cancelled ||
                receipt.version.generation != original.version.generation ||
                receipt.version.revision <= original.version.revision
        )
            return notificationPublicationFailure(FailureKind.Validation)
        val retired =
            original.copy(
                version = receipt.version,
                body =
                    original.body.copy(
                        state = CollaborationNotificationPublicationState.Cancelled,
                        message = null,
                        actions = null,
                    ),
            )
        if (!validCollaborationNotificationPublication(retired))
            return notificationPublicationFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) { validate(original, retired) }
            ?: notificationPublicationFailure(FailureKind.Unavailable)
    }

    private suspend fun validate(
        expected: CollaborationNotificationPublication,
        retained: CollaborationNotificationPublication,
    ): AppResult<Unit> {
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        if (parent != expected.body.parent)
            return notificationPublicationFailure(FailureKind.Unauthorized)
        val message = requireNotNull(expected.body.message)
        authority.guard(parent, message.access).let { if (it is AppResult.Failed) return it }
        exact(retained, message).let { if (it is AppResult.Failed) return it }
        val inspected = authority.inspect(parent, message)
        if (inspected is AppResult.Failed) return inspected
        val original = (inspected as AppResult.Success).value
        if (original.timestamp != expected.body.timestamp)
            return notificationPublicationFailure(FailureKind.Conflict)
        authority.authenticate(expected.body, actions).let { if (it is AppResult.Failed) return it }
        val confirmed = authority.inspect(parent, message)
        if (confirmed is AppResult.Failed) return confirmed
        if ((confirmed as AppResult.Success).value != original)
            return notificationPublicationFailure(FailureKind.Conflict)
        return exact(retained, message)
    }

    private suspend fun exact(
        expected: CollaborationNotificationPublication,
        message: CollaborationMessageNotification,
    ): AppResult<Unit> {
        val parent = expected.body.parent
        val found =
            authority.checked(parent, message) {
                journal.read(
                    CollaborationRecoveryAccess(
                        parent.sessionId,
                        parent.audience,
                        parent.accountId,
                    ),
                    message.access.companyId,
                    message.conversationId,
                )
            }
        if (found is AppResult.Failed) return found
        return if ((found as AppResult.Success).value == expected) AppResult.Success(Unit)
        else notificationPublicationFailure(FailureKind.Conflict)
    }
}
