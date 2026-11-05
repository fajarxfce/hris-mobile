@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.replica

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val COLLABORATION_LIST_PREFIX = "collaboration-list/"
internal const val COLLABORATION_LIST_ACCESS_PREFIX = "collaboration-list-access/"
internal const val COLLABORATION_LIST_CAPACITY = 10_000
internal const val MAX_LIST_METADATA_REVISION = 9_007_199_254_740_991L

internal fun listScope(access: CollaborationLocalAccess) =
    ReplicaScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

internal fun listPartition(access: CollaborationLocalAccess) =
    COLLABORATION_LIST_PREFIX + collaborationId(access.companyId)

internal fun listAdmissionPartition(access: CollaborationLocalAccess) =
    COLLABORATION_LIST_ACCESS_PREFIX + collaborationId(access.companyId)

internal fun listAdmissionKey(access: CollaborationLocalAccess) =
    Json.encodeToString(listOf(access.audience, access.accountId, access.companyId))

@Serializable
internal data class CollaborationListCheckpointDto(
    val count: Int,
    val acquiredAt: String,
    val schema: Int = 1,
    val sync: CollaborationListSyncStateDto? = null,
)

@Serializable
internal data class CollaborationListSyncStateDto(
    val nativeSessionId: String,
    val phase: String,
    val cursor: String?,
    val lastPosition: Long,
    val afterConversationId: String?,
    val bootstrapPages: Int,
)

@Serializable
internal data class CollaborationListFactDto(
    val summary: CollaborationDirectSummaryDto,
    val trackedRevision: Long?,
)

@Serializable
data class CollaborationListAdmissionDto(val revision: String, val kind: String, val code: String?)

internal fun CollaborationListAdmissionDto.toAdmission(): CollaborationListAdmission {
    collaborationId(revision)
    require(code == null || (code.length in 1..128 && code.all { it in '!'..'~' }))
    return CollaborationListAdmission(
        revision,
        Failure(
            FailureKind.valueOf(kind),
            "Conversation access requires verification.",
            code = code,
        ),
    )
}

internal fun CollaborationDirectSummary.toListDto() =
    CollaborationDirectSummaryDto(
        conversation.let {
            CollaborationDirectDto(
                it.id,
                it.companyId,
                it.firstAccountId,
                it.secondAccountId,
                it.createdBy,
                Instant.fromEpochMilliseconds(it.createdAt).toString(),
                it.version,
            )
        },
        CollaborationParticipantDto(peer.accountId, peer.displayName),
    )

