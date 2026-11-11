package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val HISTORY_REFERENCES = "references"
internal val historyReferenceId = Regex("r-[0-9a-f]{64}")
internal val historyReferenceCursor = Regex("[0-9]{16}/r-[0-9a-f]{64}")

internal fun traversalPartition(
    access: CollaborationLocalAccess,
    traversal: CollaborationHistoryTraversal,
): String =
    CollaborationHistoryCoordinates.Company(access, traversal.conversationId)
        .traversalPartition(traversal)

internal fun traversalPartition(
    access: CollaborationSpaceLocalAccess,
    traversal: CollaborationHistoryTraversal,
): String =
    CollaborationHistoryCoordinates.Space(access, traversal.conversationId)
        .traversalPartition(traversal)

@Serializable
private data class HistoryProgressDto(
    val roomId: String,
    val next: String?,
    val references: Long,
    val schema: Int = 1,
    val incoming: IncomingHistoryPositionDto? = null,
    val provider: HistoryProviderIdentityDto? = null,
    val recovery: HistoryRecoveryPositionDto? = null,
)

@Serializable
private data class HistoryProviderIdentityDto(val companyId: String, val deviceId: String)

@Serializable
private data class IncomingHistoryPositionDto(
    val head: String,
    val until: String?,
    val pendingAfter: String? = null,
    val pendingThrough: Long? = null,
    val pendingRetryRequired: Boolean = false,
)

@Serializable
private data class HistoryRecoveryPositionDto(
    val generation: String,
    val through: Long,
    val after: String? = null,
    val complete: Boolean = false,
    val unresolved: Boolean = false,
    val requestedGeneration: String? = null,
)

@Serializable
private data class HistoryReferenceDto(
    val sequence: Long,
    val eventId: String,
    val sender: String,
    val timestamp: Long,
    val type: String,
    val resolved: Boolean = false,
)

