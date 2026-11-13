package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Build one bounded proposal under captured source/index revisions. The caller reads all queried
 * anchors and at least updates.size + 1 visible candidates (or all remaining candidates). Neither
 * provider time nor an edit acknowledgement can move a retained original or replace a draft.
 */
fun collaborationSpaceInboxHistoryProjection(
    access: CollaborationSpaceLocalAccess,
    binding: CollaborationSpaceRoomBinding,
    location: CollaborationSpaceHistoryLocation,
    current: CollaborationSpaceInboxConversation,
    anchors: CollaborationSpaceInboxActivities,
    updates: List<CollaborationSpaceInboxHistoryUpdate>,
): AppResult<CollaborationSpaceInboxHistoryProjection> {
    val selected =
        resolveCollaborationSpaceHistoryLocation(access, binding.conversation.id, binding)
    if (selected is AppResult.Failed) return selected
    if ((selected as AppResult.Success).value != location || updates.size !in 1..16)
        return invalidInboxHistoryProjection()
    val captured = captureCollaborationSpaceInboxConversation(binding.conversation.id, current)
    if (captured is AppResult.Failed) return captured
    val owned = (captured as AppResult.Success).value
    if (anchors.version != owned.version) return changedInboxHistoryProjection()
    val changes = updates.toList()
    val retained = anchors.values.toList()
    val createdAt = binding.conversation.createdAt.toEpochMilliseconds()
    val firstOrdinal = owned.version?.lastOrdinal ?: 0L
    if (
        changes.any { !validCollaborationHistoryMessage(it.original) } ||
            changes.map { it.original.primaryEventId }.distinct().size != changes.size ||
            retained.size > changes.size * 2 ||
            retained.map { it.source }.distinct().size != retained.size ||
            retained.any {
                !validCollaborationSpaceInboxActivity(it, owned.version?.revision ?: 0L) ||
                    it.position.ordinal > firstOrdinal
            } ||
            (retained.isNotEmpty() && owned.entry == null) ||
            (owned.entry != null && owned.entry.createdAt != createdAt)
    )
        return invalidInboxHistoryProjection()
    val batch = InboxHistoryProjectionBatch(location, owned, retained, createdAt)
    for (change in changes) {
        val result = batch.add(change)
        if (result is AppResult.Failed) return result
    }
    return batch.finish(binding.conversation.id)
}

/** Ephemeral bounded proposal state; discarded together after this synchronous policy call. */
private class InboxHistoryProjectionBatch(
    private val location: CollaborationSpaceHistoryLocation,
    private val owned: CollaborationSpaceInboxConversation,
    private val retained: List<CollaborationSpaceInboxActivity>,
    private val createdAt: Long,
) {
    private val activities = mutableListOf<CollaborationSpaceInboxActivity>()
    private val unresolved = mutableListOf<String>()
    private val queried = mutableSetOf<CollaborationSpaceInboxSource>()
    private var ordinal = owned.version?.lastOrdinal ?: 0L

    fun add(change: CollaborationSpaceInboxHistoryUpdate): AppResult<Unit> {
        val physical = historyInboxPhysicalSource(location, change.original)
        val identity = change.identity
        val identified =
            when (identity) {
                is CollaborationSpaceInboxHistorySource.Original -> identity.association.source
                is CollaborationSpaceInboxHistorySource.History -> identity.source
                is CollaborationSpaceInboxHistorySource.Unresolved -> null
            }
        val sources = setOfNotNull(physical, identified)
        if (sources.any { it in queried }) return invalidInboxHistoryProjection()
        queried.addAll(sources)
        val matches = retained.filter { it.source in sources }
        if (matches.size > 1) return invalidInboxHistoryProjection()
        val old = matches.singleOrNull()
        if (owned.candidates.any { it.source in sources && it != old })
            return invalidInboxHistoryProjection()
        if (old == null && identity is CollaborationSpaceInboxHistorySource.Unresolved) {
            if (change.resolvedEdit != null) return invalidInboxHistoryProjection()
            unresolved += change.original.primaryEventId
            return AppResult.Success(Unit)
        }
        if (old == null && ordinal == COLLABORATION_SPACE_INBOX_MAX_ORDER)
            return changedInboxHistoryProjection()
        val timestamp =
            (identity as? CollaborationSpaceInboxHistorySource.Original)?.association?.timestamp
                ?: change.original.timestamp
        val position =
            old?.position ?: CollaborationSpaceInboxPosition(maxOf(createdAt, timestamp), ++ordinal)
        val projected = projectInboxHistoryActivity(location, change, old, position)
        if (projected is AppResult.Failed) return projected
        activities += (projected as AppResult.Success).value
        return AppResult.Success(Unit)
    }

    fun finish(conversationId: String): AppResult<CollaborationSpaceInboxHistoryProjection> {
        if (retained.any { it.source !in queried }) return invalidInboxHistoryProjection()
        if (activities.isEmpty())
            return AppResult.Success(
                CollaborationSpaceInboxHistoryProjection(null, unresolved.toList())
            )
        val replaced = activities.map { it.source }.toSet()
        val newest =
            (owned.candidates.filter { it.source !in replaced } +
                    activities.filter { it.preview != null })
                .maxWithOrNull { a, b ->
                    compareCollaborationSpaceInboxPositions(a.position, b.position)
                }
        val publication =
            CollaborationSpaceInboxPublication(
                owned.version,
                ordinal,
                collaborationSpaceInboxEntry(conversationId, createdAt, owned.draft, newest),
                activities = activities.toList(),
            )
        return if (validCollaborationSpaceInboxPublication(publication))
            AppResult.Success(
                CollaborationSpaceInboxHistoryProjection(publication, unresolved.toList())
            )
        else invalidInboxHistoryProjection()
    }
}

