package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ResolveCollaborationSpaceIncomingBinding
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.Flow

internal class SpaceIncomingPhysicalResolutionFixture {
    val request = SpaceIncomingRequestFixture()
    val base = request.base
    val runtime = request.provider.runtime
    val source =
        CollaborationSpaceIncomingSource(
            "1".repeat(64),
            base.queue.identity.matrixUserId,
            base.model.transport.matrixDeviceId,
        )
    var installed: CollaborationSpaceIncomingSource? = source
    val physical =
        CollaborationSpaceIncomingPhysicalRoomDemand(
            source.storeId,
            base.model.room.roomId,
            id(701),
            0,
            "\$new",
            CollaborationSpaceIncomingMembership.Joined,
            true,
        )
    val registered =
        CollaborationSpaceRegistrationRecord(
            CollaborationSpaceRegistrationCommand(
                id(702),
                CollaborationSpaceDeviceBinding(
                    runtime.owner.parent.audience,
                    runtime.owner.parent.accountId,
                    runtime.owner.spaceId,
                    runtime.owner.parent.nativeSessionId,
                ),
                CollaborationNativePlatform.ANDROID,
                5,
                base.native.setup.now,
                base.queue.identity,
                "Fixture device",
                CollaborationSpaceDeviceOrigin.SPACE,
                null,
            ),
            2,
            1,
            CollaborationSpaceRegistrationOutcome.Registered(
                CollaborationSetupReceipt(runtime.deviceId, 0)
            ),
        )
    var registration: CollaborationSpaceRegistrationRecord? = registered
    private val registrations =
        object :
            CollaborationSpaceRegistrationCommandRepository by SpaceRegistrationFixture().commands {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                nativeSessionId: String,
            ): AppResult<CollaborationSpaceRegistrationRecord?> {
                assertEquals(request.access, access)
                assertEquals(runtime.owner.parent.nativeSessionId, nativeSessionId)
                return base.read("registration") { registration }
            }
        }
    private val bindings =
        object : CollaborationSpaceRoomBindingRepository by base.bindings {
            override suspend fun findByRoom(
                access: CollaborationSpaceLocalAccess,
                serverName: String,
                roomId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> {
                assertEquals(request.access, access)
                assertEquals(registered.command.provider.serverName, serverName)
                assertEquals(physical.roomId, roomId)
                return base.read("binding") { base.retained }
            }
        }
    private val sync =
        object : CollaborationSpaceSyncRepository {
            override fun incomingSource(
                runtime: CollaborationSpaceRuntimeReady
            ): CollaborationSpaceIncomingSource? {
                assertEquals(this@SpaceIncomingPhysicalResolutionFixture.runtime, runtime)
                return installed
            }

            override suspend fun synchronize(
                runtime: CollaborationSpaceRuntimeReady
            ): AppResult<Unit> = error("No sync acquisition")

            override fun observeBatches(
                runtime: CollaborationSpaceRuntimeReady
            ): Flow<AppResult<CollaborationSpaceSyncBatch>> = error("No raw subscription")
        }
    val useCase =
        ResolveCollaborationSpaceIncomingBinding(
            request.demands,
            bindings,
            registrations,
            sync,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            AppBuild(AppPlatform.Android, 5, "test", null),
        )

    init {
        assertTrue(validCollaborationSpaceRegistrationRecord(request.access, registered))
        assertTrue(validCollaborationSpaceRoomBinding(request.access, assertNotNull(base.retained)))
        request.snapshot =
            CollaborationSpaceIncomingDemandSnapshot(
                id(703),
                CollaborationSpaceIncomingDemand(
                    1,
                    sources =
                        listOf(
                            CollaborationSpaceIncomingSourceProgress(source, "position", id(704))
                        ),
                    physicalRooms = listOf(physical),
                    rescanGeneration = id(705),
                ),
            )
        request.wake = CollaborationSpaceIncomingWake(id(706))
    }

    suspend fun resolve(captured: CollaborationSpaceIncomingPhysicalRoomDemand = physical) =
        useCase(runtime, captured)

    fun id(value: Int) = base.queue.id(value)
}
