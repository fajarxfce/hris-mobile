package dev.fajar.starter.workforce.data.overtime.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.toSummary
import kotlin.time.Instant
import kotlinx.serialization.json.Json

/** Current facts and list projection are written together, with the original acquisition lease. */
suspend fun encodeOvertimeRequestRecords(
    snapshot: OvertimeSnapshot<OvertimeRequestView>,
    checkpoint: OvertimeCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
    history: OvertimeHistoryPage? = null,
): List<ReplicaDocument> {
    require(snapshot.value.selection.historyAfter == null)
    val dto = snapshot.toDto { it.toDto() }
    dto.toSnapshot(snapshot.access, snapshot.generation) { value, _ -> value.toView() }
    val request = snapshot.value.details.request
    val header =
        OvertimeReplicaRequestDto(
            dto,
            snapshot.generation,
            snapshot.access.sessionId,
            snapshot.access.authorizationExpiresAt.toString(),
            snapshot.access.authorizationExpiryCode,
            snapshot.value.details.history.nextCursor?.toLong(),
        )
    val summary = request.toSummary()
    val documents =
        mutableListOf(
            encodeOvertimeRequestHeader(header, checkpoint, cipher, key),
            encodeOvertimeDocument(
                cipher,
                key,
                checkpoint,
                "summary/" + request.id,
                request.version,
                summary.replicaSortKey(),
                overtimeSummaryTags(key, snapshot.access, summary),
                Json.encodeToString(summary.toDto()),
            ),
        )
    if (history != null) {
        require(history.requestId == request.id && history.requestVersion == request.version)
        history.toDto().history.toHistory(request, history.after)
        documents += encodeOvertimeHistoryPage(history.toDto(), checkpoint, cipher, key)
    }
    return documents.toList()
}

suspend fun encodeOvertimeRequestHeader(
    header: OvertimeReplicaRequestDto,
    checkpoint: OvertimeCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
): ReplicaDocument {
    val request = header.snapshot.value.request
    val id = "request/" + request.id
    val tags = mutableSetOf(overtimeRequestTag(key, checkpoint.access, request.id, "request"))
    if (header.pendingHistoryAfter != null)
        tags += overtimePendingHistoryTag(key, checkpoint.access)
    return encodeOvertimeDocument(
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

suspend fun encodeOvertimeHistoryPage(
    page: OvertimeHistoryPageDto,
    checkpoint: OvertimeCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
): ReplicaDocument {
    val id = "activity/" + page.requestId + "/" + page.after
    return encodeOvertimeDocument(
        cipher,
        key,
        checkpoint,
        id,
        page.requestVersion,
        id,
        setOf(overtimeRequestTag(key, checkpoint.access, page.requestId, "activity/" + page.after)),
        Json.encodeToString(page),
    )
}

suspend fun decodeOvertimeRequestHeader(
    access: WorkforceAccess,
    generation: String,
    document: ReplicaDocument,
    cipher: ContentCipher,
    key: ByteArray,
): OvertimeReplicaRequestDto {
    val header =
        Json.decodeFromString<OvertimeReplicaRequestDto>(
            decodeOvertimeDocument(cipher, key, access, generation, document)
        )
    val request = header.snapshot.value.toView().details.request
    require(
        document.id == "request/" + request.id &&
            document.sortKey == document.id &&
            document.version == request.version
    )
    require(
        header.snapshot.value.historyAfter == null &&
            header.acquisitionId.length in 1..512 &&
            header.sessionId.length in 1..128
    )
    require(header.authorizationExpiryCode.length in 1..128)
    Instant.parse(header.authorizationExpiresAt)
    require(header.pendingHistoryAfter == null || header.pendingHistoryAfter in 1..request.version)
    val tags = mutableSetOf(overtimeRequestTag(key, access, request.id, "request"))
    if (header.pendingHistoryAfter != null) tags += overtimePendingHistoryTag(key, access)
    require(tags == document.tags)
    return header
}

fun OvertimeReplicaRequestDto.toReplicaSnapshot(
    access: WorkforceAccess,
    selection: OvertimeRequestSelection,
    history: OvertimeHistoryPageDto? = null,
): OvertimeSnapshot<OvertimeRequestView> {
    val request = snapshot.value.request
    require(selection.requestId == request.id && selection.employeeId == request.employeeId)
    require(
        history == null ||
            (history.requestId == request.id &&
                history.requestVersion in 0..request.version &&
                history.after == selection.historyAfter)
    )
    val view =
        snapshot.value.copy(
            employmentAfter = selection.employmentAfter,
            historyAfter = selection.historyAfter,
            history = history?.history ?: snapshot.value.history,
        )
    require(selection.historyAfter == null || history != null)
    return snapshot.copy(value = view).toSnapshot(
        access.copy(
            sessionId = sessionId,
            authorizationExpiresAt = Instant.parse(authorizationExpiresAt),
            authorizationExpiryCode = authorizationExpiryCode,
        ),
        acquisitionId,
    ) { value, _ ->
        value.toView()
    }
}

suspend fun decodeOvertimeHistoryPage(
    access: WorkforceAccess,
    generation: String,
    selection: OvertimeRequestSelection,
    document: ReplicaDocument,
    cipher: ContentCipher,
    key: ByteArray,
): OvertimeHistoryPageDto {
    val page =
        Json.decodeFromString<OvertimeHistoryPageDto>(
            decodeOvertimeDocument(cipher, key, access, generation, document)
        )
    val id = "activity/" + selection.requestId + "/" + selection.historyAfter
    require(page.requestId == selection.requestId && page.after == selection.historyAfter)
    require(document.id == id && document.sortKey == id && document.version == page.requestVersion)
    require(
        document.tags ==
            setOf(
                overtimeRequestTag(
                    key,
                    access,
                    selection.requestId,
                    "activity/" + selection.historyAfter,
                )
            )
    )
    return page
}
