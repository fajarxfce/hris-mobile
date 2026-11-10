package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.mergeCollaborationHistoryText
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow

/** Two distinct history ports; unexpected acquisition or publication fails the owning test. */
internal class SpaceOriginalReceiptHistoryFixture(private val f: SpaceOriginalDeliveryFixture) {
    private val q = f.queue
    val physical =
        CollaborationOwner(
            q.owner.parent.sessionId,
            q.binding.audience,
            q.binding.accountId,
            q.binding.companyId,
            q.binding.nativeSessionId,
        )
    var snapshot =
        CollaborationSpaceOriginalHistorySnapshot(
            q.runtime,
            CollaborationHistoryHead(physical, q.device.deviceId, q.device.roomId, null),
            null,
        )
    val local = mutableMapOf<String, CollaborationHistoryMessage>()
    val provider =
        object : CollaborationSpaceOriginalHistoryRepository {
            override suspend fun read(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
            ): AppResult<CollaborationSpaceOriginalHistorySnapshot> {
                assertEquals(q.runtime, runtime)
                assertEquals(q.device.roomId, matrixRoomId)
                return f.read("originalHistory") { snapshot }
            }
        }
    val stored =
        object : CollaborationHistoryRepository {
            override suspend fun getByLogicalId(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> {
                assertEquals(f.access, access)
                assertEquals(q.position.conversationId, conversationId)
                assertEquals(q.device.roomId, roomId)
                assertEquals(q.origin.sender, sender)
                return f.read("localHistory") { local[logicalId] }
            }

            override suspend fun getEventFact(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> = error("No alias scan")

            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ): Flow<AppResult<CollaborationHistoryPage>> = error("No history stream")

            override suspend fun getByEvent(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("Missing ACK has no event ID")

            override suspend fun merge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> = error("Recovery does not advance history")
        }

    fun event(record: CollaborationMessageRecord = q.record, id: String = "\$accepted") =
        CollaborationHistoryChange.Text(
            id,
            q.origin.sender,
            record.intent.id,
            1,
            record.intent.text,
            q.origin.contentHash,
        )

    fun publish(events: List<CollaborationHistoryChange> = listOf(event())) {
        val anchor = events.last().eventId
        snapshot =
            snapshot.copy(
                head = snapshot.head.copy(eventId = anchor),
                window =
                    CollaborationOriginalWindow(
                        physical,
                        q.device.deviceId,
                        q.device.roomId,
                        anchor,
                        events,
                        emptyList(),
                    ),
            )
    }

    fun retain() {
        local[q.record.intent.id] = requireNotNull(mergeCollaborationHistoryText(null, event()))
    }

    fun loseSdkOriginals() {
        f.snapshot = f.snapshot.copy(original = f.snapshot.original.copy(messages = emptyList()))
    }
}
