package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceIncomingDemand
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.DemoteCollaborationSpaceIncomingRoom
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.flow

internal class SpaceKeyDemotionFixture {
    val reader = SpaceKeyReferenceReadFixture()
    val base = reader.base
    val runtime = base.runtime
    val access = base.access
    val physical = assertNotNull(assertNotNull(base.registration).command.legacy)
    val calls = reader.calls
    val hooks = reader.hooks
    val failures = reader.failures
    var room: CollaborationSpaceIncomingRoom
    var snapshot: CollaborationSpaceIncomingDemandSnapshot?
    val sources = mutableMapOf<String, CollaborationHistoryReference>()
    val sourceResponses = mutableMapOf<String, CollaborationHistorySource>()
    var writes = 0
    var commit = true
    var lostReply = false
    var fabricatedReply = false
    var requested: CollaborationSpaceIncomingWake? = null
    var wake = CollaborationSpaceIncomingWake(base.id(970))

    init {
        reader.keyHistory =
            reader.keyHistory.copy(
                traversal = reader.keyHistory.traversal.copy(deviceId = runtime.deviceId)
            )
        val metadata = assertNotNull(reader.binding).conversation
        val conversation =
            CollaborationSpaceConversation(
                runtime.owner,
                metadata.id,
                metadata.firstAccountId,
                metadata.secondAccountId,
                metadata.createdBy,
                metadata.createdAt,
                metadata.version,
            )
        val captured =
            base.captured.copy(
                target = CollaborationSpaceIncomingTarget.Published(metadata.id),
                headEventId = reader.events.first().event.eventId,
                phase = CollaborationSpaceIncomingPhase.Pending,
            )
        room = CollaborationSpaceIncomingRoom(captured, conversation, reader.keyHistory.traversal)
        snapshot =
            CollaborationSpaceIncomingDemandSnapshot(
                base.id(4),
                CollaborationSpaceIncomingDemand(
                    nextPosition = 1,
                    rooms = listOf(captured),
                    sources =
                        listOf(CollaborationSpaceIncomingSourceProgress(base.source, "position-1")),
                ),
            )
        assertTrue(validCollaborationSpaceIncomingDemand(access, assertNotNull(snapshot).demand))
        val progress = assertNotNull(reader.progress)
        reader.progress =
            progress.copy(
                next = null,
                incoming =
                    assertNotNull(progress.incoming)
                        .copy(
                            pendingAfter = null,
                            pendingThrough = null,
                            pendingRetryRequired = false,
                        ),
                provider =
                    CollaborationHistoryProviderIdentity(
                        physical.binding.companyId,
                        physical.deviceId,
                    ),
            )
        reader.events = listOf(reader.events.first())
        sources[reader.events.single().event.eventId] = reader.events.single()
    }

    private suspend fun <T> read(stage: String, value: () -> T): AppResult<T> {
        reader.touch(stage)
        return failures[stage]?.let { AppResult.Failed(it) } ?: AppResult.Success(value())
    }

    val demands =
        object : CollaborationSpaceIncomingDemandRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(this@SpaceKeyDemotionFixture.access, access)
                return read("demand") { snapshot }
            }

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceIncomingDemandSnapshot?,
                demand: CollaborationSpaceIncomingDemand,
                wake: CollaborationSpaceIncomingWake?,
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(this@SpaceKeyDemotionFixture.access, access)
                assertNotNull(wake)
                writes++
                requested = wake
                reader.touch("write")
                if (snapshot != expected) return AppResult.Success(null)
                val next = CollaborationSpaceIncomingDemandSnapshot(base.id(980 + writes), demand)
                if (commit) {
                    snapshot = next
                    this@SpaceKeyDemotionFixture.wake = wake
                }
                reader.touch("written")
                if (lostReply) return AppResult.Failed(base.directory.storageFailure)
                return AppResult.Success(if (commit || fabricatedReply) next else null)
            }
        }
    val history =
        object : CollaborationSpaceHistoryTraversalRepository by reader.traversal {
            override fun observeReferences(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ) =
                flow<AppResult<CollaborationHistoryReferences>> {
                    reader.scope(access, traversal)
                    assertNull(after)
                    assertEquals(1, limit)
                    reader.requests += after to limit
                    reader.touch("pending")
                    if (!reader.emptyFlow)
                        emit(
                            failures["pending"]?.let { AppResult.Failed(it) }
                                ?: AppResult.Success(
                                    reader.page
                                        ?: CollaborationHistoryReferences(
                                            reader.progress,
                                            reader.events,
                                            reader.after,
                                        )
                                )
                        )
                }

            override suspend fun source(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                eventId: String,
            ): AppResult<CollaborationHistorySource> {
                reader.scope(access, traversal)
                return read("source:$eventId") {
                    sourceResponses[eventId]
                        ?: CollaborationHistorySource(reader.progress, sources[eventId])
                }
            }
        }
    val demote =
        DemoteCollaborationSpaceIncomingRoom(
            demands,
            reader.bindings,
            base.registrations,
            history,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.sync,
        )

    suspend fun run() = demote(runtime, room)

    fun demand(value: CollaborationSpaceIncomingDemand) {
        snapshot = assertNotNull(snapshot).copy(demand = value)
    }

    fun retainedTask() =
        assertNotNull(snapshot).demand.rooms.singleOrNull { it.target == room.captured.target }

    fun recovery() = assertNotNull(snapshot).demand.historyRecovery
}
