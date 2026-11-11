package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.DeferCollaborationSpaceIncomingPhysicalRoom
import dev.fajar.starter.collaboration.domain.usecases.ResolveCollaborationSpaceIncomingBinding
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow

internal class SpaceIncomingPhysicalResolutionFixture(
    timeSource: TimeSource = TimeSource.Monotonic
) {
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
                CollaborationSpaceDeviceOrigin.LEGACY,
                CollaborationSpaceLegacySdk(
                    base.queue.binding.copy(nativeSessionId = runtime.owner.parent.nativeSessionId),
                    base.queue.device.deviceId,
                    CollaborationLocalDeviceIdentity(
                        source.matrixUserId,
                        source.matrixDeviceId,
                        "a".repeat(64),
                    ),
                ),
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
    val missing =
        Failure(
            FailureKind.Unexpected,
            "Fictional missing publication",
            code = "collaboration_conversation_not_found",
        )
    var physicalFailure: Failure? = missing
    var physicalView =
        CollaborationSpacePhysicalRoomView(
            runtime.owner,
            base.model.conversation.id,
            base.model.conversation.let { conversation ->
                val at = conversation.createdAt
                CollaborationSpaceRoomStatus(
                    CollaborationSpaceRoomAttempt(conversation.createdBy, 0),
                    CollaborationProvisioningState.SUCCEEDED,
                    1,
                    at,
                    at,
                    at + 5.minutes,
                    at,
                    null,
                    CollaborationSpaceRoomOutcome(
                        0,
                        CollaborationProvisioningState.SUCCEEDED,
                        at,
                        null,
                    ),
                )
            },
            base.model.room,
        )
    var remoteConversation = base.model.conversation
    var remoteOriginal = base.model.queue.conversation
    val originalPosition = base.queue.position
    var originalCanonical: CollaborationSpaceOriginalRoomMetadata? = null
    var originalIndex: CollaborationSpaceOriginalRoomMetadata? = null
    var originalPhysicalFailure: Failure? = missing
    var originalMetadataCommit = true
    var originalMetadataReply = true
    var loseOriginalMetadataReply = false
    val originalMetadataWrites = mutableListOf<CollaborationSpaceOriginalRoomMetadata>()
    var canonical: CollaborationSpaceConversationMetadata? = null
    var metadataReply = true
    var bindingReply = true
    var loseMetadataReply = false
    var loseBindingReply = false
    val metadataWrites = mutableListOf<CollaborationSpaceConversation>()
    val bindingWrites = mutableListOf<CollaborationSpaceRoomBinding>()
    val remoteReads = mutableListOf<String>()
    val originalReads = mutableListOf<CollaborationOriginalConversationPosition>()
    val expectedBinding
        get() =
            CollaborationSpaceRoomBinding(
                remoteConversation.localMetadata(),
                physicalView.room,
                if (physicalView.room.origin == CollaborationSpaceRoomOrigin.LEGACY)
                    CollaborationSpaceOriginalRoomMetadata(
                        remoteOriginal.conversation,
                        requireNotNull(remoteOriginal.room),
                    )
                else null,
            )

    fun missingLocal(legacy: Boolean = true) {
        base.retained = null
        physicalFailure = null
        if (!legacy)
            physicalView =
                physicalView.copy(
                    room =
                        physicalView.room.copy(
                            origin = CollaborationSpaceRoomOrigin.SPACE,
                            original = null,
                        )
                )
    }

    private val physicalRooms =
        object : CollaborationSpacePhysicalRoomRepository {
            override suspend fun published(
                owner: CollaborationSpaceOwner,
                serverName: String,
                roomId: String,
            ): AppResult<CollaborationSpacePhysicalRoomView> {
                assertEquals(runtime.owner, owner)
                assertEquals(registered.command.provider.serverName, serverName)
                assertEquals(physical.roomId, roomId)
                remoteReads += "physical"
                val read = base.read("remote-physical") { physicalView }
                return if (read is AppResult.Failed) read
                else physicalFailure?.let { AppResult.Failed(it) } ?: read
            }

            override suspend fun original(
                owner: CollaborationSpaceOwner,
                companyId: String,
                serverName: String,
                roomId: String,
            ): AppResult<CollaborationSpaceOriginalConversation> {
                assertEquals(runtime.owner, owner)
                assertEquals(originalPosition.companyId, companyId)
                assertEquals(registered.command.provider.serverName, serverName)
                assertEquals(physical.roomId, roomId)
                remoteReads += "original-physical"
                val read = base.read("remote-original-physical") { remoteOriginal }
                return if (read is AppResult.Failed) read
                else originalPhysicalFailure?.let { AppResult.Failed(it) } ?: read
            }
        }
    private val conversations =
        object : CollaborationSpaceConversationRepository {
            override suspend fun get(
                owner: CollaborationSpaceOwner,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversation> {
                assertEquals(runtime.owner, owner)
                assertEquals(physicalView.conversationId, conversationId)
                remoteReads += "conversation"
                return base.read("remote-conversation") { remoteConversation }
            }

            override suspend fun list(
                owner: CollaborationSpaceOwner,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationSpaceConversationPage> = error("No directory sweep")

            override suspend fun open(
                owner: CollaborationSpaceOwner,
                operation: String,
                peerAccountId: String,
            ): AppResult<CollaborationSetupReceipt> = error("No conversation creation")
        }
    private val originals =
        object : CollaborationSpaceOriginalConversationRepository {
            override suspend fun get(
                owner: CollaborationSpaceOwner,
                position: CollaborationOriginalConversationPosition,
            ): AppResult<CollaborationSpaceOriginalConversation> {
                assertEquals(runtime.owner, owner)
                originalReads += position
                remoteReads += "original"
                return base.read("remote-original") { remoteOriginal }
            }

            override suspend fun list(
                owner: CollaborationSpaceOwner,
                after: CollaborationOriginalConversationPosition?,
                limit: Int,
            ): AppResult<CollaborationSpaceOriginalConversationPage> = error("No original sweep")
        }
    private val metadata =
        object : CollaborationSpaceConversationMetadataRepository {
            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                assertEquals(request.access, access)
                assertEquals(remoteConversation.id, conversationId)
                return base.read("metadata") { canonical }
            }

            override suspend fun findByPeer(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> = error("No peer lookup")

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                conversation: CollaborationSpaceConversation,
            ): AppResult<Boolean> {
                assertEquals(request.access, access)
                val before = base.read("metadata-write") { Unit }
                if (before is AppResult.Failed) return before
                metadataWrites += conversation
                canonical = conversation.localMetadata()
                val after = base.read("metadata-committed") { Unit }
                return if (after is AppResult.Failed) after
                else if (loseMetadataReply) AppResult.Failed(base.directory.storageFailure)
                else AppResult.Success(metadataReply)
            }
        }

    private val bindings =
        object : CollaborationSpaceRoomBindingRepository by base.bindings {
            override suspend fun getByConversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> {
                assertEquals(request.access, access)
                assertEquals(remoteConversation.id, conversationId)
                return base.read("binding-confirm") { base.retained }
            }

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                binding: CollaborationSpaceRoomBinding,
            ): AppResult<Boolean> {
                assertEquals(request.access, access)
                val before = base.read("binding-retain") { Unit }
                if (before is AppResult.Failed) return before
                bindingWrites += binding
                base.retained = binding
                val after = base.read("binding-committed") { Unit }
                return if (after is AppResult.Failed) after
                else if (loseBindingReply) AppResult.Failed(base.directory.storageFailure)
                else AppResult.Success(bindingReply)
            }

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
    private val originalMetadata =
        object : CollaborationSpaceOriginalRoomMetadataRepository {
            override suspend fun getByConversation(
                access: CollaborationSpaceLocalAccess,
                position: CollaborationOriginalConversationPosition,
            ): AppResult<CollaborationSpaceOriginalRoomMetadata?> {
                assertEquals(request.access, access)
                assertEquals(originalPosition, position)
                return base.read("original-metadata") { originalCanonical }
            }

            override suspend fun findByRoom(
                access: CollaborationSpaceLocalAccess,
                companyId: String,
                serverName: String,
                roomId: String,
            ): AppResult<CollaborationSpaceOriginalRoomMetadata?> {
                assertEquals(request.access, access)
                assertEquals(originalPosition.companyId, companyId)
                assertEquals(registered.command.provider.serverName, serverName)
                assertEquals(physical.roomId, roomId)
                return base.read("original-index") { originalIndex }
            }

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                metadata: CollaborationSpaceOriginalRoomMetadata,
            ): AppResult<Boolean> {
                assertEquals(request.access, access)
                val before = base.read("original-metadata-write") { Unit }
                if (before is AppResult.Failed) return before
                originalMetadataWrites += metadata
                if (originalMetadataCommit) {
                    originalCanonical = metadata
                    originalIndex = metadata
                }
                val after = base.read("original-metadata-committed") { Unit }
                return if (after is AppResult.Failed) after
                else if (loseOriginalMetadataReply) AppResult.Failed(base.directory.storageFailure)
                else AppResult.Success(originalMetadataReply)
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
            physicalRooms,
            conversations,
            metadata,
            originals,
            originalMetadata,
            base.native.clock,
            timeSource,
        )

    val defer =
        DeferCollaborationSpaceIncomingPhysicalRoom(
            request.demands,
            sync,
            base.directory.directory,
            base.native.setup.sessions,
            base.native.server,
            AppBuild(AppPlatform.Android, 5, "test", null),
            base.native.clock,
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
