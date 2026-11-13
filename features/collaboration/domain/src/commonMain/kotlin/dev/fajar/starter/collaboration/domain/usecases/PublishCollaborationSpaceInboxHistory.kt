package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One finite admitted publication. Existing incoming/backfill owners retain all unresolved work.
 */
class PublishCollaborationSpaceInboxHistory(
    private val history: CollaborationSpaceHistorySnapshotRepository,
    private val companyHistory: CollaborationCompanyHistorySnapshotRepository,
    private val messages: CollaborationSpaceMessageRepository,
    private val companyMessages: CollaborationMessageRepository,
    private val completions: CollaborationSpaceMessageCompletionRepository,
    private val companyCompletions: CollaborationMessageCompletionRepository,
    private val inbox: CollaborationSpaceInboxRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        eventIds: List<String>,
    ): AppResult<CollaborationSpaceInboxHistoryCommit> {
        if (eventIds.size !in 1..16) return unavailableHistoryInbox(FailureKind.Validation)
        val ids = eventIds.toList()
        if (
            !validCollaborationMessageId(conversationId) ||
                ids.distinct().size != ids.size ||
                ids.any { !validCollaborationEventId(it) }
        )
            return unavailableHistoryInbox(FailureKind.Validation)
        return Publication(access, conversationId, ids).run()
    }

    /** Admission, contention and suspended reads belong to this exact captured reference batch. */
    private inner class Publication(
        private val access: CollaborationSpaceLocalAccess,
        private val conversationId: String,
        private val eventIds: List<String>,
    ) {
        private var denial: Failure? = null

        suspend fun run(): AppResult<CollaborationSpaceInboxHistoryCommit> {
            repeat(3) {
                current().let { if (it is AppResult.Failed) return it }
                val acquired = readSource()
                if (acquired is AppResult.Failed) return acquired
                val source = (acquired as AppResult.Success).value ?: return@repeat
                if (source.originals.isEmpty()) return completed(source.snapshot, eventIds)
                val captured = readIndex(source)
                if (captured is AppResult.Failed) return captured
                val index = (captured as AppResult.Success).value ?: return@repeat
                val evidence = readOperationEvidence(index)
                if (evidence is AppResult.Failed) return evidence
                val coherent = (evidence as AppResult.Success).value ?: return@repeat
                val updates = resolveEdits(index, coherent)
                if (updates is AppResult.Failed) return updates
                val prepared =
                    collaborationSpaceInboxHistoryProjection(
                        access,
                        source.binding,
                        source.location,
                        index.conversation,
                        index.anchors,
                        (updates as AppResult.Success).value,
                    )
                if (prepared is AppResult.Failed) return prepared
                val projection = (prepared as AppResult.Success).value
                val proposed = projection.publication
                if (proposed == null) return completed(coherent, projection.unresolvedEventIds)
                val revision = coherent.revision ?: return unavailableHistoryInbox()
                current().let { if (it is AppResult.Failed) return it }
                val saved =
                    when (val location = source.location) {
                        is CollaborationSpaceHistoryLocation.Space ->
                            history.publishInbox(
                                access,
                                location.conversationId,
                                location.roomId,
                                revision,
                                proposed,
                            )
                        is CollaborationSpaceHistoryLocation.Original ->
                            companyHistory.publishInbox(access, location, revision, proposed)
                    }
                current().let { if (it is AppResult.Failed) return it }
                // Lost responses remain failures. A later admitted invocation confirms a no-op;
                // this batch must not complete an incoming reference on an uncertain write.
                if (saved is AppResult.Failed) return saved
                if ((saved as AppResult.Success).value)
                    return completed(coherent, projection.unresolvedEventIds)
            }
            return unavailableHistoryInbox(FailureKind.Conflict)
        }

        private fun completed(
            snapshot: CollaborationSpaceHistorySnapshot,
            unresolvedOriginals: List<String>,
        ): AppResult<CollaborationSpaceInboxHistoryCommit> {
            val unresolved =
                snapshot.events
                    .filter {
                        it.original == null || it.original.primaryEventId in unresolvedOriginals
                    }
                    .map { it.eventId }
                    .toSet()
            return AppResult.Success(
                CollaborationSpaceInboxHistoryCommit(
                    eventIds.filterNot { it in unresolved },
                    eventIds.filter { it in unresolved },
                )
            )
        }

        private suspend fun readSource(): AppResult<HistorySource?> {
            val bound = bindings.getByConversation(access, conversationId)
            current().let { if (it is AppResult.Failed) return it }
            if (bound is AppResult.Failed) return bound
            val binding =
                (bound as AppResult.Success).value
                    ?: return unavailableHistoryInbox(FailureKind.Conflict)
            val selected = resolveCollaborationSpaceHistoryLocation(access, conversationId, binding)
            if (selected is AppResult.Failed) return selected
            val location = (selected as AppResult.Success).value
            val read = readSnapshot(location, emptyList())
            if (read is AppResult.Failed) return read
            val snapshot = (read as AppResult.Success).value ?: return AppResult.Success(null)
            val captured =
                captureCollaborationSpaceInboxHistory(access, binding, location, eventIds, snapshot)
            if (captured is AppResult.Failed) return captured
            if (snapshot.editOperations.isNotEmpty()) return unavailableHistoryInbox()
            return AppResult.Success(
                HistorySource(binding, location, snapshot, (captured as AppResult.Success).value)
            )
        }

        private suspend fun readSnapshot(
            location: CollaborationSpaceHistoryLocation,
            operations: List<CollaborationHistoryEditOperation>,
        ): AppResult<CollaborationSpaceHistorySnapshot?> {
            val read =
                when (location) {
                    is CollaborationSpaceHistoryLocation.Space ->
                        history.snapshot(
                            access,
                            location.conversationId,
                            location.roomId,
                            eventIds,
                            operations,
                        )
                    is CollaborationSpaceHistoryLocation.Original ->
                        companyHistory.snapshot(location, eventIds, operations)
                }
            // Freeze bounded proof before admission suspends again. A repository-owned mutable
            // list cannot change which aliases or operation facts this invocation later uses.
            val captured: AppResult<CollaborationSpaceHistorySnapshot?> =
                when (read) {
                    is AppResult.Failed -> read
                    is AppResult.Success -> {
                        val value = read.value
                        when {
                            value == null -> AppResult.Success(null)
                            value.events.size > 16 ||
                                value.editOperations.size > 16 ||
                                value.events.any { (it.original?.eventIds?.size ?: 0) > 128 } ||
                                value.editOperations.any { it.events.size > 128 } ->
                                unavailableHistoryInbox()
                            else ->
                                AppResult.Success(
                                    value.copy(
                                        events =
                                            value.events.map { event ->
                                                event.copy(
                                                    original =
                                                        event.original?.let {
                                                            it.copy(eventIds = it.eventIds.toList())
                                                        }
                                                )
                                            },
                                        editOperations =
                                            value.editOperations.map {
                                                it.copy(events = it.events.toList())
                                            },
                                    )
                                )
                        }
                    }
                }
            current().let { if (it is AppResult.Failed) return it }
            return captured
        }

        private suspend fun originalIdentity(
            source: HistorySource,
            original: CollaborationHistoryMessage,
        ): AppResult<CollaborationSpaceInboxHistorySource> {
            val group =
                original.logicalId?.let { messages.get(access, it) } ?: AppResult.Success(null)
            current().let { if (it is AppResult.Failed) return it }
            if (group is AppResult.Failed) return group
            val companyAccess = source.companyAccess
            val company =
                if (companyAccess != null && original.logicalId != null)
                    companyMessages.get(companyAccess, original.logicalId)
                else AppResult.Success(null)
            current().let { if (it is AppResult.Failed) return it }
            if (company is AppResult.Failed) return company
            return resolveCollaborationSpaceInboxHistorySource(
                access,
                source.binding,
                source.location,
                original,
                (group as AppResult.Success).value,
                (company as AppResult.Success).value,
            )
        }

        private suspend fun readIndex(source: HistorySource): AppResult<HistoryIndex?> {
            val changes = mutableListOf<CollaborationSpaceInboxHistoryUpdate>()
            val candidates = linkedMapOf<String, List<CollaborationSpaceInboxSource>>()
            for (original in source.originals) {
                val identity = originalIdentity(source, original)
                if (identity is AppResult.Failed) return identity
                val physical =
                    CollaborationSpaceInboxSource.History(
                        (source.location as? CollaborationSpaceHistoryLocation.Original)
                            ?.access
                            ?.companyId,
                        source.location.conversationId,
                        source.location.roomId,
                        original.sender,
                        original.primaryEventId,
                    )
                candidates[original.primaryEventId] = buildList {
                    add(physical)
                    original.logicalId?.let { id ->
                        add(CollaborationSpaceInboxSource.GroupOriginal(id))
                        source.companyAccess?.let { company ->
                            add(
                                CollaborationSpaceInboxSource.CompanyOriginal(
                                    company.companyId,
                                    requireNotNull(source.companyConversationId),
                                    id,
                                )
                            )
                        }
                    }
                }
                changes +=
                    CollaborationSpaceInboxHistoryUpdate(
                        original,
                        (identity as AppResult.Success).value,
                    )
            }
            val currentIndex = inbox.conversation(access, conversationId, source.originals.size + 1)
            val captured =
                when (currentIndex) {
                    is AppResult.Failed -> currentIndex
                    is AppResult.Success ->
                        captureCollaborationSpaceInboxConversation(
                            conversationId,
                            currentIndex.value,
                        )
                }
            current().let { if (it is AppResult.Failed) return it }
            if (captured is AppResult.Failed) return captured
            val conversation = (captured as AppResult.Success).value
            val read = readAnchors(candidates.values.flatten().distinct(), conversation.version)
            if (read is AppResult.Failed) return read
            val anchors = (read as AppResult.Success).value ?: return AppResult.Success(null)
            val excluded = mutableSetOf<CollaborationSpaceInboxSource>()
            val admitted = mutableListOf<CollaborationSpaceInboxHistoryUpdate>()
            for (update in changes) {
                val keys = candidates.getValue(update.original.primaryEventId)
                val found = anchors.values.filter { it.source in keys }
                if (found.size > 1) return unavailableHistoryInbox()
                val old = found.singleOrNull()
                val proven =
                    (update.identity as? CollaborationSpaceInboxHistorySource.Original)
                        ?.association
                        ?.source
                if (old != null && old.source != keys.first() && old.source != proven) {
                    // A retained original anchor outlives its command proof. Missing proof is
                    // unresolved work, never permission to allocate a second physical History row.
                    excluded += old.source
                    admitted +=
                        update.copy(
                            identity =
                                CollaborationSpaceInboxHistorySource.Unresolved(
                                    CollaborationSpaceInboxHistorySourceReason.ORIGINAL_NOT_PROVEN
                                )
                        )
                } else admitted += update
            }
            val selected = anchors.copy(values = anchors.values.filterNot { it.source in excluded })
            val validated =
                collaborationSpaceInboxHistoryProjection(
                    access,
                    source.binding,
                    source.location,
                    conversation,
                    selected,
                    admitted,
                )
            if (validated is AppResult.Failed) return validated
            return AppResult.Success(
                HistoryIndex(source, conversation, selected, admitted.toList())
            )
        }

        /** At most48 possible identities in two bounded reads of the SAME index generation. */
        private suspend fun readAnchors(
            requested: List<CollaborationSpaceInboxSource>,
            version: CollaborationSpaceInboxVersion?,
        ): AppResult<CollaborationSpaceInboxActivities?> {
            if (requested.size !in 1..48 || requested.distinct() != requested)
                return unavailableHistoryInbox()
            val values = mutableListOf<CollaborationSpaceInboxActivity>()
            for (chunk in requested.chunked(32)) {
                val read = inbox.activities(access, conversationId, chunk)
                val detached =
                    when (read) {
                        is AppResult.Failed -> read
                        is AppResult.Success ->
                            if (read.value.values.size > chunk.size) unavailableHistoryInbox()
                            else
                                AppResult.Success(
                                    read.value.copy(values = read.value.values.toList())
                                )
                    }
                current().let { if (it is AppResult.Failed) return it }
                if (detached is AppResult.Failed) return detached
                val captured = (detached as AppResult.Success).value
                if (captured.version != version) return AppResult.Success(null)
                if (
                    captured.values.size > chunk.size ||
                        captured.values.map { it.source }.distinct().size != captured.values.size ||
                        captured.values.any {
                            it.source !in chunk ||
                                !validCollaborationSpaceInboxActivity(
                                    it,
                                    version?.revision ?: 0L,
                                ) ||
                                it.position.ordinal > (version?.lastOrdinal ?: 0L)
                        }
                )
                    return unavailableHistoryInbox()
                values += captured.values
            }
            return AppResult.Success(CollaborationSpaceInboxActivities(version, values.toList()))
        }

        private suspend fun readOperationEvidence(
            index: HistoryIndex
        ): AppResult<CollaborationSpaceHistorySnapshot?> {
            val operations =
                index.anchors.values
                    .mapNotNull {
                        it.pendingEdit?.let { edit ->
                            CollaborationHistoryEditOperation(edit.target.sender, edit.source.id)
                        }
                    }
                    .distinct()
            if (operations.isEmpty()) return AppResult.Success(index.source.snapshot)
            if (operations.size > 16) return unavailableHistoryInbox()
            val acquired = readSnapshot(index.source.location, operations)
            if (acquired is AppResult.Failed) return acquired
            val snapshot = (acquired as AppResult.Success).value ?: return AppResult.Success(null)
            if (
                snapshot.revision != index.source.snapshot.revision ||
                    snapshot.events != index.source.snapshot.events
            )
                return AppResult.Success(null)
            if (snapshot.editOperations.map { it.operation } != operations)
                return unavailableHistoryInbox()
            return AppResult.Success(
                snapshot.copy(
                    editOperations =
                        snapshot.editOperations.map { it.copy(events = it.events.toList()) }
                )
            )
        }

        private suspend fun resolveEdits(
            index: HistoryIndex,
            snapshot: CollaborationSpaceHistorySnapshot,
        ): AppResult<List<CollaborationSpaceInboxHistoryUpdate>> {
            val updates = mutableListOf<CollaborationSpaceInboxHistoryUpdate>()
            for (update in index.updates) {
                val original = update.original
                val physical =
                    CollaborationSpaceInboxSource.History(
                        (index.source.location as? CollaborationSpaceHistoryLocation.Original)
                            ?.access
                            ?.companyId,
                        index.source.location.conversationId,
                        index.source.location.roomId,
                        original.sender,
                        original.primaryEventId,
                    )
                val command =
                    (update.identity as? CollaborationSpaceInboxHistorySource.Original)
                        ?.association
                        ?.source
                val activity =
                    index.anchors.values.singleOrNull {
                        it.source == physical || it.source == command
                    }
                val pending = activity?.pendingEdit
                if (pending == null) {
                    updates += update
                    continue
                }
                val evidence =
                    snapshot.editOperations
                        .singleOrNull {
                            it.operation ==
                                CollaborationHistoryEditOperation(
                                    pending.target.sender,
                                    pending.source.id,
                                )
                        }
                        ?.events ?: return unavailableHistoryInbox()
                val cleared = resolveEdit(index.source, activity, original, evidence)
                if (cleared is AppResult.Failed) return cleared
                updates +=
                    update.copy(
                        resolvedEdit = pending.takeIf { (cleared as AppResult.Success).value }
                    )
            }
            return AppResult.Success(updates.toList())
        }

        private suspend fun resolveEdit(
            source: HistorySource,
            activity: CollaborationSpaceInboxActivity,
            original: CollaborationHistoryMessage,
            evidence: List<CollaborationEditEvidence>,
        ): AppResult<Boolean> {
            val pending = requireNotNull(activity.pendingEdit)
            val outcome: AppResult<CollaborationEditOutcomeKind>
            val present: Boolean
            when (val command = pending.source) {
                is CollaborationSpaceInboxEditSource.Group -> {
                    val read = messages.get(access, command.id)
                    current().let { if (it is AppResult.Failed) return it }
                    if (read is AppResult.Failed) return read
                    val record = (read as AppResult.Success).value
                    present = record != null
                    outcome =
                        collaborationSpaceInboxEditOutcome(
                            access,
                            conversationId,
                            activity,
                            original,
                            record,
                            evidence,
                        )
                }
                is CollaborationSpaceInboxEditSource.Company -> {
                    val companyAccess = source.companyAccess ?: return unavailableHistoryInbox()
                    if (
                        command.companyId != companyAccess.companyId ||
                            command.conversationId != source.companyConversationId
                    )
                        return unavailableHistoryInbox()
                    val read = companyMessages.get(companyAccess, command.id)
                    current().let { if (it is AppResult.Failed) return it }
                    if (read is AppResult.Failed) return read
                    val record = (read as AppResult.Success).value
                    present = record != null
                    outcome =
                        collaborationSpaceInboxEditOutcome(
                            companyAccess,
                            activity,
                            original,
                            record,
                            evidence,
                        )
                }
            }
            if (outcome is AppResult.Failed) return outcome
            if (
                (outcome as AppResult.Success).value !=
                    CollaborationEditOutcomeKind.AWAITING_PROVIDER
            )
                return AppResult.Success(true)
            if (!present) return AppResult.Success(false)
            return newerEdit(source, activity, original)
        }

        private suspend fun newerEdit(
            source: HistorySource,
            activity: CollaborationSpaceInboxActivity,
            original: CollaborationHistoryMessage,
        ): AppResult<Boolean> {
            val id = original.latestEdit?.edit?.operationId ?: return AppResult.Success(false)
            val group = completions.readCompletion(access, id)
            current().let { if (it is AppResult.Failed) return it }
            if (group is AppResult.Failed) return group
            val companyAccess = source.companyAccess
            val company =
                if (companyAccess != null) companyCompletions.readCompletion(companyAccess, id)
                else AppResult.Success(null)
            current().let { if (it is AppResult.Failed) return it }
            if (company is AppResult.Failed) return company
            return collaborationSpaceInboxEditSuperseded(
                access,
                conversationId,
                activity,
                original,
                (group as AppResult.Success).value,
                companyAccess,
                source.companyConversationId,
                (company as AppResult.Success).value,
            )
        }

        private suspend fun current(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val identity = sessions.current()
            currentCoroutineContext().ensureActive()
            if (identity is AppResult.Failed) return AppResult.Failed(denial ?: identity.failure)
            val owned =
                localCollaborationSpaceAccess(
                    access,
                    (identity as AppResult.Success).value,
                    server.audience,
                )
            if (owned is AppResult.Failed) return AppResult.Failed(denial ?: owned.failure)
            val read = directory.admission(access)
            currentCoroutineContext().ensureActive()
            if (read is AppResult.Failed) return AppResult.Failed(denial ?: read.failure)
            val gate = (read as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(gate))
                return AppResult.Failed(denial ?: unavailableHistoryInbox().failure)
            denial = denial ?: gate.denial
            val latest = sessions.current()
            currentCoroutineContext().ensureActive()
            denial?.let {
                return AppResult.Failed(it)
            }
            if (latest is AppResult.Failed) return latest
            val current =
                localCollaborationSpaceAccess(
                    access,
                    (latest as AppResult.Success).value,
                    server.audience,
                )
            return if (current is AppResult.Failed) current else AppResult.Success(Unit)
        }
    }

    private data class HistorySource(
        val binding: CollaborationSpaceRoomBinding,
        val location: CollaborationSpaceHistoryLocation,
        val snapshot: CollaborationSpaceHistorySnapshot,
        val originals: List<CollaborationHistoryMessage>,
    ) {
        val companyAccess: CollaborationLocalAccess? =
            (location as? CollaborationSpaceHistoryLocation.Original)?.access
        val companyConversationId: String? =
            (location as? CollaborationSpaceHistoryLocation.Original)?.conversationId

        override fun toString() = "HistoryInboxSource([redacted])"
    }

    private data class HistoryIndex(
        val source: HistorySource,
        val conversation: CollaborationSpaceInboxConversation,
        val anchors: CollaborationSpaceInboxActivities,
        val updates: List<CollaborationSpaceInboxHistoryUpdate>,
    ) {
        override fun toString() = "HistoryInboxIndex([redacted])"
    }
}

private fun unavailableHistoryInbox(kind: FailureKind = FailureKind.Unexpected) =
    collaborationRecoveryFailure(
        if (kind == FailureKind.Conflict) "client.collaboration_inbox_busy"
        else "client.collaboration_invalid_response",
        kind,
    )