/** Keep the actual history namespace even if proof later associates a retained original command. */
private fun historyInboxPhysicalSource(
    location: CollaborationSpaceHistoryLocation,
    original: CollaborationHistoryMessage,
) =
    CollaborationSpaceInboxSource.History(
        (location as? CollaborationSpaceHistoryLocation.Original)?.access?.companyId,
        location.conversationId,
        location.roomId,
        original.sender,
        original.primaryEventId,
    )

/** Only this source mapping decides text/hidden/deleted precedence; it performs no acquisition. */
private fun projectInboxHistoryActivity(
    location: CollaborationSpaceHistoryLocation,
    change: CollaborationSpaceInboxHistoryUpdate,
    old: CollaborationSpaceInboxActivity?,
    position: CollaborationSpaceInboxPosition,
): AppResult<CollaborationSpaceInboxActivity> {
    val original = change.original
    val physical = historyInboxPhysicalSource(location, original)
    val association =
        (change.identity as? CollaborationSpaceInboxHistorySource.Original)?.association
    val source = old?.source ?: association?.source ?: physical
    val pendingTarget = old?.pendingEdit?.target
    if (
        !validCollaborationHistoryMessage(original) ||
            (change.identity is CollaborationSpaceInboxHistorySource.History &&
                change.identity.source != physical) ||
            (old != null && old.source != physical && old.source != association?.source) ||
            (pendingTarget != null &&
                (pendingTarget.roomId != location.roomId ||
                    !matchesCollaborationEditTarget(original, pendingTarget))) ||
            (association != null && !validInboxHistoryAssociation(location, original, association))
    )
        return invalidInboxHistoryProjection()
    if (change.resolvedEdit != null && change.resolvedEdit != old?.pendingEdit)
        return changedInboxHistoryProjection()
    val pending = if (change.resolvedEdit == null) old?.pendingEdit else null
    val acknowledged = association?.delivery == CollaborationMessageDelivery.SENT
    val delivery =
        when {
            acknowledged -> CollaborationMessageDelivery.SENT
            source is CollaborationSpaceInboxSource.History -> old?.preview?.delivery
            else -> old?.preview?.delivery ?: association?.delivery
        }
    val timestamp = old?.preview?.timestamp ?: association?.timestamp ?: original.timestamp
    val preview =
        when {
            original.locallyHidden || (old != null && old.preview == null) -> null
            original.redacted || old?.preview?.kind == CollaborationSpaceInboxContent.DELETED ->
                CollaborationSpaceInboxPreview(
                    CollaborationSpaceInboxContent.DELETED,
                    null,
                    timestamp,
                    delivery,
                )
            pending != null -> requireNotNull(old?.preview).copy(delivery = delivery)
            else ->
                CollaborationSpaceInboxPreview(
                    CollaborationSpaceInboxContent.TEXT,
                    original.latestEdit?.edit?.text ?: original.text,
                    timestamp,
                    delivery,
                )
        }
    val activity = CollaborationSpaceInboxActivity(source, position, preview, pending)
    return if (validCollaborationSpaceInboxActivity(activity)) AppResult.Success(activity)
    else invalidInboxHistoryProjection()
}

private fun validInboxHistoryAssociation(
    location: CollaborationSpaceHistoryLocation,
    original: CollaborationHistoryMessage,
    association: CollaborationSpaceInboxOriginalAssociation,
): Boolean {
    if (
        association.timestamp !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER ||
            association.delivery !in
                listOf(CollaborationMessageDelivery.PENDING, CollaborationMessageDelivery.SENT)
    )
        return false
    return when (val source = association.source) {
        is CollaborationSpaceInboxSource.GroupOriginal -> source.id == original.logicalId
        is CollaborationSpaceInboxSource.CompanyOriginal ->
            location is CollaborationSpaceHistoryLocation.Original &&
                source.id == original.logicalId &&
                source.companyId == location.access.companyId &&
                source.conversationId == location.conversationId
        is CollaborationSpaceInboxSource.History -> false
    }
}

private fun invalidInboxHistoryProjection() =
    collaborationRecoveryFailure("client.collaboration_invalid_response", FailureKind.Unexpected)

private fun changedInboxHistoryProjection() =
    collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Conflict)
