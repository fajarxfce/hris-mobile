package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** A bounded source publication or explicit checked no-op, never an implicit empty write. */
fun validCollaborationHistoryInboxProposal(value: CollaborationHistoryInboxProposal): Boolean {
    if (!validCollaborationMessageId(value.conversationId)) return false
    val expected = value.expected
    if (expected != null && !validHistoryInboxDestination(expected)) return false
    val projection = value.projection ?: return true
    return projection.expected == expected &&
        projection.entry.conversationId == value.conversationId &&
        projection.draft == null &&
        projection.repair == null &&
        validCollaborationSpaceInboxPublication(projection)
}

/** Validate the conditional result, without decoding the storage-owned snapshot token. */
fun validCollaborationHistoryInboxReceipt(
    proposal: CollaborationHistoryInboxProposal,
    sourcePresent: Boolean,
    receipt: CollaborationHistoryInboxReceipt,
): Boolean {
    if (!validCollaborationHistoryInboxProposal(proposal)) return false
    val source = receipt.source
    if ((source != null) != sourcePresent) return false
    if (
        source != null &&
            (!validCollaborationMessageId(source.generation) ||
                source.revision !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER)
    )
        return false
    val destination = receipt.destination
    if (destination != null && !validHistoryInboxDestination(destination)) return false
    val projection = proposal.projection ?: return destination == proposal.expected
    if (source == null || destination == null || destination.lastOrdinal != projection.lastOrdinal)
        return false
    val previous = proposal.expected ?: return destination.revision == 0L
    return destination.generation == previous.generation &&
        destination.revision in previous.revision..(previous.revision + 1)
}

private fun validHistoryInboxDestination(value: CollaborationSpaceInboxVersion): Boolean =
    validCollaborationMessageId(value.generation) &&
        value.revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER &&
        value.lastOrdinal in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER
