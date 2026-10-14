package dev.fajar.starter.people.data.replica.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.people.data.replica.dto.*
import dev.fajar.starter.people.data.replica.mappers.*
import dev.fajar.starter.people.domain.replica.entities.*
import dev.fajar.starter.people.domain.replica.entities.DirectoryAccess
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

suspend fun decodeDirectoryCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: DirectoryAccess,
    raw: ReplicaCheckpoint,
): DirectoryCheckpoint {
    val bytes =
        cipher.decrypt(
            key,
            directoryReplicaAssociatedData(
                access,
                raw.handle.generation,
                "checkpoint",
                raw.revision,
            ),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<DirectoryCheckpointDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toCheckpoint(raw, access)
    } finally {
        bytes.fill(0)
    }
}

suspend fun encodeDirectoryCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: DirectoryCheckpoint,
): String {
    val bytes = Json.encodeToString(checkpoint.toReplicaDto()).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                directoryReplicaAssociatedData(
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

suspend fun encodeDirectoryDocument(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: DirectoryCheckpoint,
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
                directoryReplicaAssociatedData(
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

suspend fun decodeDirectoryDocument(
    cipher: ContentCipher,
    key: ByteArray,
    access: DirectoryAccess,
    generation: String,
    document: ReplicaDocument,
): String {
    val bytes =
        cipher.decrypt(
            key,
            directoryReplicaAssociatedData(
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
