@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Retires at most 32 proved original messages; edits keep their independent evidence contract. */
class RetireCollaborationSpaceOriginals(
    private val messages: CollaborationSpaceMessageRepository,
    private val completions: CollaborationSpaceMessageCompletionRepository,
    private val receipts: CollaborationSpaceReceiptRepository,
    private val retirement: CollaborationSpaceReceiptRetirementRepository,
    private val history: CollaborationSpaceHistoryRepository,
    private val progress: CollaborationSpaceInboxRepairProgressRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
    ): AppResult<Int> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationSpaceReceiptLedger(access, conversationId, emptyList()))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        admitted(access).let { if (it is AppResult.Failed) return it }
        val read = receipts.read(access, conversationId)
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val journal = (read as AppResult.Success).value
        if (!validCollaborationSpaceReceiptLedger(access, conversationId, journal))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
        val captured = journal.map { it.copy(eventIds = it.eventIds.toList()) }
        val batch =
            mutableListOf<
                Pair<CollaborationSpaceMessageCompletion, CollaborationSpaceMessageReceipt>
            >()
        for (receipt in captured) {
            if (receipt.intent.editTarget != null) continue
            val prepared = prepareOriginal(access, receipt)
            if (prepared is AppResult.Failed) return prepared
            (prepared as AppResult.Success).value?.let { batch += it to receipt }
            if (batch.size == 32) break
        }
        if (batch.isEmpty()) return AppResult.Success(0)
        for ((completion, _) in batch) {
            val archived = archiveOriginal(access, completion)
            if (archived is AppResult.Failed) return archived
        }
        admitted(access).let { if (it is AppResult.Failed) return it }
        val removed = retirement.removeReceipts(access, conversationId, batch.map { it.second })
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (removed is AppResult.Failed) return removed
        if ((removed as AppResult.Success).value) return AppResult.Success(batch.size)
        // A concurrent exact cleanup can win. Confirm absence once; a new alias with the same
        // original ID still owns its journal entry and cannot be removed by this captured batch.
        val remaining = receipts.read(access, conversationId)
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (remaining is AppResult.Failed) return remaining
        val current = (remaining as AppResult.Success).value
        if (!validCollaborationSpaceReceiptLedger(access, conversationId, current))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
        val ids = batch.map { it.second.intent.id }.toSet()
        return if (current.none { it.intent.id in ids }) AppResult.Success(batch.size)
        else
            collaborationRecoveryFailure(
                "client.collaboration_receipt_unresolved",
                FailureKind.Conflict,
            )
    }

    private suspend fun prepareOriginal(
        access: CollaborationSpaceLocalAccess,
        receipt: CollaborationSpaceMessageReceipt,
    ): AppResult<CollaborationSpaceMessageCompletion?> {
        admitted(access).let { if (it is AppResult.Failed) return it }
        val archived = completions.readCompletion(access, receipt.intent.id)
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (archived is AppResult.Failed) return archived
        val previous = (archived as AppResult.Success).value
        if (previous != null && !validCollaborationSpaceMessageCompletion(access, previous))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
        val detached = previous?.copy(eventIds = previous.eventIds.toList())
        val read =
            if (detached != null) AppResult.Success(detached.record)
            else messages.get(access, receipt.intent.id)
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (read is AppResult.Failed) return read
        val record = (read as AppResult.Success).value
        if (
            record == null ||
                !validCollaborationSpaceMessageRecord(access, record) ||
                record.intent != receipt.intent
        )
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
        val sent = record.state as? CollaborationMessageState.Sent ?: return AppResult.Success(null)
        val original =
            history.getByLogicalId(
                access,
                receipt.intent.conversationId,
                sent.device.roomId,
                requireNotNull(sent.origin).sender,
                receipt.intent.id,
            )
        admitted(access).let { if (it is AppResult.Failed) return it }
        if (original is AppResult.Failed) return original
        val retained = (original as AppResult.Success).value ?: return AppResult.Success(null)
        if (!validCollaborationHistoryMessage(retained))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
        val prepared =
            collaborationSpaceOriginalRetirementCompletion(
                access,
                record,
                receipt,
                retained,
                detached,
            )
                ?: return collaborationRecoveryFailure(
                    "client.collaboration_receipt_unresolved",
                    FailureKind.Conflict,
                )
        return AppResult.Success(prepared)
    }

    private suspend fun archiveOriginal(
        access: CollaborationSpaceLocalAccess,
        completion: CollaborationSpaceMessageCompletion,
    ): AppResult<Unit> {
        val demand = Uuid.random().toString()
        repeat(3) {
            admitted(access).let { if (it is AppResult.Failed) return it }
            val archived = completions.readCompletion(access, completion.record.intent.id)
            admitted(access).let { if (it is AppResult.Failed) return it }
            if (archived is AppResult.Failed) return archived
            val currentArchive = (archived as AppResult.Success).value
            if (
                currentArchive != null &&
                    !validCollaborationSpaceMessageCompletion(access, currentArchive)
            )
                return collaborationRecoveryFailure(
                    "client.collaboration_invalid_response",
                    FailureKind.Unexpected,
                )
            if (currentArchive == completion) return AppResult.Success(Unit)
            if (currentArchive != null)
                return collaborationRecoveryFailure(
                    "client.collaboration_receipt_unresolved",
                    FailureKind.Conflict,
                )
            val read = progress.current(access)
            admitted(access).let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return read
            val current = (read as AppResult.Success).value
            if (current != null && !validCollaborationSpaceInboxRepairProgress(current))
                return collaborationRecoveryFailure(
                    "client.collaboration_invalid_response",
                    FailureKind.Unexpected,
                )
            val prepared =
                collaborationSpaceInboxRepairProgress(
                    current,
                    CollaborationSpaceInboxRepairProgressChange.Request(demand),
                    Uuid.random().toString(),
                )
            if (prepared is AppResult.Failed) return prepared
            currentCoroutineContext().ensureActive()
            val saved =
                completions.archiveCompletion(
                    access,
                    completion,
                    CollaborationSpaceInboxRepairPublication(
                        current,
                        (prepared as AppResult.Success).value,
                    ),
                )
            admitted(access).let { if (it is AppResult.Failed) return it }
            if (saved is AppResult.Failed) return saved
            if ((saved as AppResult.Success).value) return AppResult.Success(Unit)
        }
        return collaborationRecoveryFailure(
            "client.collaboration_receipt_unresolved",
            FailureKind.Conflict,
        )
    }

    private suspend fun admitted(access: CollaborationSpaceLocalAccess): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val identity = sessions.current()
        currentCoroutineContext().ensureActive()
        if (identity is AppResult.Failed) return identity
        val owned =
            localCollaborationSpaceAccess(
                access,
                (identity as AppResult.Success).value,
                server.audience,
            )
        if (owned is AppResult.Failed) return owned
        val read = directory.admission(access)
        currentCoroutineContext().ensureActive()
        if (read is AppResult.Failed) return read
        val gate = (read as AppResult.Success).value
        if (!validCollaborationSpaceDirectoryAdmission(gate))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_response",
                FailureKind.Unexpected,
            )
        gate.denial?.let {
            return AppResult.Failed(it)
        }
        val latest = sessions.current()
        currentCoroutineContext().ensureActive()
        if (latest is AppResult.Failed) return latest
        val current =
            localCollaborationSpaceAccess(
                access,
                (latest as AppResult.Success).value,
                server.audience,
            )
        return if (current is AppResult.Failed) current else AppResult.Success(Unit)
    }
}
