package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.flow.first
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

    data class Snapshot(
        val page: CollaborationHistoryPage,
        val original: CollaborationHistoryEventFact,
        val replacement: CollaborationReplacementEventFact,
        val redacted: CollaborationHistoryEventFact,
        val references: CollaborationHistoryReferences,
    ) {
        override fun toString() = "SpaceIncomingProofSnapshot([redacted])"
    }

    private fun <T> expect(value: AppResult<T>): T =
        when (value) {
            is AppResult.Success -> value.value
            is AppResult.Failed ->
                error("Incoming fixture: ${value.failure.kind}:${value.failure.code}")
        }
}
