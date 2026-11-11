package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow

internal class SpaceIncomingScanFixture {
    val request = SpaceIncomingRequestFixture()
    val access = request.access
    val originalGeneration = id(701)
    val originalWake = CollaborationSpaceIncomingWake(originalGeneration)
    val boundary =
        CollaborationSpaceRoomBindingBoundary(
            access.audience,
            access.accountId,
            access.spaceId,
            id(702),
            4,
        )
    var nextPage = page()
    val requests = mutableListOf<Pair<CollaborationSpaceRoomBindingCursor?, Int>>()
    val inventory =
        object : CollaborationSpaceRoomBindingInventoryRepository {
            override suspend fun page(
                access: CollaborationSpaceLocalAccess,
                after: CollaborationSpaceRoomBindingCursor?,
                limit: Int,
            ): AppResult<CollaborationSpaceRoomBindingPage> {
                assertEquals(this@SpaceIncomingScanFixture.access, access)
                requests += after to limit
                return request.base.read("inventory") { nextPage }
            }
        }
    var root: CollaborationSpaceRoot? = CollaborationSpaceRoot(access.spaceId)
    var acknowledgements = 0
    var wakeRequests = 0
    var lostWakeReply = false
    val roots =
        object : CollaborationSpaceRootRepository {
            override suspend fun current(
                access: CollaborationSpaceAccountAccess
            ): AppResult<CollaborationSpaceRoot?> = request.base.read("root") { root }

            override fun observe(
                access: CollaborationSpaceAccountAccess
            ): Flow<AppResult<CollaborationSpaceRoot?>> = error("No observation")

            override suspend fun retain(
                access: CollaborationSpaceAccountAccess,
                root: CollaborationSpaceRoot,
            ): AppResult<Boolean> = error("No root replacement")
        }
    val wakes =
        object : CollaborationSpaceIncomingWakeRepository {
            override suspend fun pending(): AppResult<CollaborationSpaceIncomingWake?> =
                request.base.read("wake") { request.wake }

            override suspend fun request(): AppResult<CollaborationSpaceIncomingWake> {
                wakeRequests++
                val next = CollaborationSpaceIncomingWake(id(910 + wakeRequests))
                request.wake = next
                return AppResult.Success(next)
            }

            override suspend fun acknowledge(
                wake: CollaborationSpaceIncomingWake
            ): AppResult<Boolean> {
                acknowledgements++
                val entered = request.base.read("acknowledge") { Unit }
                if (entered is AppResult.Failed) return entered
                val matched = request.wake == wake
                if (matched) request.wake = null
                val committed = request.base.read("wakeCommitted") { Unit }
                if (committed is AppResult.Failed) return committed
                return if (lostWakeReply) AppResult.Failed(request.base.directory.storageFailure)
                else AppResult.Success(matched)
            }
        }
    val schedules =
        object : SyncScheduleRepository {
            override suspend fun request(key: String): AppResult<Unit> {
                assertEquals(CollaborationSpaceIncomingWake.WORK_KEY, key)
                request.schedules++
                val entered = request.base.read("schedule") { Unit }
                return if (entered is AppResult.Failed) entered else request.schedule()
            }
        }
    val requestScan =
        RequestCollaborationSpaceIncomingScan(
            request.demands,
            request.base.directory.directory,
            request.base.native.setup.sessions,
            request.base.native.server,
            schedules,
        )
    val finish =
        FinishCollaborationSpaceIncomingWork(
            request.demands,
            wakes,
            roots,
            request.base.directory.directory,
            request.base.native.setup.sessions,
            request.base.native.server,
        )
    val ensureWake = EnsureCollaborationSpaceIncomingWake(wakes)
    val readWake = ReadCollaborationSpaceIncomingWake(wakes)
    val resume = ResumeCollaborationSpaceIncomingRecovery(wakes, schedules)
    val discover =
        DiscoverCollaborationSpaceIncomingRooms(
            request.demands,
            inventory,
            request.base.directory.directory,
            request.base.native.setup.sessions,
            request.base.native.server,
        )
    val read =
        ReadCollaborationSpaceIncomingDemand(
            request.demands,
            request.base.directory.directory,
            request.base.native.setup.sessions,
            request.base.native.server,
            request.base.native.clock,
            object : CollaborationSpaceSyncRepository {
                override fun incomingSource(
                    runtime: CollaborationSpaceRuntimeReady
                ): CollaborationSpaceIncomingSource? {
                    assertEquals(request.provider.runtime, runtime)
                    sourceReads++
                    return installedSource
                }

                override suspend fun synchronize(
                    runtime: CollaborationSpaceRuntimeReady
                ): AppResult<Unit> = error("No implicit sync")

                override fun observeBatches(
                    runtime: CollaborationSpaceRuntimeReady
                ): Flow<AppResult<CollaborationSpaceSyncBatch>> =
                    error("No implicit batch observation")
            },
        )
    var installedSource: CollaborationSpaceIncomingSource? = null
    var sourceReads = 0
    val defer =
        DeferCollaborationSpaceIncomingRoom(
            request.demands,
            request.base.directory.directory,
            request.base.native.setup.sessions,
            request.base.native.server,
            request.base.native.clock,
        )

    init {
        request.wake = originalWake
        set(CollaborationSpaceIncomingDemand(rescanGeneration = originalGeneration))
    }

    fun id(index: Int) = request.provider.queue.id(index)

    fun after(index: Int) = "b-" + index.toString(16).padStart(64, '0')

    fun binding(index: Int) =
        request.base.model.binding.let { value ->
            value.copy(
                conversation = value.conversation.copy(id = id(1000 + index)),
                room =
                    value.room.copy(
                        origin = CollaborationSpaceRoomOrigin.SPACE,
                        original = null,
                        roomId = "!incoming-$index:example.test",
                    ),
                original = null,
            )
        }

    fun page(count: Int = 4, position: Int? = 4, newer: Boolean = false) =
        CollaborationSpaceRoomBindingPage(
            boundary,
            List(count) { binding(it) },
            position?.let { CollaborationSpaceRoomBindingCursor(boundary, after(it)) },
            newer,
        )

    fun current() = request.snapshot!!.demand

    fun set(value: CollaborationSpaceIncomingDemand) {
        request.snapshot = CollaborationSpaceIncomingDemandSnapshot(id(703), value)
    }
}
