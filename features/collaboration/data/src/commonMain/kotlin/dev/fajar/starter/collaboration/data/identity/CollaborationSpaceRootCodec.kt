package dev.fajar.starter.collaboration.data.identity

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceAccountAccess
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Account metadata shares the established protected content key; no company-derived scope.
internal const val COLLABORATION_SPACE_ROOT_PARTITION =
    COLLABORATION_HISTORY_PREFIX + "space-root/v1"

internal fun spaceAccountScope(access: CollaborationSpaceAccountAccess): ReplicaScope {
    require(validCollaborationSpaceAccountAccess(access))
    return ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))
}

internal class CollaborationSpaceRootCodec(
    private val access: CollaborationSpaceAccountAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(raw: ReplicaCheckpoint): CollaborationSpaceRoot {
        require(
            raw.published &&
                raw.revision == 0L &&
                raw.handle.scope == spaceAccountScope(access) &&
                raw.handle.partition == COLLABORATION_SPACE_ROOT_PARTITION
        )
        require(validCollaborationMessageId(raw.handle.generation) && raw.payload.length in 1..4096)
        val bytes = cipher.decrypt(key, aad(raw.handle.generation), Base64.decode(raw.payload))
        return try {
            require(bytes.size in 1..1024)
            val value =
                Json.decodeFromString<SpaceRootRecord>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
            require(value.schema == 1 && validCollaborationMessageId(value.spaceId))
            CollaborationSpaceRoot(value.spaceId)
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(generation: String, value: CollaborationSpaceRoot): String {
        require(
            validCollaborationMessageId(generation) && validCollaborationMessageId(value.spaceId)
        )
        val bytes = Json.encodeToString(SpaceRootRecord(1, value.spaceId)).encodeToByteArray()
        return try {
            require(bytes.size in 1..1024)
            Base64.encode(cipher.encrypt(key, aad(generation), bytes)).also {
                require(it.length <= 4096)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-space-root-v1",
                    access.audience,
                    access.accountId,
                    generation,
                    "0",
                )
            )
            .encodeToByteArray()
}

@Serializable private data class SpaceRootRecord(val schema: Int, val spaceId: String)
