package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceRegistrationRecord
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.koin.core.Koin

/** Real application admission/storage bindings for the isolated incoming-event acceptance. */
internal class HrisSpaceIncomingProofHost(private val koin: Koin) {
    suspend fun acquire(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
        events: List<String>,
    ) = expect(koin.get<AcquireCollaborationSpaceEvents>()(runtime, conversation, events))

    /** Actual admitted acquisition; an exhausted repeat must not acquire transport again. */
    suspend fun stageProviderPages(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
        roomId: String,
        physicalOwner: CollaborationOwner,
        physicalDeviceId: String,
    ): Int {
        val access = runtime.owner.localDirectoryAccess()
        val traversal =
            CollaborationHistoryTraversal(
                conversation.id,
                roomId,
                runtime.deviceId,
                CollaborationHistoryPurpose.Incoming,
            )
        val storage = koin.get<CollaborationSpaceHistoryTraversalRepository>()
        val acquire = koin.get<AcquireCollaborationSpaceProviderHistory>()
        check(expect(storage.progress(access, traversal)) == null)
        repeat(4) { index ->
            val result = expect(acquire(runtime, conversation))
            val retained = requireNotNull(expect(storage.progress(access, traversal)))
            check(
                retained.provider ==
                    CollaborationHistoryProviderIdentity(physicalOwner.companyId, physicalDeviceId)
            )
            check(
                result.progress == retained &&
                    result.traversal == traversal &&
                    result.runtime == runtime
            )
            check(result.exhausted == retained.exhausted)
            if (result.exhausted) {
                val before = retained
                val exhausted = expect(acquire(runtime, conversation))
                check(exhausted.references == 0 && exhausted.exhausted)
                check(exhausted.previous == before && exhausted.progress == before)
                check(exhausted.origin == CollaborationHistoryPageOrigin.SavedPosition)
                check(expect(storage.progress(access, traversal)) == before)
                return index + 1
            }
        }
        error("Fictional provider history exceeded four pages")
    }

    /** Uses the registered local owners and one current-admitted acquisition for a whole page. */
    suspend fun completePending(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
        roomId: String,
    ): CollaborationSpaceIncomingReferenceCompletion {
        val access = runtime.owner.localDirectoryAccess()
        val key =
            CollaborationHistoryTraversal(
                conversation.id,
                roomId,
                runtime.deviceId,
                CollaborationHistoryPurpose.Incoming,
            )
        val read = koin.get<ReadCollaborationSpaceIncomingReferences>()
        val complete = koin.get<CompleteCollaborationSpaceIncomingReferences>()
        val source = expect(read(access, key))
        check(source.events.size in 1..16 && source.after == null)
        val batch = acquire(runtime, conversation, source.events.map { it.event.eventId })
        val result = expect(complete(access, key, source, batch))
        check(result.resolved == source.events.size && result.unresolved == 0)
        check(!result.more && !result.retryRequired)
        val drained = expect(read(access, key))
        check(drained.events.isEmpty() && drained.after == null)
        val empty = expect(complete(access, key, drained, null))
        check(empty == CollaborationSpaceIncomingReferenceCompletion(0, 0, false, false))
        check(expect(read(access, key)) == drained)
        return result
    }

    suspend fun resolveRetainedSources(
        runtime: CollaborationSpaceRuntimeReady,
        conversationId: String,
        roomId: String,
        sources: List<String>,
    ) {
        val access = runtime.owner.localDirectoryAccess()
        val traversal =
            CollaborationHistoryTraversal(
                conversationId,
                roomId,
                runtime.deviceId,
                CollaborationHistoryPurpose.Incoming,
            )
        val storage = koin.get<CollaborationSpaceHistoryTraversalRepository>()
        val references = expect(storage.observeReferences(access, traversal, limit = 50).first())
        check(references.after == null)
        val retained = sources.map { id -> references.events.single { it.event.eventId == id } }
        check(retained.size == 2)
        check(
            expect(
                storage.resolve(access, traversal, requireNotNull(references.progress), retained)
            )
        )
    }

