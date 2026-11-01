@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.messages

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val COLLABORATION_RECEIPT_PREFIX = "collaboration-receipt/"

internal fun receiptScope(access: CollaborationLocalAccess) =
    messageScope(access).let { ReplicaScope(it.sessionId, it.accountKey) }

internal fun receiptPartition(access: CollaborationLocalAccess, conversationId: String): String {
    require(validCollaborationMessageId(conversationId))
    return COLLABORATION_RECEIPT_PREFIX + access.companyId + "/" + conversationId
}

@Serializable
internal data class ReceiptCheckpointDto(val count: Int, val aliases: Int, val schema: Int = 1)

@Serializable
internal data class MessageReceiptDto(
    val intent: MessageIntentDto,
    val mapping: MessageStateDto,
    val eventIds: List<String>,
) {
    override fun toString() = "MessageReceiptDto([redacted])"
}

internal class CollaborationReceiptCodec(
    private val access: CollaborationLocalAccess,
    private val conversationId: String,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    fun validate(receipt: CollaborationMessageReceipt) {
        receipt.intent.toDto(access)
        require(receipt.intent.conversationId == conversationId)
        require(validCollaborationMessageDevice(receipt.device))
        require(validCollaborationTransactionId(receipt.transactionId))
        require(receipt.eventIds.size in 1..128)
        require(receipt.eventIds.all(::validCollaborationEventId))
        require(receipt.eventIds.distinct().size == receipt.eventIds.size)
    }

    suspend fun read(page: ReplicaPage): List<CollaborationMessageReceipt> {
        val raw = page.checkpoint
        if (raw == null) {
            require(page.documents.isEmpty())
            return emptyList()
        }
        require(raw.published && raw.handle.scope == receiptScope(access))
        require(raw.handle.partition == receiptPartition(access, conversationId))
        require(validCollaborationMessageId(raw.handle.generation))
        require(raw.revision in 0..9_007_199_254_740_991L)
        val summary =
            Json.decodeFromString<ReceiptCheckpointDto>(
                open(raw.handle.generation, "checkpoint", raw.revision, raw.payload)
            )
        require(summary.schema == 1 && summary.count in 1..128 && summary.aliases in 1..512)
        require(page.documents.size == summary.count)
        val ids = page.documents.map { it.id }
        require(ids.zipWithNext().all { (a, b) -> a < b })
        val receipts = page.documents.map { document(raw, it) }
        validateLedger(receipts)
        require(receipts.sumOf { it.eventIds.size } == summary.aliases)
        return receipts
    }

    fun validateLedger(receipts: List<CollaborationMessageReceipt>) {
        require(validCollaborationReceiptLedger(receipts))
    }

    suspend fun checkpoint(
        generation: String,
        revision: Long,
        receipts: List<CollaborationMessageReceipt>,
    ) =
        seal(
            generation,
            "checkpoint",
            revision,
            Json.encodeToString(
                ReceiptCheckpointDto(receipts.size, receipts.sumOf { it.eventIds.size })
            ),
        )

    suspend fun document(
        generation: String,
        revision: Long,
        receipt: CollaborationMessageReceipt,
    ): ReplicaDocument {
        validate(receipt)
        val dto =
            MessageReceiptDto(
                receipt.intent.toDto(access),
                CollaborationMessageState.Staged(receipt.device, receipt.transactionId).toDto(),
                receipt.eventIds,
            )
        val id = receipt.intent.id
        return ReplicaDocument(
            id,
            revision,
            id,
            seal(generation, id, revision, Json.encodeToString(dto)),
        )
    }

    private suspend fun document(
        checkpoint: ReplicaCheckpoint,
        raw: ReplicaDocument,
    ): CollaborationMessageReceipt {
        require(validCollaborationMessageId(raw.id) && raw.sortKey == raw.id && raw.tags.isEmpty())
        require(raw.version in 0..checkpoint.revision)
        val dto =
            Json.decodeFromString<MessageReceiptDto>(
                open(checkpoint.handle.generation, raw.id, raw.version, requireNotNull(raw.payload))
            )
        val mapping = dto.mapping.toState() as? CollaborationMessageState.Staged
        requireNotNull(mapping)
        return CollaborationMessageReceipt(
                dto.intent.toIntent(access),
                mapping.device,
                mapping.transactionId,
                dto.eventIds,
            )
            .also {
                validate(it)
                require(it.intent.id == raw.id)
            }
    }

    private fun aad(generation: String, id: String, revision: Long) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-receipt-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    conversationId,
                    generation,
                    id,
                    revision.toString(),
                )
            )
            .encodeToByteArray()

    private suspend fun seal(
        generation: String,
        id: String,
        revision: Long,
        value: String,
    ): String {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size <= 98_000)
            Base64.encode(cipher.encrypt(key, aad(generation, id, revision), bytes)).also {
                require(it.length <= limit(id))
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(
        generation: String,
        id: String,
        revision: Long,
        value: String,
    ): String {
        require(value.length in 1..limit(id))
        val bytes = cipher.decrypt(key, aad(generation, id, revision), Base64.decode(value))
        return try {
            require(bytes.size <= 98_000)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }

    private fun limit(id: String) = if (id == "checkpoint") 4096 else 131_072
}
