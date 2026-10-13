package dev.fajar.starter.workforce.data.overtime.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

fun overtimePartition(access: WorkforceAccess, view: String): String {
    require(view == "overview" || view == "request")
    return "overtime-$view/" + access.companyId
}

fun overtimeAssociatedData(
    access: WorkforceAccess,
    generation: String,
    view: String,
    purpose: String,
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-overtime-v1",
                access.sessionId,
                access.accountId,
                access.audience,
                access.companyId,
                generation,
                view,
                purpose,
            )
        )
        .encodeToByteArray()

/** The caller owns and clears the returned plaintext, including when DTO mapping fails. */
suspend fun decodeOvertimeCache(
    page: ReplicaPage,
    access: WorkforceAccess,
    view: String,
    cipher: ContentCipher,
    key: ByteArray,
): ByteArray {
    val checkpoint = requireNotNull(page.checkpoint)
    require(checkpoint.published && checkpoint.revision == 0L)
    require(
        checkpoint.handle.scope.sessionId == access.sessionId &&
            checkpoint.handle.partition == overtimePartition(access, view)
    )
    val document = page.documents.single()
    require(document.id == view && document.sortKey == view && document.version == 0L)
    val header =
        cipher.decrypt(
            key,
            overtimeAssociatedData(access, checkpoint.handle.generation, view, "checkpoint"),
            Base64.decode(checkpoint.payload),
        )
    try {
        require(header.decodeToString(throwOnInvalidSequence = true) == "v1")
    } finally {
        header.fill(0)
    }
    return cipher.decrypt(
        key,
        overtimeAssociatedData(access, checkpoint.handle.generation, view, "content"),
        Base64.decode(requireNotNull(document.payload)),
    )
}

/** Decode the preceding session-owned request for read-only migration. */
suspend fun decodeLegacyOvertimeRequest(
    page: ReplicaPage,
    access: WorkforceAccess,
    cipher: ContentCipher,
    key: ByteArray,
): OvertimeSnapshot<OvertimeRequestView> {
    val checkpoint = requireNotNull(page.checkpoint)
    val bytes = decodeOvertimeCache(page, access, "request", cipher, key)
    try {
        require(bytes.size <= 96_000)
        return Json.decodeFromString<OvertimeSnapshotDto<OvertimeRequestCacheDto>>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toSnapshot(access, checkpoint.handle.generation) { value, _ -> value.toView() }
    } finally {
        bytes.fill(0)
    }
}
