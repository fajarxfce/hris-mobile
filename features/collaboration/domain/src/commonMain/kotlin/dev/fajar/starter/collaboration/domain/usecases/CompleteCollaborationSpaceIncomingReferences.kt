package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Resolves one admitted batch and its finite pending seek; no provider or outgoing completion. */
class CompleteCollaborationSpaceIncomingReferences(
    private val history: CollaborationSpaceHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        key: CollaborationHistoryTraversal,
        source: CollaborationHistoryReferences,
        batch: CollaborationSpaceIncomingBatch?,
    ): AppResult<CollaborationSpaceIncomingReferenceCompletion> {
        currentCoroutineContext().ensureActive()
        if (source.events.size > 16 || (batch != null && batch.items.size > 16))
            return changed(FailureKind.Validation)
        val captured = source.copy(events = source.events.toList())
        val items =
            batch?.items?.map { item ->
                val outcome = item.outcome
                if (outcome is CollaborationIncomingHistoryOutcome.Retained) {
                    if (outcome.originalEventIds.size > 16 || outcome.replacementEventIds.size > 16)
                        return changed(FailureKind.Validation)
                    item.copy(
                        outcome =
                            outcome.copy(
                                originalEventIds = outcome.originalEventIds.toList(),
                                replacementEventIds = outcome.replacementEventIds.toList(),
                            )
                    )
                } else item
            }
        val observed = batch?.copy(items = requireNotNull(items))
        val planned = planCollaborationSpaceIncomingReferences(access, key, captured, observed)
        if (planned is AppResult.Failed) return planned
        val plan = (planned as AppResult.Success).value
        return withTimeoutOrNull(5_000) {
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (plan.resolved.isNotEmpty()) {
                val saved =
                    history.resolve(access, key, requireNotNull(captured.progress), plan.resolved)
                admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
                if (saved is AppResult.Failed) return@withTimeoutOrNull saved
                if (!(saved as AppResult.Success).value) return@withTimeoutOrNull changed()
            }
            val advanced =
                history.advanceIncomingReferences(
                    access,
                    key,
                    captured,
                    plan.retryRequired,
                    limit = 16,
                )
            admission(access).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (advanced is AppResult.Failed) return@withTimeoutOrNull advanced
            if (!(advanced as AppResult.Success).value) return@withTimeoutOrNull changed()
            AppResult.Success(
                CollaborationSpaceIncomingReferenceCompletion(
                    plan.resolved.size,
                    plan.unresolved,
                    plan.more,
                    plan.retryRequired,
                )
            )
        } ?: changed(FailureKind.Timeout)
    }

    private suspend fun admission(access: CollaborationSpaceLocalAccess): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = directory.admission(access)
        val current = sessions.current()
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success) {
            if (!validCollaborationSpaceDirectoryAdmission(gate.value)) return changed()
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        }
        if (current is AppResult.Failed) return current
        val owned =
            localCollaborationSpaceAccess(
                access,
                (current as AppResult.Success).value,
                server.audience,
            )
        if (owned is AppResult.Failed) return owned
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_incoming_references_changed", kind)
}
