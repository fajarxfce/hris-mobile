package dev.fajar.starter.collaboration.data.notifications

import dev.fajar.starter.collaboration.data.history.COLLABORATION_HISTORY_PREFIX
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

internal const val NOTIFICATION_PUBLICATION_PARTITION =
    COLLABORATION_HISTORY_PREFIX + "notification-publications"

/**
 * One exact active index and per-conversation encrypted records, including compact inactive
 * watermarks.
 */
internal class CollaborationNotificationPublicationCodec(
    private val access: CollaborationRecoveryAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val indexKey: ByteArray,
) {
    val scope = recoveryScope(access)

    suspend fun id(companyId: String, conversationId: String): String {
        require(
            validCollaborationMessageId(companyId) && validCollaborationMessageId(conversationId)
        )
        val plain =
            Json.encodeToString(
                    listOf(
                        "hris-notification-publication-index-v1",
                        access.audience,
                        access.accountId,
                        companyId,
                        conversationId,
                    )
                )
                .encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            "p-" +
                CryptographyProvider.Default.get(HMAC)
                    .keyDecoder(SHA256)
                    .decodeFromByteArray(HMAC.Key.Format.RAW, indexKey)
                    .signatureGenerator()
                    .generateSignature(plain)
                    .toHexString()
        } finally {
            plain.fill(0)
        }
    }

    suspend fun checkpoint(raw: ReplicaCheckpoint): List<PublicationReference> {
        require(
            raw.published &&
                raw.handle.scope == scope &&
                raw.handle.partition == NOTIFICATION_PUBLICATION_PARTITION
        )
        val plain =
            open(raw.payload, raw.handle.generation, "checkpoint", raw.revision, "", 24_576, 16_384)
        return try {
            val value =
                Json.decodeFromString<PublicationCheckpointDto>(
                    plain.decodeToString(throwOnInvalidSequence = true)
                )
            require(value.schema == 1)
            references(raw.handle.generation, raw.revision, value.active)
            value.active.toList()
        } finally {
            plain.fill(0)
        }
    }

    suspend fun checkpoint(
        generation: String,
        revision: Long,
        active: List<PublicationReference>,
    ): String {
        references(generation, revision, active)
        return seal(
            Json.encodeToString(PublicationCheckpointDto(active))
                .encodeToByteArray(throwOnInvalidSequence = true),
            generation,
            "checkpoint",
            revision,
            "",
            24_576,
            16_384,
        )
    }

    suspend fun publication(
        raw: ReplicaCheckpoint,
        active: List<PublicationReference>,
        document: ReplicaDocument,
    ): CollaborationNotificationPublication {
        require(
            validCollaborationNotificationPublicationId(document.id) &&
                document.sortKey == document.id &&
                document.tags.isEmpty()
        )
        require(document.version in 0..raw.revision)
        val plain =
            open(
                requireNotNull(document.payload),
                raw.handle.generation,
                document.id,
                document.version,
                document.sortKey,
                49_152,
                32_768,
            )
        val body =
            try {
                Json.decodeFromString<PublicationBodyDto>(
                        plain.decodeToString(throwOnInvalidSequence = true)
                    )
                    .body()
            } finally {
                plain.fill(0)
            }
        val value =
            CollaborationNotificationPublication(
                document.id,
                CollaborationNotificationPublicationRevision(
                    raw.handle.generation,
                    document.version,
                ),
                body,
            )
        require(validCollaborationNotificationPublication(value))
        require(
            body.parent.audience == access.audience && body.parent.accountId == access.accountId
        )
        require(id(body.target.companyId, body.target.conversationId) == value.id)
        val storedReference = active.find { it.id == value.id }
        if (body.state.isPendingPublication()) require(storedReference == reference(value))
        else require(storedReference == null)
        return value
    }

    suspend fun publication(value: CollaborationNotificationPublication): ReplicaDocument {
        require(validCollaborationNotificationPublication(value))
        require(
            value.body.parent.audience == access.audience &&
                value.body.parent.accountId == access.accountId
        )
        require(id(value.body.target.companyId, value.body.target.conversationId) == value.id)
        val plain =
            Json.encodeToString(publicationDto(value.body))
                .encodeToByteArray(throwOnInvalidSequence = true)
        val payload =
            seal(
                plain,
                value.version.generation,
                value.id,
                value.version.revision,
                value.id,
                49_152,
                32_768,
            )
        return ReplicaDocument(value.id, value.version.revision, value.id, payload)
    }

    private fun references(generation: String, revision: Long, active: List<PublicationReference>) {
        require(
            validCollaborationMessageId(generation) && revision in 0 until 9_007_199_254_740_991L
        )
        require(
            active.size <= COLLABORATION_NOTIFICATION_PUBLICATION_CAPACITY &&
                active.map { it.id }.distinct().size == active.size
        )
        require(active.map { it.revision }.distinct().size == active.size)
        for (entry in active) {
            require(
                validCollaborationNotificationPublicationId(entry.id) &&
                    entry.revision in 0..revision
            )
            require(
                enumValueOf<CollaborationNotificationPublicationState>(entry.state)
                    .isPendingPublication()
            )
        }
    }

    private suspend fun open(
        payload: String,
        generation: String,
        id: String,
        revision: Long,
        order: String,
        encodedLimit: Int,
        plainLimit: Int,
    ): ByteArray {
        require(
            validCollaborationMessageId(generation) &&
                revision in 0 until 9_007_199_254_740_991L &&
                payload.length in 1..encodedLimit
        )
        val plain =
            cipher.decrypt(key, aad(generation, id, revision, order), Base64.decode(payload))
        if (plain.size !in 1..plainLimit) {
            plain.fill(0)
            error("Notification publication exceeds storage bounds")
        }
        return plain
    }

    private suspend fun seal(
        plain: ByteArray,
        generation: String,
        id: String,
        revision: Long,
        order: String,
        encodedLimit: Int,
        plainLimit: Int,
    ): String =
        try {
            require(plain.size in 1..plainLimit)
            Base64.encode(cipher.encrypt(key, aad(generation, id, revision, order), plain)).also {
                require(it.length <= encodedLimit)
            }
        } finally {
            plain.fill(0)
        }

    private fun aad(generation: String, id: String, revision: Long, order: String) =
        Json.encodeToString(
                listOf(
                    "hris-notification-publication-v1",
                    access.audience,
                    access.accountId,
                    NOTIFICATION_PUBLICATION_PARTITION,
                    generation,
                    id,
                    revision.toString(),
                    order,
                )
            )
            .encodeToByteArray()

    companion object {
        fun reference(value: CollaborationNotificationPublication) =
            PublicationReference(value.id, value.version.revision, value.body.state.name)
    }
}
