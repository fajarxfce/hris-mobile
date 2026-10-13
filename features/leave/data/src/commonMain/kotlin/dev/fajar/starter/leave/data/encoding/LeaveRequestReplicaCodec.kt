package dev.fajar.starter.leave.data.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.time.Instant
import kotlinx.serialization.json.Json

/** Complete current facts, their newest activity page, and the indexed list projection. */
suspend fun encodeLeaveRequestRecords(
    snapshot: LeaveRequestSnapshot,
    checkpoint: LeaveCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
    pendingHistoryAfter: Long?,
    history: LeaveHistoryPage? = null,
): List<ReplicaDocument> {
    require(snapshot.selection.historyAfter == null)
    val dto = snapshot.toDto()
    dto.toSnapshot(snapshot.access, snapshot.generation)
    val request = snapshot.request
    val days = dto.request.days.chunked(128)
    val header =
        LeaveRequestReplicaHeaderDto(
            dto.copy(
                request =
                    dto.request.copy(
                        days = emptyList(),
                        history = LeavePageDto(emptyList(), dto.request.history.nextCursor),
                    )
            ),
            snapshot.generation,
            snapshot.access.sessionId,
            snapshot.access.authorizationExpiresAt.toString(),
            snapshot.access.authorizationExpiryCode,
            days.size,
            pendingHistoryAfter,
        )
    val summary = request.toSummary()
    val detailTag =
        leaveRequestComponentTag(key, snapshot.access, request.id, "detail/${request.version}")
    val documents =
        mutableListOf(
            encodeLeaveDocument(
                cipher,
                key,
                checkpoint,
                "request/${request.id}",
                request.version,
                summary.replicaSortKey(),
                leaveSummaryTags(key, snapshot.access, summary),
                Json.encodeToString(summary.toDto()),
            ),
            encodeLeaveRequestHeader(header, checkpoint, cipher, key),
        )
    for ((index, batch) in days.withIndex()) {
        val id = leaveDetailId(request.id, "day/" + index.toString().padStart(2, '0'))
        documents +=
            encodeLeaveDocument(
                cipher,
                key,
                checkpoint,
                id,
                request.version,
                id,
                setOf(detailTag),
                Json.encodeToString(batch),
            )
    }
    documents +=
        encodeLeaveActivityPage(
            LeaveHistoryPageDto(request.id, request.version, null, dto.request.history),
            checkpoint,
            cipher,
            key,
        )
    if (history != null) {
        require(history.requestId == request.id && history.requestVersion == request.version)
        history.history
            .toHistoryDto()
            .toChanges(history.after, history.requestVersion, request.status, request.submittedAt)
        documents += encodeLeaveActivityPage(history.toDto(), checkpoint, cipher, key)
    }
    return documents.toList()
}

suspend fun encodeLeaveRequestHeader(
    header: LeaveRequestReplicaHeaderDto,
    checkpoint: LeaveCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
): ReplicaDocument {
    val request = header.snapshot.request
    val id = leaveDetailId(request.id, "header")
    val tags =
        mutableSetOf(
            leaveRequestComponentTag(
                key,
                checkpoint.access,
                request.id,
                "detail/${request.version}",
            )
        )
    if (header.pendingHistoryAfter != null) tags += leavePendingHistoryTag(key, checkpoint.access)
    return encodeLeaveDocument(
        cipher,
        key,
        checkpoint,
        id,
        request.version,
        id,
        tags.toSet(),
        Json.encodeToString(header),
    )
}

suspend fun encodeLeaveActivityPage(
    page: LeaveHistoryPageDto,
    checkpoint: LeaveCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
): ReplicaDocument {
    val id = leaveActivityId(page.requestId, page.after)
    return encodeLeaveDocument(
        cipher,
        key,
        checkpoint,
        id,
        page.requestVersion,
        id,
        setOf(
            leaveRequestComponentTag(
                key,
                checkpoint.access,
                page.requestId,
                leaveActivityComponent(page.after),
            )
        ),
        Json.encodeToString(page),
    )
}

