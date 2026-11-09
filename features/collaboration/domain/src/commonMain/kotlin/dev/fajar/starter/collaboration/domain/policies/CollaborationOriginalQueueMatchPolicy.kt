package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

fun validCollaborationOriginalQueueRequest(
    runtime: CollaborationSpaceRuntimeReady,
    position: CollaborationOriginalConversationPosition,
): Boolean =
    validCollaborationOriginalConversationOwner(runtime.owner) &&
        validCollaborationOriginalConversationPosition(position) &&
        originalQueueId(runtime.deviceId) &&
        originalQueueId(runtime.operationId)

/** Validate captured provenance before any immutable command lookup; never grants delivery. */
fun collaborationOriginalQueueLogicalIds(
    runtime: CollaborationSpaceRuntimeReady,
    conversation: CollaborationSpaceOriginalConversation,
    identity: CollaborationSpaceIdentity,
    snapshot: CollaborationSpaceOriginalQueueSnapshot,
): AppResult<List<String>> {
    val room = conversation.room ?: return mismatch("scope")
    if (
        !validCollaborationSpaceIdentity(runtime.owner, identity) ||
            identity.origin != CollaborationSpaceIdentityOrigin.LEGACY ||
            identity.legacyCompanyId != conversation.conversation.position.companyId ||
            identity.serverName != room.serverName
    )
        return mismatch("scope")
    return collaborationOriginalQueueLogicalIds(
        runtime,
        conversation,
        identity.matrixUserId,
        snapshot,
    )
}

/**
 * Exact original sender facts, without inventing an identity-selection timestamp or observation.
 */
fun collaborationOriginalQueueLogicalIds(
    runtime: CollaborationSpaceRuntimeReady,
    conversation: CollaborationSpaceOriginalConversation,
    matrixUserId: String,
    snapshot: CollaborationSpaceOriginalQueueSnapshot,
): AppResult<List<String>> {
    val position = conversation.conversation.position
    val room = conversation.room ?: return mismatch("scope")
    val physical = snapshot.original
    val binding = physical.binding
    val device = physical.device
    if (
        !validCollaborationOriginalQueueRequest(runtime, position) ||
            snapshot.runtime != runtime ||
            !validCollaborationSpaceOriginalConversation(conversation, runtime.owner) ||
            matrixUserId !=
                "@hris_${position.companyId.replace("-", "")}_${runtime.owner.parent.accountId.replace("-", "")}:${room.serverName}" ||
            binding.audience != runtime.owner.parent.audience ||
            binding.accountId != runtime.owner.parent.accountId ||
            binding.companyId != position.companyId ||
            !originalQueueId(binding.nativeSessionId) ||
            !validCollaborationMessageDevice(device) ||
            !originalQueueId(device.deviceId) ||
            !originalQueueId(device.bootstrapOperationId) ||
            device.nativeSessionId != binding.nativeSessionId ||
            device.roomId != room.roomId
    )
        return mismatch("scope")
    val originals = physical.messages
    if (
        originals.size > 128 ||
            originals.any {
                it.serializedContent.length !in 2..96_000 ||
                    !validCollaborationQueuedOriginal(it) ||
                    it.logicalId == null ||
                    !originalQueueId(it.logicalId) ||
                    it.origin.sender != matrixUserId
            } ||
            originals.map { it.transactionId }.distinct().size != originals.size ||
            originals.map { it.logicalId }.distinct().size != originals.size
    )
        return mismatch("inventory")
    return AppResult.Success(originals.map { requireNotNull(it.logicalId) })
}

/** Match the whole captured queue. No match here permits resume, settlement or a new ACK. */
fun matchCollaborationOriginalQueue(
    runtime: CollaborationSpaceRuntimeReady,
    conversation: CollaborationSpaceOriginalConversation,
    identity: CollaborationSpaceIdentity,
    snapshot: CollaborationSpaceOriginalQueueSnapshot,
    records: List<CollaborationMessageRecord>,
    completions: List<CollaborationMessageCompletion>,
): AppResult<CollaborationOriginalQueueMatch> {
    val checked = collaborationOriginalQueueLogicalIds(runtime, conversation, identity, snapshot)
    if (checked is AppResult.Failed) return checked
    return when (
        val matched =
            matchCollaborationOriginalMessages(
                runtime,
                conversation,
                identity.matrixUserId,
                snapshot,
                records,
                completions,
            )
    ) {
        is AppResult.Failed -> matched
        is AppResult.Success ->
            AppResult.Success(
                CollaborationOriginalQueueMatch(
                    runtime,
                    conversation,
                    identity,
                    snapshot.original.binding,
                    snapshot.original.device,
                    matched.value,
                )
            )
    }
}

