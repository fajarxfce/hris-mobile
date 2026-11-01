package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationMessageId(value: String) = messageId.matches(value)

fun validCollaborationText(value: String): Boolean =
    value.length in 1..16_384 &&
        value.isNotBlank() &&
        value.none { it.isISOControl() && it !in "\n\r\t" } &&
        runCatching { value.encodeToByteArray(throwOnInvalidSequence = true).size <= 32_768 }
            .getOrDefault(false)

fun validCollaborationTransactionId(value: String) =
    value.length in 1..128 &&
        value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._~:-" }

fun validCollaborationEventId(value: String) =
    value.startsWith('$') &&
        value.length in 2..255 &&
        value.none { it.isWhitespace() || it.isISOControl() }

fun validCollaborationMessageDevice(device: CollaborationMessageDevice) =
    listOf(device.nativeSessionId, device.deviceId, device.bootstrapOperationId)
        .all(::validCollaborationMessageId) &&
        device.roomId.startsWith('!') &&
        device.roomId.length in 2..255 &&
        device.roomId.none { it.isWhitespace() || it.isISOControl() }

fun validCollaborationMessageState(state: CollaborationMessageState): Boolean =
    when (state) {
        CollaborationMessageState.Queued -> true
        is CollaborationMessageState.Staging ->
            validCollaborationMessageDevice(state.device) &&
                state.before.size <= 128 &&
                state.before.all(::validCollaborationTransactionId)
        is CollaborationMessageState.Staged ->
            validCollaborationMessageDevice(state.device) &&
                validCollaborationTransactionId(state.transactionId)
        is CollaborationMessageState.Sent ->
            validCollaborationMessageDevice(state.device) &&
                validCollaborationTransactionId(state.transactionId) &&
                validCollaborationEventId(state.eventId)
    }

/** Native uncertainty and a durable transaction mapping can never be reset or retargeted. */
fun collaborationMessageTransition(
    previous: CollaborationMessageState,
    next: CollaborationMessageState,
): Boolean =
    validCollaborationMessageState(previous) &&
        validCollaborationMessageState(next) &&
        when (previous) {
            CollaborationMessageState.Queued -> next is CollaborationMessageState.Staging
            is CollaborationMessageState.Staging ->
                next is CollaborationMessageState.Staged &&
                    next.device == previous.device &&
                    next.transactionId !in previous.before
            is CollaborationMessageState.Staged ->
                next is CollaborationMessageState.Sent &&
                    next.device == previous.device &&
                    next.transactionId == previous.transactionId
            is CollaborationMessageState.Sent -> false
        }

/**
 * A per-room SDK subscription replays its captured IDs before returning. With one exclusive enqueue
 * between durable snapshots, set difference identifies the original transaction even though the SDK
 * deliberately exposes no JSON/logical ID on unsent local echoes. Callers must keep delivery paused
 * and drain any previous native enqueue before using this.
 */
fun recoverCollaborationQueue(
    attempt: CollaborationMessageState.Staging,
    device: CollaborationMessageDevice,
    current: Set<String>,
): CollaborationQueueRecovery {
    if (attempt.device != device) return CollaborationQueueRecovery.DifferentDevice
    if (
        !validCollaborationMessageState(attempt) ||
            current.size > 128 ||
            !current.all(::validCollaborationTransactionId)
    )
        return CollaborationQueueRecovery.Ambiguous
    val added = current - attempt.before
    return when (added.size) {
        0 -> CollaborationQueueRecovery.Missing
        1 -> CollaborationQueueRecovery.Found(added.single())
        else -> CollaborationQueueRecovery.Ambiguous
    }
}

private val messageId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
