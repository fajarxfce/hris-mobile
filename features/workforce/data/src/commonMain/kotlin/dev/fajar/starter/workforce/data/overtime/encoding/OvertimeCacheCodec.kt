package dev.fajar.starter.workforce.data.overtime.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

suspend fun encodeOvertimeCache(
    bytes: ByteArray,
    access: WorkforceAccess,
    generation: String,
    view: String,
    cipher: ContentCipher,
    key: ByteArray,
): ReplicaSnapshot {
    require(bytes.size in 1..96_000)
    val content =
        cipher.encrypt(key, overtimeAssociatedData(access, generation, view, "content"), bytes)
    currentCoroutineContext().ensureActive()
    val marker = "v1".encodeToByteArray()
    val header =
        try {
            cipher.encrypt(
                key,
                overtimeAssociatedData(access, generation, view, "checkpoint"),
                marker,
            )
        } finally {
            marker.fill(0)
        }
    currentCoroutineContext().ensureActive()
    return ReplicaSnapshot(
        generation,
        Base64.encode(header),
        listOf(ReplicaDocument(view, 0, view, Base64.encode(content))),
    )
}

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
