package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Local original scope only; this derives no content or delivery permission. */
fun collaborationSpaceRecoveryQueueAccess(
    runtime: CollaborationSpaceRuntimeReady,
    conversation: CollaborationSpaceConversation,
    binding: CollaborationSpaceRoomBinding,
    snapshot: CollaborationSpaceOriginalQueueSnapshot,
): AppResult<CollaborationLocalAccess> {
    val original = binding.original ?: return invalidCollaborationSpaceRecoveryDemand()
    if (
        runtime.owner != conversation.owner ||
            !validCollaborationSpaceRoomBinding(runtime.owner.localDirectoryAccess(), binding) ||
            binding.conversation != conversation.localMetadata()
    )
        return invalidCollaborationSpaceRecoveryDemand()
    val position = original.conversation.position
    val sender =
        "@hris_${position.companyId.replace("-", "")}_${runtime.owner.parent.accountId.replace("-", "")}:${original.room.serverName}"
    val matched =
        collaborationOriginalQueueLogicalIds(
            runtime,
            CollaborationSpaceOriginalConversation(
                runtime.owner,
                original.conversation,
                original.room,
            ),
            sender,
            snapshot,
        )
    if (matched is AppResult.Failed) return matched
    return AppResult.Success(
        CollaborationLocalAccess(
            runtime.owner.parent.sessionId,
            snapshot.original.binding.audience,
            snapshot.original.binding.accountId,
            position.companyId,
        )
    )
}

/** An empty SDK is checked separately. Every retained ACK must already have its Sent projection. */
fun collaborationSpaceRecoveryLedgerIdle(
    records: List<CollaborationMessageRecord>,
    receipts: List<CollaborationMessageReceipt>,
    completions: List<CollaborationMessageCompletion>,
): Boolean {
    val completed = completions.associateBy { it.record.intent.id }
    if (completed.size != completions.size) return false
    for (record in records) {
        if (record.state == CollaborationMessageState.Cancelled) continue
        if (
            record.state !is CollaborationMessageState.Sent ||
                collaborationOriginalAcknowledgement(
                    record,
                    receipts.find { it.intent.id == record.intent.id },
                    completed[record.intent.id],
                ) == null
        )
            return false
    }
    val active = records.associateBy { it.intent.id }
    return receipts.all { receipt ->
        val record =
            active[receipt.intent.id] ?: completed[receipt.intent.id]?.record ?: return@all false
        record.state is CollaborationMessageState.Sent &&
            collaborationOriginalAcknowledgement(record, receipt, completed[receipt.intent.id]) !=
                null
    }
}

internal fun invalidCollaborationSpaceRecoveryDemand() =
    collaborationRecoveryFailure(
        "client.collaboration_space_recovery_demand_changed",
        FailureKind.Conflict,
    )
