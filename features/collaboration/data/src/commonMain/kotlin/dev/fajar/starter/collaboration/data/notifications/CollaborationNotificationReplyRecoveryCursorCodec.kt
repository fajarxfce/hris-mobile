package dev.fajar.starter.collaboration.data.notifications

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal const val NOTIFICATION_REPLY_RECOVERY_PARTITION =
    COLLABORATION_HISTORY_PREFIX + "notification-reply-recovery"

@Serializable
private data class ReplyRecoveryCycleDto(
    val id: String,
    val initial: Boolean,
    val inFlight: Boolean,
    val retryRequired: Boolean,
    val finished: Boolean,
)

@Serializable
private data class ReplyRecoveryCursorDto(
    val schema: Int,
    val after: Long,
    val cycle: ReplyRecoveryCycleDto,
)

internal class CollaborationNotificationReplyRecoveryCursorCodec(
    private val access: CollaborationRecoveryAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(raw: ReplicaCheckpoint): CollaborationNotificationReplyRecoveryCursor {
        require(
            raw.published &&
                raw.revision == 0L &&
                raw.handle.scope == recoveryScope(access) &&
                raw.handle.partition == NOTIFICATION_REPLY_RECOVERY_PARTITION
        )
        require(validCollaborationMessageId(raw.handle.generation) && raw.payload.length in 1..2048)
        val bytes = cipher.decrypt(key, aad(raw.handle.generation), Base64.decode(raw.payload))
        return try {
            require(bytes.size in 1..1024)
            val element =
                Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
            val value =
                if (element is JsonPrimitive) {
                    require(!element.isString)
                    val after = requireNotNull(element.longOrNull)
                    require(element.content == after.toString())
                    CollaborationNotificationReplyRecoveryCursor(raw.handle.generation, after)
                } else {
                    val dto = Json.decodeFromJsonElement<ReplyRecoveryCursorDto>(element)
                    require(dto.schema == 2)
                    CollaborationNotificationReplyRecoveryCursor(
                        raw.handle.generation,
                        dto.after,
                        dto.cycle.let {
                            CollaborationNotificationReplyRecoveryCycle(
                                it.id,
                                it.initial,
                                it.inFlight,
                                it.retryRequired,
                                it.finished,
                            )
                        },
                    )
                }
            require(validCollaborationNotificationReplyRecoveryCursor(value))
            value
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(value: CollaborationNotificationReplyRecoveryCursor): String {
        require(validCollaborationNotificationReplyRecoveryCursor(value))
        val cycle = value.cycle
        val plain =
            if (cycle == null) Json.encodeToString(value.afterSequence)
            else
                Json.encodeToString(
                    ReplyRecoveryCursorDto(
                        2,
                        value.afterSequence,
                        ReplyRecoveryCycleDto(
                            cycle.id,
                            cycle.initial,
                            cycle.inFlight,
                            cycle.retryRequired,
                            cycle.finished,
                        ),
                    )
                )
        val bytes = plain.encodeToByteArray()
        require(bytes.size in 1..1024)
        return try {
            Base64.encode(cipher.encrypt(key, aad(value.generation), bytes)).also {
                require(it.length <= 2048)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-notification-reply-recovery-cursor-v1",
                    access.audience,
                    access.accountId,
                    NOTIFICATION_REPLY_RECOVERY_PARTITION,
                    generation,
                    "0",
                )
            )
            .encodeToByteArray()
}
