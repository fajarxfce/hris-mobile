package dev.fajar.starter.collaboration.data.messages

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun spaceReceiptScope(access: CollaborationSpaceLocalAccess) =
    spaceMessageScope(access).let { ReplicaScope(it.sessionId, it.accountKey) }

internal fun spaceReceiptPartition(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
): String {
    require(validCollaborationSpaceConversationId(conversationId))
    return COLLABORATION_RECEIPT_PREFIX + "space/v1/" + access.spaceId + "/" + conversationId
}

@Serializable
internal data class SpaceMessageReceiptDto(
    val intent: SpaceMessageIntentDto,
    val mapping: MessageStateDto,
    val eventIds: List<String>,
) {
    override fun toString() = "SpaceMessageReceiptDto([redacted])"
}

internal class CollaborationSpaceReceiptCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val conversationId: String,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    fun validate(receipt: CollaborationSpaceMessageReceipt) {
        require(validCollaborationSpaceReceipt(access, conversationId, receipt))
    }

    suspend fun read(page: ReplicaPage): List<CollaborationSpaceMessageReceipt> {
        val raw = page.checkpoint
        if (raw == null) {
            require(page.documents.isEmpty())
            return emptyList()
        }
        require(raw.published && raw.handle.scope == spaceReceiptScope(access))
        require(raw.handle.partition == spaceReceiptPartition(access, conversationId))
        require(validCollaborationMessageId(raw.handle.generation))
        require(raw.revision in 0..9_007_199_254_740_991L)
        val summary =
            Json.decodeFromString<ReceiptCheckpointDto>(
                open(raw.handle.generation, "checkpoint", raw.revision, raw.payload)
            )
        require(summary.schema == 1 && summary.count in 0..128 && summary.aliases in 0..512)
        require((summary.count == 0) == (summary.aliases == 0))
        require(page.documents.size == summary.count)
        val ids = page.documents.map { it.id }
        require(ids.zipWithNext().all { (a, b) -> a < b })
        val receipts = page.documents.map { document(raw, it) }
        validateLedger(receipts)
        require(receipts.sumOf { it.eventIds.size } == summary.aliases)
        return receipts
    }

    fun validateLedger(receipts: List<CollaborationSpaceMessageReceipt>) {
        require(validCollaborationSpaceReceiptLedger(access, conversationId, receipts))
    }

    suspend fun checkpoint(
        generation: String,
        revision: Long,
        receipts: List<CollaborationSpaceMessageReceipt>,
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
        receipt: CollaborationSpaceMessageReceipt,
    ): ReplicaDocument {
        validate(receipt)
        val dto =
            SpaceMessageReceiptDto(
                receipt.intent.toSpaceMessageDto(access),
                CollaborationMessageState.Staged(
                        receipt.device,
                        receipt.transactionId,
                        receipt.origin,
                    )
                    .toDto(),
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
    ): CollaborationSpaceMessageReceipt {
        require(validCollaborationMessageId(raw.id) && raw.sortKey == raw.id && raw.tags.isEmpty())
        require(raw.version in 0..checkpoint.revision)
        val dto =
            Json.decodeFromString<SpaceMessageReceiptDto>(
                open(checkpoint.handle.generation, raw.id, raw.version, requireNotNull(raw.payload))
            )
        val mapping = dto.mapping.toState() as? CollaborationMessageState.Staged
        requireNotNull(mapping)
        val origin = requireNotNull(mapping.origin)
        return CollaborationSpaceMessageReceipt(
                dto.intent.toSpaceMessageIntent(access),
                mapping.device,
                mapping.transactionId,
                origin,
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
                    "hris-collaboration-space-receipt-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
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
