package dev.fajar.starter.people.data.profile.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.people.data.profile.dto.*
import dev.fajar.starter.people.data.profile.mappers.*
import dev.fajar.starter.people.domain.profile.entities.*
import dev.fajar.starter.people.domain.profile.entities.SelfProfileAccess
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

suspend fun decodeSelfProfileCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: SelfProfileAccess,
    raw: ReplicaCheckpoint,
): SelfProfileCheckpoint {
    val bytes =
        cipher.decrypt(
            key,
            selfProfileReplicaAssociatedData(
                access,
                raw.handle.generation,
                "checkpoint",
                raw.revision,
            ),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<SelfProfileCheckpointDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toCheckpoint(raw, access)
    } finally {
        bytes.fill(0)
    }
}

suspend fun encodeSelfProfileCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: SelfProfileCheckpoint,
): String {
    val bytes = Json.encodeToString(checkpoint.toReplicaDto()).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                selfProfileReplicaAssociatedData(
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

suspend fun encodeSelfProfileDocument(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: SelfProfileCheckpoint,
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
                selfProfileReplicaAssociatedData(
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

suspend fun decodeSelfProfileDocument(
    cipher: ContentCipher,
    key: ByteArray,
    access: SelfProfileAccess,
    generation: String,
    document: ReplicaDocument,
): String {
    val bytes =
        cipher.decrypt(
            key,
            selfProfileReplicaAssociatedData(
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
