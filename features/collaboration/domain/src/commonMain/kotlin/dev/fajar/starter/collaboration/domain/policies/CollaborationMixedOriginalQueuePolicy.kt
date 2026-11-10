package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Matches the complete adopted physical queue to exactly one of two owned ledgers. This is local
 * evidence only; neither a successful match nor an empty capture admits native resume or enqueue.
 */
fun matchCollaborationMixedOriginalQueue(
    runtime: CollaborationSpaceRuntimeReady,
    conversation: CollaborationSpaceConversation,
    binding: CollaborationSpaceRoomBinding,
    snapshot: CollaborationSpaceOriginalQueueSnapshot,
    companyRecords: List<CollaborationMessageRecord>,
    companyCompletions: List<CollaborationMessageCompletion>,
    spaceRecords: List<CollaborationSpaceMessageRecord>,
    spaceCompletions: List<CollaborationSpaceMessageCompletion>,
): AppResult<CollaborationMixedOriginalQueueMatch> {
    if (!validCollaborationSpaceConversation(conversation, runtime.owner)) return mismatch("scope")
    val access = collaborationMixedQueueCompanyAccess(runtime, conversation, binding, snapshot)
    if (access is AppResult.Failed) return access
    if (
        companyRecords.size > 128 ||
            spaceRecords.size > 128 ||
            companyRecords.size + spaceRecords.size != snapshot.original.messages.size ||
            companyCompletions.size > companyRecords.size ||
            spaceCompletions.size > spaceRecords.size ||
            companyCompletions.any { it.eventIds.size !in 1..128 } ||
            spaceCompletions.any { it.eventIds.size !in 1..128 } ||
            companyCompletions.sumOf { it.eventIds.size } +
                spaceCompletions.sumOf { it.eventIds.size } > 512
    )
        return mismatch("inventory")
    val ids = companyRecords.map { it.intent.id } + spaceRecords.map { it.intent.id }
    if (
        ids.distinct().size != ids.size ||
            ids.toSet() != snapshot.original.messages.map { it.logicalId }.toSet()
    ) {
        return mismatch("inventory")
    }
    val originalMetadata = binding.original
    val old: AppResult<List<CollaborationMatchedOriginalMessage>> =
        if (originalMetadata == null) {
            if (companyRecords.isNotEmpty() || companyCompletions.isNotEmpty())
                return mismatch("scope")
            AppResult.Success(emptyList())
        } else {
            val oldIds = companyRecords.map { it.intent.id }.toSet()
            val oldConversation =
                CollaborationSpaceOriginalConversation(
                    runtime.owner,
                    originalMetadata.conversation,
                    originalMetadata.room,
                )
            val sender =
                "@hris_${originalMetadata.conversation.position.companyId.replace("-", "")}_${runtime.owner.parent.accountId.replace("-", "")}:${originalMetadata.room.serverName}"
            val oldSnapshot =
                snapshot.copy(
                    original =
                        snapshot.original.copy(
                            messages = snapshot.original.messages.filter { it.logicalId in oldIds }
                        )
                )
            matchCollaborationOriginalMessages(
                runtime,
                oldConversation,
                sender,
                oldSnapshot,
                companyRecords,
                companyCompletions,
            )
        }
    if (old is AppResult.Failed) return old
    val space =
        matchSpaceCommandsToOriginals(
            runtime.owner.localDirectoryAccess(),
            conversation.id,
            snapshot.original.device,
            snapshot.original.messages,
            spaceRecords,
            spaceCompletions,
        )
    if (space is AppResult.Failed) return space
    val aliases =
        companyCompletions.flatMap { it.eventIds } + spaceCompletions.flatMap { it.eventIds }
    if (aliases.distinct().size != aliases.size) return mismatch("completion")
    val matched: List<CollaborationMatchedQueueMessage> =
        (old as AppResult.Success).value.map {
            CollaborationMatchedQueueMessage.Company(it.original, it.record, it.completion)
        } + (space as AppResult.Success).value
    val byId = matched.associateBy { it.original.logicalId }
    return AppResult.Success(
        CollaborationMixedOriginalQueueMatch(
            runtime,
            conversation,
            snapshot.original.binding,
            snapshot.original.device,
            snapshot.original.messages.map { byId.getValue(it.logicalId) },
        )
    )
}

/** New logical envelopes keep their own scope while retaining the original physical mapping. */
private fun matchSpaceCommandsToOriginals(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    device: CollaborationMessageDevice,
    originals: List<CollaborationQueuedOriginal>,
    records: List<CollaborationSpaceMessageRecord>,
    completions: List<CollaborationSpaceMessageCompletion>,
): AppResult<List<CollaborationMatchedQueueMessage.Space>> {
    val completed = completions.associateBy { it.record.intent.id }
    val sentIds =
        records.filter { it.state is CollaborationMessageState.Sent }.map { it.intent.id }.toSet()
    if (completed.size != completions.size || completed.keys != sentIds)
        return mismatch("completion")
    val byId = originals.associateBy { it.logicalId }
    val matched = mutableListOf<CollaborationMatchedQueueMessage.Space>()
    for (record in records) {
        val intent = record.intent
        if (
            !validCollaborationSpaceMessageRecord(access, record) ||
                intent.conversationId != conversationId
        )
            return mismatch("command")
        val original = byId[intent.id] ?: return mismatch("inventory")
        val originalDevice: CollaborationMessageDevice
        val transactionId: String
        when (val state = record.state) {
            is CollaborationMessageState.Staged -> {
                originalDevice = state.device
                transactionId = state.transactionId
            }
            is CollaborationMessageState.Sent -> {
                originalDevice = state.device
                transactionId = state.transactionId
            }
            else -> return mismatch("command")
        }
        if (
            originalDevice != device ||
                transactionId != original.transactionId ||
                record.state.origin != original.origin
        )
            return mismatch("command")
        val completion = completed[intent.id]?.let { it.copy(eventIds = it.eventIds.toList()) }
        if (
            completion != null &&
                (completion.record != record ||
                    !validCollaborationSpaceMessageCompletion(access, completion))
        )
            return mismatch("completion")
        matched += CollaborationMatchedQueueMessage.Space(original, record, completion)
    }
    return AppResult.Success(matched.toList())
}

private fun mismatch(reason: String) =
    collaborationSetupFailure("client.collaboration_mixed_queue_$reason")
