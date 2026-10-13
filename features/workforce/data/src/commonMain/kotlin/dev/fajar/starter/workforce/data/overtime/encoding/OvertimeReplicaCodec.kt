package dev.fajar.starter.workforce.data.overtime.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

suspend fun decodeOvertimeCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: WorkforceAccess,
    raw: ReplicaCheckpoint,
): OvertimeCheckpoint {
    val bytes =
        cipher.decrypt(
            key,
            overtimeReplicaAssociatedData(
                access,
                raw.handle.generation,
                "checkpoint",
                raw.revision,
            ),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<OvertimeCheckpointDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toCheckpoint(raw, access)
    } finally {
        bytes.fill(0)
    }
}

suspend fun encodeOvertimeCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: OvertimeCheckpoint,
): String {
    val bytes = Json.encodeToString(checkpoint.toReplicaDto()).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                overtimeReplicaAssociatedData(
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

suspend fun encodeOvertimeDocument(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: OvertimeCheckpoint,
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
                overtimeReplicaAssociatedData(
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

suspend fun decodeOvertimeDocument(
    cipher: ContentCipher,
    key: ByteArray,
    access: WorkforceAccess,
    generation: String,
    document: ReplicaDocument,
): String {
    val bytes =
        cipher.decrypt(
            key,
            overtimeReplicaAssociatedData(
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
