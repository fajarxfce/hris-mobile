package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationPushPriorityRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Single snapshot CAS: late completion can never erase a newer hint or a new empty generation. */
class CompleteCollaborationPushPriority(
    private val priorities: CollaborationPushPriorityRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        snapshot: CollaborationPushSnapshot,
        hint: CollaborationPushHint,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        val before = snapshot.priority?.let { it.copy(hints = it.hints.toList()) }
        if (before == null || !validCollaborationPushPriority(before) || hint !in before.hints)
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_push_priority",
                FailureKind.Validation,
            )
        val guarded = sessions.guardPushParent(server, build, snapshot.parent)
        if (guarded is AppResult.Failed) return guarded
        val next = before.hints.filterNot { it == hint }
        val saved = priorities.replace(snapshot.parent, before, next)
        currentCoroutineContext().ensureActive()
        val after = sessions.guardPushParent(server, build, snapshot.parent)
        if (after is AppResult.Failed) return after
        if (saved is AppResult.Failed) return saved
        val value = (saved as AppResult.Success).value ?: return AppResult.Success(false)
        if (
            !validCollaborationPushPriority(value) ||
                value.hints != next ||
                value.generation == before.generation
        )
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_push_priority",
                FailureKind.Unexpected,
            )
        return AppResult.Success(true)
    }
}
