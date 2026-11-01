package dev.fajar.starter.collaboration.data.delivery

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.data.history.historyScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

// Deliberately beneath the protected history prefix so delivery-only storage fences key creation.
internal fun deliveryPartition(access: CollaborationLocalAccess): String {
    require(validCollaborationMessageId(access.companyId))
    return COLLABORATION_HISTORY_PREFIX + "delivery/" + access.companyId
}

internal class CollaborationDeliveryCursorCodec(
    private val access: CollaborationLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(raw: ReplicaCheckpoint): CollaborationDeliveryCursor {
        require(
            raw.published &&
                raw.revision == 0L &&
                raw.handle.scope == historyScope(access) &&
                raw.handle.partition == deliveryPartition(access)
        )
        require(validCollaborationMessageId(raw.handle.generation) && raw.payload.length in 1..2048)
        val bytes = cipher.decrypt(key, aad(raw.handle.generation), Base64.decode(raw.payload))
        return try {
            require(bytes.size in 1..1024)
            val value =
                Json.decodeFromString<String>(bytes.decodeToString(throwOnInvalidSequence = true))
            require(validCollaborationMessageId(value))
            CollaborationDeliveryCursor(raw.handle.generation, value)
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(generation: String, value: CollaborationDeliveryCursor): String {
        require(
            validCollaborationMessageId(generation) &&
                value.generation == generation &&
                validCollaborationMessageId(value.afterConversationId)
        )
        val bytes =
            Json.encodeToString(value.afterConversationId)
                .encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size in 1..1024)
            Base64.encode(cipher.encrypt(key, aad(generation), bytes)).also {
                require(it.length <= 2048)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-delivery-cursor-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    generation,
                    "0",
                )
            )
            .encodeToByteArray()
}
