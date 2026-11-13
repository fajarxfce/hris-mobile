package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.time.Instant

fun validCollaborationSpaceEditRequest(request: CollaborationSpaceEditRequest) =
    validCollaborationMessageId(request.id) &&
        validCollaborationSpaceConversationId(request.conversationId) &&
        validCollaborationEditTarget(request.reviewed.target) &&
        request.id != request.reviewed.target.originalLogicalId &&
        validCollaborationText(request.reviewed.text) &&
        validCollaborationText(request.text) &&
        request.text != request.reviewed.text

/** An old single-ledger command cannot acknowledge a joint publication that never happened. */
fun retainedCollaborationSpaceEdit(
    access: CollaborationSpaceLocalAccess,
    request: CollaborationSpaceEditRequest,
    record: CollaborationSpaceMessageRecord,
): Boolean =
    validCollaborationSpaceEditRequest(request) &&
        validCollaborationSpaceMessageRecord(access, record) &&
        record.intent.inboxOrigin != null &&
        record.intent.id == request.id &&
        record.intent.conversationId == request.conversationId &&
        record.intent.editTarget == request.reviewed.target &&
        record.intent.text == request.text

/**
 * Preserve an admitted original's anchor and editor. Reads supplied here must be successful,
 * bounded snapshots; the repository guards their physical history/index revisions at commit.
 */
fun prepareCollaborationSpaceInboxEdit(
    access: CollaborationSpaceLocalAccess,
    request: CollaborationSpaceEditRequest,
    sender: String,
    now: Instant,
    history: CollaborationSpaceEditSourceSnapshot,
    source: CollaborationSpaceInboxSource,
    current: CollaborationSpaceInboxConversation,
    anchors: CollaborationSpaceInboxActivities,
    groupCommands: List<CollaborationSpaceMessageRecord>,
    companyAccess: CollaborationLocalAccess?,
    companyConversationId: String?,
    companyCommands: List<CollaborationMessageRecord>,
): AppResult<CollaborationSpacePreparedEdit> {
    if (!validCollaborationSpaceEditRequest(request))
        return unavailableSpaceEdit(FailureKind.Validation)
    val target = request.reviewed.target
    if (!canUseCollaborationSpaceEditSource(request, sender, now, history))
        return unavailableSpaceEdit()
    val original = requireNotNull(history.original)
    val candidate = requireNotNull(history.candidate)

    val captured = captureCollaborationSpaceInboxConversation(request.conversationId, current)
    if (captured is AppResult.Failed) return captured
    val owned = (captured as AppResult.Success).value
    val version = owned.version ?: return unavailableSpaceEdit()
    val entry = owned.entry ?: return unavailableSpaceEdit()
    if (
        version.revision == COLLABORATION_SPACE_INBOX_MAX_ORDER ||
            anchors.version != version ||
            anchors.values.size != 1
    )
        return unavailableSpaceEdit()
    val anchor = anchors.values.single()
    if (
        anchor.source != source ||
            !validCollaborationSpaceInboxActivity(anchor, version.revision) ||
            anchor.position.ordinal > version.lastOrdinal ||
            owned.candidates.any { it.source == source && it != anchor } ||
            (entry.newest?.source == source && entry.newest != anchor)
    )
        return unavailableSpaceEdit()
    val newestOriginal = entry.newest ?: return unavailableSpaceEdit()
    if (compareCollaborationSpaceInboxPositions(anchor.position, newestOriginal.position) > 0)
        return unavailableSpaceEdit(FailureKind.Unexpected)
    val preview = anchor.preview ?: return unavailableSpaceEdit()
    if (preview.kind != CollaborationSpaceInboxContent.TEXT) return unavailableSpaceEdit()
    val previous = anchor.pendingEdit
    if (previous == null) {
        if (candidate != request.reviewed) return unavailableSpaceEdit()
    } else if (previous.target != target || preview.text != request.reviewed.text)
        return unavailableSpaceEdit()

    val ordered =
        collaborationSpaceEditCreatedAt(
            access,
            request,
            sender,
            now,
            original,
            previous,
            groupCommands,
            companyAccess,
            companyConversationId,
            companyCommands,
        )
    if (ordered is AppResult.Failed) return ordered
    val createdAt = (ordered as AppResult.Success).value
    val revision = version.revision + 1
    val intent =
        CollaborationSpaceMessageIntent(
            request.id,
            access.messageOwner(),
            request.conversationId,
            createdAt,
            request.text,
            target.replyToEventId,
            target.forwarded,
            target,
            CollaborationSpaceEditInboxOrigin(source, revision),
        )
    val replacement =
        anchor.copy(
            preview = preview.copy(text = request.text),
            pendingEdit =
                CollaborationSpaceInboxPendingEdit(
                    CollaborationSpaceInboxEditSource.Group(request.id),
                    target,
                    createdAt.toEpochMilliseconds(),
                    revision,
                ),
        )
    val newest = if (newestOriginal.source == source) replacement else newestOriginal
    val publication =
        CollaborationSpaceInboxPublication(
            version,
            version.lastOrdinal,
            collaborationSpaceInboxEntry(
                request.conversationId,
                entry.createdAt,
                owned.draft,
                newest,
            ),
            activities = listOf(replacement),
        )
    if (!validCollaborationSpaceEditPublication(access, intent, publication))
        return unavailableSpaceEdit(FailureKind.Unexpected)
    return AppResult.Success(CollaborationSpacePreparedEdit(intent, publication))
}