/** Device/room-bound AEAD with separate keyed indexes; no provider IDs or cursors in plaintext. */
internal class CollaborationHistoryTraversalCodec(
    private val coordinates: CollaborationHistoryCoordinates,
    private val traversal: CollaborationHistoryTraversal,
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val indexKey: ByteArray,
) {
    constructor(
        access: CollaborationLocalAccess,
        traversal: CollaborationHistoryTraversal,
        cipher: ContentCipher,
        key: ByteArray,
        indexKey: ByteArray,
    ) : this(
        CollaborationHistoryCoordinates.Company(access, traversal.conversationId),
        traversal,
        cipher,
        key,
        indexKey,
    )

    init {
        coordinates.traversalPartition(traversal)
    }

    suspend fun progress(raw: ReplicaCheckpoint): CollaborationHistoryProgress {
        require(raw.published && raw.handle.scope == coordinates.scope)
        require(raw.handle.partition == coordinates.traversalPartition(traversal))
        require(
            validCollaborationMessageId(raw.handle.generation) &&
                raw.revision in 0 until 9_007_199_254_740_991L
        )
        val value =
            Json.decodeFromString<HistoryProgressDto>(
                open(raw.handle.generation, "checkpoint", raw.revision, "", raw.payload)
            )
        require(value.roomId == traversal.roomId)
        if (coordinates is CollaborationHistoryCoordinates.Space) {
            require(value.schema in 3..4 && value.provider != null)
        } else {
            require(value.schema in setOf(1, 2, 4) && value.provider == null)
            require((value.schema == 1) == (value.incoming == null))
        }
        require((value.schema == 4) == (value.recovery != null))
        require(traversal.purpose == CollaborationHistoryPurpose.Incoming || value.incoming == null)
        require(value.recovery?.after?.let(historyReferenceCursor::matches) != false)
        require(
            value.recovery?.let {
                val after = it.after
                after == null || after.substringBefore('/').toLong() < it.through
            } != false
        )
        require(value.incoming?.pendingAfter?.let(historyReferenceCursor::matches) != false)
        require(value.references in 0..9_007_199_254_740_991L)
        return CollaborationHistoryProgress(
                raw.handle.generation,
                raw.revision,
                value.next?.let { position(it, value.schema) },
                value.references,
                value.incoming?.let {
                    CollaborationIncomingHistoryPosition(
                        position(it.head, value.schema),
                        it.until?.let { until -> position(until, value.schema) },
                        it.pendingAfter,
                        it.pendingThrough,
                        it.pendingRetryRequired,
                    )
                },
                value.provider?.let {
                    CollaborationHistoryProviderIdentity(it.companyId, it.deviceId)
                },
                value.recovery?.let {
                    CollaborationHistoryRecoveryPosition(
                        it.generation,
                        it.through,
                        it.after,
                        it.complete,
                        it.unresolved,
                        it.requestedGeneration,
                    )
                },
            )
            .also { require(validCollaborationHistoryProgress(it)) }
    }

    suspend fun checkpoint(progress: CollaborationHistoryProgress): String {
        require(validCollaborationHistoryProgress(progress))
        val space = coordinates is CollaborationHistoryCoordinates.Space
        require(space == (progress.provider != null))
        val schema =
            if (progress.recovery != null) 4
            else if (space) 3 else if (progress.incoming == null) 1 else 2
        require(progress.recovery?.after?.let(historyReferenceCursor::matches) != false)
        require(
            progress.recovery?.let {
                val after = it.after
                after == null || after.substringBefore('/').toLong() < it.through
            } != false
        )
        require(
            traversal.purpose == CollaborationHistoryPurpose.Incoming || progress.incoming == null
        )
        require(progress.incoming?.pendingAfter?.let(historyReferenceCursor::matches) != false)
        val next = progress.next
        require(
            validCollaborationMessageId(progress.generation) &&
                progress.revision in 0 until 9_007_199_254_740_991L
        )
        require(
            progress.references in 0..9_007_199_254_740_991L &&
                (next == null || validCollaborationProviderPosition(next))
        )
        return seal(
            progress.generation,
            "checkpoint",
            progress.revision,
            "",
            Json.encodeToString(
                HistoryProgressDto(
                    traversal.roomId,
                    progress.next?.let { if (schema == 1) it else encodedPosition(it) },
                    progress.references,
                    schema = schema,
                    incoming =
                        progress.incoming?.let {
                            IncomingHistoryPositionDto(
                                encodedPosition(it.head),
                                it.until?.let(::encodedPosition),
                                it.pendingAfter,
                                it.pendingThrough,
                                it.pendingRetryRequired,
                            )
                        },
                    provider =
                        progress.provider?.let {
                            HistoryProviderIdentityDto(it.companyId, it.deviceId)
                        },
                    recovery =
                        progress.recovery?.let {
                            HistoryRecoveryPositionDto(
                                it.generation,
                                it.through,
                                it.after,
                                it.complete,
                                it.unresolved,
                                it.requestedGeneration,
                            )
                        },
                )
            ),
        )
    }

    suspend fun id(eventId: String): String {
        require(validCollaborationEventId(eventId))
        val bytes =
            Json.encodeToString(context() + listOf("reference-index", eventId)).encodeToByteArray()
        return try {
            "r-" +
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

    suspend fun reference(
        checkpoint: ReplicaCheckpoint,
        raw: ReplicaDocument,
    ): CollaborationHistoryReference {
        require(raw.version in 0..checkpoint.revision)
        require(historyReferenceId.matches(raw.id) && historyReferenceCursor.matches(raw.sortKey))
        val value =
            Json.decodeFromString<HistoryReferenceDto>(
                open(
                    checkpoint.handle.generation,
                    raw.id,
                    raw.version,
                    raw.sortKey,
                    requireNotNull(raw.payload),
                )
            )
        val event =
            CollaborationProviderHistoryEvent(
                value.eventId,
                value.sender,
                value.timestamp,
                value.type,
            )
        require(raw.tags == if (value.resolved) emptySet<String>() else setOf(HISTORY_REFERENCES))
        require(
            validCollaborationProviderEvent(event) &&
                value.sequence in 0 until 9_007_199_254_740_991L
        )
        require(raw.id == id(value.eventId) && raw.sortKey == sort(value.sequence, raw.id))
        return CollaborationHistoryReference(value.sequence, event, value.resolved)
    }

    suspend fun reference(
        progress: CollaborationHistoryProgress,
        value: CollaborationHistoryReference,
    ): ReplicaDocument {
        require(
            validCollaborationProviderEvent(value.event) &&
                value.sequence in 0 until progress.references
        )
        val id = id(value.event.eventId)
        val sort = sort(value.sequence, id)
        val event = value.event
        return ReplicaDocument(
            id,
            progress.revision,
            sort,
            seal(
                progress.generation,
                id,
                progress.revision,
                sort,
                Json.encodeToString(
                    HistoryReferenceDto(
                        value.sequence,
                        event.eventId,
                        event.sender,
                        event.timestamp,
                        event.type,
                        value.resolved,
                    )
                ),
            ),
            if (value.resolved) emptySet() else setOf(HISTORY_REFERENCES),
        )
    }

    private fun sort(sequence: Long, id: String) = sequence.toString().padStart(16, '0') + "/" + id

    private fun context() = coordinates.traversalFrame(traversal)

    private fun aad(generation: String, id: String, revision: Long, sort: String) =
        Json.encodeToString(context() + listOf(generation, id, revision.toString(), sort))
            .encodeToByteArray()

    private suspend fun seal(
        generation: String,
        id: String,
        revision: Long,
        sort: String,
        value: String,
    ): String {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(bytes.size <= payloadLimit(id))
            Base64.encode(cipher.encrypt(key, aad(generation, id, revision, sort), bytes)).also {
                require(it.length <= 32_768)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(
        generation: String,
        id: String,
        revision: Long,
        sort: String,
        value: String,
    ): String {
        require(value.length in 1..32_768)
        val bytes = cipher.decrypt(key, aad(generation, id, revision, sort), Base64.decode(value))
        return try {
            require(bytes.size <= payloadLimit(id))
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }

    private fun payloadLimit(id: String) =
        if (id == "checkpoint" && traversal.purpose == CollaborationHistoryPurpose.Incoming) 24_000
        else 16_384

    // Canonical ASCII encoding bounds JSON expansion without weakening the replica envelope cap.
    private fun encodedPosition(value: String): String {
        require(validCollaborationProviderPosition(value))
        return Base64.encode(value.encodeToByteArray())
    }

    private fun position(value: String, schema: Int): String {
        if (schema == 1) return value.also { require(validCollaborationProviderPosition(it)) }
        require(value.length in 1..5464)
        val decoded = Base64.decode(value).decodeToString(throwOnInvalidSequence = true)
        require(validCollaborationProviderPosition(decoded) && encodedPosition(decoded) == value)
        return decoded
    }
}