    /** Local observation admits the owner before any separate raw evidence/journal read. */
    suspend fun read(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String,
        deviceId: String,
        originalId: String,
        editId: String,
        redactedId: String,
    ): Snapshot {
        val page =
            expect(koin.get<ObserveCollaborationSpaceHistory>()(access, conversationId).first())
        check(page.roomId == roomId && page.next == null && page.messages.size == 1)
        val history = koin.get<CollaborationSpaceHistoryRepository>()
        val edits = koin.get<CollaborationSpaceMessageEditRepository>()
        val original =
            requireNotNull(expect(history.getEventFact(access, conversationId, roomId, originalId)))
        val replacement =
            requireNotNull(expect(edits.getReplacementFact(access, conversationId, roomId, editId)))
        val redacted =
            requireNotNull(expect(history.getEventFact(access, conversationId, roomId, redactedId)))
        check(expect(history.getEventFact(access, conversationId, roomId, editId)) == null)
        check(
            expect(
                    koin
                        .get<CollaborationSpaceMessageRepository>()
                        .observe(access, conversationId)
                        .first()
                )
                .isEmpty()
        )
        check(
            expect(koin.get<CollaborationSpaceReceiptRepository>().read(access, conversationId))
                .isEmpty()
        )
        val references =
            expect(
                koin
                    .get<CollaborationSpaceHistoryTraversalRepository>()
                    .observeReferences(
                        access,
                        CollaborationHistoryTraversal(
                            conversationId,
                            roomId,
                            deviceId,
                            CollaborationHistoryPurpose.Incoming,
                        ),
                        limit = 50,
                    )
                    .first()
            )
        check(references.after == null && references.progress?.exhausted == true)
        return Snapshot(page, original, replacement, redacted, references)
    }

    /**
     * Local canonical metadata and registration, also available after offline process reopening.
     */
    suspend fun originalLocation(
        owner: CollaborationSpaceOwner,
        canonicalConversationId: String,
    ): OriginalLocation {
        val access = owner.localDirectoryAccess()
        val binding =
            requireNotNull(
                expect(
                    koin
                        .get<CollaborationSpaceRoomBindingRepository>()
                        .getByConversation(access, canonicalConversationId)
                )
            )
        check(binding.room.origin == CollaborationSpaceRoomOrigin.LEGACY)
        val original = requireNotNull(binding.original)
        val registered =
            requireNotNull(
                expect(
                    koin
                        .get<CollaborationSpaceRegistrationCommandRepository>()
                        .current(access, owner.parent.nativeSessionId)
                )
            )
        check(validCollaborationSpaceRegistrationRecord(access, registered))
        check(registered.outcome is CollaborationSpaceRegistrationOutcome.Registered)
        val legacy = requireNotNull(registered.command.legacy)
        check(legacy.binding.companyId == original.conversation.position.companyId)
        check(original.room.roomId == binding.room.roomId)
        return OriginalLocation(
            access,
            canonicalConversationId,
            CollaborationLocalAccess(
                access.sessionId,
                access.audience,
                access.accountId,
                original.conversation.position.companyId,
            ),
            CollaborationHistoryTraversal(
                original.conversation.position.conversationId,
                original.room.roomId,
                legacy.deviceId,
                CollaborationHistoryPurpose.Incoming,
            ),
            original,
        )
    }

    /** The registered worker must publish both metadata indexes before any content is read. */
    suspend fun readOriginalPage(location: OriginalLocation): CollaborationHistoryPage {
        val metadata = koin.get<CollaborationSpaceOriginalRoomMetadataRepository>()
        val original = location.metadata
        check(
            expect(
                metadata.getByConversation(location.spaceAccess, original.conversation.position)
            ) == original
        )
        check(
            expect(
                metadata.findByRoom(
                    location.spaceAccess,
                    original.conversation.position.companyId,
                    original.room.serverName,
                    original.room.roomId,
                )
            ) == original
        )
        val key = location.traversal
        return expect(
            koin
                .get<CollaborationHistoryRepository>()
                .observe(location.companyAccess, key.conversationId, key.roomId)
                .first()
        )
    }

