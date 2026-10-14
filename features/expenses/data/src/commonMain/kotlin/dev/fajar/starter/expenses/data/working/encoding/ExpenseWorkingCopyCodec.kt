package dev.fajar.starter.expenses.data.working.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.replica.mappers.expenseReplicaScope
import dev.fajar.starter.expenses.data.working.dto.*
import dev.fajar.starter.expenses.data.working.mappers.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

private const val chunkBytes = 64 * 1024
private const val maximumChunks = 4

suspend fun encodeExpenseWorkingCopy(
    access: ExpenseAccess,
    slot: ExpenseWorkingSlot,
    copy: ExpenseWorkingCopy,
    cipher: ContentCipher,
    key: ByteArray,
): ReplicaSnapshot {
    val dto = copy.toWorkingDto()
    dto.toWorkingCopy(slot)
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    try {
        require(bytes.size in 1..maximumChunks * chunkBytes)
        val header =
            Json.encodeToString(ExpenseWorkingCopyHeaderDto(copy.claimId, bytes.size))
                .encodeToByteArray()
        val checkpoint =
            try {
                Base64.encode(
                    cipher.encrypt(
                        key,
                        expenseWorkingAssociatedData(access, slot, copy.revision, "header"),
                        header,
                    )
                )
            } finally {
                header.fill(0)
            }
        val documents = mutableListOf<ReplicaDocument>()
        for (index in 0 until maximumChunks) {
            currentCoroutineContext().ensureActive()
            val id = "chunk/$index"
            val offset = index * chunkBytes
            val chunk =
                if (offset >= bytes.size) ByteArray(0)
                else bytes.copyOfRange(offset, minOf(bytes.size, offset + chunkBytes))
            val encrypted =
                try {
                    cipher.encrypt(
                        key,
                        expenseWorkingAssociatedData(access, slot, copy.revision, id),
                        chunk,
                    )
                } finally {
                    chunk.fill(0)
                }
            documents += ReplicaDocument(id, 0, id, Base64.encode(encrypted))
        }
        return ReplicaSnapshot(copy.revision, checkpoint, documents.toList())
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeExpenseWorkingCopy(
    access: ExpenseAccess,
    slot: ExpenseWorkingSlot,
    page: ReplicaPage,
    cipher: ContentCipher,
    key: ByteArray,
): ExpenseWorkingCopy? {
    val checkpoint =
        page.checkpoint
            ?: run {
                require(page.documents.isEmpty())
                return null
            }
    require(checkpoint.published && checkpoint.revision == 0L)
    require(checkpoint.handle.scope == expenseReplicaScope(access))
    require(checkpoint.handle.partition == expenseWorkingPartition(access, slot))
    require(checkpoint.payload.length in 1..4096 && page.documents.size == maximumChunks)
    val revision = checkpoint.handle.generation
    val headerBytes =
        cipher.decrypt(
            key,
            expenseWorkingAssociatedData(access, slot, revision, "header"),
            Base64.decode(checkpoint.payload),
        )
    val header =
        try {
            Json.decodeFromString<ExpenseWorkingCopyHeaderDto>(
                headerBytes.decodeToString(throwOnInvalidSequence = true)
            )
        } finally {
            headerBytes.fill(0)
        }
    require(header.schema == 1 && header.size in 1..maximumChunks * chunkBytes)
    val bytes = ByteArray(header.size)
    try {
        for (index in 0 until maximumChunks) {
            currentCoroutineContext().ensureActive()
            val id = "chunk/$index"
            val document = page.documents.single { it.id == id }
            require(document.version == 0L && document.sortKey == id && document.tags.isEmpty())
            val payload = requireNotNull(document.payload)
            require(payload.length in 1..131_072)
            val chunk =
                cipher.decrypt(
                    key,
                    expenseWorkingAssociatedData(access, slot, revision, id),
                    Base64.decode(payload),
                )
            try {
                currentCoroutineContext().ensureActive()
                val offset = index * chunkBytes
                require(chunk.size == (header.size - offset).coerceIn(0, chunkBytes))
                if (chunk.isNotEmpty()) chunk.copyInto(bytes, offset)
            } finally {
                chunk.fill(0)
            }
        }
        return Json.decodeFromString<ExpenseWorkingCopyDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toWorkingCopy(slot)
            .also { require(it.revision == revision && it.claimId == header.claimId) }
    } finally {
        bytes.fill(0)
    }
}