/** Matches every captured original command and receipt; no permission or SDK side effect. */
fun matchCollaborationOriginalMessages(
    runtime: CollaborationSpaceRuntimeReady,
    conversation: CollaborationSpaceOriginalConversation,
    matrixUserId: String,
    snapshot: CollaborationSpaceOriginalQueueSnapshot,
    records: List<CollaborationMessageRecord>,
    completions: List<CollaborationMessageCompletion>,
): AppResult<List<CollaborationMatchedOriginalMessage>> {
    val requested =
        collaborationOriginalQueueLogicalIds(runtime, conversation, matrixUserId, snapshot)
    if (requested is AppResult.Failed) return requested
    val logicalIds = (requested as AppResult.Success).value
    val position = conversation.conversation.position
    val physical = snapshot.original
    val binding = physical.binding
    val device = physical.device
    val originals = physical.messages
    if (
        records.size != logicalIds.size ||
            completions.size > logicalIds.size ||
            records.map { it.intent.id }.toSet() != logicalIds.toSet() ||
            completions.any { it.eventIds.size !in 1..128 } ||
            completions.sumOf { it.eventIds.size } > 512 ||
            completions.map { it.record.intent.id }.distinct().size != completions.size
    )
        return mismatch("inventory")
    val sentIds =
        records.filter { it.state is CollaborationMessageState.Sent }.map { it.intent.id }.toSet()
    if (completions.map { it.record.intent.id }.toSet() != sentIds) return mismatch("completion")
    val byId = records.associateBy { it.intent.id }
    val completed = completions.associateBy { it.record.intent.id }
    val captured = originals.map { it.transactionId }.toSet()
    val access =
        CollaborationLocalAccess(
            runtime.owner.parent.sessionId,
            binding.audience,
            binding.accountId,
            binding.companyId,
        )
    val matched = mutableListOf<CollaborationMatchedOriginalMessage>()
    val receipts = mutableListOf<CollaborationMessageReceipt>()
    for (original in originals) {
        val record = byId[original.logicalId] ?: return mismatch("inventory")
        val intent = record.intent
        val originalDevice =
            when (val state = record.state) {
                is CollaborationMessageState.Staged -> state.device
                is CollaborationMessageState.Sent -> state.device
                else -> return mismatch("command")
            }
        if (
            intent.owner != access.messageOwner() ||
                intent.conversationId != position.conversationId ||
                !validCollaborationText(intent.text) ||
                !validCollaborationEditRecord(record) ||
                intent.createdAt.toEpochMilliseconds() !in 0 until 253_402_300_800_000L ||
                (intent.replyToEventId != null &&
                    !validCollaborationReplyEventId(intent.replyToEventId)) ||
                record.revision !in 1..9_007_199_254_740_991L ||
                !originalQueueId(originalDevice.bootstrapOperationId) ||
                record.state.origin != original.origin ||
                collaborationRetainedTransaction(record, device, captured) != original.transactionId
        )
            return mismatch("command")
        val completion = completed[intent.id]?.let { it.copy(eventIds = it.eventIds.toList()) }
        if (completion != null) {
            if (
                completion.record != record ||
                    collaborationQueueCompletionEvent(
                        access,
                        position.conversationId,
                        device,
                        original,
                        completion,
                    ) == null
            )
                return mismatch("completion")
            receipts += collaborationCompletionReceipt(completion) ?: return mismatch("completion")
        }
        matched += CollaborationMatchedOriginalMessage(original, record, completion)
    }
    if (!validCollaborationReceiptLedger(receipts)) return mismatch("completion")
    return AppResult.Success(matched.toList())
}

private fun originalQueueId(value: String) =
    validCollaborationMessageId(value) && value != "00000000-0000-0000-0000-000000000000"

private fun mismatch(reason: String) =
    collaborationSetupFailure("client.collaboration_original_queue_$reason")
