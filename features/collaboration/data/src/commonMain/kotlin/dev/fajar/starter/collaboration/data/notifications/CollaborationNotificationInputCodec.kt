package dev.fajar.starter.collaboration.data.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.DeviceRecord
import dev.fajar.starter.securestorage.ContentCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class NotificationInputDto(val token: String, val text: String, val schema: Int = 1)

internal class CollaborationNotificationInputCodec(
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val index: ByteArray,
) {
    suspend fun id(reply: CollaborationUnverifiedNotificationReply): String {
        validateNotificationInputReply(reply)
        val input =
            Json.encodeToString(
                    listOf("hris-notification-input-index-v1", reply.token.encoded, reply.text)
                )
                .encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            "q-" +
                CryptographyProvider.Default.get(HMAC)
                    .keyDecoder(SHA256)
                    .decodeFromByteArray(HMAC.Key.Format.RAW, index)
                    .signatureGenerator()
                    .generateSignature(input)
                    .toHexString()
        } finally {
            input.fill(0)
        }
    }

    suspend fun seal(
        reference: CollaborationNotificationInputReference,
        reply: CollaborationUnverifiedNotificationReply,
    ): DeviceRecord {
        validateNotificationInputReply(reply)
        require(reference.id == id(reply))
        val aad = aad(reference)
        val plain =
            Json.encodeToString(NotificationInputDto(reply.token.encoded, reply.text))
                .encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(plain.size in 1..65_536)
            val encrypted = cipher.encrypt(key, aad, plain)
            require(encrypted.size in 1..98_304)
            DeviceRecord(
                NOTIFICATION_INPUT_NAMESPACE,
                reference.id,
                reference.generation,
                Base64.encode(encrypted),
            )
        } finally {
            plain.fill(0)
        }
    }

    suspend fun open(record: DeviceRecord): CollaborationNotificationInput {
        require(
            record.namespace == NOTIFICATION_INPUT_NAMESPACE && record.payload.length in 1..131_072
        )
        val reference = CollaborationNotificationInputReference(record.id, record.generation)
        val aad = aad(reference)
        val encrypted = Base64.decode(record.payload)
        require(encrypted.size in 1..98_304 && Base64.encode(encrypted) == record.payload)
        val plain = cipher.decrypt(key, aad, encrypted)
        return try {
            require(plain.size in 1..65_536)
            val dto =
                Json.decodeFromString<NotificationInputDto>(
                    plain.decodeToString(throwOnInvalidSequence = true)
                )
            require(dto.schema == 1)
            val reply =
                CollaborationUnverifiedNotificationReply(
                    CollaborationNotificationActionToken(dto.token),
                    dto.text,
                )
            validateNotificationInputReply(reply)
            require(reference.id == id(reply))
            CollaborationNotificationInput(reference, reply)
        } finally {
            plain.fill(0)
        }
    }

    private fun aad(reference: CollaborationNotificationInputReference): ByteArray {
        require(validCollaborationNotificationInputReference(reference))
        return Json.encodeToString(
                listOf(NOTIFICATION_INPUT_NAMESPACE, reference.id, reference.generation)
            )
            .encodeToByteArray()
    }
}

internal fun validateNotificationInputReply(reply: CollaborationUnverifiedNotificationReply) {
    require(validCollaborationUnverifiedNotificationReply(reply))
    val encoded = reply.token.encoded.drop(3)
    val decoded = Base64.UrlSafe.decode(encoded)
    require(decoded.size in 1..3072 && Base64.UrlSafe.encode(decoded) == encoded)
}
