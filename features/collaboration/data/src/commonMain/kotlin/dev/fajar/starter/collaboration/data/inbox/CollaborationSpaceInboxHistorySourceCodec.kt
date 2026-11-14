package dev.fajar.starter.collaboration.data.inbox

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

internal const val SPACE_INBOX_HISTORY_REFERENCES = "pending"

internal fun spaceInboxHistorySourcePrefix(access: CollaborationSpaceLocalAccess): String {
    spaceHistoryScope(access)
    return COLLABORATION_HISTORY_PREFIX + "space-inbox-history-progress/v1/" + access.spaceId + "/"
}

internal fun spaceInboxHistorySourcePartition(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
): String {
    require(validCollaborationMessageId(conversationId))
    return spaceInboxHistorySourcePrefix(access) + conversationId
}

/**
 * Stable source identity and bounded pending records authenticated under the existing history key.
 */
internal class CollaborationSpaceInboxHistorySourceCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val conversationId: String,
    private val cipher: ContentCipher,
    private val contentKey: ByteArray,
    private val indexKey: ByteArray,
) {
    suspend fun checkpoint(raw: ReplicaCheckpoint): CollaborationSpaceInboxHistorySourceCheckpoint {
        require(raw.published && raw.handle.scope == spaceHistoryScope(access))
        require(raw.handle.partition == spaceInboxHistorySourcePartition(access, conversationId))
        require(validCollaborationMessageId(raw.handle.generation))
        require(raw.revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER)
        val dto =
            Json.decodeFromString<InboxHistorySourceDto>(
                open(raw.handle.generation, "checkpoint", raw.revision, "", raw.payload)
            )
        return CollaborationSpaceInboxHistorySourceCheckpoint(
                CollaborationSpaceInboxHistoryProgressVersion(raw.handle.generation, raw.revision),
                dto.domain(conversationId),
            )
            .also { require(validCollaborationSpaceInboxHistorySourceCheckpoint(access, it)) }
    }

    suspend fun checkpoint(value: CollaborationSpaceInboxHistorySourceCheckpoint): String {
        require(value.progress.conversationId == conversationId)
        require(validCollaborationSpaceInboxHistorySourceCheckpoint(access, value))
        return seal(
            value.version.generation,
            "checkpoint",
            value.version.revision,
            "",
            Json.encodeToString(InboxHistorySourceDto(value.progress)),
        )
    }

    suspend fun id(eventId: String): String {
        require(validCollaborationEventId(eventId))
        val aad = context("index", listOf(eventId))
        return try {
            "r-" +
                CryptographyProvider.Default.get(HMAC)
                    .keyDecoder(SHA256)
                    .decodeFromByteArray(HMAC.Key.Format.RAW, indexKey)
                    .signatureGenerator()
                    .generateSignature(aad)
                    .toHexString()
        } finally {
            aad.fill(0)
        }
    }

    suspend fun reference(
        checkpoint: CollaborationSpaceInboxHistorySourceCheckpoint,
        document: ReplicaDocument,
    ): CollaborationSpaceInboxHistoryRepairReference {
        require(document.version in 0..checkpoint.version.revision)
        val dto =
            Json.decodeFromString<InboxHistoryReferenceDto>(
                open(
                    checkpoint.version.generation,
                    document.id,
                    document.version,
                    document.sortKey,
                    requireNotNull(document.payload),
                )
            )
        require(dto.schema == 1 && dto.insertedAtRevision in 0..document.version)
        val expectedId = id(dto.eventId)
        require(
            document.id == expectedId &&
                document.sortKey == sort(dto.insertedAtRevision, expectedId)
        )
        require(document.tags == setOf(SPACE_INBOX_HISTORY_REFERENCES))
        return CollaborationSpaceInboxHistoryRepairReference(
            conversationId,
            checkpoint.progress.owner,
            dto.eventId,
            dto.insertedAtRevision,
        )
    }

    suspend fun reference(
        version: CollaborationSpaceInboxHistoryProgressVersion,
        eventId: String,
    ): ReplicaDocument {
        require(validCollaborationMessageId(version.generation))
        require(version.revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER)
        val id = id(eventId)
        val order = sort(version.revision, id)
        return ReplicaDocument(
            id,
            version.revision,
            order,
            seal(
                version.generation,
                id,
                version.revision,
                order,
                Json.encodeToString(InboxHistoryReferenceDto(1, eventId, version.revision)),
            ),
            setOf(SPACE_INBOX_HISTORY_REFERENCES),
        )
    }

    fun sort(revision: Long, id: String): String {
        require(revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER)
        require(id.matches(Regex("r-[0-9a-f]{64}")))
        return revision.toString().padStart(16, '0') + ":" + id
    }

    fun pendingAfter(value: String?, throughRevision: Long) {
        if (value == null) return
        require(value.matches(Regex("[0-9]{16}:r-[0-9a-f]{64}")))
        require(value.substringBefore(':').toLong() in 0..throughRevision)
    }

    suspend fun mutation(
        raw: ReplicaCheckpoint?,
        before: CollaborationSpaceInboxHistorySourceCheckpoint?,
        next: CollaborationSpaceInboxHistorySourceCheckpoint,
        existing: List<ReplicaDocument>,
        retained: List<String>,
        unresolved: List<String>,
    ): ReplicaMutation {
        val documents = documents(before, next.version, existing, retained, unresolved)
        val payload = checkpoint(next)
        return if (raw == null)
            ReplicaMutation.Replace(
                spaceInboxHistorySourcePartition(access, conversationId),
                null,
                ReplicaSnapshot(next.version.generation, payload, documents),
            )
        else ReplicaMutation.Commit(raw.handle, raw.revision, payload, documents, true)
    }

    private suspend fun documents(
        before: CollaborationSpaceInboxHistorySourceCheckpoint?,
        next: CollaborationSpaceInboxHistoryProgressVersion,
        existing: List<ReplicaDocument>,
        retained: List<String>,
        unresolved: List<String>,
    ): List<ReplicaDocument> {
        val requested = (retained + unresolved).associateBy { id(it) }
        require(
            existing.size <= requested.size &&
                existing.map { it.id }.distinct().size == existing.size
        )
        require(existing.all { it.id in requested })
        val rows = existing.associateBy { it.id }
        for (row in existing) {
            require(before != null && row.version in 0..before.version.revision)
            if (row.payload != null) require(reference(before, row).eventId == requested[row.id])
        }
        val mutations = mutableListOf<ReplicaDocument>()
        for (eventId in retained) {
            val row = rows[id(eventId)] ?: continue
            if (row.payload != null)
                mutations += row.copy(version = next.revision, payload = null, tags = emptySet())
        }
        for (eventId in unresolved) {
            if (rows[id(eventId)]?.payload == null) mutations += reference(next, eventId)
        }
        return mutations.toList()
    }

    private suspend fun seal(
        generation: String,
        id: String,
        revision: Long,
        sort: String,
        value: String,
    ): String {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        val aad = context("content", listOf(generation, id, revision.toString(), sort))
        return try {
            require(bytes.size in 1..16384)
            Base64.encode(cipher.encrypt(contentKey, aad, bytes)).also {
                require(it.length <= 32768)
            }
        } finally {
            bytes.fill(0)
            aad.fill(0)
        }
    }

    private suspend fun open(
        generation: String,
        id: String,
        revision: Long,
        sort: String,
        value: String,
    ): String {
        require(value.length in 1..32768)
        val aad = context("content", listOf(generation, id, revision.toString(), sort))
        val bytes =
            try {
                cipher.decrypt(contentKey, aad, Base64.decode(value))
            } finally {
                aad.fill(0)
            }
        return try {
            require(bytes.size in 1..16384)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }

    private fun context(purpose: String, parts: List<String>) =
        Json.encodeToString(
                listOf(
                    "hris-space-inbox-history-source-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    conversationId,
                    purpose,
                ) + parts
            )
            .encodeToByteArray()
}
