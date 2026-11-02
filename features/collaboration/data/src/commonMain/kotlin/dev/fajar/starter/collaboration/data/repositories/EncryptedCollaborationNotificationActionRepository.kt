package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.notifications.*
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationActionRepository
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationNotificationActionRepository(
    replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") credentials: AtomicCredentialStore,
) : CollaborationNotificationActionRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun seal(
        parent: CollaborationNativeParent,
        action: CollaborationNotificationAction,
    ) = safeStorageCall {
        val aad = aad(parent)
        val plain = encodeNotificationAction(action)
        try {
            require(plain.size in 1..3072)
            keys.use { key, _ ->
                CollaborationNotificationActionToken(
                        "v1." + Base64.UrlSafe.encode(cipher.encrypt(key, aad, plain))
                    )
                    .also { require(it.encoded.length <= 4096) }
            }
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun open(
        parent: CollaborationNativeParent,
        token: CollaborationNotificationActionToken,
    ) = safeStorageCall {
        val aad = aad(parent)
        require(token.encoded.length in 4..4096 && token.encoded.startsWith("v1."))
        val encoded = token.encoded.removePrefix("v1.")
        val encrypted = Base64.UrlSafe.decode(encoded)
        require(encrypted.size in 1..3072 && Base64.UrlSafe.encode(encrypted) == encoded)
        keys.use { key, _ ->
            val plain = cipher.decrypt(key, aad, encrypted)
            try {
                require(plain.size in 1..3072)
                decodeNotificationAction(plain)
            } finally {
                plain.fill(0)
            }
        }
    }

    private fun aad(parent: CollaborationNativeParent): ByteArray {
        require(validCollaborationMessageId(parent.nativeSessionId))
        recoveryScope(
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        )
        return Json.encodeToString(
                listOf(
                    "hris-notification-action-v1",
                    parent.sessionId,
                    parent.audience,
                    parent.accountId,
                    parent.nativeSessionId,
                )
            )
            .encodeToByteArray()
    }
}
