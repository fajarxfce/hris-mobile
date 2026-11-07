package dev.fajar.starter.collaboration.data.identity

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// This entire namespace is covered by CollaborationHistoryKeys' retained-record inventory.
internal fun spaceConversationMetadataPartition(access: CollaborationSpaceLocalAccess): String {
    spaceConversationMetadataScope(access)
    return COLLABORATION_HISTORY_PREFIX + "space-conversations/v1/" + access.spaceId
}

internal fun spaceConversationMetadataScope(access: CollaborationSpaceLocalAccess): ReplicaScope {
    require(validCollaborationSpaceConversationId(access.accountId))
    require(validCollaborationSpaceConversationId(access.spaceId))
    return spaceAccountScope(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    )
}

internal class CollaborationSpaceConversationMetadataCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val indexKey: ByteArray,
) {
    suspend fun lookupId(kind: String, id: String): String {
        require(kind in listOf("conversation", "peer") && validCollaborationSpaceConversationId(id))
        val bytes = context("index", listOf(kind, id))
        return try {
            "m-" +
                CryptographyProvider.Default.get(HMAC)
                    .keyDecoder(SHA256)
                    .decodeFromByteArray(HMAC.Key.Format.RAW, indexKey)
                    .signatureGenerator()
                    .generateSignature(bytes)
                    .toHexString()
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun ids(value: CollaborationSpaceConversationMetadata): List<String> {
        require(validCollaborationSpaceConversationMetadata(access, value))
        val peer =
            if (value.firstAccountId == access.accountId) value.secondAccountId
            else value.firstAccountId
        return listOf(lookupId("conversation", value.id), lookupId("peer", peer))
    }

    suspend fun checkpoint(generation: String, revision: Long): String {
        require(
            validCollaborationSpaceConversationId(generation) &&
                revision in 0..9_007_199_254_740_991L
        )
        return seal(generation, "checkpoint", revision, Json.encodeToString(MetadataCheckpoint(1)))
    }

    suspend fun validate(raw: ReplicaCheckpoint) {
        require(raw.published && raw.handle.scope == spaceConversationMetadataScope(access))
        require(raw.handle.partition == spaceConversationMetadataPartition(access))
        require(validCollaborationSpaceConversationId(raw.handle.generation))
        require(raw.revision in 0..9_007_199_254_740_991L)
        require(
            Json.decodeFromString<MetadataCheckpoint>(
                    open(raw.handle.generation, "checkpoint", raw.revision, raw.payload)
                )
                .schema == 1
        )
    }

    suspend fun documents(
        generation: String,
        revision: Long,
        value: CollaborationSpaceConversationMetadata,
    ): List<ReplicaDocument> {
        val payload =
            Json.encodeToString(
                MetadataRecord(
                    1,
                    value.spaceId,
                    value.id,
                    value.firstAccountId,
                    value.secondAccountId,
                    value.createdBy,
                    value.createdAt.toString(),
                    value.version,
                )
            )
        return ids(value).map { id ->
            ReplicaDocument(id, revision, id, seal(generation, id, revision, payload), setOf(id))
        }
    }

    suspend fun decode(
        checkpoint: ReplicaCheckpoint,
        raw: ReplicaDocument,
    ): CollaborationSpaceConversationMetadata {
        require(
            raw.version in 0..checkpoint.revision &&
                raw.sortKey == raw.id &&
                raw.tags == setOf(raw.id)
        )
        val value =
            Json.decodeFromString<MetadataRecord>(
                open(checkpoint.handle.generation, raw.id, raw.version, requireNotNull(raw.payload))
            )
        require(value.schema == 1)
        val metadata =
            CollaborationSpaceConversationMetadata(
                value.spaceId,
                value.id,
                value.firstAccountId,
                value.secondAccountId,
                value.createdBy,
                Instant.parse(value.createdAt),
                value.version,
            )
        require(raw.id in ids(metadata))
        return metadata
    }

    /** Both indexes must name the same immutable fact and original insertion revision. */
    suspend fun pair(
        checkpoint: ReplicaCheckpoint,
        documents: List<ReplicaDocument>,
    ): CollaborationSpaceConversationMetadata {
        require(
            documents.size == 2 &&
                documents[0].id != documents[1].id &&
                documents[0].version == documents[1].version
        )
        val first = decode(checkpoint, documents[0])
        require(
            decode(checkpoint, documents[1]) == first &&
                documents.map { it.id }.toSet() == ids(first).toSet()
        )
        return first
    }

    private suspend fun seal(
        generation: String,
        id: String,
        revision: Long,
        value: String,
    ): String {
        val bytes = value.encodeToByteArray()
        return try {
            require(bytes.size in 1..2048)
            Base64.encode(
                    cipher.encrypt(
                        key,
                        context("content", listOf(generation, id, revision.toString())),
                        bytes,
                    )
                )
                .also { require(it.length <= 4096) }
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
        require(value.length in 1..4096)
        val bytes =
            cipher.decrypt(
                key,
                context("content", listOf(generation, id, revision.toString())),
                Base64.decode(value),
            )
        return try {
            require(bytes.size in 1..2048)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }

    private fun context(purpose: String, parts: List<String>) =
        Json.encodeToString(
                listOf(
                    "hris-space-conversation-metadata-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    purpose,
                ) + parts
            )
            .encodeToByteArray()
}

@Serializable private data class MetadataCheckpoint(val schema: Int)

@Serializable
private data class MetadataRecord(
    val schema: Int,
    val spaceId: String,
    val id: String,
    val firstAccountId: String,
    val secondAccountId: String,
    val createdBy: String,
    val createdAt: String,
    val version: Long,
)
