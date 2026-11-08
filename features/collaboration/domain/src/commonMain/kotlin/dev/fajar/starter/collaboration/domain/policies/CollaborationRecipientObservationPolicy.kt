package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationRecipientObservation

fun validCollaborationRecipientObservation(value: CollaborationRecipientObservation): Boolean =
    validCollaborationMessageId(value.conversationId) && validCollaborationMessageId(value.revision)

/** Identical facts retain their revision; an unknown ready recipient needs no display record. */
fun collaborationRecipientObservation(
    conversationId: String,
    previous: CollaborationRecipientObservation?,
    ready: Boolean,
    revision: String,
): CollaborationRecipientObservation? {
    require(validCollaborationMessageId(conversationId) && validCollaborationMessageId(revision))
    require(
        previous == null ||
            (validCollaborationRecipientObservation(previous) &&
                previous.conversationId == conversationId)
    )
    if (previous?.ready == ready || (previous == null && ready)) return null
    require(previous?.revision != revision)
    return CollaborationRecipientObservation(conversationId, revision, ready)
}
