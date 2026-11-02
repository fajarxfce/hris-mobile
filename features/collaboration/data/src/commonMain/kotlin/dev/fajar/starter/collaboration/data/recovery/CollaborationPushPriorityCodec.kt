package dev.fajar.starter.collaboration.data.recovery

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun pushPriorityScope(parent: CollaborationNativeParent): ReplicaScope {
    require(validCollaborationMessageId(parent.nativeSessionId))
    return recoveryScope(
        CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
    )
}

internal fun pushPriorityPartition(parent: CollaborationNativeParent): String {
    pushPriorityScope(parent)
    return COLLABORATION_HISTORY_PREFIX + "push-priority/" + parent.nativeSessionId
}

internal class CollaborationPushPriorityCodec(
    private val parent: CollaborationNativeParent,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(raw: ReplicaCheckpoint): CollaborationPushPriority {
        require(
            raw.published && raw.revision == 0L && raw.handle.scope == pushPriorityScope(parent)
        )
        require(raw.handle.partition == pushPriorityPartition(parent))
        require(
            validCollaborationMessageId(raw.handle.generation) && raw.payload.length in 1..32768
        )
        val bytes = cipher.decrypt(key, aad(raw.handle.generation), Base64.decode(raw.payload))
        return try {
            require(bytes.size in 1..24000)
            val dto =
                Json.decodeFromString<PriorityDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            require(dto.schema == 1)
            CollaborationPushPriority(
                    raw.handle.generation,
                    dto.hints.map {
                        CollaborationPushHint(
                            it.eventId,
                            it.companyId,
                            it.deviceId,
                            it.revision,
                            it.issuedAt,
                            it.expiresAt,
                            it.roomId,
                            it.matrixEventId,
                        )
                    },
                )
                .also { require(validCollaborationPushPriority(it)) }
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(value: CollaborationPushPriority): String {
        pushPriorityScope(parent)
        require(validCollaborationPushPriority(value))
        val dto =
            PriorityDto(
                1,
                value.hints.map {
                    HintDto(
                        it.eventId,
                        it.companyId,
                        it.deviceId,
                        it.revision,
                        it.issuedAt,
                        it.expiresAt,
                        it.roomId,
                        it.matrixEventId,
                    )
                },
            )
        val bytes = Json.encodeToString(dto).encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size in 1..24000)
            Base64.encode(cipher.encrypt(key, aad(value.generation), bytes)).also {
                require(it.length <= 32768)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-push-priority-v1",
                    parent.audience,
                    parent.accountId,
                    parent.nativeSessionId,
                    generation,
                    "0",
                )
            )
            .encodeToByteArray()
}

@Serializable private data class PriorityDto(val schema: Int, val hints: List<HintDto>)

@Serializable
private data class HintDto(
    val eventId: String,
    val companyId: String,
    val deviceId: String,
    val revision: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val roomId: String?,
    val matrixEventId: String?,
)
