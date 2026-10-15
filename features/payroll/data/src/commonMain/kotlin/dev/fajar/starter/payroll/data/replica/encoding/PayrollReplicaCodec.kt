package dev.fajar.starter.payroll.data.replica.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.payroll.data.replica.dto.*
import dev.fajar.starter.payroll.data.replica.mappers.*
import dev.fajar.starter.payroll.domain.entities.PayrollAccess
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

suspend fun decodePayrollCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: PayrollAccess,
    raw: ReplicaCheckpoint,
): PayrollCheckpoint {
    val bytes =
        cipher.decrypt(
            key,
            payrollReplicaAssociatedData(access, raw.handle.generation, "checkpoint", raw.revision),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<PayrollCheckpointDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toCheckpoint(raw, access)
    } finally {
        bytes.fill(0)
    }
}

suspend fun encodePayrollCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: PayrollCheckpoint,
): String {
    require(checkpoint.recordVersion in 1..PayrollCheckpoint.CURRENT_RECORD_VERSION)
    val bytes = Json.encodeToString(checkpoint.toReplicaDto()).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                payrollReplicaAssociatedData(
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

suspend fun encodePayrollDocument(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: PayrollCheckpoint,
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
                payrollReplicaAssociatedData(
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

suspend fun decodePayrollDocument(
    cipher: ContentCipher,
    key: ByteArray,
    access: PayrollAccess,
    generation: String,
    document: ReplicaDocument,
): String {
    val bytes =
        cipher.decrypt(
            key,
            payrollReplicaAssociatedData(
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
