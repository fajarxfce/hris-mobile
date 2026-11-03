package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationPublicationRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Cleanup discovery under current account ownership, independent of a company content grant. */
class ReadCollaborationNotificationPublications(
    private val journal: CollaborationNotificationPublicationRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        parent: CollaborationNativeParent,
        after: String? = null,
        limit: Int = 4,
    ): AppResult<CollaborationNotificationPublicationReferences> {
        currentCoroutineContext().ensureActive()
        if (
            limit !in 1..4 || (after != null && !validCollaborationNotificationPublicationId(after))
        )
            return notificationPublicationFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) { read(parent, after, limit) }
            ?: notificationPublicationFailure(FailureKind.Unavailable)
    }

    /** Exact cleanup lookup only. Native action authentication remains the caller's policy. */
    suspend operator fun invoke(
        parent: CollaborationNativeParent,
        target: CollaborationNotificationActionTarget,
    ): AppResult<CollaborationNotificationPublicationReference?> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationNotificationTarget(target))
            return notificationPublicationFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) {
            sessions.guardPushParent(server, build, parent).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            val access =
                CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
            val found = journal.read(access, target.companyId, target.conversationId)
            sessions.guardPushParent(server, build, parent).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (found is AppResult.Failed) return@withTimeoutOrNull found
            val current =
                (found as AppResult.Success).value
                    ?: return@withTimeoutOrNull AppResult.Success(null)
            if (
                !validCollaborationNotificationPublication(current) ||
                    current.body.parent.accountId != parent.accountId ||
                    current.body.parent.audience != parent.audience
            )
                return@withTimeoutOrNull notificationPublicationFailure(FailureKind.Unavailable)
            if (current.body.target != target) return@withTimeoutOrNull AppResult.Success(null)
            if (current.body.parent != parent)
                return@withTimeoutOrNull notificationPublicationFailure(FailureKind.Conflict)
            AppResult.Success(current.cleanupReference())
        } ?: notificationPublicationFailure(FailureKind.Timeout)
    }

    private suspend fun read(
        parent: CollaborationNativeParent,
        after: String?,
        limit: Int,
    ): AppResult<CollaborationNotificationPublicationReferences> {
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        val access =
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        val found = journal.page(access, after, limit, preparedOnly = false)
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        if (found is AppResult.Failed) return found
        val page = (found as AppResult.Success).value
        if (page.publications.size > limit)
            return notificationPublicationFailure(FailureKind.Unavailable)
        val entries = page.publications.toList()
        val ids = entries.map { it.id }
        if (
            entries.size > limit ||
                ids != ids.distinct().sorted() ||
                ids.any { after != null && it <= after } ||
                (page.nextAfter != null && (entries.isEmpty() || page.nextAfter != ids.last()))
        )
            return notificationPublicationFailure(FailureKind.Unavailable)
        if (
            entries.any {
                !validCollaborationNotificationPublication(it) ||
                    !it.body.state.isPendingPublication() ||
                    it.body.parent.accountId != parent.accountId ||
                    it.body.parent.audience != parent.audience
            }
        )
            return notificationPublicationFailure(FailureKind.Unavailable)
        currentCoroutineContext().ensureActive()
        return AppResult.Success(
            CollaborationNotificationPublicationReferences(
                parent,
                entries.map { it.cleanupReference() },
                page.nextAfter,
            )
        )
    }
}
