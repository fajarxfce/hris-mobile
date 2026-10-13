package dev.fajar.starter.workforce.data.overtime.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
