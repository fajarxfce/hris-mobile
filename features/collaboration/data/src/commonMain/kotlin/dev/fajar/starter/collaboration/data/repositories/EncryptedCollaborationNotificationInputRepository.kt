@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationInputRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationNotificationInputRepository(
    private val records: DeviceRecordStore,
    private val cipher: ContentCipher,
    @Named("collaboration-notification-input-key") credentials: AtomicCredentialStore,
) : CollaborationNotificationInputRepository {
    private val keys = CollaborationNotificationInputKeys(records, cipher, credentials)

    override suspend fun retain(reply: CollaborationUnverifiedNotificationReply) = safeStorageCall {
        validateNotificationInputReply(reply)
        keys.use(create = true) { key, index ->
            val codec = CollaborationNotificationInputCodec(cipher, key, index)
            // An existing record authenticates that this protected key still belongs to the store.
            // A well-formed but wrong key must not start a second encryption lineage in one queue.
            records.page(NOTIFICATION_INPUT_NAMESPACE, limit = 1).firstOrNull()?.let {
                codec.open(it)
            }
            val reference =
                CollaborationNotificationInputReference(codec.id(reply), Uuid.random().toString())
            val raw =
                records.retain(
                    codec.seal(reference, reply),
                    COLLABORATION_NOTIFICATION_INPUT_CAPACITY,
                ) ?: return@use CollaborationNotificationInputRetention.CapacityExceeded
            val input = codec.open(raw)
            require(input.reference.id == reference.id && input.reply == reply)
            if (records.get(NOTIFICATION_INPUT_NAMESPACE, raw.id) == raw)
                CollaborationNotificationInputRetention.Retained(input)
            else CollaborationNotificationInputRetention.Contended
        }
    }

    override suspend fun get(reference: CollaborationNotificationInputReference) = safeStorageCall {
        require(validCollaborationNotificationInputReference(reference))
        val raw =
            records.get(NOTIFICATION_INPUT_NAMESPACE, reference.id) ?: return@safeStorageCall null
        if (raw.generation != reference.generation) return@safeStorageCall null
        keys.use { key, index ->
            val input = CollaborationNotificationInputCodec(cipher, key, index).open(raw)
            require(
                input.reference == reference &&
                    records.get(NOTIFICATION_INPUT_NAMESPACE, raw.id) == raw
            )
            input
        }
    }

    override suspend fun page(afterId: String?, limit: Int) = safeStorageCall {
        require(limit in 1..20)
        if (afterId != null) require(validCollaborationNotificationInputId(afterId))
        records.page(NOTIFICATION_INPUT_NAMESPACE, afterId, limit).map {
            require(it.namespace == NOTIFICATION_INPUT_NAMESPACE)
            CollaborationNotificationInputReference(it.id, it.generation).also { reference ->
                require(validCollaborationNotificationInputReference(reference))
            }
        }
    }

    override suspend fun remove(expected: CollaborationNotificationInput) = safeStorageCall {
        require(validCollaborationNotificationInput(expected))
        val raw =
            records.get(NOTIFICATION_INPUT_NAMESPACE, expected.reference.id)
                ?: return@safeStorageCall false
        if (raw.generation != expected.reference.generation) return@safeStorageCall false
        keys.use { key, index ->
            val actual = CollaborationNotificationInputCodec(cipher, key, index).open(raw)
            require(actual == expected)
            records.remove(raw)
        }
    }
}
