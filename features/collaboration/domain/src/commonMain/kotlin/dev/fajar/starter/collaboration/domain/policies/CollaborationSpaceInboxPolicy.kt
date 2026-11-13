package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

const val COLLABORATION_SPACE_INBOX_MAX_ORDER = 9_007_199_254_740_991L

fun validCollaborationSpaceInboxPosition(value: CollaborationSpaceInboxPosition) =
    value.timestamp in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER &&
        value.ordinal in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER

fun compareCollaborationSpaceInboxPositions(
    first: CollaborationSpaceInboxPosition,
    second: CollaborationSpaceInboxPosition,
): Int =
    first.timestamp.compareTo(second.timestamp).takeUnless { it == 0 }
        ?: first.ordinal.compareTo(second.ordinal)

fun validCollaborationSpaceInboxSource(value: CollaborationSpaceInboxSource): Boolean =
    when (value) {
        is CollaborationSpaceInboxSource.GroupOriginal -> validCollaborationMessageId(value.id)
        is CollaborationSpaceInboxSource.CompanyOriginal ->
            listOf(value.companyId, value.conversationId, value.id)
                .all(::validCollaborationMessageId)
        is CollaborationSpaceInboxSource.History ->
            (value.companyId == null || validCollaborationMessageId(value.companyId)) &&
                validCollaborationMessageId(value.conversationId) &&
                value.roomId.startsWith("!") &&
                value.roomId.length in 2..255 &&
                value.roomId.none { it.isWhitespace() || it.isISOControl() } &&
                validCollaborationHistorySender(value.sender) &&
                validCollaborationEventId(value.eventId)
    }

fun validCollaborationSpaceInboxActivity(value: CollaborationSpaceInboxActivity): Boolean {
    if (
        !validCollaborationSpaceInboxSource(value.source) ||
            !validCollaborationSpaceInboxPosition(value.position) ||
            value.position.ordinal == 0L
    )
        return false
    val preview = value.preview ?: return true
    return preview.timestamp in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER &&
        (if (preview.kind == CollaborationSpaceInboxContent.TEXT)
            preview.text != null && validCollaborationText(preview.text)
        else preview.text == null) &&
        (if (value.source is CollaborationSpaceInboxSource.History)
            preview.delivery == null || preview.delivery == CollaborationMessageDelivery.SENT
        else preview.delivery != null)
}

fun validCollaborationSpaceInboxDraft(value: CollaborationSpaceInboxDraft) =
    validCollaborationDraft(value.value) &&
        validCollaborationSpaceInboxPosition(value.position) &&
        value.position.ordinal > 0 &&
        value.position.timestamp == value.value.savedAt.toEpochMilliseconds()

/** Preview precedence and recency are independent: incoming activity does not erase an editor. */
fun collaborationSpaceInboxEntry(
    conversationId: String,
    createdAt: Long,
    draft: CollaborationSpaceInboxDraft?,
    newest: CollaborationSpaceInboxActivity?,
): CollaborationSpaceInboxEntry {
    require(
        validCollaborationMessageId(conversationId) &&
            createdAt in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER
    )
    require(
        draft == null ||
            (validCollaborationSpaceInboxDraft(draft) &&
                draft.value.conversationId == conversationId)
    )
    require(
        newest == null || (validCollaborationSpaceInboxActivity(newest) && newest.preview != null)
    )
    val editable = draft?.takeIf { it.value.submissionId == null && it.value.text.isNotBlank() }
    val position =
        listOfNotNull(editable?.position, newest?.position)
            .maxWithOrNull(::compareCollaborationSpaceInboxPositions)
            ?: CollaborationSpaceInboxPosition(createdAt, 0)
    return CollaborationSpaceInboxEntry(conversationId, createdAt, editable, newest, position)
}

/**
 * Finite proposal validation; source identity/receipt admission is owned by the source use case.
 */
fun validCollaborationSpaceInboxPublication(value: CollaborationSpaceInboxPublication): Boolean {
    val expected = value.expected
    if (
        expected != null &&
            (!validCollaborationMessageId(expected.generation) ||
                expected.revision !in 0 until COLLABORATION_SPACE_INBOX_MAX_ORDER ||
                expected.lastOrdinal !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER)
    )
        return false
    if (
        value.lastOrdinal !in (expected?.lastOrdinal ?: 0)..COLLABORATION_SPACE_INBOX_MAX_ORDER ||
            value.activities.size > 32
    )
        return false
    if (
        value.activities.map { it.source }.distinct().size != value.activities.size ||
            value.activities.any {
                !validCollaborationSpaceInboxActivity(it) || it.position.ordinal > value.lastOrdinal
            }
    )
        return false
    if (
        value.draft != null &&
            (!validCollaborationSpaceInboxDraft(value.draft) ||
                value.draft.value.conversationId != value.entry.conversationId ||
                value.draft.position.ordinal > value.lastOrdinal)
    )
        return false
    val entry = value.entry
    if (
        entry.draft != null &&
            (!validCollaborationSpaceInboxDraft(entry.draft) ||
                entry.draft.position.ordinal > value.lastOrdinal ||
                entry.draft.value.conversationId != entry.conversationId)
    )
        return false
    if (
        entry.newest != null &&
            (!validCollaborationSpaceInboxActivity(entry.newest) ||
                entry.newest.preview == null ||
                entry.newest.position.ordinal > value.lastOrdinal)
    )
        return false
    if (
        !validCollaborationMessageId(entry.conversationId) ||
            entry.createdAt !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER
    )
        return false
    return entry ==
        collaborationSpaceInboxEntry(
            entry.conversationId,
            entry.createdAt,
            entry.draft,
            entry.newest,
        )
}
