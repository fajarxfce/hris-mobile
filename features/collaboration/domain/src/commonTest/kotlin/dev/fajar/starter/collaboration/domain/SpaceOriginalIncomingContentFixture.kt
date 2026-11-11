package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.mergeCollaborationHistoryText
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AcquireCollaborationSpaceOriginalEvents
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow

internal class SpaceOriginalIncomingContentFixture {
    val base =
        SpaceOriginalProviderHistoryFixture().also {
            it.base.captured =
                it.base.captured.copy(phase = CollaborationSpaceIncomingPhase.Pending)
            it.base.demand(
                requireNotNull(it.base.snapshot).demand.copy(rooms = listOf(it.base.captured))
            )
            it.request = it.request.copy(captured = it.base.captured)
        }
    val local = base.base
    val native = local.native
    val runtime = base.runtime
    val request
        get() = base.request

    val access = base.companyAccess
    val roomId = base.traversal.roomId
    val physical = base.physical

    private suspend fun <T> read(stage: String, value: () -> T): AppResult<T> {
        base.calls += stage
        base.hooks[stage]?.invoke()
        return base.failures[stage]?.let { AppResult.Failed(it) } ?: AppResult.Success(value())
    }

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
                assertEquals(this@SpaceOriginalIncomingContentFixture.runtime, runtime)
                assertEquals(roomId, matrixRoomId)
                return this@SpaceOriginalIncomingContentFixture.read("event:$eventId") {
                    requireNotNull(snapshots[eventId])
                }
            }
        }
    val history =
        object : CollaborationHistoryRepository {
            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ): Flow<AppResult<CollaborationHistoryPage>> = error("No UI observation")

            override suspend fun getByLogicalId(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No receipt recovery")

            override suspend fun getByEvent(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> {
                scope(access, conversationId, roomId)
                return read("local:$eventId") { originals[eventId] }
            }

            override suspend fun getEventFact(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> {
                scope(access, conversationId, roomId)
                return read("alias:$eventId") { aliases[eventId] }
            }

            override suspend fun merge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> {
                scope(access, conversationId, roomId)
                originalWrites += changes.toList()
                val entered = read("merge") { Unit }
                if (entered is AppResult.Failed) return entered
                return originalResponse
            }
        }
    val edits =
        object : CollaborationMessageEditRepository {
            override suspend fun getReplacementFact(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationReplacementEventFact?> =
                error("No cursor or receipt inference")

            override suspend fun mergeEdits(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                edits: List<CollaborationMessageEdit>,
            ): AppResult<Boolean> {
                scope(access, conversationId, roomId)
                editWrites += edits.toList()
                val entered = read("mergeEdits") { Unit }
                if (entered is AppResult.Failed) return entered
                return editResponse
            }
        }
    val acquire =
        AcquireCollaborationSpaceOriginalEvents(
            base.demands,
            base.metadata,
            base.registrations,
            local.sync,
            base.runtimes,
            events,
            history,
            edits,
            local.directory.directory,
            native.setup.sessions,
            native.server,
            local.setup.build,
            native.clock,
            native.time,
        )

    fun original(id: String = "\$original") =
        CollaborationHistoryChange.Text(
            id,
            local.source.matrixUserId,
            local.id(101),
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
            local.source.matrixUserId,
            200,
            "m.room.message",
            CollaborationAcquiredEventContent.Replacement(
                CollaborationMessageEdit(
                    id,
                    local.source.matrixUserId,
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
                    base.traversal.deviceId,
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

    private fun scope(access: CollaborationLocalAccess, conversationId: String, roomId: String) {
        assertEquals(this.access, access)
        assertEquals(local.position.conversationId, conversationId)
        assertEquals(this.roomId, roomId)
    }
}
