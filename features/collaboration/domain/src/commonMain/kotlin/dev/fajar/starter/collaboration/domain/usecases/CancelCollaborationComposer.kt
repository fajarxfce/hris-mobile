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

/** Cancel one retained original locally; staging or transport evidence cannot be called unsent. */
class CancelCollaborationComposer(
    private val outbox: CollaborationComposerOutboxRepository,
    private val messages: CollaborationMessageRepository,
    private val prevention: CollaborationMessagePreventionRepository,
    private val cancellations: CollaborationMessageCancellationRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        access: CollaborationLocalAccess,
        original: CollaborationComposerSubmission,
    ): AppResult<Unit> {
        if (!validCollaborationComposerSubmission(original)) return invalidDraft()
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        val retained = outbox.get(access, original.id)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (retained is AppResult.Failed) return retained
        val slot = (retained as AppResult.Success).value
        if (slot != null && slot != original) return changedDraft()
        val intent = collaborationComposerIntent(access, original)
        val initial = messages.get(access, original.id)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (initial is AppResult.Failed) return initial
        var record = (initial as AppResult.Success).value
        if (record != null && record.intent != intent) return changedDraft()
        if (slot == null && record == null) return unavailable()
        if (record == null) {
            val prevented = prevention.prevent(access, intent)
            guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
            // A Boolean is not full proof: prevention deliberately ignores the creation timestamp.
            // Read the authenticated original even after a lost result or a competing insertion.
            val after = messages.get(access, original.id)
            guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
            if (after is AppResult.Failed) return after
            record = (after as AppResult.Success).value
            if (record == null)
                return if (prevented is AppResult.Failed) prevented else unavailable()
            if (record.intent != intent) return changedDraft()
        }
        if (record.state != CollaborationMessageState.Cancelled) {
            if (!validCollaborationCancellation(record)) return unavailable()
            val cancelled = cancellations.cancel(access, record)
            guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
            val after = messages.get(access, original.id)
            guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
            if (after is AppResult.Failed) return after
            val proof = (after as AppResult.Success).value
            if (proof?.intent != intent || proof.state != CollaborationMessageState.Cancelled)
                return if (cancelled is AppResult.Failed) cancelled else unavailable()
        }
        val removed = outbox.remove(access, original)
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (removed is AppResult.Failed) return removed
        if (!(removed as AppResult.Success).value) {
            val remaining = outbox.get(access, original.id)
            guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
            if (remaining is AppResult.Failed) return remaining
            if ((remaining as AppResult.Success).value != null) return changedDraft()
        }
        return AppResult.Success(Unit)
    }

    private suspend fun guard(workspaceId: String, access: CollaborationLocalAccess) =
        sessions.checkDraftContinuation(
            workspaceId,
            access,
            lists,
            workspaces,
            server,
            build,
            clock,
        )

    private fun unavailable() =
        collaborationSetupFailure(
            "client.collaboration_cancellation_unavailable",
            FailureKind.Conflict,
        )
}
