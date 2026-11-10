package dev.fajar.starter.collaboration.data.recovery

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun spaceRecoveryScope(owner: CollaborationSpaceOwner): ReplicaScope {
    require(validCollaborationSpaceRecoveryOwner(owner))
    return recoveryScope(
        CollaborationRecoveryAccess(
            owner.parent.sessionId,
            owner.parent.audience,
            owner.parent.accountId,
        )
    )
}

internal fun spaceRecoveryPartition(owner: CollaborationSpaceOwner): String {
    spaceRecoveryScope(owner)
    return COLLABORATION_HISTORY_PREFIX +
        "space-recovery/v1/" +
        owner.parent.nativeSessionId +
        "/" +
        owner.spaceId
}

internal class CollaborationSpaceRecoveryProgressCodec(
    private val owner: CollaborationSpaceOwner,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(raw: ReplicaCheckpoint): CollaborationSpaceRecoveryProgress {
        require(
            raw.published && raw.revision == 0L && raw.handle.scope == spaceRecoveryScope(owner)
        )
        require(raw.handle.partition == spaceRecoveryPartition(owner))
        require(validCollaborationMessageId(raw.handle.generation) && raw.payload.length in 1..8192)
        val bytes = cipher.decrypt(key, aad(raw.handle.generation), Base64.decode(raw.payload))
        return try {
            require(bytes.size in 1..4096)
            val dto =
                Json.decodeFromString<SpaceProgressDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            require(
                (dto.schema == 1 && dto.sweep?.messages == null) ||
                    (dto.schema == 2 && dto.sweep?.messages != null)
            )
            val sweep =
                dto.sweep?.let { s ->
                    val boundary =
                        s.boundary?.let {
                            val revision = it.revision.toLong()
                            require(it.revision == revision.toString())
                            CollaborationSpaceRoomBindingBoundary(
                                owner.parent.audience,
                                owner.parent.accountId,
                                owner.spaceId,
                                it.generation,
                                revision,
                            )
                        }
                    CollaborationSpaceRecoverySweep(
                        s.wakeGeneration,
                        boundary,
                        s.afterId,
                        s.pending?.let {
                            CollaborationSpaceRecoveryClaim(it.conversationId, it.nextAfterId)
                        },
                        s.reachedEnd,
                        s.retryRequired,
                        s.continuationRequired,
                        s.hasNewerBindings,
                        s.messages?.let { m ->
                            CollaborationSpaceMessageRecoverySweep(
                                m.after?.position(),
                                m.pending?.let {
                                    CollaborationSpaceMessageRecoveryClaim(
                                        it.position.position(),
                                        it.conversationId,
                                    )
                                },
                                m.reachedEnd,
                            )
                        },
                    )
                }
            CollaborationSpaceRecoveryProgress(raw.handle.generation, sweep).also {
                require(validCollaborationSpaceRecoveryProgress(owner, it))
            }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(value: CollaborationSpaceRecoveryProgress): String {
        require(validCollaborationSpaceRecoveryProgress(owner, value))
        val dto =
            SpaceProgressDto(
                if (value.sweep?.messages == null) 1 else 2,
                value.sweep?.let { s ->
                    SpaceSweepDto(
                        s.wakeGeneration,
                        s.boundary?.let { SpaceBoundaryDto(it.generation, it.revision.toString()) },
                        s.afterId,
                        s.pending?.let { SpaceClaimDto(it.conversationId, it.nextAfterId) },
                        s.reachedEnd,
                        s.retryRequired,
                        s.continuationRequired,
                        s.hasNewerBindings,
                        s.messages?.let { m ->
                            SpaceMessagesDto(
                                m.after?.let {
                                    SpaceMessagePositionDto(it.createdAt.toString(), it.messageId)
                                },
                                m.pending?.let {
                                    SpaceMessageClaimDto(
                                        SpaceMessagePositionDto(
                                            it.position.createdAt.toString(),
                                            it.position.messageId,
                                        ),
                                        it.conversationId,
                                    )
                                },
                                m.reachedEnd,
                            )
                        },
                    )
                },
            )
        val bytes = Json.encodeToString(dto).encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size in 1..4096)
            Base64.encode(cipher.encrypt(key, aad(value.generation), bytes)).also {
                require(it.length <= 8192)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-space-recovery-v1",
                    owner.parent.audience,
                    owner.parent.accountId,
                    owner.parent.nativeSessionId,
                    owner.spaceId,
                    generation,
                    "0",
                )
            )
            .encodeToByteArray()
}

@Serializable private data class SpaceProgressDto(val schema: Int, val sweep: SpaceSweepDto?)

@Serializable private data class SpaceBoundaryDto(val generation: String, val revision: String)

@Serializable
private data class SpaceClaimDto(val conversationId: String?, val nextAfterId: String?)

@Serializable
private data class SpaceSweepDto(
    val wakeGeneration: String,
    val boundary: SpaceBoundaryDto?,
    val afterId: String?,
    val pending: SpaceClaimDto?,
    val reachedEnd: Boolean,
    val retryRequired: Boolean,
    val continuationRequired: Boolean,
    val hasNewerBindings: Boolean,
    val messages: SpaceMessagesDto? = null,
)

@Serializable
private data class SpaceMessagePositionDto(val createdAt: String, val messageId: String) {
    fun position(): CollaborationSpaceMessageRecoveryPosition {
        require(createdAt.length in 1..40)
        val instant = Instant.parse(createdAt)
        require(instant.toString() == createdAt)
        return CollaborationSpaceMessageRecoveryPosition(instant, messageId).also {
            require(validCollaborationSpaceMessageRecoveryPosition(it))
        }
    }
}

@Serializable
private data class SpaceMessageClaimDto(
    val position: SpaceMessagePositionDto,
    val conversationId: String,
)

@Serializable
private data class SpaceMessagesDto(
    val after: SpaceMessagePositionDto?,
    val pending: SpaceMessageClaimDto?,
    val reachedEnd: Boolean,
)
