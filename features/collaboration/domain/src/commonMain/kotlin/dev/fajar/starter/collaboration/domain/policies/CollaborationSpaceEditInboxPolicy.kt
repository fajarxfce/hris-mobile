package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Shape and immutable intent binding only; source ownership/admission belongs to the use case. */
fun validCollaborationSpaceEditInboxOrigin(intent: CollaborationSpaceMessageIntent): Boolean {
    val origin = intent.inboxOrigin ?: return true
    val target = intent.editTarget ?: return false
    val activity = origin.activity
    if (
        activity is CollaborationSpaceInboxSource.History &&
            activity.companyId == null &&
            activity.conversationId != intent.conversationId
    )
        return false
    return validCollaborationSpaceInboxPendingEdit(
        CollaborationSpaceInboxPendingEdit(
            CollaborationSpaceInboxEditSource.Group(intent.id),
            target,
            intent.createdAt.toEpochMilliseconds(),
            origin.publishedAtRevision,
        ),
        activity,
    )
}

/** The queued edit and its optimistic publication must describe the same immutable operation. */
fun validCollaborationSpaceEditPublication(
    access: CollaborationSpaceLocalAccess,
    intent: CollaborationSpaceMessageIntent,
    publication: CollaborationSpaceInboxPublication,
): Boolean {
    if (
        !validCollaborationSpaceMessageIntent(access, intent) ||
            !validCollaborationSpaceInboxPublication(publication) ||
            publication.entry.conversationId != intent.conversationId ||
            publication.draft != null ||
            publication.repair != null
    )
        return false
    val origin = intent.inboxOrigin ?: return false
    val target = intent.editTarget ?: return false
    val activity = publication.activities.singleOrNull() ?: return false
    val preview = activity.preview ?: return false
    return origin.activity == activity.source &&
        origin.publishedAtRevision == (publication.expected?.revision?.plus(1) ?: 0L) &&
        preview.kind == CollaborationSpaceInboxContent.TEXT &&
        preview.text == intent.text &&
        activity.pendingEdit ==
            CollaborationSpaceInboxPendingEdit(
                CollaborationSpaceInboxEditSource.Group(intent.id),
                target,
                intent.createdAt.toEpochMilliseconds(),
                origin.publishedAtRevision,
            )
}
