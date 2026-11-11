package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.RetainCollaborationSpaceRoomInventory
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals

internal class SpaceRoomInventoryRetentionFixture {
    val base = SpaceOriginalIncomingLoadFixture()
    val runtime = base.runtime
    var captured = CollaborationSpaceIncomingSourceProgress(base.source, "before", base.id(700))
    var snapshot: CollaborationSpaceIncomingDemandSnapshot? =
        CollaborationSpaceIncomingDemandSnapshot(
            base.id(701),
            CollaborationSpaceIncomingDemand(sources = listOf(captured)),
        )
    var page =
        CollaborationSpaceRoomInventoryPage(
            runtime,
            base.source,
            null,
            listOf(
                CollaborationSpaceInventoryRoom(
                    "!room:test",
                    CollaborationSpaceInventoryMembership.Joined,
                )
            ),
            null,
        )
    var wake: CollaborationSpaceIncomingWake? = null
    var commit = true
    var replyLost = false
    var writes = 0
    var reads = 0
    var enumerations = 0
    val calls = mutableListOf<String>()
    val hooks = mutableMapOf<String, suspend () -> Unit>()
    val failures = mutableMapOf<String, Failure>()
    val storageFailure = Failure(FailureKind.Storage, "fixture storage interruption")

    suspend fun stage(name: String) {
        calls += name
        hooks[name]?.invoke()
    }

    val inventories =
        object : CollaborationSpaceRoomInventoryRepository {
            override suspend fun readInventory(
                runtime: CollaborationSpaceRuntimeReady,
                source: CollaborationSpaceIncomingSource,
                afterRoomId: String?,
                limit: Int,
            ): AppResult<CollaborationSpaceRoomInventoryPage> {
                assertEquals(this@SpaceRoomInventoryRetentionFixture.runtime, runtime)
                assertEquals(base.source, source)
                assertEquals(captured.inventoryAfterRoomId, afterRoomId)
                assertEquals(16, limit)
                enumerations++
                stage("inventory")
                return failures["inventory"]?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(page)
            }
        }

    val demands =
        object : CollaborationSpaceIncomingDemandRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(base.access, access)
                reads++
                val name = "demand$reads"
                stage(name)
                return failures[name]?.let { AppResult.Failed(it) } ?: AppResult.Success(snapshot)
            }

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceIncomingDemandSnapshot?,
                demand: CollaborationSpaceIncomingDemand,
                wake: CollaborationSpaceIncomingWake?,
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(base.access, access)
                writes++
                stage("write")
                failures["write"]?.let {
                    return AppResult.Failed(it)
                }
                val saved =
                    if (commit && expected == snapshot)
                        CollaborationSpaceIncomingDemandSnapshot(base.id(900 + writes), demand)
                    else null
                if (saved != null) {
                    snapshot = saved
                    if (wake != null) this@SpaceRoomInventoryRetentionFixture.wake = wake
                    stage("committed")
                }
                return if (replyLost) AppResult.Failed(storageFailure) else AppResult.Success(saved)
            }
        }

    val retain =
        RetainCollaborationSpaceRoomInventory(
            inventories,
            demands,
            base.registrations,
            base.sync,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            base.setup.build,
            base.native.clock,
        )

    suspend fun run() = retain(runtime, captured)

    fun progress(value: CollaborationSpaceIncomingSourceProgress) {
        snapshot =
            requireNotNull(snapshot)
                .copy(demand = requireNotNull(snapshot).demand.copy(sources = listOf(value)))
    }
}
