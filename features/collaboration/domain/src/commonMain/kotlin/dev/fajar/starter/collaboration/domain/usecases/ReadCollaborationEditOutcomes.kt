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

/** Reads local replacement outcomes without acquiring credentials, a device or network work. */
class ReadCollaborationEditOutcomes(
    private val evidence: CollaborationEditEvidenceRepository,
    private val completions: CollaborationMessageCompletionRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        workspaceId: String,
        expected: CollaborationLocalAccess,
        conversationId: String,
        roomId: String?,
        records: List<CollaborationMessageRecord>,
        originals: List<CollaborationHistoryMessage>,
    ): AppResult<List<CollaborationEditOutcome>> {
        if (
            !validCollaborationMessageId(conversationId) ||
                records.size > 128 ||
                originals.size > 500
        )
            return unavailable()
        val commands = records.toList()
        val observed = originals.toList()
        if (
            commands.map { it.intent.id }.distinct().size != commands.size ||
                commands.any {
                    it.intent.owner != expected.messageOwner() ||
                        it.intent.conversationId != conversationId ||
                        !validCollaborationMessageId(it.intent.id) ||
                        !validCollaborationEditRecord(it) ||
                        !validCollaborationMessageState(it.state)
                }
        )
            return unavailable()
        guard(workspaceId, expected).let { if (it is AppResult.Failed) return it }
        val outcomes = mutableListOf<CollaborationEditOutcome>()
        val completed = mutableMapOf<String, CollaborationMessageCompletion?>()
        for (record in commands) {
            val target = record.intent.editTarget ?: continue
            if (record.state == CollaborationMessageState.Cancelled || target.roomId != roomId)
                continue
            val result = readOutcome(workspaceId, expected, conversationId, record)
            if (result is AppResult.Failed) return result
            val resultKind = (result as AppResult.Success).value
            val outcome =
                if (resultKind == CollaborationEditOutcomeKind.AWAITING_PROVIDER)
                    completedOutcome(workspaceId, expected, record, observed, completed)
                else result
            if (outcome is AppResult.Failed) return outcome
            val kind = (outcome as AppResult.Success).value
            outcomes += CollaborationEditOutcome(record.intent.id, kind)
        }
        guard(workspaceId, expected).let { if (it is AppResult.Failed) return it }
        return AppResult.Success(outcomes.toList())
    }

    private suspend fun completedOutcome(
        workspaceId: String,
        expected: CollaborationLocalAccess,
        record: CollaborationMessageRecord,
        originals: List<CollaborationHistoryMessage>,
        completed: MutableMap<String, CollaborationMessageCompletion?>,
    ): AppResult<CollaborationEditOutcomeKind> {
        val target = requireNotNull(record.intent.editTarget)
        val visible =
            originals.singleOrNull { matchesCollaborationEditTarget(it, target) }?.latestEdit?.edit
        val id =
            visible?.operationId?.takeIf {
                it != record.intent.id && visible.sender == target.sender
            }
        if (visible == null || id == null)
            return AppResult.Success(CollaborationEditOutcomeKind.AWAITING_PROVIDER)
        if (id !in completed) {
            val read = completions.readCompletion(expected, id)
            guard(workspaceId, expected).let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            completed[id] = (read as AppResult.Success).value
        }
        val superseded = superseded(record, completed[id], visible)
        if (superseded is AppResult.Failed) return superseded
        return AppResult.Success(
            if ((superseded as AppResult.Success).value) CollaborationEditOutcomeKind.SUPERSEDED
            else CollaborationEditOutcomeKind.AWAITING_PROVIDER
        )
    }

    private fun superseded(
        record: CollaborationMessageRecord,
        archived: CollaborationMessageCompletion?,
        visible: CollaborationMessageEdit,
    ): AppResult<Boolean> {
        val newer = archived?.record ?: return AppResult.Success(false)
        if (
            newer.intent.id != visible.operationId ||
                newer.intent.owner != record.intent.owner ||
                newer.intent.conversationId != record.intent.conversationId ||
                collaborationCompletionReceipt(archived) == null
        )
            return unavailable()
        val target = requireNotNull(record.intent.editTarget)
        val newTarget = newer.intent.editTarget
        return AppResult.Success(
            newTarget?.roomId == target.roomId &&
                newTarget.eventId == target.eventId &&
                newer.state.origin?.contentHash == visible.contentHash &&
                visible.eventId in archived.eventIds &&
                newer.intent.createdAt > record.intent.createdAt
        )
    }

    private suspend fun readOutcome(
        workspaceId: String,
        access: CollaborationLocalAccess,
        conversationId: String,
        record: CollaborationMessageRecord,
    ): AppResult<CollaborationEditOutcomeKind> {
        val target = requireNotNull(record.intent.editTarget)
        val origin =
            record.state.origin
                ?: return AppResult.Success(CollaborationEditOutcomeKind.AWAITING_PROVIDER)
        val read =
            evidence.getEditOperationEvidence(
                access,
                conversationId,
                target.roomId,
                origin.sender,
                record.intent.id,
            )
        val facts = (read as? AppResult.Success)?.value?.takeIf { it.size <= 128 }?.toList()
        guard(workspaceId, access).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        if (facts == null) return unavailable()
        if (facts.isEmpty())
            return AppResult.Success(CollaborationEditOutcomeKind.AWAITING_PROVIDER)
        if (!validCollaborationEditEvidence(record, facts)) return unavailable()
        return AppResult.Success(
            if (facts.any { it.eligible } || facts.all { it.redacted })
                CollaborationEditOutcomeKind.OBSERVED
            else CollaborationEditOutcomeKind.NOT_APPLIED
        )
    }

    private suspend fun guard(
        workspaceId: String,
        expected: CollaborationLocalAccess,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(expected)
        val current = sessions.captureMessageAccess(workspaceId, workspaces, server, build, clock)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != expected)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private fun unavailable() =
        collaborationSetupFailure("client.collaboration_edit_outcome_unavailable")
}
