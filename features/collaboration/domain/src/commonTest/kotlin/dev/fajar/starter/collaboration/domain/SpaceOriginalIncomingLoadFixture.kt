package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.LoadCollaborationSpaceOriginalIncomingRoom
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

internal class SpaceOriginalIncomingLoadFixture {
    val setup = SpaceRegistrationFixture()
    val directory = setup.base
    val native = setup.native
    val owner = setup.owner
    val access = setup.access
    val runtime = CollaborationSpaceRuntimeReady(owner, setup.receipt.id, id(1))
    val source =
        CollaborationSpaceIncomingSource(
            "a".repeat(64),
            setup.sdkIdentity.matrixUserId,
            setup.sdkIdentity.matrixDeviceId,
        )
    var installed: CollaborationSpaceIncomingSource? = source
    var sourceReads = 0
    val position = CollaborationOriginalConversationPosition(setup.company, id(2))
    var captured =
        CollaborationSpaceIncomingRoomDemand(
            CollaborationSpaceIncomingTarget.Original(position),
            id(3),
            0,
            "\$head",
            CollaborationSpaceIncomingPhase.Pending,
            sourceId = source.storeId,
        )
    var snapshot: CollaborationSpaceIncomingDemandSnapshot? =
        CollaborationSpaceIncomingDemandSnapshot(
            id(4),
            CollaborationSpaceIncomingDemand(
                nextPosition = 1,
                rooms = listOf(captured),
                sources = listOf(CollaborationSpaceIncomingSourceProgress(source, "position-1")),
            ),
        )
    val command =
        CollaborationSpaceRegistrationCommand(
            id(5),
            CollaborationSpaceDeviceBinding(
                access.audience,
                access.accountId,
                access.spaceId,
                owner.parent.nativeSessionId,
            ),
            CollaborationNativePlatform.ANDROID,
            5,
            native.setup.now,
            setup.provider,
            "Fictional Android",
            CollaborationSpaceDeviceOrigin.LEGACY,
            CollaborationSpaceLegacySdk(
                setup.sdkOwner.setupBinding(),
                setup.sdkDevice,
                setup.sdkIdentity,
            ),
        )
    var registration: CollaborationSpaceRegistrationRecord? =
        CollaborationSpaceRegistrationRecord(
            command,
            2,
            1,
            CollaborationSpaceRegistrationOutcome.Registered(setup.receipt),
        )
    val original =
        CollaborationOriginalConversation(
            position,
            access.accountId,
            native.contact.accountId,
            access.accountId,
            Instant.parse("2026-10-01T10:00:00.123456Z"),
            0,
        )
    val room =
        CollaborationOriginalRoom(
            setup.provider.serverName,
            "!original:matrix.hris.internal",
            Instant.parse("2026-10-01T10:00:01.654321Z"),
            native.contact.accountId,
        )
    var retained: CollaborationSpaceOriginalRoomMetadata? =
        CollaborationSpaceOriginalRoomMetadata(original, room)
    val calls = mutableListOf<String>()
    val hooks = mutableMapOf<String, suspend () -> Unit>()
    val failures = mutableMapOf<String, Failure>()
    private var registrationReads = 0

    private suspend fun <T> read(stage: String, value: () -> T): AppResult<T> {
        calls += stage
        hooks[stage]?.invoke()
        return failures[stage]?.let { AppResult.Failed(it) } ?: AppResult.Success(value())
    }

    val demands =
        object : CollaborationSpaceIncomingDemandRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                assertEquals(this@SpaceOriginalIncomingLoadFixture.access, access)
                return read("demand") { snapshot }
            }

            override suspend fun replace(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceIncomingDemandSnapshot?,
                demand: CollaborationSpaceIncomingDemand,
                wake: CollaborationSpaceIncomingWake?,
            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> =
                error("Local resolution must not write or schedule")
        }
    val registrations =
        object : CollaborationSpaceRegistrationCommandRepository by setup.commands {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                nativeSessionId: String,
            ): AppResult<CollaborationSpaceRegistrationRecord?> {
                assertEquals(this@SpaceOriginalIncomingLoadFixture.access, access)
                assertEquals(owner.parent.nativeSessionId, nativeSessionId)
                registrationReads++
                return read("registration$registrationReads") { registration }
            }
        }
    val metadata =
        object : CollaborationSpaceOriginalRoomMetadataRepository {
            override suspend fun getByConversation(
                access: CollaborationSpaceLocalAccess,
                position: CollaborationOriginalConversationPosition,
            ): AppResult<CollaborationSpaceOriginalRoomMetadata?> {
                assertEquals(this@SpaceOriginalIncomingLoadFixture.access, access)
                assertEquals(this@SpaceOriginalIncomingLoadFixture.position, position)
                return read("metadata") { retained }
            }

            override suspend fun findByRoom(
                access: CollaborationSpaceLocalAccess,
                companyId: String,
                serverName: String,
                roomId: String,
            ): AppResult<CollaborationSpaceOriginalRoomMetadata?> =
                error("Captured original position is already known")

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                metadata: CollaborationSpaceOriginalRoomMetadata,
            ): AppResult<Boolean> =
                error("Local resolution cannot acquire or replace historical facts")
        }
    val sync =
        object : CollaborationSpaceSyncRepository {
            override suspend fun synchronize(
                runtime: CollaborationSpaceRuntimeReady
            ): AppResult<Unit> = error("No sync startup")

            override fun incomingSource(
                runtime: CollaborationSpaceRuntimeReady
            ): CollaborationSpaceIncomingSource? {
                assertEquals(this@SpaceOriginalIncomingLoadFixture.runtime, runtime)
                sourceReads++
                return installed
            }

            override fun observeBatches(
                runtime: CollaborationSpaceRuntimeReady
            ): Flow<AppResult<CollaborationSpaceSyncBatch>> = error("No new collector")
        }

    fun loader(platform: AppPlatform = AppPlatform.Android) =
        LoadCollaborationSpaceOriginalIncomingRoom(
            demands,
            metadata,
            registrations,
            setup.build.copy(platform = platform),
            directory.directory,
            native.setup.sessions,
            native.server,
            sync,
        )

    suspend fun load() = loader()(runtime, captured)

    fun demand(value: CollaborationSpaceIncomingDemand) {
        snapshot = requireNotNull(snapshot).copy(demand = value)
    }

    fun id(value: Int) = "60000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
}
