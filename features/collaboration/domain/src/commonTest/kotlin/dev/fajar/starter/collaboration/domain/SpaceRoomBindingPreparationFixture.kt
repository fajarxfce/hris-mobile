package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.PrepareCollaborationSpaceRoomBinding
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days

internal class SpaceRoomBindingPreparationFixture(legacy: Boolean = false) {
    val source = SpaceRoomPreparationFixture()
    val native = source.native
    val base = source.base
    val owner = source.owner
    val access = source.access
    val conversation = source.conversation
    val position =
        CollaborationOriginalConversationPosition(
            "a1000000-0000-4000-8000-000000000001",
            "a2000000-0000-4000-8000-000000000001",
        )
    val roomId = "!original:matrix.example.test"
    val published =
        CollaborationSpacePublishedRoom(
            roomId,
            "matrix.example.test",
            if (legacy) CollaborationSpaceRoomOrigin.LEGACY else CollaborationSpaceRoomOrigin.SPACE,
            conversation.createdAt,
            if (legacy)
                CollaborationSpaceOriginalRoom(position, roomId, conversation.createdAt - 1.days)
            else null,
        )
    val original =
        CollaborationSpaceOriginalConversation(
            owner,
            CollaborationOriginalConversation(
                position,
                conversation.firstAccountId,
                conversation.secondAccountId,
                conversation.secondAccountId,
                conversation.createdAt - 2.days,
                0,
            ),
            CollaborationOriginalRoom(
                published.serverName,
                roomId,
                conversation.createdAt - 1.days,
                conversation.firstAccountId,
            ),
        )
    val binding =
        CollaborationSpaceRoomBinding(
            conversation.localMetadata(),
            published,
            if (legacy)
                CollaborationSpaceOriginalRoomMetadata(
                    original.conversation,
                    requireNotNull(original.room),
                )
            else null,
        )
    var retained: AppResult<CollaborationSpaceRoomBinding?> = AppResult.Success(null)
    var canonical: AppResult<CollaborationSpaceConversationMetadata?> = AppResult.Success(null)
    var bindingReads = 0
    var canonicalReads = 0
    var admissionReads = 0
    val bindingWrites = mutableListOf<CollaborationSpaceRoomBinding>()
    val canonicalWrites = mutableListOf<CollaborationSpaceConversation>()
    val originalReads =
        mutableListOf<Pair<CollaborationSpaceOwner, CollaborationOriginalConversationPosition>>()
    var beforeBindingRead: suspend () -> Unit = {}
    var beforeCanonicalRead: suspend () -> Unit = {}
    var onOriginal: suspend () -> AppResult<CollaborationSpaceOriginalConversation> = {
        AppResult.Success(original)
    }
    var onBindingWrite: suspend (CollaborationSpaceRoomBinding) -> AppResult<Boolean> = {
        retained = AppResult.Success(it)
        AppResult.Success(true)
    }
    var onCanonicalWrite: suspend (CollaborationSpaceConversation) -> AppResult<Boolean> = {
        canonical = AppResult.Success(it.localMetadata())
        AppResult.Success(true)
    }

    init {
        source.view =
            CollaborationSpaceRoomView(
                conversation,
                source.terminal(CollaborationProvisioningState.SUCCEEDED),
                published,
            )
    }

    val bindings =
        object : CollaborationSpaceRoomBindingRepository {
            override suspend fun getByConversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> {
                assertEquals(this@SpaceRoomBindingPreparationFixture.access, access)
                assertEquals(conversation.id, conversationId)
                bindingReads++
                beforeBindingRead()
                return retained
            }

            override suspend fun findByRoom(
                access: CollaborationSpaceLocalAccess,
                serverName: String,
                roomId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> = error("Exact conversation lookup only")

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                binding: CollaborationSpaceRoomBinding,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceRoomBindingPreparationFixture.access, access)
                bindingWrites += binding
                return onBindingWrite(binding)
            }
        }
    val metadata =
        object : CollaborationSpaceConversationMetadataRepository {
            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                assertEquals(this@SpaceRoomBindingPreparationFixture.access, access)
                assertEquals(conversation.id, conversationId)
                canonicalReads++
                beforeCanonicalRead()
                return canonical
            }

            override suspend fun findByPeer(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> =
                error("Exact canonical coordinates only")

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                conversation: CollaborationSpaceConversation,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceRoomBindingPreparationFixture.access, access)
                canonicalWrites += conversation
                return onCanonicalWrite(conversation)
            }
        }
    val originals =
        object : CollaborationSpaceOriginalConversationRepository {
            override suspend fun list(
                owner: CollaborationSpaceOwner,
                after: CollaborationOriginalConversationPosition?,
                limit: Int,
            ): AppResult<CollaborationSpaceOriginalConversationPage> =
                error("Never scan original conversations")

            override suspend fun get(
                owner: CollaborationSpaceOwner,
                position: CollaborationOriginalConversationPosition,
            ): AppResult<CollaborationSpaceOriginalConversation> {
                originalReads += owner to position
                return onOriginal()
            }
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by base.directory {
            override suspend fun admission(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceDirectoryAdmission> {
                admissionReads++
                return base.directory.admission(access)
            }
        }

    fun prepare() =
        PrepareCollaborationSpaceRoomBinding(
            bindings,
            metadata,
            source.rooms,
            originals,
            directory,
            source.local.sessions,
            native.server,
            source.local.build,
            native.clock,
            native.time,
        )
}
