package dev.fajar.starter.expenses.data.replica.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.replica.dto.*
import dev.fajar.starter.expenses.data.replica.mappers.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

suspend fun decodeExpenseCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: ExpenseAccess,
    raw: ReplicaCheckpoint,
): ExpenseCheckpoint {
    val bytes =
        cipher.decrypt(
            key,
            expenseReplicaAssociatedData(access, raw.handle.generation, "checkpoint", raw.revision),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<ExpenseCheckpointDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toCheckpoint(raw, access)
    } finally {
        bytes.fill(0)
    }
}

suspend fun encodeExpenseCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: ExpenseCheckpoint,
): String {
    val bytes = Json.encodeToString(checkpoint.toReplicaDto()).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                expenseReplicaAssociatedData(
                    checkpoint.access,
                    checkpoint.generation,
                    "checkpoint",
                    checkpoint.revision,
                ),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun encodeExpenseDocument(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: ExpenseCheckpoint,
    id: String,
    version: Long,
    sortKey: String,
    tags: Set<String>,
    json: String,
): ReplicaDocument {
    val bytes = json.encodeToByteArray()
    return try {
        require(bytes.size <= 96 * 1024 - 64)
        val encrypted =
            cipher.encrypt(
                key,
                expenseReplicaAssociatedData(
                    checkpoint.access,
                    checkpoint.generation,
                    id,
                    version,
                    sortKey,
                    tags,
                ),
                bytes,
            )
        ReplicaDocument(id, version, sortKey, Base64.encode(encrypted), tags)
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeExpenseDocument(
    cipher: ContentCipher,
    key: ByteArray,
    access: ExpenseAccess,
    generation: String,
    document: ReplicaDocument,
): String {
    val bytes =
        cipher.decrypt(
            key,
            expenseReplicaAssociatedData(
                access,
                generation,
                document.id,
                document.version,
                document.sortKey,
                document.tags,
            ),
            Base64.decode(requireNotNull(document.payload)),
        )
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } finally {
        bytes.fill(0)
    }
}
