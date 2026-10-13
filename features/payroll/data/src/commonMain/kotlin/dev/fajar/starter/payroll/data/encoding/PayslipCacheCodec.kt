package dev.fajar.starter.payroll.data.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.payroll.domain.entities.PayrollAccess
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

const val PAYSLIP_CACHE_MAXIMUM_BYTES = 262_144

fun payslipPartition(access: PayrollAccess, view: String): String {
    require(view == "overview" || view == "details")
    return "payslips-$view/" + access.companyId
}

fun payslipAssociatedData(
    access: PayrollAccess,
    generation: String,
    view: String,
    purpose: String,
): ByteArray =
    Json.encodeToString(
            listOf(
                "hris-payslips-v1",
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

suspend fun encodePayslipCache(
    bytes: ByteArray,
    access: PayrollAccess,
    generation: String,
    view: String,
    cipher: ContentCipher,
    key: ByteArray,
): ReplicaSnapshot {
    require(bytes.size in 1..PAYSLIP_CACHE_MAXIMUM_BYTES)
    val content =
        cipher.encrypt(key, payslipAssociatedData(access, generation, view, "content"), bytes)
    currentCoroutineContext().ensureActive()
    val marker = "v1".encodeToByteArray()
    val header =
        try {
            cipher.encrypt(
                key,
                payslipAssociatedData(access, generation, view, "checkpoint"),
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

/** The repository owns and clears the returned plaintext even when DTO mapping fails. */
suspend fun decodePayslipCache(
    page: ReplicaPage,
    access: PayrollAccess,
    view: String,
    cipher: ContentCipher,
    key: ByteArray,
): ByteArray {
    val checkpoint = requireNotNull(page.checkpoint)
    require(checkpoint.published && checkpoint.revision == 0L && checkpoint.payload.length <= 4096)
    require(
        checkpoint.handle.scope.sessionId == access.sessionId &&
            checkpoint.handle.partition == payslipPartition(access, view)
    )
    val document = page.documents.single()
    require(document.id == view && document.sortKey == view && document.version == 0L)
    val header =
        cipher.decrypt(
            key,
            payslipAssociatedData(access, checkpoint.handle.generation, view, "checkpoint"),
            Base64.decode(checkpoint.payload),
        )
    try {
        require(header.decodeToString(throwOnInvalidSequence = true) == "v1")
    } finally {
        header.fill(0)
    }
    val payload = requireNotNull(document.payload)
    require(payload.length <= PAYSLIP_CACHE_MAXIMUM_BYTES * 2)
    return cipher.decrypt(
        key,
        payslipAssociatedData(access, checkpoint.handle.generation, view, "content"),
        Base64.decode(payload),
    )
}
