package dev.fajar.starter.expenses.data.replica.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.dto.ExpenseClaimRecordDto
import dev.fajar.starter.expenses.data.encoding.*
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.data.replica.dto.ExpenseClaimHeaderDto
import dev.fajar.starter.expenses.data.replica.mappers.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.sameStoredExpenseScope
import dev.fajar.starter.expenses.domain.replica.entities.ExpenseCheckpoint
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

suspend fun encodeExpenseClaimHeader(
    header: ExpenseClaimHeaderDto,
    checkpoint: ExpenseCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
): ReplicaDocument {
    header.validateHeader()
    val tags = buildSet {
        add(expenseClaimTag(key, checkpoint.access, header.claimId))
        if (header.pendingHistoryAfter != null)
            add(expensePendingHistoryTag(key, checkpoint.access))
    }
    val id = "claim/${header.claimId}/head"
    return encodeExpenseDocument(
        cipher,
        key,
        checkpoint,
        id,
        header.claimVersion,
        id,
        tags,
        Json.encodeToString(header),
    )
}

suspend fun decodeExpenseClaimHeader(
    document: ReplicaDocument,
    access: ExpenseAccess,
    generation: String,
    cipher: ContentCipher,
    key: ByteArray,
): ExpenseClaimHeaderDto {
    val value =
        Json.decodeFromString<ExpenseClaimHeaderDto>(
            decodeExpenseDocument(cipher, key, access, generation, document)
        )
    value.validateHeader()
    require(
        document.id == "claim/${value.claimId}/head" &&
            document.sortKey == document.id &&
            document.version == value.claimVersion
    )
    val tags = buildSet {
        add(expenseClaimTag(key, access, value.claimId))
        if (value.pendingHistoryAfter != null) add(expensePendingHistoryTag(key, access))
    }
    require(document.tags == tags)
    return value
}

/** Encrypted empty slots support shrinking and growing again at the same claim version. */
suspend fun encodeExpenseClaimRecords(
    snapshot: ExpenseSnapshot<ExpenseClaimRecord>,
    checkpoint: ExpenseCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
): List<ReplicaDocument> {
    require(
        sameStoredExpenseScope(snapshot.access, checkpoint.access) &&
            snapshot.value.historyAfter == null
    )
    val record = snapshot.value
    val dto = record.toDto()
    dto.toRecord(record.claim.id, null)
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    try {
        require(bytes.size in 1..EXPENSE_CACHE_MAXIMUM_BYTES)
        val header = snapshot.toHeader(bytes.size)
        val tag = expenseClaimTag(key, checkpoint.access, record.claim.id)
        val documents = mutableListOf(encodeExpenseClaimHeader(header, checkpoint, cipher, key))
        for (index in 0 until EXPENSE_CACHE_MAXIMUM_CHUNKS) {
            currentCoroutineContext().ensureActive()
            val id = "claim/${record.claim.id}/chunk/" + index.toString().padStart(2, '0')
            val offset = index * EXPENSE_CACHE_CHUNK_BYTES
            val chunk =
                if (offset >= bytes.size) ByteArray(0)
                else
                    bytes.copyOfRange(offset, minOf(bytes.size, offset + EXPENSE_CACHE_CHUNK_BYTES))
            val encrypted =
                try {
                    cipher.encrypt(
                        key,
                        expenseReplicaAssociatedData(
                            checkpoint.access,
                            checkpoint.generation,
                            "$id#${header.acquisitionId}",
                            record.claim.version,
                            id,
                            setOf(tag),
                        ),
                        chunk,
                    )
                } finally {
                    chunk.fill(0)
                }
            documents +=
                ReplicaDocument(id, record.claim.version, id, Base64.encode(encrypted), setOf(tag))
        }
        val summary = record.toSummary()
        documents +=
            encodeExpenseDocument(
                cipher,
                key,
                checkpoint,
                "summary/${summary.id}",
                summary.version,
                expenseSummarySortKey(summary),
                expenseSummaryTags(key, checkpoint.access, summary, snapshot.access.timezone),
                Json.encodeToString(summary.toDto()),
            )
        return documents.toList()
    } finally {
        bytes.fill(0)
    }
}

/** The authenticated acquisition ID prevents mixing chunks from equal-version invalidations. */
suspend fun decodeExpenseClaimRecords(
    documents: List<ReplicaDocument>,
    access: ExpenseAccess,
    generation: String,
    id: String,
    cipher: ContentCipher,
    key: ByteArray,
): ExpenseSnapshot<ExpenseClaimRecord> {
    val head = documents.single { it.id == "claim/$id/head" }
    val header = decodeExpenseClaimHeader(head, access, generation, cipher, key)
    require(header.claimId == id)
    require(documents.size == EXPENSE_CACHE_MAXIMUM_CHUNKS + 1)
    val tag = expenseClaimTag(key, access, id)
    val bytes = ByteArray(header.size)
    try {
        for (index in 0 until EXPENSE_CACHE_MAXIMUM_CHUNKS) {
            currentCoroutineContext().ensureActive()
            val chunkId = "claim/$id/chunk/" + index.toString().padStart(2, '0')
            val document = documents.single { it.id == chunkId }
            require(
                document.version == header.claimVersion &&
                    document.sortKey == chunkId &&
                    document.tags == setOf(tag)
            )
            val payload = requireNotNull(document.payload)
            require(payload.length in 1..131_072)
            val chunk =
                cipher.decrypt(
                    key,
                    expenseReplicaAssociatedData(
                        access,
                        generation,
                        "$chunkId#${header.acquisitionId}",
                        document.version,
                        chunkId,
                        setOf(tag),
                    ),
                    Base64.decode(payload),
                )
            try {
                currentCoroutineContext().ensureActive()
                val offset = index * EXPENSE_CACHE_CHUNK_BYTES
                val expectedSize = (header.size - offset).coerceIn(0, EXPENSE_CACHE_CHUNK_BYTES)
                require(chunk.size == expectedSize)
                if (chunk.isNotEmpty()) chunk.copyInto(bytes, offset)
            } finally {
                chunk.fill(0)
            }
        }
        val record =
            Json.decodeFromString<ExpenseClaimRecordDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
                .toRecord(id, null)
        return header.toSnapshot(access, record)
    } finally {
        bytes.fill(0)
    }
}