/** Admit exactly the original and candidate returned by one authenticated history revision. */
fun canUseCollaborationSpaceEditSource(
    request: CollaborationSpaceEditRequest,
    sender: String,
    now: Instant,
    history: CollaborationSpaceEditSourceSnapshot,
): Boolean {
    val target = request.reviewed.target
    val original = history.original ?: return false
    val candidate = history.candidate ?: return false
    if (
        history.revision.isNullOrEmpty() ||
            history.revision.length > 128 ||
            !validCollaborationHistoryMessage(original) ||
            original.redacted ||
            original.locallyHidden ||
            candidate.target != target ||
            target.eventId != original.primaryEventId ||
            target.sender != original.sender ||
            target.originalLogicalId != original.logicalId ||
            target.originalContentHash != original.contentHash ||
            target.targetAcceptedAt != original.timestamp ||
            target.replyToEventId != original.replyToEventId ||
            target.forwarded != original.forwarded ||
            (original.firstAcceptedAt != null &&
                target.firstAcceptedAt != original.firstAcceptedAt) ||
            candidate.text != (original.latestEdit?.edit?.text ?: original.text) ||
            !canAuthorCollaborationEdit(target, sender, now.toEpochMilliseconds())
    )
        return false

    return true
}

/** Consecutive local replacements retain delivery order even after a backward clock change. */
fun collaborationSpaceEditCreatedAt(
    access: CollaborationSpaceLocalAccess,
    request: CollaborationSpaceEditRequest,
    sender: String,
    now: Instant,
    original: CollaborationHistoryMessage,
    pending: CollaborationSpaceInboxPendingEdit?,
    groupCommands: List<CollaborationSpaceMessageRecord>,
    companyAccess: CollaborationLocalAccess?,
    companyConversationId: String?,
    companyCommands: List<CollaborationMessageRecord>,
): AppResult<Instant> {
    val target = request.reviewed.target
    // Existing command inventories are bounded by their stores. Reject malformed scopes before
    // using a timestamp, even when the malformed row describes a different original.
    val group = groupCommands.toList()
    val company = companyCommands.toList()
    if (
        group.size > 128 ||
            company.size > 128 ||
            group.map { it.intent.id }.distinct().size != group.size ||
            company.map { it.intent.id }.distinct().size != company.size ||
            group.any {
                !validCollaborationSpaceMessageRecord(access, it) ||
                    it.intent.conversationId != request.conversationId
            } ||
            (company.isNotEmpty() && (companyAccess == null || companyConversationId == null)) ||
            (companyAccess != null &&
                (companyAccess.sessionId != access.sessionId ||
                    companyAccess.accountId != access.accountId ||
                    companyAccess.audience != access.audience ||
                    !validCollaborationMessageId(companyAccess.companyId))) ||
            company.any {
                it.intent.owner != companyAccess?.messageOwner() ||
                    it.intent.conversationId != companyConversationId ||
                    !validCollaborationEditRecord(it) ||
                    !validCollaborationMessageId(it.intent.id) ||
                    !validCollaborationMessageId(it.intent.conversationId) ||
                    !validCollaborationText(it.intent.text) ||
                    !validCollaborationMessageState(it.state) ||
                    it.intent.createdAt.toEpochMilliseconds() !in 0 until 253_402_300_800_000L ||
                    it.revision !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER
            }
    )
        return unavailableSpaceEdit(FailureKind.Unexpected)
    if (group.any { it.intent.id == request.id }) return unavailableSpaceEdit()
    val times = buildList {
        pending?.let { add(it.createdAt) }
        original.latestEdit?.let { add(it.edit.timestamp) }
        group
            .filter {
                it.intent.editTarget?.let { t ->
                    t.roomId == target.roomId && t.eventId == target.eventId
                } == true
            }
            .forEach { add(it.intent.createdAt.toEpochMilliseconds()) }
        company
            .filter {
                it.intent.editTarget?.let { t ->
                    t.roomId == target.roomId && t.eventId == target.eventId
                } == true
            }
            .forEach { add(it.intent.createdAt.toEpochMilliseconds()) }
    }
    val latest = times.maxOrNull()
    if (latest != null && latest !in 0 until 253_402_300_799_999L) return unavailableSpaceEdit()
    val createdAt = maxOf(now.toEpochMilliseconds(), latest?.plus(1) ?: 0L)
    if (!canAuthorCollaborationEdit(target, sender, createdAt)) return unavailableSpaceEdit()
    return AppResult.Success(Instant.fromEpochMilliseconds(createdAt))
}

private fun unavailableSpaceEdit(kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_edit_unavailable", kind)
