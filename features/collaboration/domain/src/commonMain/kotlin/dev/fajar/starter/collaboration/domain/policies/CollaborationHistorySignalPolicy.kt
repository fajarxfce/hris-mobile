package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationTimelineItem

/**
 * A live timeline invalidates originals only for new or changed remote content. Both snapshots
 * belong to the same leased room. Local echoes, including an ACK with an event ID, grant no remote
 * provenance. Entry, explicit recovery and failed-acquisition retry remain the caller's signals.
 */
fun collaborationTimelineRequiresHistory(
    previous: List<CollaborationTimelineItem>,
    current: List<CollaborationTimelineItem>,
): Boolean {
    val known = previous.filterNot { it.localEcho }.associateBy { it.eventId }
    return current.any { incoming ->
        !incoming.localEcho &&
            incoming.eventId?.let(::validCollaborationEventId) == true &&
            known[incoming.eventId]?.copy(
                key = incoming.key,
                transactionId = incoming.transactionId,
                delivery = incoming.delivery,
            ) != incoming
    }
}
