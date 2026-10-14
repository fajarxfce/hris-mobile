package dev.fajar.starter.workforce.data.replica.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.data.replica.dto.*
import dev.fajar.starter.workforce.data.replica.mappers.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.replica.entities.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

suspend fun decodeWorkforceCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: WorkforceAccess,
    raw: ReplicaCheckpoint,
): WorkforceCheckpoint {
    val bytes =
        cipher.decrypt(
            key,
            workforceReplicaAssociatedData(
                access,
                raw.handle.generation,
                "checkpoint",
                raw.revision,
            ),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<WorkforceCheckpointDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toCheckpoint(raw, access)
    } finally {
        bytes.fill(0)
    }
}

suspend fun encodeWorkforceCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: WorkforceCheckpoint,
): String {
    val bytes = Json.encodeToString(checkpoint.toReplicaDto()).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                workforceReplicaAssociatedData(
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

suspend fun encodeWorkforceDocument(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: WorkforceCheckpoint,
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
                workforceReplicaAssociatedData(
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

suspend fun decodeWorkforceDocument(
    cipher: ContentCipher,
    key: ByteArray,
    access: WorkforceAccess,
    generation: String,
    document: ReplicaDocument,
): String {
    val bytes =
        cipher.decrypt(
            key,
            workforceReplicaAssociatedData(
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
