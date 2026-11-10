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

    /** Local observation admits the owner before any separate raw evidence/journal read. */
    suspend fun read(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String,
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
        return Snapshot(page, original, replacement, redacted)
    }

    data class Snapshot(
        val page: CollaborationHistoryPage,
        val original: CollaborationHistoryEventFact,
        val replacement: CollaborationReplacementEventFact,
        val redacted: CollaborationHistoryEventFact,
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
