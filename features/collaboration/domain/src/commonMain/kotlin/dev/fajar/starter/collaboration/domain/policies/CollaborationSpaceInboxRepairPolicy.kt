package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceInboxRepairReference(value: CollaborationSpaceInboxRepairReference) =
    validCollaborationSpaceMessageIntentContent(value.intent) &&
        value.intent.editTarget == null &&
        value.insertedAtRevision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER

/** A repair change accompanies its exact original anchor, never a coincidentally equal UUID. */
fun validCollaborationSpaceInboxRepairChange(
    publication: CollaborationSpaceInboxPublication
): Boolean {
    val change = publication.repair ?: return true
    val original =
        when (change) {
            is CollaborationSpaceInboxRepairChange.Retain -> change.intent
            is CollaborationSpaceInboxRepairChange.Complete -> {
                if (
                    !validCollaborationSpaceInboxRepairReference(change.reference) ||
                        change.reference.insertedAtRevision > (publication.expected?.revision ?: -1)
                )
                    return false
                change.reference.intent
            }
        }
    return validCollaborationSpaceMessageIntentContent(original) &&
        original.editTarget == null &&
        original.conversationId == publication.entry.conversationId &&
        publication.activities.any {
            it.source == CollaborationSpaceInboxSource.GroupOriginal(original.id)
        }
}
