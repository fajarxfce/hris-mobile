@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationReceiptRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-receipt-content-key") private val credentials: AtomicCredentialStore,
) : CollaborationReceiptRepository, CollaborationReceiptRetirementRepository {
    private val keyCreation = Mutex()

    override suspend fun read(access: CollaborationLocalAccess, conversationId: String) =
        safeStorageCall {
            val page = page(access, conversationId)
            if (page.checkpoint == null) {
                require(page.documents.isEmpty())
                emptyList()
            } else withKey(access, conversationId) { it.read(page) }
        }

    override suspend fun append(
        access: CollaborationLocalAccess,
        conversationId: String,
        receipts: List<CollaborationMessageReceipt>,
    ) = safeStorageCall {
        require(receipts.size in 1..32 && receipts.all { it.eventIds.size in 1..128 })
        // Detach mutable input before any suspension, including nested event alias lists.
        val batch = receipts.map { it.copy(eventIds = it.eventIds.toList()) }
        require(batch.size in 1..32 && batch.map { it.intent.id }.distinct().size == batch.size)
        val page = page(access, conversationId)
        withKey(access, conversationId, create = page.checkpoint == null) { codec ->
            batch.forEach(codec::validate)
            val previous = codec.read(page)
            val next = previous.associateBy { it.intent.id }.toMutableMap()
            for (receipt in batch) {
                val old = next[receipt.intent.id]
                if (old == null) next[receipt.intent.id] = receipt
                else {
                    require(
                        old.intent == receipt.intent &&
                            old.device == receipt.device &&
                            old.transactionId == receipt.transactionId
                    )
                    next[receipt.intent.id] =
                        old.copy(eventIds = (old.eventIds + receipt.eventIds).distinct())
                }
            }
            val ledger = next.values.sortedBy { it.intent.id }
            codec.validateLedger(ledger)
            val raw = page.checkpoint
            val generation = raw?.handle?.generation ?: Uuid.random().toString()
            val revision = raw?.revision?.plus(1) ?: 0
            val changed =
                batch.map { codec.document(generation, revision, next.getValue(it.intent.id)) }
            val checkpoint = codec.checkpoint(generation, revision, ledger)
            currentCoroutineContext().ensureActive()
            if (raw == null)
                replicas.replace(
                    receiptScope(access),
                    receiptPartition(access, conversationId),
                    null,
                    ReplicaSnapshot(generation, checkpoint, changed),
                )
            else replicas.commit(raw.handle, raw.revision, checkpoint, changed, true)
        }
    }

    override suspend fun removeReceipts(
        access: CollaborationLocalAccess,
        conversationId: String,
        expected: List<CollaborationMessageReceipt>,
    ) = safeStorageCall {
        require(expected.size in 1..32 && expected.all { it.eventIds.size in 1..128 })
        val batch = expected.map { it.copy(eventIds = it.eventIds.toList()) }
        require(batch.map { it.intent.id }.distinct().size == batch.size)
        val page = page(access, conversationId)
        val raw = page.checkpoint
        if (raw == null) {
            require(page.documents.isEmpty())
            false
        } else
            withKey(access, conversationId) { codec ->
                batch.forEach(codec::validate)
                val previous = codec.read(page)
                val byId = previous.associateBy { it.intent.id }
                if (batch.any { byId[it.intent.id] != it }) false
                else {
                    val removed = batch.map { it.intent.id }.toSet()
                    val remaining = previous.filterNot { it.intent.id in removed }
                    require(raw.revision < 9_007_199_254_740_991L)
                    val revision = raw.revision + 1
                    val checkpoint = codec.checkpoint(raw.handle.generation, revision, remaining)
                    val tombstones =
                        batch.map { ReplicaDocument(it.intent.id, revision, it.intent.id, null) }
                    currentCoroutineContext().ensureActive()
                    replicas.commit(raw.handle, raw.revision, checkpoint, tombstones, true)
                }
            }
    }

    private suspend fun page(access: CollaborationLocalAccess, conversationId: String) =
        replicas
            .observe(receiptScope(access), receiptPartition(access, conversationId), limit = 129)
            .first()

    private suspend fun <T> withKey(
        access: CollaborationLocalAccess,
        conversationId: String,
        create: Boolean = false,
        operation: suspend (CollaborationReceiptCodec) -> T,
    ): T {
        val key =
            if (create) keyCreation.withLock { acquireKey() }
            else Base64.decode(requireNotNull(credentials.read()))
        return try {
            require(key.size == 32)
            operation(CollaborationReceiptCodec(access, conversationId, cipher, key))
        } finally {
            key.fill(0)
        }
    }

    private suspend fun acquireKey(): ByteArray {
        credentials.read()?.let {
            return Base64.decode(it)
        }
        check(!replicas.containsRecords(COLLABORATION_RECEIPT_PREFIX))
        val generated = cipher.generateKey()
        return try {
            Base64.decode(credentials.writeIfAbsent(Base64.encode(generated)))
        } finally {
            generated.fill(0)
        }
    }
}