suspend fun decodeLeaveRequestHeader(
    document: ReplicaDocument,
    generation: String,
    access: LeaveAccess,
    cipher: ContentCipher,
    key: ByteArray,
): LeaveRequestReplicaHeaderDto {
    val header =
        Json.decodeFromString<LeaveRequestReplicaHeaderDto>(
            decodeLeaveDocument(cipher, key, access, generation, document)
        )
    val request = header.snapshot.request
    require(document.id == leaveDetailId(request.id, "header") && document.sortKey == document.id)
    require(document.version == request.version && header.dayBatches in 1..3)
    require(header.acquisitionId.length in 1..512 && header.sessionId.length in 1..128)
    require(header.authorizationExpiryCode.length in 1..128)
    Instant.parse(header.authorizationExpiresAt)
    require(
        header.snapshot.historyAfter == null &&
            request.days.isEmpty() &&
            request.history.items.isEmpty()
    )
    require(header.pendingHistoryAfter == null || header.pendingHistoryAfter in 0..request.version)
    val tags =
        mutableSetOf(leaveRequestComponentTag(key, access, request.id, "detail/${request.version}"))
    if (header.pendingHistoryAfter != null) tags += leavePendingHistoryTag(key, access)
    require(document.tags == tags)
    return header
}

suspend fun decodeLeaveRequestRecords(
    access: LeaveAccess,
    selection: LeaveRequestSelection,
    page: ReplicaQueryPage,
    cipher: ContentCipher,
    key: ByteArray,
): LeaveRequestSnapshot? {
    val raw = page.checkpoint ?: return null
    require(page.pages.size == 3)
    val parent = page.pages[0].singleOrNull() ?: return null
    require(parent.id == "request/${selection.requestId}")
    val summary =
        Json.decodeFromString<LeaveRequestSummaryDto>(
                decodeLeaveDocument(cipher, key, access, raw.handle.generation, parent)
            )
            .toReplicaSummary(selection.requestId)
    require(parent.version == summary.version && parent.sortKey == summary.replicaSortKey())
    require(parent.tags == leaveSummaryTags(key, access, summary))
    val parts = page.pages[1]
    val header =
        decodeLeaveRequestHeader(
            parts.single { it.id == leaveDetailId(selection.requestId, "header") },
            raw.handle.generation,
            access,
            cipher,
            key,
        )
    require(
        header.snapshot.request.id == selection.requestId &&
            header.snapshot.request.version == summary.version
    )
    require(parts.size == 1 + header.dayBatches)
    val days =
        (0 until header.dayBatches).flatMap { index ->
            val id = leaveDetailId(selection.requestId, "day/" + index.toString().padStart(2, '0'))
            val part = parts.single { it.id == id }
            require(part.sortKey == id && part.version == summary.version)
            require(
                part.tags ==
                    setOf(
                        leaveRequestComponentTag(
                            key,
                            access,
                            selection.requestId,
                            "detail/${summary.version}",
                        )
                    )
            )
            val batch =
                Json.decodeFromString<List<LeaveDayDto>>(
                    decodeLeaveDocument(cipher, key, access, raw.handle.generation, part)
                )
            require(batch.size in 1..128 && (index == header.dayBatches - 1 || batch.size == 128))
            batch
        }
    val activity = page.pages[2].singleOrNull() ?: return null
    val history =
        Json.decodeFromString<LeaveHistoryPageDto>(
            decodeLeaveDocument(cipher, key, access, raw.handle.generation, activity)
        )
    require(history.requestId == selection.requestId && history.after == selection.historyAfter)
    require(
        activity.id == leaveActivityId(selection.requestId, selection.historyAfter) &&
            activity.sortKey == activity.id
    )
    require(
        activity.version == history.requestVersion && history.requestVersion in 0..summary.version
    )
    require(selection.historyAfter != null || history.requestVersion == summary.version)
    require(
        activity.tags ==
            setOf(
                leaveRequestComponentTag(
                    key,
                    access,
                    selection.requestId,
                    leaveActivityComponent(selection.historyAfter),
                )
            )
    )
    val snapshot =
        header.snapshot
            .copy(
                historyAfter = selection.historyAfter,
                request = header.snapshot.request.copy(days = days, history = history.history),
            )
            .toSnapshot(
                access.copy(
                    sessionId = header.sessionId,
                    authorizationExpiresAt = Instant.parse(header.authorizationExpiresAt),
                    authorizationExpiryCode = header.authorizationExpiryCode,
                ),
                header.acquisitionId,
            )
    require(snapshot.request.toSummary() == summary)
    return snapshot
}
