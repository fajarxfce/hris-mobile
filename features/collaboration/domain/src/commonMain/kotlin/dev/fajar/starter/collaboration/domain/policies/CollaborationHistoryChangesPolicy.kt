package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Call only after canonical binding admission; Company coordinates never come from Group IDs. */
fun collaborationHistoryChangeOwner(
    source: CollaborationSpaceHistoryLocation
): CollaborationHistoryChangeOwner =
    when (source) {
        is CollaborationSpaceHistoryLocation.Space ->
            CollaborationHistoryChangeOwner(
                source.access.audience,
                source.access.accountId,
                CollaborationHistoryChangeOwnerKind.SPACE,
                source.access.spaceId,
                source.conversationId,
                source.roomId,
            )
        is CollaborationSpaceHistoryLocation.Original ->
            CollaborationHistoryChangeOwner(
                source.access.audience,
                source.access.accountId,
                CollaborationHistoryChangeOwnerKind.COMPANY,
                source.access.companyId,
                source.conversationId,
                source.roomId,
            )
    }

fun validCollaborationHistoryChangeCursor(cursor: CollaborationHistoryChangeCursor?): Boolean =
    when (cursor) {
        null -> true
        is CollaborationHistoryChangeCursor.After ->
            validCollaborationMessageId(cursor.generation) && cursor.revision >= 0
        is CollaborationHistoryChangeCursor.Within ->
            validCollaborationHistoryChangeWindow(cursor.window) &&
                cursor.revision in 0..cursor.window.throughRevision &&
                (cursor.window.afterRevision == null ||
                    cursor.revision > cursor.window.afterRevision) &&
                cursor.recordId.length in 1..512 &&
                cursor.recordId.none { it.isWhitespace() || it.isISOControl() }
    }

fun collaborationHistoryChangeCursorOwner(
    cursor: CollaborationHistoryChangeCursor
): CollaborationHistoryChangeOwner =
    when (cursor) {
        is CollaborationHistoryChangeCursor.After -> cursor.owner
        is CollaborationHistoryChangeCursor.Within -> cursor.window.owner
    }

/**
 * Bounded detached response with exact owner, frozen upper bound, and strictly advancing cursor.
 */
fun validCollaborationHistoryChanges(
    owner: CollaborationHistoryChangeOwner,
    cursor: CollaborationHistoryChangeCursor?,
    limit: Int,
    changes: CollaborationHistoryChanges,
): Boolean {
    if (limit !in 1..16 || !validCollaborationHistoryChangeCursor(cursor)) return false
    if (cursor != null && collaborationHistoryChangeCursorOwner(cursor) != owner) return false
    when (changes) {
        CollaborationHistoryChanges.Absent -> return true
        CollaborationHistoryChanges.Restart -> return cursor != null
        is CollaborationHistoryChanges.Page -> {
            val window = changes.window
            if (window.owner != owner || !validCollaborationHistoryChangeWindow(window))
                return false
            when (cursor) {
                null -> if (window.afterRevision != null) return false
                is CollaborationHistoryChangeCursor.After ->
                    if (
                        window.generation != cursor.generation ||
                            window.afterRevision != cursor.revision
                    )
                        return false
                is CollaborationHistoryChangeCursor.Within ->
                    if (window != cursor.window) return false
            }
            // Storage owns revision encoding; only the typed window carries cursor arithmetic.
            if (changes.sourceRevision.length !in 1..128) return false
            if (
                changes.eventIds.size > limit ||
                    changes.eventIds.distinct() != changes.eventIds ||
                    changes.eventIds.any { !validCollaborationEventId(it) }
            )
                return false
            val next = changes.next ?: return true
            if (next.window != window || !validCollaborationHistoryChangeCursor(next)) return false
            if (
                cursor is CollaborationHistoryChangeCursor.Within &&
                    (next.revision < cursor.revision ||
                        (next.revision == cursor.revision && next.recordId <= cursor.recordId))
            )
                return false
            return true
        }
    }
}

private fun validCollaborationHistoryChangeWindow(
    window: CollaborationHistoryChangeWindow
): Boolean =
    validCollaborationMessageId(window.generation) &&
        window.throughRevision >= 0 &&
        (window.afterRevision == null || window.afterRevision in 0..window.throughRevision)
