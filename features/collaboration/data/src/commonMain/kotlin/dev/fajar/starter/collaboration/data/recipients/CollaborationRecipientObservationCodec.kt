package dev.fajar.starter.collaboration.data.recipients

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun recipientObservationPartition(access: CollaborationLocalAccess): String {
    require(validCollaborationMessageId(access.companyId))
    return COLLABORATION_HISTORY_PREFIX + "recipient-observation/" + access.companyId
}

internal class CollaborationRecipientObservationCodec(
    private val access: CollaborationLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun checkpoint(raw: ReplicaCheckpoint) {
        require(raw.published && raw.handle.scope == historyScope(access))
        require(raw.handle.partition == recipientObservationPartition(access))
        require(
            raw.revision in 0 until 9_007_199_254_740_991L &&
                validCollaborationMessageId(raw.handle.generation)
        )
        require(
            open(raw.handle.generation, "checkpoint", raw.revision, raw.payload) ==
                "recipient-observation-v1"
        )
    }

    suspend fun checkpoint(generation: String, revision: Long) =
        seal(generation, "checkpoint", revision, "recipient-observation-v1")

    suspend fun decode(
        checkpoint: ReplicaCheckpoint,
        document: ReplicaDocument,
    ): CollaborationRecipientObservation {
        require(
            validCollaborationMessageId(document.id) &&
                document.sortKey == document.id &&
                document.tags.isEmpty()
        )
        require(document.version in 0..checkpoint.revision)
        val dto =
            Json.decodeFromString<RecipientObservationDto>(
                open(
                    checkpoint.handle.generation,
                    document.id,
                    document.version,
                    requireNotNull(document.payload),
                )
            )
        require(dto.schema == 1)
        return CollaborationRecipientObservation(document.id, dto.revision, dto.ready).also {
            require(validCollaborationRecipientObservation(it))
        }
    }

    suspend fun encode(
        generation: String,
        revision: Long,
        value: CollaborationRecipientObservation,
    ): ReplicaDocument {
        require(validCollaborationRecipientObservation(value))
        val payload =
            Json.encodeToString(
                RecipientObservationDto(revision = value.revision, ready = value.ready)
            )
        return ReplicaDocument(
            value.conversationId,
            revision,
            value.conversationId,
            seal(generation, value.conversationId, revision, payload),
        )
    }

    private fun aad(generation: String, kind: String, revision: Long) =
        Json.encodeToString(
                listOf(
                    "hris-recipient-observation-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    recipientObservationPartition(access),
                    generation,
                    kind,
                    revision.toString(),
                )
            )
            .encodeToByteArray()

    private suspend fun seal(
        generation: String,
        kind: String,
        revision: Long,
        value: String,
    ): String {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size <= 1024)
            Base64.encode(cipher.encrypt(key, aad(generation, kind, revision), bytes)).also {
                require(it.length <= 2048)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(
        generation: String,
        kind: String,
        revision: Long,
        payload: String,
    ): String {
        require(payload.length in 1..2048)
        val bytes = cipher.decrypt(key, aad(generation, kind, revision), Base64.decode(payload))
        return try {
            require(bytes.size <= 1024)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }
}

@Serializable
private data class RecipientObservationDto(
    val schema: Int = 1,
    val revision: String,
    val ready: Boolean,
) {
    override fun toString() = "RecipientObservationDto([redacted])"
}
