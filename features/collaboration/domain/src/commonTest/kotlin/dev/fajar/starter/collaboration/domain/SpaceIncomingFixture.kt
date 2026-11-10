package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.mergeCollaborationHistoryText
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AcquireCollaborationSpaceEvents
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow

internal class SpaceIncomingFixture {
    val base = SpaceOriginalDeliveryFixture()
    val native = base.native
    val queue = base.queue
    val runtime = queue.runtime
    val conversation = base.model.conversation
    val access = runtime.owner.localDirectoryAccess()
    val roomId = base.model.room.roomId
    val physical =
        CollaborationOwner(
            runtime.owner.parent.sessionId,
            queue.binding.audience,
            queue.binding.accountId,
            queue.binding.companyId,
            queue.binding.nativeSessionId,
        )
    val snapshots = mutableMapOf<String, CollaborationSpaceEventSnapshot>()
    val originals = mutableMapOf<String, CollaborationHistoryMessage>()
    val aliases = mutableMapOf<String, CollaborationHistoryEventFact>()
    val originalWrites = mutableListOf<List<CollaborationHistoryChange>>()
    val editWrites = mutableListOf<List<CollaborationMessageEdit>>()
    var originalResponse: AppResult<Boolean> = AppResult.Success(true)
    var editResponse: AppResult<Boolean> = AppResult.Success(true)
    val events =
        object : CollaborationSpaceEventRepository {
            override suspend fun findHead(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
            ): AppResult<CollaborationSpaceEventHead> =
                error("Caller supplies owned input, not a head scan")

            override suspend fun read(
                runtime: CollaborationSpaceRuntimeReady,
                matrixRoomId: String,
                eventId: String,
            ): AppResult<CollaborationSpaceEventSnapshot> {
                assertEquals(this@SpaceIncomingFixture.runtime, runtime)
                assertEquals(roomId, matrixRoomId)
                return base.read("event:$eventId") { requireNotNull(snapshots[eventId]) }
            }
        }
    val history =
        object : CollaborationSpaceHistoryRepository {
            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ): Flow<AppResult<CollaborationHistoryPage>> = error("No UI observation")

            override suspend fun getByLogicalId(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No receipt recovery")

            override suspend fun getByEvent(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> {
                scope(access, conversationId, roomId)
                return base.read("local:$eventId") { originals[eventId] }
            }

            override suspend fun getEventFact(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> {
                scope(access, conversationId, roomId)
                return base.read("alias:$eventId") { aliases[eventId] }
            }

            override suspend fun merge(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> {
                scope(access, conversationId, roomId)
                originalWrites += changes.toList()
                val entered = base.read("merge") { Unit }
                if (entered is AppResult.Failed) return entered
                return originalResponse
            }
        }
    val edits =
        object : CollaborationSpaceMessageEditRepository {
            override suspend fun getReplacementFact(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationReplacementEventFact?> =
                error("No cursor or receipt inference")

            override suspend fun mergeEdits(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                edits: List<CollaborationMessageEdit>,
            ): AppResult<Boolean> {
                scope(access, conversationId, roomId)
                editWrites += edits.toList()
                val entered = base.read("mergeEdits") { Unit }
                if (entered is AppResult.Failed) return entered
                return editResponse
            }
        }
    val acquire =
        AcquireCollaborationSpaceEvents(
            base.bindings,
            base.transport,
            base.runtimes,
            events,
            history,
            edits,
            base.directory.directory,
            native.setup.sessions,
            native.server,
            AppBuild(AppPlatform.Android, 5, "test", null),
            native.clock,
            native.time,
        )

    fun original(id: String = "\$original") =
        CollaborationHistoryChange.Text(
            id,
            queue.identity.matrixUserId,
            queue.id(101),
            100,
            "Fictional original",
            "a".repeat(64),
        )

    fun source(value: CollaborationHistoryChange.Text = original()) =
        CollaborationAcquiredEvent(
            value.eventId,
            value.sender,
            value.timestamp,
            "m.room.message",
            CollaborationAcquiredEventContent.Original(value),
        )

    fun replacement(id: String = "\$edit", target: String = "\$original") =
        CollaborationAcquiredEvent(
            id,
            queue.identity.matrixUserId,
            200,
            "m.room.message",
            CollaborationAcquiredEventContent.Replacement(
                CollaborationMessageEdit(
                    id,
                    queue.identity.matrixUserId,
                    target,
                    200,
                    "Fictional revision",
                    "b".repeat(64),
                )
            ),
        )

    fun put(event: CollaborationAcquiredEvent) {
        snapshots[event.eventId] =
            CollaborationSpaceEventSnapshot(
                runtime,
                CollaborationEventSnapshot(
                    physical,
                    queue.device.deviceId,
                    roomId,
                    true,
                    true,
                    event,
                ),
            )
    }

    fun local(value: CollaborationHistoryChange.Text = original()) {
        originals[value.eventId] = requireNotNull(mergeCollaborationHistoryText(null, value))
        aliases[value.eventId] =
            CollaborationHistoryEventFact(value.eventId, value.sender, value.timestamp, false)
    }

    private fun scope(
        access: CollaborationSpaceLocalAccess,
        conversationId: String,
        roomId: String,
    ) {
        assertEquals(this.access, access)
        assertEquals(conversation.id, conversationId)
        assertEquals(this.roomId, roomId)
    }
}