/** Local identity and visible indexes are authenticated; login tokens/families are excluded. */
internal class CollaborationListCodec(
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val access: CollaborationLocalAccess,
) {
    suspend fun checkpoint(raw: ReplicaCheckpoint): CollaborationListCheckpoint {
        require(raw.published)
        return syncCheckpoint(raw).local
    }

    suspend fun syncCheckpoint(raw: ReplicaCheckpoint): CollaborationListSyncCheckpoint {
        require(
            raw.handle.scope == listScope(access) && raw.handle.partition == listPartition(access)
        )
        collaborationId(raw.handle.generation)
        require(raw.revision in 0..MAX_LIST_METADATA_REVISION)
        val dto =
            Json.decodeFromString<CollaborationListCheckpointDto>(
                open(raw.handle.generation, "checkpoint", raw.revision, "", raw.payload)
            )
        require(dto.schema in 1..2 && dto.count in 0..COLLABORATION_LIST_CAPACITY)
        require((dto.schema == 1) == (dto.sync == null))
        val acquired = Instant.parse(dto.acquiredAt)
        require(acquired.toEpochMilliseconds() >= 0)
        val state =
            dto.sync?.let {
                CollaborationListSyncState(
                        it.nativeSessionId,
                        CollaborationListSyncPhase.valueOf(it.phase),
                        it.cursor,
                        it.lastPosition,
                        it.afterConversationId,
                        it.bootstrapPages,
                    )
                    .also(::validateSyncState)
            }
        require(dto.schema == 2 || raw.published)
        require(
            !raw.published || state == null || state.phase == CollaborationListSyncPhase.CHANGES
        )
        require(raw.published || state?.phase != CollaborationListSyncPhase.CHANGES)
        return CollaborationListSyncCheckpoint(
            CollaborationListCheckpoint(
                raw.handle.generation,
                raw.revision,
                dto.count,
                acquired,
                dto.schema,
            ),
            raw.published,
            state,
        )
    }

    suspend fun checkpoint(value: CollaborationListCheckpoint): String {
        require(value.format == 1)
        return syncCheckpoint(CollaborationListSyncCheckpoint(value, true, null))
    }

    suspend fun syncCheckpoint(value: CollaborationListSyncCheckpoint): String {
        val local = value.local
        collaborationId(local.generation)
        require(
            local.revision in 0..MAX_LIST_METADATA_REVISION &&
                local.count in 0..COLLABORATION_LIST_CAPACITY
        )
        require(local.acquiredAt.toEpochMilliseconds() >= 0 && local.format in 1..2)
        val state = value.sync
        require((local.format == 1) == (state == null))
        require(local.format == 2 || value.published)
        require(
            !value.published || state == null || state.phase == CollaborationListSyncPhase.CHANGES
        )
        require(value.published || state?.phase != CollaborationListSyncPhase.CHANGES)
        val dto =
            state?.also(::validateSyncState)?.let {
                CollaborationListSyncStateDto(
                    it.nativeSessionId,
                    it.phase.name,
                    it.cursor,
                    it.lastPosition,
                    it.afterConversationId,
                    it.bootstrapPages,
                )
            }
        return seal(
            local.generation,
            "checkpoint",
            local.revision,
            "",
            Json.encodeToString(
                CollaborationListCheckpointDto(
                    local.count,
                    local.acquiredAt.toString(),
                    local.format,
                    dto,
                )
            ),
        )
    }

    suspend fun document(
        checkpoint: CollaborationListCheckpoint,
        raw: ReplicaDocument,
    ): CollaborationDirectSummary = requireNotNull(storedFact(checkpoint, raw).summary)

    suspend fun storedFact(
        checkpoint: CollaborationListCheckpoint,
        raw: ReplicaDocument,
    ): CollaborationListStoredFact {
        collaborationId(raw.id)
        require(
            raw.sortKey == raw.id &&
                raw.tags.isEmpty() &&
                raw.version in 0..MAX_LIST_METADATA_REVISION
        )
        if (checkpoint.format == 1) {
            require(raw.version <= checkpoint.revision)
            val dto =
                Json.decodeFromString<CollaborationDirectSummaryDto>(
                    open(
                        checkpoint.generation,
                        raw.id,
                        raw.version,
                        raw.sortKey,
                        requireNotNull(raw.payload),
                    )
                )
            val summary = dto.toSummary(access.accountId, access.companyId)
            require(summary.conversation.id == raw.id)
            return CollaborationListStoredFact(raw.id, null, summary)
        }
        require(checkpoint.format == 2)
        val payload = raw.payload ?: return CollaborationListStoredFact(raw.id, raw.version, null)
        val dto =
            Json.decodeFromString<CollaborationListFactDto>(
                open(checkpoint.generation, raw.id, raw.version, raw.sortKey, payload)
            )
        require(
            if (dto.trackedRevision == null) raw.version == 0L
            else dto.trackedRevision == raw.version
        )
        val summary = dto.summary.toSummary(access.accountId, access.companyId)
        require(summary.conversation.id == raw.id)
        return CollaborationListStoredFact(raw.id, dto.trackedRevision, summary)
    }

    /** Interactive fallback before this identity has a tracked server projection. */
    suspend fun document(
        checkpoint: CollaborationListCheckpoint,
        summary: CollaborationDirectSummary,
    ): ReplicaDocument {
        val dto = summary.toListDto()
        require(dto.toSummary(access.accountId, access.companyId) == summary)
        val id = summary.conversation.id
        require(checkpoint.format in 1..2)
        val version = if (checkpoint.format == 1) checkpoint.revision else 0L
        val body =
            if (checkpoint.format == 1) Json.encodeToString(dto)
            else Json.encodeToString(CollaborationListFactDto(dto, null))
        return ReplicaDocument(id, version, id, seal(checkpoint.generation, id, version, id, body))
    }

    suspend fun fact(
        checkpoint: CollaborationListCheckpoint,
        fact: CollaborationSummaryFact,
    ): ReplicaDocument {
        require(checkpoint.format == 2 && fact.revision in 0..MAX_LIST_METADATA_REVISION)
        val id = collaborationId(fact.conversationId)
        val body =
            fact.summary?.let { summary ->
                val dto = summary.toListDto()
                require(
                    summary.conversation.id == id &&
                        dto.toSummary(access.accountId, access.companyId) == summary
                )
                seal(
                    checkpoint.generation,
                    id,
                    fact.revision,
                    id,
                    Json.encodeToString(CollaborationListFactDto(dto, fact.revision)),
                )
            }
        return ReplicaDocument(id, fact.revision, id, body)
    }

    private fun validateSyncState(state: CollaborationListSyncState) {
        collaborationId(state.nativeSessionId)
        state.cursor?.let { require(it.length in 1..2048 && it.all { ch -> ch in '!'..'~' }) }
        require(state.lastPosition >= 0 && state.bootstrapPages in 0..1000)
        state.afterConversationId?.let(::collaborationId)
        if (state.phase == CollaborationListSyncPhase.BOOTSTRAP) {
            require(state.lastPosition == 0L)
            require((state.bootstrapPages == 0) == (state.cursor == null))
            require((state.bootstrapPages == 0) == (state.afterConversationId == null))
        } else {
            require(
                state.cursor != null &&
                    state.afterConversationId == null &&
                    state.bootstrapPages == 0
            )
        }
    }

    suspend fun admission(raw: ReplicaCheckpoint): CollaborationListAdmissionDto {
        require(
            raw.handle.scope == listScope(access) &&
                raw.handle.partition == listAdmissionPartition(access) &&
                raw.published &&
                raw.revision == 0L
        )
        return Json.decodeFromString<CollaborationListAdmissionDto>(
                open(raw.handle.generation, "admission", 0, "", raw.payload)
            )
            .also {
                require(it.revision == raw.handle.generation)
                it.toAdmission()
            }
    }

    suspend fun admission(value: CollaborationListAdmissionDto): String {
        value.toAdmission()
        return seal(value.revision, "admission", 0, "", Json.encodeToString(value))
    }

    private fun aad(generation: String, id: String, revision: Long, sort: String) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-list-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    generation,
                    id,
                    revision.toString(),
                    sort,
                )
            )
            .encodeToByteArray()

    private suspend fun seal(
        generation: String,
        id: String,
        revision: Long,
        sort: String,
        json: String,
    ): String {
        val bytes = json.encodeToByteArray()
        return try {
            require(bytes.size <= 4096)
            Base64.encode(cipher.encrypt(key, aad(generation, id, revision, sort), bytes))
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(
        generation: String,
        id: String,
        revision: Long,
        sort: String,
        payload: String,
    ): String {
        require(payload.length <= 6144)
        val bytes = cipher.decrypt(key, aad(generation, id, revision, sort), Base64.decode(payload))
        return try {
            require(bytes.size <= 4096)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }
}
