package dev.fajar.starter.collaboration.data.identity

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.data.history.historyScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

// Deliberately beneath the protected history prefix so identity-only storage fences key creation.
internal fun identityPartition(access: CollaborationLocalAccess): String {
    require(validCollaborationMessageId(access.companyId))
    return COLLABORATION_HISTORY_PREFIX + "self/" + access.companyId
}

internal class CollaborationIdentityCodec(
    private val access: CollaborationLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(raw: ReplicaCheckpoint): CollaborationSelfIdentity {
        require(
            raw.published &&
                raw.revision == 0L &&
                raw.handle.scope == historyScope(access) &&
                raw.handle.partition == identityPartition(access)
        )
        require(validCollaborationMessageId(raw.handle.generation) && raw.payload.length in 1..8192)
        val bytes = cipher.decrypt(key, aad(raw.handle.generation), Base64.decode(raw.payload))
        return try {
            require(bytes.size in 1..4096)
            val value =
                Json.decodeFromString<String>(bytes.decodeToString(throwOnInvalidSequence = true))
            require(validCollaborationHistorySender(value))
            CollaborationSelfIdentity(value)
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(generation: String, value: CollaborationSelfIdentity): String {
        require(
            validCollaborationMessageId(generation) &&
                validCollaborationHistorySender(value.matrixUserId)
        )
        val bytes =
            Json.encodeToString(value.matrixUserId).encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size in 1..4096)
            Base64.encode(cipher.encrypt(key, aad(generation), bytes)).also {
                require(it.length <= 8192)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-self-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    generation,
                    "0",
                )
            )
            .encodeToByteArray()
}
