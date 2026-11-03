package dev.fajar.starter.collaboration.data.notifications

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val NOTIFICATION_INPUT_RECOVERY_PARTITION =
    COLLABORATION_HISTORY_PREFIX + "notification-input-recovery"

@Serializable private data class InputRecoveryReferenceDto(val id: String, val generation: String)

@Serializable
private data class InputRecoveryStateDto(
    val schema: Int,
    val cycleId: String,
    val references: List<InputRecoveryReferenceDto>,
    val nextIndex: Int,
    val inFlight: Boolean,
    val retryRequired: Boolean,
    val continuationRequired: Boolean,
    val finished: Boolean,
)

internal class CollaborationNotificationInputRecoveryCodec(
    private val access: CollaborationRecoveryAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun decode(raw: ReplicaCheckpoint): CollaborationNotificationInputRecoveryCursor {
        require(
            raw.published &&
                raw.revision == 0L &&
                raw.handle.scope == recoveryScope(access) &&
                raw.handle.partition == NOTIFICATION_INPUT_RECOVERY_PARTITION
        )
        require(
            validCollaborationMessageId(raw.handle.generation) && raw.payload.length in 1..32_768
        )
        val encoded = Base64.decode(raw.payload)
        require(Base64.encode(encoded) == raw.payload)
        val plain = cipher.decrypt(key, aad(raw.handle.generation), encoded)
        return try {
            require(plain.size in 1..16_384)
            val value =
                Json.decodeFromString<InputRecoveryStateDto>(
                    plain.decodeToString(throwOnInvalidSequence = true)
                )
            require(value.schema == 1)
            CollaborationNotificationInputRecoveryCursor(
                    raw.handle.generation,
                    CollaborationNotificationInputRecoveryState(
                        value.cycleId,
                        value.references.map {
                            CollaborationNotificationInputReference(it.id, it.generation)
                        },
                        value.nextIndex,
                        value.inFlight,
                        value.retryRequired,
                        value.continuationRequired,
                        value.finished,
                    ),
                )
                .also { require(validCollaborationNotificationInputRecoveryCursor(it)) }
        } finally {
            plain.fill(0)
        }
    }

    suspend fun encode(value: CollaborationNotificationInputRecoveryCursor): String {
        require(validCollaborationNotificationInputRecoveryCursor(value))
        val state = value.state
        val plain =
            Json.encodeToString(
                    InputRecoveryStateDto(
                        1,
                        state.cycleId,
                        state.references.map { InputRecoveryReferenceDto(it.id, it.generation) },
                        state.nextIndex,
                        state.inFlight,
                        state.retryRequired,
                        state.continuationRequired,
                        state.finished,
                    )
                )
                .encodeToByteArray()
        return try {
            require(plain.size in 1..16_384)
            Base64.encode(cipher.encrypt(key, aad(value.generation), plain)).also {
                require(it.length <= 32_768)
            }
        } finally {
            plain.fill(0)
        }
    }

    private fun aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-notification-input-recovery-v1",
                    access.audience,
                    access.accountId,
                    NOTIFICATION_INPUT_RECOVERY_PARTITION,
                    generation,
                    "0",
                )
            )
            .encodeToByteArray()
}
