package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.notifications.*
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
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
        val aad = aad(parent, 2)
        val plain = encodeNotificationActionEnvelope(parent, action)
        try {
            require(plain.size in 1..3072)
            keys.use { key, _ ->
                val encrypted = cipher.encrypt(key, aad, plain)
                require(encrypted.size in 1..3072)
                CollaborationNotificationActionToken("v2." + Base64.UrlSafe.encode(encrypted))
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
        val decoded = decode(parent, token)
        require(decoded.parent == parent)
        decoded.action
    }

    override suspend fun recover(
        parent: CollaborationNativeParent,
        token: CollaborationNotificationActionToken,
    ) = safeStorageCall { decode(parent, token) }

    private suspend fun decode(
        parent: CollaborationNativeParent,
        token: CollaborationNotificationActionToken,
    ): CollaborationAuthenticatedNotificationAction {
        require(validCollaborationNotificationTokenShape(token))
        val version = if (token.encoded.startsWith("v2.")) 2 else 1
        val aad = aad(parent, version)
        val encoded = token.encoded.drop(3)
        val encrypted = Base64.UrlSafe.decode(encoded)
        require(encrypted.size in 1..3072 && Base64.UrlSafe.encode(encrypted) == encoded)
        return keys.use { key, _ ->
            val plain = cipher.decrypt(key, aad, encrypted)
            try {
                require(plain.size in 1..3072)
                val decoded =
                    if (version == 2) decodeNotificationActionEnvelope(plain)
                    else
                        CollaborationAuthenticatedNotificationAction(
                            parent,
                            decodeNotificationAction(plain),
                        )
                require(
                    decoded.parent.accountId == parent.accountId &&
                        decoded.parent.audience == parent.audience
                )
                decoded
            } finally {
                plain.fill(0)
            }
        }
    }

    private fun aad(parent: CollaborationNativeParent, version: Int): ByteArray {
        require(validCollaborationNotificationParent(parent))
        recoveryScope(
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        )
        val fields =
            if (version == 2)
                listOf("hris-notification-action-v2", parent.audience, parent.accountId)
            else
                listOf(
                    "hris-notification-action-v1",
                    parent.sessionId,
                    parent.audience,
                    parent.accountId,
                    parent.nativeSessionId,
                )
        return Json.encodeToString(fields).encodeToByteArray(throwOnInvalidSequence = true)
    }
}
