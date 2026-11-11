package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationSpaceIncomingRoom
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceIncomingRequestFixture {
    val provider = SpaceProviderHistoryFixture()
    val base = provider.base
    val access = provider.access
    val key = provider.traversal
    val request =
        CollaborationSpaceIncomingDemandChange.Request(
            key.conversationId,
            provider.queue.id(700),
            "\$new",
        )
    var snapshot: CollaborationSpaceIncomingDemandSnapshot? = null
    var wake: CollaborationSpaceIncomingWake? = null
    var source = CollaborationHistorySource(null, null)
    var pending = CollaborationHistoryReferences(null, emptyList(), null)
    var commit = true
    var responseLoss = false
    var writes = 0
    var schedules = 0
    var reads = 0
    var expectedAtWrite: CollaborationSpaceIncomingDemandSnapshot? = null
    var schedule: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    val demands =
        object : CollaborationSpaceIncomingDemandRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(this@SpaceIncomingRequestFixture.access, access)
                reads++
                return base.read(if (reads == 1) "demand" else "readback") { snapshot }
            }

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceIncomingDemandSnapshot?,
                demand: CollaborationSpaceIncomingDemand,
                wake: CollaborationSpaceIncomingWake?,
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(this@SpaceIncomingRequestFixture.access, access)
                writes++
                expectedAtWrite = expected
                val entered = base.read("write") { Unit }
                if (entered is AppResult.Failed) return entered
                if (expected != snapshot || !commit) return AppResult.Success(null)
                snapshot = CollaborationSpaceIncomingDemandSnapshot(provider.queue.id(800), demand)
                if (wake != null) this@SpaceIncomingRequestFixture.wake = wake
                val after = base.read("committed") { Unit }
                if (after is AppResult.Failed) return after
                return if (responseLoss)
                    AppResult.Failed(Failure(FailureKind.Storage, "Fictional lost response"))
                else AppResult.Success(snapshot)
            }
        }
    val history =
        object : CollaborationSpaceHistoryTraversalRepository by provider.history {
            override suspend fun source(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                eventId: String,
            ): AppResult<CollaborationHistorySource> {
                assertEquals(this@SpaceIncomingRequestFixture.access, access)
                assertEquals(key, traversal)
                return base.read("source") { source }
            }

            override fun observeReferences(
                access: CollaborationSpaceLocalAccess,
                traversal: CollaborationHistoryTraversal,
                after: String?,
                limit: Int,
            ): Flow<AppResult<CollaborationHistoryReferences>> {
                assertEquals(this@SpaceIncomingRequestFixture.access, access)
                assertEquals(key, traversal)
                assertEquals(null, after)
                assertEquals(1, limit)
                return flow { emit(base.read("pending") { pending }) }
            }
        }
    val useCase =
        QueueCollaborationSpaceIncomingRoom(
            demands,
            base.bindings,
            history,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            object : SyncScheduleRepository {
                override suspend fun request(key: String): AppResult<Unit> {
                    assertEquals(CollaborationSpaceIncomingWake.WORK_KEY, key)
                    schedules++
                    val entered = base.read("schedule") { Unit }
                    return if (entered is AppResult.Failed) entered else schedule()
                }
            },
        )

    suspend fun queue(value: CollaborationSpaceIncomingDemandChange.Request = request) =
        useCase(access, key, value)

    fun retainIndependentProducerWork() {
        val current = snapshot!!.demand
        val owner = CollaborationSpaceIncomingSource("1".repeat(64), "@producer:test", "DEVICE")
        snapshot =
            CollaborationSpaceIncomingDemandSnapshot(
                provider.queue.id(850),
                current.copy(
                    nextPosition = current.nextPosition + 1,
                    sources =
                        listOf(
                            CollaborationSpaceIncomingSourceProgress(
                                owner,
                                "later",
                                provider.queue.id(851),
                            )
                        ),
                    physicalRooms =
                        current.physicalRooms +
                            CollaborationSpaceIncomingPhysicalRoomDemand(
                                owner.storeId,
                                "!producer:test",
                                provider.queue.id(852),
                                current.nextPosition,
                                "\$producer",
                                CollaborationSpaceIncomingMembership.Joined,
                                true,
                            ),
                ),
            )
    }

    fun covered(exhausted: Boolean = true, unresolved: Boolean = false) {
        val progress = provider.checkpoint(if (exhausted) null else "next")
        val reference =
            CollaborationHistoryReference(
                0,
                provider.page.physical.events.first().copy(eventId = request.headEventId!!),
                !unresolved,
            )
        source = CollaborationHistorySource(progress, reference)
        pending =
            CollaborationHistoryReferences(
                progress,
                if (unresolved) listOf(reference.copy(resolved = false)) else emptyList(),
                null,
            )
    }

    fun queued(head: String? = request.headEventId) {
        snapshot =
            CollaborationSpaceIncomingDemandSnapshot(
                provider.queue.id(801),
                CollaborationSpaceIncomingDemand(
                    1,
                    listOf(
                        CollaborationSpaceIncomingRoomDemand(
                            key.conversationId,
                            request.generation,
                            0,
                            head,
                            CollaborationSpaceIncomingPhase.Pending,
                        )
                    ),
                ),
            )
        wake = CollaborationSpaceIncomingWake(request.generation)
    }
}
