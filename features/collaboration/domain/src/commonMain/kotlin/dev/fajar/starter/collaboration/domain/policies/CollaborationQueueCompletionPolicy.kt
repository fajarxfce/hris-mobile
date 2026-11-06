package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationQueuedOriginal(original: CollaborationQueuedOriginal): Boolean =
    validCollaborationTransactionId(original.transactionId) &&
        (original.logicalId == null || validCollaborationMessageId(original.logicalId)) &&
        validCollaborationMessageOrigin(original.origin) &&
        original.eventType == "m.room.message" &&
        runCatching {
                original.serializedContent.encodeToByteArray(throwOnInvalidSequence = true).size in
                    2..96_000
            }
            .getOrDefault(false)

/** An opaque native ID alone never establishes accepted application content or ownership. */
fun collaborationQueueCompletionEvent(
    access: CollaborationLocalAccess,
    conversationId: String,
    device: CollaborationMessageDevice,
    original: CollaborationQueuedOriginal,
    completion: CollaborationMessageCompletion,
): String? {
    if (!validCollaborationQueuedOriginal(original)) return null
    collaborationCompletionReceipt(completion) ?: return null
    val record = completion.record
    val sent = record.state as? CollaborationMessageState.Sent ?: return null
    if (
        record.intent.owner != access.messageOwner() ||
            record.intent.conversationId != conversationId ||
            record.intent.id != original.logicalId ||
            sent.origin != original.origin ||
            collaborationRetainedTransaction(record, device, setOf(original.transactionId)) !=
                original.transactionId
    )
        return null
    return sent.eventId.takeIf { it in completion.eventIds }
}
