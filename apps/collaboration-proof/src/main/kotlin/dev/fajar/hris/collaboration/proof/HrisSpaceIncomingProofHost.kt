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
            check(result.exhausted == retained.exhausted)
            if (result.exhausted) {
                val before = retained
                val exhausted = expect(acquire(runtime, conversation))
                check(exhausted == CollaborationProviderHistoryAcquisition(0, true))
                check(expect(storage.progress(access, traversal)) == before)
                return index + 1
            }
        }
        error("Fictional provider history exceeded four pages")
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