    /** Company content must not be relabeled into a Published Space ledger. */
    suspend fun readOriginal(
        location: OriginalLocation,
        originalId: String,
        editId: String,
        redactedId: String,
    ): Snapshot {
        val access = location.companyAccess
        val traversal = location.traversal
        val history = koin.get<CollaborationHistoryRepository>()
        val edits = koin.get<CollaborationMessageEditRepository>()
        val page = readOriginalPage(location)
        check(page.roomId == traversal.roomId && page.next == null && page.messages.size == 1)
        val original =
            requireNotNull(
                expect(
                    history.getEventFact(
                        access,
                        traversal.conversationId,
                        traversal.roomId,
                        originalId,
                    )
                )
            )
        val replacement =
            requireNotNull(
                expect(
                    edits.getReplacementFact(
                        access,
                        traversal.conversationId,
                        traversal.roomId,
                        editId,
                    )
                )
            )
        val redacted =
            requireNotNull(
                expect(
                    history.getEventFact(
                        access,
                        traversal.conversationId,
                        traversal.roomId,
                        redactedId,
                    )
                )
            )
        check(
            expect(
                history.getEventFact(access, traversal.conversationId, traversal.roomId, editId)
            ) == null
        )
        val spaceHistory = koin.get<CollaborationSpaceHistoryRepository>()
        for (id in listOf(originalId, editId, redactedId)) {
            check(
                expect(
                    spaceHistory.getEventFact(
                        location.spaceAccess,
                        location.canonicalConversationId,
                        traversal.roomId,
                        id,
                    )
                ) == null
            )
        }
        check(
            expect(
                    koin
                        .get<CollaborationSpaceMessageRepository>()
                        .observe(location.spaceAccess, location.canonicalConversationId)
                        .first()
                )
                .isEmpty()
        )
        check(
            expect(
                    koin
                        .get<CollaborationSpaceReceiptRepository>()
                        .read(location.spaceAccess, location.canonicalConversationId)
                )
                .isEmpty()
        )
        val references =
            expect(
                koin
                    .get<CollaborationHistoryTraversalRepository>()
                    .observeReferences(access, traversal, limit = 50)
                    .first()
            )
        check(references.after == null && references.progress?.exhausted == true)
        check(requireNotNull(references.progress).provider == null)
        return Snapshot(
            page,
            original,
            replacement,
            redacted,
            references,
            companyOwned = true,
            physicalProvider =
                CollaborationHistoryProviderIdentity(access.companyId, traversal.deviceId),
        )
    }

