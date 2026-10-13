package dev.fajar.starter.leave.data.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

suspend fun decodeLeaveCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: LeaveAccess,
    raw: ReplicaCheckpoint,
): LeaveCheckpoint {
    val bytes =
        cipher.decrypt(
            key,
            leaveReplicaAssociatedData(access, raw.handle.generation, "checkpoint", raw.revision),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<LeaveCheckpointDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toCheckpoint(raw, access)
    } finally {
        bytes.fill(0)
    }
}

suspend fun encodeLeaveCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: LeaveCheckpoint,
): String {
    val bytes = Json.encodeToString(checkpoint.toReplicaDto()).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                leaveReplicaAssociatedData(
                    checkpoint.access,
                    checkpoint.generation,
                    "checkpoint",
                    checkpoint.revision,
                ),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun encodeLeaveDocument(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: LeaveCheckpoint,
    id: String,
    version: Long,
    sortKey: String,
    tags: Set<String>,
    json: String,
): ReplicaDocument {
    val bytes = json.encodeToByteArray()
    return try {
        require(bytes.size <= 96 * 1024 - 64)
        val encrypted =
            cipher.encrypt(
                key,
                leaveReplicaAssociatedData(
                    checkpoint.access,
                    checkpoint.generation,
                    id,
                    version,
                    sortKey,
                    tags,
                ),
                bytes,
            )
        ReplicaDocument(id, version, sortKey, Base64.encode(encrypted), tags)
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeLeaveDocument(
    cipher: ContentCipher,
    key: ByteArray,
    access: LeaveAccess,
    checkpoint: ReplicaCheckpoint,
    document: ReplicaDocument,
): String {
    val bytes =
        cipher.decrypt(
            key,
            leaveReplicaAssociatedData(
                access,
                checkpoint.handle.generation,
                document.id,
                document.version,
                document.sortKey,
                document.tags,
            ),
            Base64.decode(requireNotNull(document.payload)),
        )
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeLeaveReplicaPage(
    cipher: ContentCipher,
    key: ByteArray,
    access: LeaveAccess,
    selection: LeaveSelection,
    page: ReplicaQueryPage,
): LeaveReplicaPage {
    val raw = requireNotNull(page.checkpoint)
    val checkpoint = decodeLeaveCheckpoint(cipher, key, access, raw)
    require(page.pages.size == 2)
    val balances =
        page.pages[0].take(20).map { document ->
            require(document.id.startsWith("balance/"))
            val record =
                Json.decodeFromString<LeaveAccountDto>(
                        decodeLeaveDocument(cipher, key, access, raw, document)
                    )
                    .toReplicaBalance(document.id.removePrefix("balance/"))
            require(
                record.employeeId == selection.employeeId && record.value.year == selection.year
            )
            require(
                record.value.version == document.version &&
                    record.value.replicaSortKey() == document.sortKey
            )
            require(document.tags == leaveRecordTags(key, access, record))
            record.value
        }
    val requests =
        page.pages[1].take(20).map { document ->
            require(document.id.startsWith("request/"))
            val record =
                Json.decodeFromString<LeaveRequestSummaryDto>(
                        decodeLeaveDocument(cipher, key, access, raw, document)
                    )
                    .toReplicaRequest(document.id.removePrefix("request/"))
            require(record.value.employeeId == selection.employeeId)
            require(selection.status == null || record.value.status == selection.status)
            require(
                record.value.version == document.version &&
                    record.value.replicaSortKey() == document.sortKey
            )
            require(document.tags == leaveRecordTags(key, access, record))
            record.value
        }
    return LeaveReplicaPage(
        checkpoint,
        LeavePage(balances, if (page.pages[0].size > 20) page.pages[0][19].sortKey else null),
        LeavePage(requests, if (page.pages[1].size > 20) page.pages[1][19].sortKey else null),
    )
}
