package dev.fajar.starter.expenses.data.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

const val EXPENSE_CACHE_MAXIMUM_BYTES = 1_048_576
const val EXPENSE_CACHE_CHUNK_BYTES = 65_536
const val EXPENSE_CACHE_MAXIMUM_CHUNKS = EXPENSE_CACHE_MAXIMUM_BYTES / EXPENSE_CACHE_CHUNK_BYTES

fun expensePartition(access: ExpenseAccess, view: String): String {
    require(view == "overview" || view == "details")
    return "expenses-$view/" + access.companyId
}

fun expenseAssociatedData(
    access: ExpenseAccess,
    generation: String,
    view: String,
    purpose: String,
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-expenses-v1",
                access.sessionId,
                access.accountId,
                access.audience,
                access.companyId,
                generation,
                view,
                purpose,
            )
        )
        .encodeToByteArray()

suspend fun encodeExpenseCache(
    bytes: ByteArray,
    access: ExpenseAccess,
    generation: String,
    view: String,
    cipher: ContentCipher,
    key: ByteArray,
): ReplicaSnapshot {
    require(bytes.size in 1..EXPENSE_CACHE_MAXIMUM_BYTES)
    val documents = mutableListOf<ReplicaDocument>()
    for (offset in bytes.indices step EXPENSE_CACHE_CHUNK_BYTES) {
        currentCoroutineContext().ensureActive()
        val id = "chunk/" + documents.size.toString().padStart(2, '0')
        val chunk = bytes.copyOfRange(offset, minOf(bytes.size, offset + EXPENSE_CACHE_CHUNK_BYTES))
        val content =
            try {
                cipher.encrypt(key, expenseAssociatedData(access, generation, view, id), chunk)
            } finally {
                chunk.fill(0)
            }
        currentCoroutineContext().ensureActive()
        documents += ReplicaDocument(id, 0, id, Base64.encode(content))
    }
    val marker = "v1:${bytes.size}".encodeToByteArray()
    val header =
        try {
            cipher.encrypt(
                key,
                expenseAssociatedData(access, generation, view, "checkpoint"),
                marker,
            )
        } finally {
            marker.fill(0)
        }
    currentCoroutineContext().ensureActive()
    return ReplicaSnapshot(generation, Base64.encode(header), documents.toList())
}

/** The repository owns and clears the returned plaintext even when DTO mapping fails. */
suspend fun decodeExpenseCache(
    page: ReplicaPage,
    access: ExpenseAccess,
    view: String,
    cipher: ContentCipher,
    key: ByteArray,
): ByteArray {
    val checkpoint = requireNotNull(page.checkpoint)
    require(checkpoint.published && checkpoint.revision == 0L && checkpoint.payload.length <= 4096)
    require(
        checkpoint.handle.sessionId == access.sessionId &&
            checkpoint.handle.partition == expensePartition(access, view)
    )
    require(page.documents.size in 1..EXPENSE_CACHE_MAXIMUM_CHUNKS)
    val generation = checkpoint.handle.generation
    val header =
        cipher.decrypt(
            key,
            expenseAssociatedData(access, generation, view, "checkpoint"),
            Base64.decode(checkpoint.payload),
        )
    val size =
        try {
            val marker = header.decodeToString(throwOnInvalidSequence = true)
            val length = marker.substringAfter("v1:", "").toInt()
            require(marker == "v1:$length" && length in 1..EXPENSE_CACHE_MAXIMUM_BYTES)
            length
        } finally {
            header.fill(0)
        }
    currentCoroutineContext().ensureActive()
    require(
        page.documents.size == (size + EXPENSE_CACHE_CHUNK_BYTES - 1) / EXPENSE_CACHE_CHUNK_BYTES
    )
    val bytes = ByteArray(size)
    var complete = false
    try {
        for ((index, document) in page.documents.withIndex()) {
            currentCoroutineContext().ensureActive()
            val id = "chunk/" + index.toString().padStart(2, '0')
            require(document.id == id && document.sortKey == id && document.version == 0L)
            val payload = requireNotNull(document.payload)
            require(payload.length in 1..131_072)
            val chunk =
                cipher.decrypt(
                    key,
                    expenseAssociatedData(access, generation, view, id),
                    Base64.decode(payload),
                )
            try {
                currentCoroutineContext().ensureActive()
                val offset = index * EXPENSE_CACHE_CHUNK_BYTES
                require(chunk.size == minOf(EXPENSE_CACHE_CHUNK_BYTES, size - offset))
                chunk.copyInto(bytes, offset)
            } finally {
                chunk.fill(0)
            }
        }
        complete = true
        return bytes
    } finally {
        if (!complete) bytes.fill(0)
    }
}
