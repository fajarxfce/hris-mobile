@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

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
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val SPACE_INBOX_ENTRIES = "entries"

internal fun spaceInboxPartition(access: CollaborationSpaceLocalAccess): String {
    spaceHistoryScope(access)
    return COLLABORATION_HISTORY_PREFIX + "space-inbox/v1/" + access.spaceId
}

/** Maps an admitted immutable proposal to ciphertext; never reads another repository/source. */
internal class CollaborationSpaceInboxCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val cipher: ContentCipher,
    private val key: ByteArray,
    private val indexKey: ByteArray,
) {
    suspend fun id(
        kind: String,
        conversationId: String,
        source: CollaborationSpaceInboxSource? = null,
    ): String {
        require(
            kind in listOf("entry", "draft", "activity", "candidates", "repair") &&
                validCollaborationMessageId(conversationId)
        )
        require((kind in listOf("activity", "repair")) == (source != null))
        require(kind != "repair" || source is CollaborationSpaceInboxSource.GroupOriginal)
        val parts =
            listOf(kind, conversationId) +
                (source?.let { InboxSourceDto(it).partsWithKind() } ?: emptyList())
        val bytes = context("index", parts)
        return try {
            "i-" +
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

    suspend fun version(raw: ReplicaCheckpoint): CollaborationSpaceInboxVersion {
        require(
            raw.published &&
                raw.handle.scope == spaceHistoryScope(access) &&
                raw.handle.partition == spaceInboxPartition(access)
        )
        require(
            validCollaborationMessageId(raw.handle.generation) &&
                raw.revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER
        )
        val value =
            Json.decodeFromString<InboxCheckpointDto>(
                open(raw.handle.generation, "checkpoint", raw.revision, "", raw.payload)
            )
        require(value.schema == 1 && value.lastOrdinal in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER)
        return CollaborationSpaceInboxVersion(
            raw.handle.generation,
            raw.revision,
            value.lastOrdinal,
        )
    }

    suspend fun decode(raw: ReplicaCheckpoint, document: ReplicaDocument): InboxDocumentDto {
        require(document.version in 0..raw.revision)
        val value =
            Json.decodeFromString<InboxDocumentDto>(
                open(
                    raw.handle.generation,
                    document.id,
                    document.version,
                    document.sortKey,
                    requireNotNull(document.payload),
                )
            )
        require(value.schema == 1 && validCollaborationMessageId(value.conversationId))
        val expected = coordinates(value)
        require(
            document.id == expected.first &&
                document.sortKey == expected.second &&
                document.tags == expected.third
        )
        require(value.repair == null || value.repair.insertedAtRevision <= document.version)
        for (activity in listOfNotNull(value.activity, value.entry?.newest)) {
            require(validCollaborationSpaceInboxActivity(activity.domain(), document.version))
        }
        return value
    }

    suspend fun cursor(version: CollaborationSpaceInboxVersion, sort: String) =
        seal(
            "cursor",
            "page",
            0,
            "",
            Json.encodeToString(InboxCursorDto(1, version.generation, version.revision, sort)),
        )

    suspend fun cursor(value: String): InboxCursorDto {
        val decoded = Json.decodeFromString<InboxCursorDto>(open("cursor", "page", 0, "", value))
        require(
            decoded.schema == 1 &&
                validCollaborationMessageId(decoded.generation) &&
                decoded.revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER
        )
        require(decoded.after.matches(Regex("[0-9]{16}:[0-9]{16}:i-[0-9a-f]{64}")))
        return decoded
    }

    suspend fun repairCursor(generation: String, highRevision: Long, sort: String): String =
        seal(
            "cursor",
            "repair-page",
            0,
            "",
            Json.encodeToString(InboxRepairCursorDto(1, generation, highRevision, sort)),
        )

    suspend fun repairCursor(value: String): InboxRepairCursorDto {
        val decoded =
            Json.decodeFromString<InboxRepairCursorDto>(open("cursor", "repair-page", 0, "", value))
        require(decoded.schema == 1 && validCollaborationMessageId(decoded.generation))
        require(decoded.highRevision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER)
        require(decoded.after.matches(Regex("[0-9]{16}:i-[0-9a-f]{64}")))
        require(decoded.after.substringBefore(':').toLong() <= decoded.highRevision)
        return decoded
    }

    /** Existing anchors and creation facts are checked against the same preflight revision. */
    suspend fun mutation(
        raw: ReplicaCheckpoint?,
        existing: List<ReplicaDocument>,
        proposal: CollaborationSpaceInboxPublication,
    ): ReplicaMutation? {
        require(validCollaborationSpaceInboxPublication(proposal))
        require((raw?.let { version(it) }) == proposal.expected)
        val generation = raw?.handle?.generation ?: Uuid.random().toString()
        val revision = raw?.revision?.plus(1) ?: 0L
        val values = documents(raw, existing, proposal, revision)
        val previous = existing.associateBy { it.id }
        require(previous.size == existing.size && (raw != null || existing.isEmpty()))
        val retainedValues =
            existing.associate { it.id to decode(requireNotNull(raw), it) }.toMutableMap()
        val changed = mutableListOf<ReplicaDocument>()
        val allocated = mutableListOf<Long>()
        for (value in values) {
            val coordinates = coordinates(value)
            val old = previous[coordinates.first]?.let { decode(requireNotNull(raw), it) }
            retainedValues[coordinates.first] = value
            require(old == null || old.conversationId == value.conversationId)
            if (old?.entry != null) require(value.entry?.createdAt == old.entry.createdAt)
            if (old?.activity != null) require(value.activity?.position == old.activity.position)
            value.activity?.pendingEdit?.let { pending ->
                val previousEdit = old?.activity?.pendingEdit
                if (previousEdit?.source == pending.source) {
                    require(pending == previousEdit)
                    val previousPreview = requireNotNull(requireNotNull(old).activity).preview
                    val preview = requireNotNull(value.activity).preview
                    when (previousPreview?.kind) {
                        null -> require(preview == null)
                        "DELETED" -> require(preview == null || preview.kind == "DELETED")
                        "TEXT" ->
                            if (preview?.kind == "TEXT")
                                require(preview.text == previousPreview.text)
                        else -> error("Invalid pending edit preview")
                    }
                } else require(pending.publishedAtRevision == revision)
            }
            if (value.activity != null && old == null) allocated += value.activity.position.ordinal
            if (value.draft != null && value.draft != old?.draft) {
                require(
                    old?.draft == null || value.draft.value.revision != old.draft.value.revision
                )
                allocated += value.draft.position.ordinal
            }
            if (old == value) continue
            changed +=
                ReplicaDocument(
                    coordinates.first,
                    revision,
                    coordinates.second,
                    seal(
                        generation,
                        coordinates.first,
                        revision,
                        coordinates.second,
                        Json.encodeToString(value),
                    ),
                    coordinates.third,
                )
        }
        require(allocated.distinct().size == allocated.size)
        val previousOrdinal = proposal.expected?.lastOrdinal ?: 0L
        require(allocated.sorted() == (1..allocated.size).map { previousOrdinal + it })
        require(proposal.lastOrdinal == previousOrdinal + allocated.size)
        proposal.entry.newest?.let { newest ->
            val stored =
                retainedValues[id("activity", proposal.entry.conversationId, newest.source)]
            require(stored?.activity?.domain() == newest)
        }
        val draft = retainedValues[id("draft", proposal.entry.conversationId)]?.draft?.domain()
        require(
            proposal.entry.draft ==
                draft?.takeIf { it.value.submissionId == null && it.value.text.isNotBlank() }
        )
        if (changed.isEmpty()) return null
        val checkpoint =
            seal(
                generation,
                "checkpoint",
                revision,
                "",
                Json.encodeToString(InboxCheckpointDto(1, proposal.lastOrdinal)),
            )
        return if (raw == null)
            ReplicaMutation.Replace(
                spaceInboxPartition(access),
                null,
                ReplicaSnapshot(generation, checkpoint, changed),
            )
        else ReplicaMutation.Commit(raw.handle, raw.revision, checkpoint, changed, true)
    }

    private suspend fun documents(
        raw: ReplicaCheckpoint?,
        existing: List<ReplicaDocument>,
        proposal: CollaborationSpaceInboxPublication,
        revision: Long,
    ): List<InboxDocumentDto> {
        return buildList {
            add(
                InboxDocumentDto(
                    conversationId = proposal.entry.conversationId,
                    entry = InboxEntryDto(proposal.entry),
                )
            )
            proposal.draft?.let {
                add(
                    InboxDocumentDto(
                        conversationId = proposal.entry.conversationId,
                        draft = InboxDraftDto(it),
                    )
                )
            }
            proposal.activities.forEach {
                add(
                    InboxDocumentDto(
                        conversationId = proposal.entry.conversationId,
                        activity = InboxActivityDto(it),
                    )
                )
            }
            proposal.repair?.let { change ->
                val source =
                    when (change) {
                        is CollaborationSpaceInboxRepairChange.Retain -> change.intent
                        is CollaborationSpaceInboxRepairChange.Complete -> change.reference.intent
                    }
                val token =
                    id(
                        "repair",
                        proposal.entry.conversationId,
                        CollaborationSpaceInboxSource.GroupOriginal(source.id),
                    )
                val old =
                    existing
                        .singleOrNull { it.id == token }
                        ?.let { decode(requireNotNull(raw), it).repair }
                val repair =
                    CollaborationSpaceInboxRepairCodec(access).proposed(change, old, revision)
                add(
                    InboxDocumentDto(
                        conversationId = proposal.entry.conversationId,
                        repair = repair,
                    )
                )
            }
        }
    }

    /** Guard an unchanged index alongside a source write without replacing its documents. */
    suspend fun guardCheckpoint(raw: ReplicaCheckpoint): ReplicaMutation.Commit {
        val current = version(raw)
        require(current.revision < COLLABORATION_SPACE_INBOX_MAX_ORDER)
        val checkpoint =
            seal(
                current.generation,
                "checkpoint",
                current.revision + 1,
                "",
                Json.encodeToString(InboxCheckpointDto(1, current.lastOrdinal)),
            )
        return ReplicaMutation.Commit(raw.handle, current.revision, checkpoint, emptyList(), true)
    }

    private suspend fun coordinates(value: InboxDocumentDto): Triple<String, String, Set<String>> {
        require(
            listOf(value.entry, value.draft, value.activity, value.repair).count { it != null } == 1
        )
        value.repair?.let { repair ->
            CollaborationSpaceInboxRepairCodec(access).validate(repair, value.conversationId)
            val token =
                id(
                    "repair",
                    value.conversationId,
                    CollaborationSpaceInboxSource.GroupOriginal(repair.id),
                )
            val sort = repair.insertedAtRevision.toString().padStart(16, '0') + ":" + token
            return Triple(
                token,
                sort,
                if (repair.intent == null) setOf(token) else setOf(token, SPACE_INBOX_REPAIRS),
            )
        }
        val entry = value.entry
        val draft = value.draft
        val activity = value.activity
        val token: String
        val position: CollaborationSpaceInboxPosition
        val tags: Set<String>
        when {
            entry != null -> {
                val domain = entry.domain(value.conversationId)
                require(
                    domain ==
                        collaborationSpaceInboxEntry(
                            domain.conversationId,
                            domain.createdAt,
                            domain.draft,
                            domain.newest,
                        )
                )
                token = id("entry", value.conversationId)
                position = domain.position
                tags = setOf(token, SPACE_INBOX_ENTRIES)
            }
            draft != null -> {
                val domain = draft.domain()
                require(
                    validCollaborationSpaceInboxDraft(domain) &&
                        domain.value.conversationId == value.conversationId
                )
                token = id("draft", value.conversationId)
                position = domain.position
                tags = setOf(token)
            }
            else -> {
                val domain = requireNotNull(activity).domain()
                require(validCollaborationSpaceInboxActivity(domain))
                token = id("activity", value.conversationId, domain.source)
                position = domain.position
                tags =
                    if (domain.preview == null) setOf(token)
                    else setOf(token, id("candidates", value.conversationId))
            }
        }
        require(validCollaborationSpaceInboxPosition(position))
        val sort =
            (COLLABORATION_SPACE_INBOX_MAX_ORDER - position.timestamp)
                .toString()
                .padStart(16, '0') +
                ":" +
                (COLLABORATION_SPACE_INBOX_MAX_ORDER - position.ordinal)
                    .toString()
                    .padStart(16, '0') +
                ":" +
                token
        return Triple(token, sort, tags)
    }

    private suspend fun seal(
        generation: String,
        id: String,
        revision: Long,
        sort: String,
        value: String,
    ): String {
        val bytes = value.encodeToByteArray()
        val frame = context("content", listOf(generation, id, revision.toString(), sort))
        return try {
            require(bytes.size in 1..96_000)
            Base64.encode(cipher.encrypt(key, frame, bytes)).also { require(it.length <= 131_072) }
        } finally {
            bytes.fill(0)
            frame.fill(0)
        }
    }

    private suspend fun open(
        generation: String,
        id: String,
        revision: Long,
        sort: String,
        value: String,
    ): String {
        require(value.length in 1..131_072)
        val frame = context("content", listOf(generation, id, revision.toString(), sort))
        val bytes =
            try {
                cipher.decrypt(key, frame, Base64.decode(value))
            } finally {
                frame.fill(0)
            }
        return try {
            require(bytes.size in 1..96_000)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }

    private fun context(purpose: String, parts: List<String>) =
        Json.encodeToString(
                listOf(
                    "hris-space-inbox-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    purpose,
                ) + parts
            )
            .encodeToByteArray()
}

@Serializable internal data class InboxCheckpointDto(val schema: Int, val lastOrdinal: Long)

@Serializable
internal data class InboxCursorDto(
    val schema: Int,
    val generation: String,
    val revision: Long,
    val after: String,
)

@Serializable
internal data class InboxDocumentDto(
    val schema: Int = 1,
    val conversationId: String,
    val entry: InboxEntryDto? = null,
    val draft: InboxDraftDto? = null,
    val activity: InboxActivityDto? = null,
    val repair: InboxRepairDto? = null,
)

@Serializable
internal data class InboxRepairCursorDto(
    val schema: Int,
    val generation: String,
    val highRevision: Long,
    val after: String,
)
