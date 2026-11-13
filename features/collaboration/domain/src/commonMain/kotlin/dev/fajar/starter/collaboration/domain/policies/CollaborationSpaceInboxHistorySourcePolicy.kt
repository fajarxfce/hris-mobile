package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/**
 * Freeze one bounded, binding-selected snapshot. Replacements and aliases resolve to the same
 * complete original; callers retain the snapshot revision for conditional publication.
 */
fun captureCollaborationSpaceInboxHistory(
    access: CollaborationSpaceLocalAccess,
    binding: CollaborationSpaceRoomBinding,
    source: CollaborationSpaceHistoryLocation,
    eventIds: List<String>,
    snapshot: CollaborationSpaceHistorySnapshot,
): AppResult<List<CollaborationHistoryMessage>> {
    if (eventIds.size !in 1..16 || snapshot.events.size != eventIds.size)
        return invalidInboxHistorySource()
    val selected =
        resolveCollaborationSpaceHistoryLocation(access, binding.conversation.id, binding)
    if (selected is AppResult.Failed) return selected
    val ids = eventIds.toList()
    val events = snapshot.events.toList()
    if (
        (selected as AppResult.Success).value != source ||
            ids.size !in 1..16 ||
            ids.distinct().size != ids.size ||
            ids.any { !validCollaborationEventId(it) } ||
            events.map { it.eventId } != ids ||
            (snapshot.revision != null && snapshot.revision.length !in 1..128) ||
            (snapshot.revision == null && events.any { it.original != null })
    )
        return invalidInboxHistorySource()
    val originals = linkedMapOf<String, CollaborationHistoryMessage>()
    val aliases = mutableMapOf<String, String>()
    val logical = mutableMapOf<Pair<String, String>, String>()
    for (event in events) {
        val value = event.original ?: continue
        if (value.eventIds.size !in 1..128) return invalidInboxHistorySource()
        val original = value.copy(eventIds = value.eventIds.toList())
        if (!validCollaborationHistoryMessage(original)) return invalidInboxHistorySource()
        val previous = originals[original.primaryEventId]
        if (previous != null && previous != original) return invalidInboxHistorySource()
        for (alias in original.eventIds) {
            val primary = aliases.put(alias, original.primaryEventId)
            if (primary != null && primary != original.primaryEventId)
                return invalidInboxHistorySource()
        }
        original.logicalId?.let { id ->
            val primary = logical.put(original.sender to id, original.primaryEventId)
            if (primary != null && primary != original.primaryEventId)
                return invalidInboxHistorySource()
        }
        originals[original.primaryEventId] = original
    }
    for (event in events) {
        val primary = aliases[event.eventId] ?: continue
        if (event.original?.primaryEventId != primary) return invalidInboxHistorySource()
    }
    return AppResult.Success(originals.values.toList())
}

/**
 * Both nullable records must come from successful exact logical-ID reads of the actual owned
 * namespaces, including archives. Absence and failed reads must never be conflated. A queued or
 * legacy unstamped original can remain ambiguous even when another namespace already matches.
 */
fun resolveCollaborationSpaceInboxHistorySource(
    access: CollaborationSpaceLocalAccess,
    binding: CollaborationSpaceRoomBinding,
    source: CollaborationSpaceHistoryLocation,
    original: CollaborationHistoryMessage,
    group: CollaborationSpaceMessageRecord?,
    company: CollaborationMessageRecord?,
): AppResult<CollaborationSpaceInboxHistorySource> {
    val selected =
        resolveCollaborationSpaceHistoryLocation(access, binding.conversation.id, binding)
    if (selected is AppResult.Failed) return selected
    if (
        (selected as AppResult.Success).value != source ||
            !validCollaborationHistoryMessage(original) ||
            (group != null && group.intent.id != original.logicalId) ||
            (company != null && company.intent.id != original.logicalId) ||
            (company != null && binding.original == null)
    )
        return invalidInboxHistorySource()
    val matches = mutableListOf<CollaborationSpaceInboxOriginalAssociation>()
    var unproven = false
    if (group != null) {
        val result = collaborationSpaceInboxOriginalAssociation(access, binding, group, original)
        if (result is AppResult.Failed) return result
        (result as AppResult.Success).value?.let(matches::add)
        unproven =
            group.intent.conversationId == binding.conversation.id &&
                group.intent.editTarget == null &&
                unprovenInboxOriginal(group.state, binding.room.roomId, group.intent.text, original)
    }
    if (company != null) {
        val result = collaborationSpaceInboxOriginalAssociation(access, binding, company, original)
        if (result is AppResult.Failed) return result
        (result as AppResult.Success).value?.let(matches::add)
        if (
            company.intent.conversationId ==
                binding.original?.conversation?.position?.conversationId &&
                company.intent.editTarget == null &&
                unprovenInboxOriginal(
                    company.state,
                    binding.room.roomId,
                    company.intent.text,
                    original,
                )
        )
            unproven = true
    }
    return AppResult.Success(
        when {
            matches.size > 1 ->
                CollaborationSpaceInboxHistorySource.Unresolved(
                    CollaborationSpaceInboxHistorySourceReason.MULTIPLE_ORIGINALS
                )
            unproven ->
                CollaborationSpaceInboxHistorySource.Unresolved(
                    CollaborationSpaceInboxHistorySourceReason.ORIGINAL_NOT_PROVEN
                )
            matches.size == 1 -> CollaborationSpaceInboxHistorySource.Original(matches.single())
            else ->
                CollaborationSpaceInboxHistorySource.History(
                    CollaborationSpaceInboxSource.History(
                        (source as? CollaborationSpaceHistoryLocation.Original)?.access?.companyId,
                        source.conversationId,
                        source.roomId,
                        original.sender,
                        original.primaryEventId,
                    )
                )
        }
    )
}

/** Missing proof matters only while immutable text/room facts still permit this association. */
private fun unprovenInboxOriginal(
    state: CollaborationMessageState,
    roomId: String,
    text: String,
    original: CollaborationHistoryMessage,
): Boolean {
    if (state.origin != null || (!original.redacted && original.text != text)) return false
    return when (state) {
        CollaborationMessageState.Queued -> true
        CollaborationMessageState.Cancelled -> false
        is CollaborationMessageState.Staging -> state.device.roomId == roomId
        is CollaborationMessageState.Staged -> state.device.roomId == roomId
        is CollaborationMessageState.Sent -> state.device.roomId == roomId
    }
}

private fun invalidInboxHistorySource() =
    collaborationRecoveryFailure("client.collaboration_invalid_response", FailureKind.Unexpected)
