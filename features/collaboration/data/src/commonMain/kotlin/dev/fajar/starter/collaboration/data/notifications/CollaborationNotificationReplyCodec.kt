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
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val NOTIFICATION_REPLY_PARTITION =
    COLLABORATION_HISTORY_PREFIX + "notification-replies"

@Serializable
internal data class NotificationReplyReference(
    val id: String,
    val revision: Long,
    val sequence: Long,
    val review: String?,
)

@Serializable
private data class NotificationReplyCheckpoint(
    val active: List<NotificationReplyReference>,
    val schema: Int = 1,
)

@Serializable
private data class NotificationReplyDto(
    val sessionId: String,
    val audience: String,
    val accountId: String,
    val nativeSessionId: String,
    val action: String,
    val token: String,
    val text: String,
    val review: String?,
    val schema: Int = 1,
    val draft: String? = null,
    val resolution: NotificationReplyResolutionDto? = null,
)

@Serializable
private data class NotificationReplyResolutionDto(
    val sessionId: String,
    val nativeSessionId: String,
    val operationId: String,
    val createdAtMillis: Long,
    val text: String,
)

// Leave room for authenticated-encryption framing within the existing replica document limit.
private const val REPLY_INPUT_ENCODED_LIMIT = 131_072
private const val REPLY_INPUT_PLAIN_LIMIT = 98_200