    /** Read retained work after a failed process without starting a worker or native source. */
    suspend fun readWorkerState(
        owner: CollaborationSpaceOwner,
        canonicalConversationId: String,
        deviceId: String,
        operationId: String,
    ): JSONObject {
        val runtime = koin.get<CollaborationSpaceRuntimeRepository>()
        check(!expect(runtime.isSpaceOpen(owner, deviceId, operationId)))
        val location = originalLocation(owner, canonicalConversationId)
        val work = expect(koin.get<ReadCollaborationSpaceIncomingDemand>()(location.spaceAccess))
        val demand = requireNotNull(work.snapshot).demand
        val traversal = koin.get<CollaborationHistoryTraversalRepository>()
        val references =
            expect(
                traversal
                    .observeReferences(location.companyAccess, location.traversal, limit = 1)
                    .first()
            )
        val progress = requireNotNull(references.progress)
        val rooms =
            JSONArray(
                demand.rooms.take(8).map { room ->
                    JSONObject()
                        .put("original", room.target is CollaborationSpaceIncomingTarget.Original)
                        .put("phase", room.phase.name)
                        .put("position", room.position)
                        .put("retryFailures", room.retry?.failures ?: 0)
                        .put("hasHead", room.headEventId != null)
                }
            )
        val sources =
            JSONArray(
                demand.sources.take(8).map { source ->
                    JSONObject()
                        .put("inventoryActive", source.inventoryGeneration != null)
                        .put("inventoryRequested", source.inventoryRequestedGeneration != null)
                        .put("inventoryAfter", source.inventoryAfterRoomId != null)
                        .put("retryFailures", source.inventoryRetry?.failures ?: 0)
                }
            )
        val recovery =
            demand.historyRecovery?.let {
                JSONObject()
                    .put("complete", it.complete)
                    .put("requested", it.requestedGeneration != null)
                    .put("after", it.afterPartition != null)
                    .put("moreHistoryPages", it.moreHistoryPages)
                    .put("unresolved", it.unresolved)
                    .put("retryFailures", it.retry?.failures ?: 0)
            }
        val result =
            JSONObject()
                .put(
                    "wakePresent",
                    expect(koin.get<ReadCollaborationSpaceIncomingWake>()()) != null,
                )
                .put("rooms", rooms)
                .put("roomCount", demand.rooms.size)
                .put("physicalCount", demand.physicalRooms.size)
                .put("physicalRetries", demand.physicalRooms.count { it.retry != null })
                .put("sources", sources)
                .put("sourceCount", demand.sources.size)
                .put("rescan", demand.rescanGeneration != null)
                .put("scan", demand.scan != null)
                .put("readyRooms", work.ready.size)
                .put("readyPhysical", work.physicalReady.size)
                .put("readyInventory", work.inventoryReady.size)
                .put("readyHistory", work.historyReady != null)
                .put("deferredHistory", work.historyDeferred != null)
                .put("recovery", recovery)
                .put("roomsPending", work.roomsPending)
                .put("scanPending", work.scanPending)
                .put("companyProviderExhausted", progress.exhausted)
                .put("providerReferences", progress.references)
                .put("companyProviderMarkerAbsent", progress.provider == null)
                .put("pendingPageSize", references.events.size)
                .put("morePending", references.after != null)
                .put("pendingRetryRequired", progress.incoming?.pendingRetryRequired)
                .put("messages", readOriginalPage(location).messages.size)
        check(
            expect(
                koin.get<CollaborationSpaceIncomingDemandRepository>().current(location.spaceAccess)
            ) == work.snapshot
        )
        check(!expect(runtime.isSpaceOpen(owner, deviceId, operationId)))
        return result.put("demandUnchanged", true).put("runtimeNeverOpened", true)
    }

    data class OriginalLocation(
        val spaceAccess: CollaborationSpaceLocalAccess,
        val canonicalConversationId: String,
        val companyAccess: CollaborationLocalAccess,
        val traversal: CollaborationHistoryTraversal,
        val metadata: CollaborationSpaceOriginalRoomMetadata,
    ) {
        override fun toString() = "OriginalHistoryProofLocation([redacted])"
    }

    data class Snapshot(
        val page: CollaborationHistoryPage,
        val original: CollaborationHistoryEventFact,
        val replacement: CollaborationReplacementEventFact,
        val redacted: CollaborationHistoryEventFact,
        val references: CollaborationHistoryReferences,
        val companyOwned: Boolean = false,
        val physicalProvider: CollaborationHistoryProviderIdentity =
            requireNotNull(references.progress?.provider),
    ) {
        override fun toString() = "IncomingProofSnapshot([redacted])"
    }

    private fun <T> expect(value: AppResult<T>): T =
        when (value) {
            is AppResult.Success -> value.value
            is AppResult.Failed ->
                error("Incoming fixture: ${value.failure.kind}:${value.failure.code}")
        }
}
