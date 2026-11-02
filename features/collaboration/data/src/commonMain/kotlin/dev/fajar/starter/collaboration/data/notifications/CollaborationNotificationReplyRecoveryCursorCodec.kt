package dev.fajar.starter.collaboration.data.notifications

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

internal const val NOTIFICATION_REPLY_RECOVERY_PARTITION =
    COLLABORATION_HISTORY_PREFIX + "notification-reply-recovery"

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
            val after =
                Json.decodeFromString<Long>(bytes.decodeToString(throwOnInvalidSequence = true))
            require(validCollaborationNotificationReplyRecoverySequence(after))
            CollaborationNotificationReplyRecoveryCursor(raw.handle.generation, after)
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun encode(value: CollaborationNotificationReplyRecoveryCursor): String {
        require(validCollaborationNotificationReplyRecoveryCursor(value))
        val bytes = Json.encodeToString(value.afterSequence).encodeToByteArray()
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
