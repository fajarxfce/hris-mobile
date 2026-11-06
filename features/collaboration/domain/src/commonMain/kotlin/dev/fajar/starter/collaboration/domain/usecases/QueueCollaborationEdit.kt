package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One explicit offline replacement, retaining its identity across lost insertion responses. */
class QueueCollaborationEdit(
    private val messages: CollaborationMessageRepository,
    private val targets: CollaborationEditTargetRepository,
    private val identities: CollaborationIdentityRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val schedules: SyncScheduleRepository,
) {
    private val submissions = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        expected: CollaborationLocalAccess,
        conversationId: String,
        id: String,
        reviewed: CollaborationEditCandidate,
        text: String,
    ): AppResult<CollaborationMessageRecord> =
        submissions.withLock {
            if (
                !validCollaborationMessageId(id) ||
                    !validCollaborationMessageId(conversationId) ||
                    !validCollaborationText(text) ||
                    !validCollaborationText(reviewed.text) ||
                    !validCollaborationEditTarget(reviewed.target) ||
                    id == reviewed.target.originalLogicalId ||
                    text == reviewed.text
            )
                return@withLock failed(FailureKind.Validation)
            suspend fun guard() = current(workspaceId, expected)
            guard().let { if (it is AppResult.Failed) return@withLock it }
            val retained = messages.get(expected, id)
            guard().let { if (it is AppResult.Failed) return@withLock it }
            if (retained is AppResult.Failed) return@withLock retained
            (retained as AppResult.Success).value?.let {
                if (!matching(it, expected, conversationId, id, reviewed.target, text))
                    return@withLock failed()
                return@withLock wake(workspaceId, expected, it)
            }
            val direct = lists.get(expected, conversationId)
            guard().let { if (it is AppResult.Failed) return@withLock it }
            if (direct is AppResult.Failed) return@withLock direct
            if (
                !ownsCollaborationReadConversation(
                    expected,
                    conversationId,
                    (direct as AppResult.Success).value,
                )
            )
                return@withLock failed()
            val identity = identities.current(expected)
            guard().let { if (it is AppResult.Failed) return@withLock it }
            if (identity is AppResult.Failed) return@withLock identity
            val sender =
                (identity as AppResult.Success).value?.matrixUserId ?: return@withLock failed()
            val target = reviewed.target
            val read =
                targets.getEditCandidate(expected, conversationId, target.roomId, target.eventId)
            guard().let { if (it is AppResult.Failed) return@withLock it }
            if (read is AppResult.Failed) return@withLock read
            if (
                (read as AppResult.Success).value != reviewed ||
                    !canAuthorCollaborationEdit(target, sender, clock.now().toEpochMilliseconds())
            )
                return@withLock failed()
            val ordered = nextCreatedAt(workspaceId, expected, conversationId, target, sender)
            if (ordered is AppResult.Failed) return@withLock ordered
            val createdAt = (ordered as AppResult.Success).value
            val intent =
                CollaborationMessageIntent(
                    id,
                    expected.messageOwner(),
                    conversationId,
                    createdAt,
                    text,
                    target.replyToEventId,
                    target.forwarded,
                    target,
                )
            currentCoroutineContext().ensureActive()
            val inserted = messages.insert(expected, intent)
            guard().let { if (it is AppResult.Failed) return@withLock it }
            if (inserted is AppResult.Failed) return@withLock inserted
            when ((inserted as AppResult.Success).value) {
                CollaborationMessageInsertion.CAPACITY_EXCEEDED ->
                    return@withLock collaborationSetupFailure(
                        "client.command_queue_full",
                        FailureKind.Storage,
                    )
                CollaborationMessageInsertion.SCOPE_CHANGED ->
                    return@withLock collaborationSetupFailure(
                        "client.session_changed",
                        FailureKind.Unauthorized,
                    )
                CollaborationMessageInsertion.STORED -> Unit
            }
            val stored = messages.get(expected, id)
            guard().let { if (it is AppResult.Failed) return@withLock it }
            if (stored is AppResult.Failed) return@withLock stored
            val record = (stored as AppResult.Success).value ?: return@withLock failed()
            if (!matching(record, expected, conversationId, id, reviewed.target, text))
                return@withLock failed()
            wake(workspaceId, expected, record)
        }

    private suspend fun nextCreatedAt(
        workspaceId: String,
        expected: CollaborationLocalAccess,
        conversationId: String,
        target: CollaborationEditTarget,
        sender: String,
    ): AppResult<Instant> {
        val listed = messages.observe(expected, conversationId).first()
        val records = (listed as? AppResult.Success)?.value?.takeIf { it.size <= 128 }?.toList()
        current(workspaceId, expected).let { if (it is AppResult.Failed) return it }
        if (listed is AppResult.Failed) return listed
        if (records == null) return failed()
        if (
            records.map { it.intent.id }.distinct().size != records.size ||
                records.any {
                    it.intent.owner != expected.messageOwner() ||
                        it.intent.conversationId != conversationId
                }
        )
            return failed()
        // A backward clock adjustment must not reorder consecutive edits of one original.
        val latest =
            records
                .filter {
                    it.intent.editTarget?.let { t ->
                        t.roomId == target.roomId && t.eventId == target.eventId
                    } == true
                }
                .maxOfOrNull { it.intent.createdAt }
        val now = clock.now()
        if (!canAuthorCollaborationEdit(target, sender, now.toEpochMilliseconds())) return failed()
        return AppResult.Success(maxOf(now, latest?.plus(1.milliseconds) ?: now))
    }

    private fun matching(
        record: CollaborationMessageRecord,
        access: CollaborationLocalAccess,
        conversationId: String,
        id: String,
        target: CollaborationEditTarget,
        text: String,
    ) =
        record.intent.owner == access.messageOwner() &&
            record.intent.id == id &&
            record.intent.conversationId == conversationId &&
            record.intent.text == text &&
            record.intent.editTarget == target &&
            validCollaborationEditRecord(record)

    private suspend fun wake(
        workspaceId: String,
        expected: CollaborationLocalAccess,
        record: CollaborationMessageRecord,
    ): AppResult<CollaborationMessageRecord> {
        if (
            record.state !is CollaborationMessageState.Sent &&
                record.state != CollaborationMessageState.Cancelled
        ) {
            withTimeoutOrNull(1_500) { schedules.request(QueueCollaborationText.DELIVERY_KEY) }
            current(workspaceId, expected).let { if (it is AppResult.Failed) return it }
        }
        return AppResult.Success(record)
    }

    private suspend fun current(
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

    private fun failed(kind: FailureKind = FailureKind.Conflict) =
        collaborationSetupFailure("client.collaboration_edit_unavailable", kind)
}