/** The encrypted checkpoint authenticates every active document identity and exact revision. */
internal class CollaborationNotificationReplyCodec(
    private val access: CollaborationRecoveryAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val indexKey: ByteArray,
) {
    val scope = recoveryScope(access)

    suspend fun id(candidate: CollaborationNotificationReplyCandidate): String {
        require(validCollaborationNotificationReplyCandidate(candidate))
        val action = encodeNotificationAction(candidate.action)
        val plain =
            try {
                Json.encodeToString(
                        listOf(
                            "hris-notification-reply-index-v1",
                            candidate.parent.audience,
                            candidate.parent.accountId,
                            candidate.parent.sessionId,
                            candidate.parent.nativeSessionId,
                            Base64.encode(action),
                            candidate.text,
                        )
                    )
                    .encodeToByteArray(throwOnInvalidSequence = true)
            } finally {
                action.fill(0)
            }
        return try {
            "n-" +
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

    suspend fun checkpoint(raw: ReplicaCheckpoint): List<NotificationReplyReference> {
        require(
            raw.published &&
                raw.handle.scope == scope &&
                raw.handle.partition == NOTIFICATION_REPLY_PARTITION
        )
        val plain =
            open(raw.payload, raw.handle.generation, "checkpoint", raw.revision, "", 32768, 24576)
        return try {
            val dto =
                Json.decodeFromString<NotificationReplyCheckpoint>(
                    plain.decodeToString(throwOnInvalidSequence = true)
                )
            require(dto.schema == 1)
            validateReferences(raw.handle.generation, raw.revision, dto.active)
            dto.active.toList()
        } finally {
            plain.fill(0)
        }
    }

    suspend fun checkpoint(
        generation: String,
        revision: Long,
        active: List<NotificationReplyReference>,
    ): String {
        validateReferences(generation, revision, active)
        val plain =
            Json.encodeToString(NotificationReplyCheckpoint(active))
                .encodeToByteArray(throwOnInvalidSequence = true)
        return seal(plain, generation, "checkpoint", revision, "", 32768, 24576)
    }

    suspend fun input(
        raw: ReplicaCheckpoint,
        reference: NotificationReplyReference,
        document: ReplicaDocument,
    ): CollaborationNotificationReplyInput {
        require(
            document.id == reference.id &&
                document.version == reference.revision &&
                document.sortKey == sort(reference) &&
                document.tags.isEmpty()
        )
        val plain =
            open(
                requireNotNull(document.payload),
                raw.handle.generation,
                document.id,
                document.version,
                document.sortKey,
                REPLY_INPUT_ENCODED_LIMIT,
                REPLY_INPUT_PLAIN_LIMIT,
            )
        val input =
            try {
                val dto =
                    Json.decodeFromString<NotificationReplyDto>(
                        plain.decodeToString(throwOnInvalidSequence = true)
                    )
                require(
                    dto.schema in 1..2 &&
                        dto.review == reference.review &&
                        dto.action.length in 1..4096
                )
                require(
                    dto.schema != 1 ||
                        (dto.draft == null && dto.resolution == null && plain.size <= 65536)
                )
                val actionBytes = Base64.decode(dto.action)
                val action =
                    try {
                        require(actionBytes.size <= 3072)
                        decodeNotificationAction(actionBytes)
                            as? CollaborationNotificationAction.Reply
                            ?: error("Reply input requires reply metadata")
                    } finally {
                        actionBytes.fill(0)
                    }
                val candidate =
                    CollaborationNotificationReplyCandidate(
                        CollaborationNativeParent(
                            dto.sessionId,
                            dto.audience,
                            dto.accountId,
                            dto.nativeSessionId,
                        ),
                        action,
                        CollaborationNotificationActionToken(dto.token),
                        dto.text,
                    )
                CollaborationNotificationReplyInput(
                    document.id,
                    CollaborationNotificationReplyRevision(raw.handle.generation, document.version),
                    reference.sequence,
                    candidate,
                    dto.review?.let { enumValueOf<CollaborationNotificationReplyReview>(it) },
                    dto.draft,
                    dto.resolution?.let {
                        CollaborationNotificationReplyResolution(
                            candidate.parent.copy(
                                sessionId = it.sessionId,
                                nativeSessionId = it.nativeSessionId,
                            ),
                            it.operationId,
                            Instant.fromEpochMilliseconds(it.createdAtMillis),
                            it.text,
                        )
                    },
                )
            } finally {
                plain.fill(0)
            }
        require(validCollaborationNotificationReplyInput(input))
        require(
            input.candidate.parent.audience == access.audience &&
                input.candidate.parent.accountId == access.accountId
        )
        require(id(input.candidate) == input.id)
        return input
    }

    suspend fun input(value: CollaborationNotificationReplyInput): ReplicaDocument {
        require(validCollaborationNotificationReplyInput(value) && id(value.candidate) == value.id)
        require(
            value.candidate.parent.audience == access.audience &&
                value.candidate.parent.accountId == access.accountId
        )
        val candidate = value.candidate
        val actionBytes = encodeNotificationAction(candidate.action)
        val dto =
            try {
                NotificationReplyDto(
                    candidate.parent.sessionId,
                    candidate.parent.audience,
                    candidate.parent.accountId,
                    candidate.parent.nativeSessionId,
                    Base64.encode(actionBytes),
                    candidate.token.encoded,
                    candidate.text,
                    value.review?.name,
                    schema = 2,
                    draft = value.draft,
                    resolution =
                        value.resolution?.let {
                            NotificationReplyResolutionDto(
                                it.parent.sessionId,
                                it.parent.nativeSessionId,
                                it.operationId,
                                it.createdAt.toEpochMilliseconds(),
                                it.text,
                            )
                        },
                )
            } finally {
                actionBytes.fill(0)
            }
        val order = sort(reference(value))
        val payload =
            seal(
                Json.encodeToString(dto).encodeToByteArray(throwOnInvalidSequence = true),
                value.version.generation,
                value.id,
                value.version.revision,
                order,
                REPLY_INPUT_ENCODED_LIMIT,
                REPLY_INPUT_PLAIN_LIMIT,
            )
        return ReplicaDocument(value.id, value.version.revision, order, payload)
    }

    private fun validateReferences(
        generation: String,
        revision: Long,
        active: List<NotificationReplyReference>,
    ) {
        require(
            validCollaborationMessageId(generation) && revision in 0 until 9_007_199_254_740_991L
        )
        require(
            active.size <= COLLABORATION_NOTIFICATION_REPLY_CAPACITY &&
                active.map { it.id }.distinct().size == active.size &&
                active.map { it.sequence }.distinct().size == active.size
        )
        active.forEach {
            require(
                validCollaborationNotificationReplyId(it.id) &&
                    it.sequence in 0..it.revision &&
                    it.revision <= revision
            )
            it.review?.let { value -> enumValueOf<CollaborationNotificationReplyReview>(value) }
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
            error("Reply input exceeds storage bounds")
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
                    "hris-notification-reply-input-v1",
                    access.audience,
                    access.accountId,
                    generation,
                    id,
                    revision.toString(),
                    order,
                )
            )
            .encodeToByteArray()

    companion object {
        fun reference(value: CollaborationNotificationReplyInput) =
            NotificationReplyReference(
                value.id,
                value.version.revision,
                value.sequence,
                value.review?.name,
            )

        fun sort(reference: NotificationReplyReference) =
            reference.sequence.toString().padStart(16, '0') + "/" + reference.id
    }
}
