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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Frees at most 32 completed commands/receipts using owned local evidence; never starts transport.
 */
class RetireCollaborationMessages(
    private val messages: CollaborationMessageRepository,
    private val completions: CollaborationMessageCompletionRepository,
    private val receipts: CollaborationReceiptRepository,
    private val retirement: CollaborationReceiptRetirementRepository,
    private val history: CollaborationHistoryRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val edits: CollaborationEditEvidenceRepository,
    private val editTargets: CollaborationEditTargetRepository,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        workspaceId: String,
        conversationId: String,
        acknowledgedEdits: CollaborationEditAcknowledgement? = null,
        expectedAccess: CollaborationLocalAccess? = null,
    ): AppResult<Int> =
        invoke(
            CollaborationLocalScope.Selected(workspaceId),
            conversationId,
            acknowledgedEdits,
            expectedAccess,
        )

    suspend operator fun invoke(
        scope: CollaborationLocalScope,
        conversationId: String,
        acknowledgedEdits: CollaborationEditAcknowledgement? = null,
        expectedAccess: CollaborationLocalAccess? = null,
    ): AppResult<Int> =
        execution.withLock {
            if (
                !validCollaborationMessageId(conversationId) ||
                    (acknowledgedEdits != null &&
                        (expectedAccess == null ||
                            !validCollaborationEditAcknowledgement(acknowledgedEdits)))
            )
                return@withLock failed()
            val reviewed =
                acknowledgedEdits?.copy(operationIds = acknowledgedEdits.operationIds.toList())
            val captured = capture(scope)
            if (captured is AppResult.Failed) return@withLock captured
            val access = (captured as AppResult.Success).value
            if (expectedAccess != null && access != expectedAccess)
                return@withLock collaborationSetupFailure(
                    "client.session_changed",
                    FailureKind.Unauthorized,
                )
            continuation(scope, access).let { if (it is AppResult.Failed) return@withLock it }
            val read =
                checked(scope, access) {
                    when (val result = receipts.read(access, conversationId)) {
                        is AppResult.Failed -> result
                        is AppResult.Success ->
                            AppResult.Success(
                                result.value.map { it.copy(eventIds = it.eventIds.toList()) }
                            )
                    }
                }
            if (read is AppResult.Failed) return@withLock read
            val journal = (read as AppResult.Success).value
            if (
                !validCollaborationReceiptLedger(journal) ||
                    journal.any {
                        it.intent.owner != access.messageOwner() ||
                            it.intent.conversationId != conversationId
                    }
            )
                return@withLock failed()
            val prepared = prepare(scope, access, conversationId, journal, reviewed)
            if (prepared is AppResult.Failed) return@withLock prepared
            val batch = (prepared as AppResult.Success).value
            if (batch.isEmpty()) return@withLock AppResult.Success(0)
            for ((proof, _) in batch) {
                val archived =
                    checked(scope, access) { completions.archiveCompletion(access, proof) }
                if (archived is AppResult.Failed) return@withLock archived
                if (!(archived as AppResult.Success).value) return@withLock failed()
            }
            val removed =
                checked(scope, access) {
                    retirement.removeReceipts(access, conversationId, batch.map { it.second })
                }
            if (removed is AppResult.Failed) return@withLock removed
            if (!(removed as AppResult.Success).value) return@withLock failed()
            AppResult.Success(batch.size)
        }

    private suspend fun prepare(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        conversationId: String,
        journal: List<CollaborationMessageReceipt>,
        acknowledgedEdits: CollaborationEditAcknowledgement?,
    ): AppResult<List<Pair<CollaborationMessageCompletion, CollaborationMessageReceipt>>> {
        val batch =
            mutableListOf<Pair<CollaborationMessageCompletion, CollaborationMessageReceipt>>()
        for (receipt in journal) {
            if (acknowledgedEdits != null && receipt.intent.id !in acknowledgedEdits.operationIds)
                continue
            val prepared = candidate(scope, access, conversationId, receipt, acknowledgedEdits)
            if (prepared is AppResult.Failed) return prepared
            (prepared as AppResult.Success).value?.let { batch += it to receipt }
            if (batch.size == 32) break
        }
        return AppResult.Success(batch.toList())
    }

    private suspend fun candidate(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        conversationId: String,
        receipt: CollaborationMessageReceipt,
        acknowledgedEdits: CollaborationEditAcknowledgement?,
    ): AppResult<CollaborationMessageCompletion?> {
        val retained =
            checked(scope, access) {
                when (val result = completions.readCompletion(access, receipt.intent.id)) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(
                            result.value?.let { it.copy(eventIds = it.eventIds.toList()) }
                        )
                }
            }
        if (retained is AppResult.Failed) return retained
        val previous = (retained as AppResult.Success).value
        val read =
            if (previous == null) checked(scope, access) { messages.get(access, receipt.intent.id) }
            else AppResult.Success(previous.record)
        if (read is AppResult.Failed) return read
        val record = (read as AppResult.Success).value ?: return failed()
        if (record.intent != receipt.intent || !validCollaborationMessageState(record.state))
            return failed()
        val sent = record.state as? CollaborationMessageState.Sent ?: return AppResult.Success(null)
        val origin = sent.origin ?: return AppResult.Success(null)
        val target = record.intent.editTarget
        if (
            acknowledgedEdits != null &&
                (target?.roomId != acknowledgedEdits.roomId ||
                    target.eventId != acknowledgedEdits.eventId ||
                    record.intent.id !in acknowledgedEdits.operationIds)
        )
            return failed()
        if (target != null) {
            val observed =
                checked(scope, access) {
                    when (
                        val result =
                            edits.getEditOperationEvidence(
                                access,
                                conversationId,
                                sent.device.roomId,
                                origin.sender,
                                record.intent.id,
                            )
                    ) {
                        is AppResult.Failed -> result
                        is AppResult.Success ->
                            if (result.value.size > 128) failed()
                            else AppResult.Success(result.value.toList())
                    }
                }
            if (observed is AppResult.Failed) return observed
            val evidence = (observed as AppResult.Success).value.toList()
            if (evidence.isEmpty()) return AppResult.Success(null)
            val completion =
                collaborationEditRetirementCompletion(record, receipt, evidence, previous)
                    ?: return failed()
            if (
                evidence.none { it.eligible } &&
                    evidence.any { !it.redacted } &&
                    acknowledgedEdits == null
            ) {
                val visible =
                    checked(scope, access) {
                        editTargets.getEditCandidate(
                            access,
                            conversationId,
                            target.roomId,
                            target.eventId,
                        )
                    }
                if (visible is AppResult.Failed) return visible
                // Keep a visible failed edit durable until its owner acknowledges it. Hidden or
                // redacted originals cannot leave an unreachable command consuming capacity.
                if ((visible as AppResult.Success).value != null) return AppResult.Success(null)
            }
            return AppResult.Success(completion)
        }
        val original =
            checked(scope, access) {
                when (
                    val result =
                        history.getByLogicalId(
                            access,
                            conversationId,
                            sent.device.roomId,
                            origin.sender,
                            record.intent.id,
                        )
                ) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(
                            result.value?.let { it.copy(eventIds = it.eventIds.toList()) }
                        )
                }
            }
        if (original is AppResult.Failed) return original
        val confirmed = (original as AppResult.Success).value ?: return AppResult.Success(null)
        val completion =
            collaborationRetirementCompletion(record, receipt, confirmed, previous)
                ?: return failed()
        return AppResult.Success(completion)
    }

    private suspend fun <T> checked(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
        operation: suspend () -> AppResult<T>,
    ): AppResult<T> {
        val result = operation()
        val current = continuation(scope, access)
        return if (current is AppResult.Failed) current else result
    }

    private suspend fun capture(scope: CollaborationLocalScope) =
        sessions.captureMessageAccess(scope, workspaces, server, build, clock)

    private suspend fun continuation(
        scope: CollaborationLocalScope,
        access: CollaborationLocalAccess,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(access)
        // Capture after gate I/O so an account replacement during that read cannot publish success.
        val current = capture(scope)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != access)
            return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private fun failed() = collaborationSetupFailure("client.collaboration_receipt_unresolved")
}
