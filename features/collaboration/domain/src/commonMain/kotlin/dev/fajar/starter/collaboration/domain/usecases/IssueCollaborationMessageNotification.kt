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

/**
 * Retains an admitted exact render before OS submission. The result is not a native owner lease.
 */
class IssueCollaborationMessageNotification(
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
    private val execution = Mutex()
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

    suspend operator fun invoke(
        scope: CollaborationLocalScope.Captured,
        message: CollaborationMessageNotification,
        /** Conditional reconciliation must not publish after its discovered reference changes. */
        expected: CollaborationNotificationPublicationReference? = null,
    ): AppResult<CollaborationNotificationIssuance> {
        currentCoroutineContext().ensureActive()
        if (
            scope.nativeSessionId == null ||
                scope.access != message.access ||
                !validCollaborationNotificationMessage(message)
        )
            return notificationPublicationFailure(FailureKind.Validation)
        if (
            expected != null &&
                (!validCollaborationNotificationReference(expected) ||
                    expected.target.companyId != message.access.companyId ||
                    expected.target.conversationId != message.conversationId ||
                    expected.parent.accountId != message.access.accountId ||
                    expected.parent.audience != message.access.audience)
        )
            return notificationPublicationFailure(FailureKind.Validation)
        if (!execution.tryLock()) return notificationPublicationFailure(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(10_000) {
                val captured = sessions.capturePushParent(server, build)
                if (captured is AppResult.Failed) return@withTimeoutOrNull captured
                val parent = (captured as AppResult.Success).value
                if (scope.nativeSessionId != parent.nativeSessionId)
                    return@withTimeoutOrNull notificationPublicationFailure(
                        FailureKind.Unauthorized
                    )
                issue(parent, message, expected)
            } ?: notificationPublicationFailure(FailureKind.Unavailable)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun issue(
        parent: CollaborationNativeParent,
        message: CollaborationMessageNotification,
        expected: CollaborationNotificationPublicationReference?,
    ): AppResult<CollaborationNotificationIssuance> {
        val inspected = authority.inspect(parent, message)
        if (inspected is AppResult.Failed) return inspected
        val original = (inspected as AppResult.Success).value
        val loaded =
            authority.checked(parent, message) {
                journal.read(parent.access(), message.access.companyId, message.conversationId)
            }
        if (loaded is AppResult.Failed) return loaded
        val previous = (loaded as AppResult.Success).value
        if (expected != null && previous?.cleanupReference() != expected)
            return AppResult.Success(CollaborationNotificationIssuance.Unchanged)
        val decision = collaborationNotificationReplacement(parent, message, original, previous)
        when (decision) {
            CollaborationNotificationReplacement.Ignore ->
                return AppResult.Success(CollaborationNotificationIssuance.Unchanged)
            CollaborationNotificationReplacement.Conflict ->
                return notificationPublicationFailure(FailureKind.Conflict)
            CollaborationNotificationReplacement.Reuse ->
                return reuse(parent, message, requireNotNull(previous), original)
            CollaborationNotificationReplacement.Replace -> Unit
        }
        val target =
            CollaborationNotificationActionTarget(
                message.access.companyId,
                message.conversationId,
                message.roomId,
                message.eventId,
                previous?.body?.target?.notificationId ?: Uuid.random().toString(),
                Uuid.random().toString(),
            )
        val sealed = seal(parent, message, target)
        if (sealed is AppResult.Failed) return sealed
        val body =
            CollaborationNotificationPublicationBody(
                parent,
                target,
                original.contentHash,
                original.timestamp,
                CollaborationNotificationPublicationState.Prepared,
                message,
                (sealed as AppResult.Success).value,
            )
        confirm(parent, message, original).let { if (it is AppResult.Failed) return it }
        val saved =
            authority.checked(parent, message) { journal.replace(parent.access(), previous, body) }
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
                confirm(parent, message, original).let { if (it is AppResult.Failed) return it }
                AppResult.Success(
                    CollaborationNotificationIssuance.Ready(
                        stored,
                        newOriginal = previous?.body?.target?.eventId != message.eventId,
                    )
                )
            }
        }
    }

    private suspend fun reuse(
        parent: CollaborationNativeParent,
        message: CollaborationMessageNotification,
        previous: CollaborationNotificationPublication,
        original: CollaborationHistoryMessage,
    ): AppResult<CollaborationNotificationIssuance> {
        authority.authenticate(previous.body, actions).let { if (it is AppResult.Failed) return it }
        confirm(parent, message, original).let { if (it is AppResult.Failed) return it }
        // An external writer may have replaced this render while its envelopes were being checked.
        val current =
            authority.checked(parent, message) {
                journal.read(parent.access(), message.access.companyId, message.conversationId)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != previous)
            return notificationPublicationFailure(FailureKind.Conflict)
        // After uncertain storage/OS delivery, absence is not proof that no alert occurred.
        return AppResult.Success(
            CollaborationNotificationIssuance.Ready(previous, newOriginal = false)
        )
    }

    private suspend fun confirm(
        parent: CollaborationNativeParent,
        message: CollaborationMessageNotification,
        original: CollaborationHistoryMessage,
    ): AppResult<Unit> {
        val inspected = authority.inspect(parent, message)
        if (inspected is AppResult.Failed) return inspected
        if ((inspected as AppResult.Success).value != original)
            return notificationPublicationFailure(FailureKind.Conflict)
        return AppResult.Success(Unit)
    }

    private suspend fun seal(
        parent: CollaborationNativeParent,
        message: CollaborationMessageNotification,
        target: CollaborationNotificationActionTarget,
    ): AppResult<CollaborationNotificationPublicationActions> {
        val requested =
            listOf(
                CollaborationNotificationAction.Reply(Uuid.random().toString(), target),
                CollaborationNotificationAction.MarkRead(Uuid.random().toString(), target),
                CollaborationNotificationAction.Mute(
                    Uuid.random().toString(),
                    target,
                    message.settingsVersion,
                    message.preview,
                ),
                CollaborationNotificationAction.Dismiss(Uuid.random().toString(), target),
            )
        val tokens = mutableListOf<CollaborationNotificationActionToken>()
        for (action in requested) {
            val sealed = authority.checked(parent, message) { actions.seal(parent, action) }
            if (sealed is AppResult.Failed) return sealed
            val token = (sealed as AppResult.Success).value
            val opened = authority.checked(parent, message) { actions.open(parent, token) }
            if (opened is AppResult.Failed) return opened
            if ((opened as AppResult.Success).value != action)
                return notificationPublicationFailure(FailureKind.Conflict)
            tokens += token
        }
        return AppResult.Success(
            CollaborationNotificationPublicationActions(tokens[0], tokens[1], tokens[2], tokens[3])
        )
    }
}

private fun CollaborationNativeParent.access() =
    CollaborationRecoveryAccess(sessionId, audience, accountId)
