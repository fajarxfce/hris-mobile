package dev.fajar.starter.leave.data.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.leaveRequestAssociatedData
import dev.fajar.starter.leave.domain.entities.LeaveAccess
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

/** Reproduces the previous application's wire format for migration tests only. */
suspend fun encodeLeaveRequestCache(
    snapshot: LeaveRequestSnapshotDto,
    generation: String,
    cipher: ContentCipher,
    key: ByteArray,
    access: LeaveAccess,
): ReplicaSnapshot {
    val batches = snapshot.request.days.chunked(32)
    require(batches.size in 1..12 && snapshot.request.history.items.size <= 10)
    val header =
        LeaveRequestCacheHeaderDto(
            snapshot.copy(
                request =
                    snapshot.request.copy(
                        days = emptyList(),
                        history = snapshot.request.history.copy(items = emptyList()),
                    )
            ),
            batches.size,
            snapshot.request.history.items.size,
        )
    val documents = mutableListOf<ReplicaDocument>()
    documents +=
        ReplicaDocument(
            "request",
            0,
            "request",
            sealRequestValue(
                cipher,
                key,
                leaveRequestAssociatedData(access, generation, "request"),
                Json.encodeToString(header),
            ),
        )
    for ((index, days) in batches.withIndex()) {
        val id = "day/" + index.toString().padStart(2, '0')
        documents +=
            ReplicaDocument(
                id,
                0,
                id,
                sealRequestValue(
                    cipher,
                    key,
                    leaveRequestAssociatedData(access, generation, id),
                    Json.encodeToString(days),
                ),
            )
    }
    for ((index, change) in snapshot.request.history.items.withIndex()) {
        val id = "history/" + index.toString().padStart(2, '0')
        documents +=
            ReplicaDocument(
                id,
                0,
                id,
                sealRequestValue(
                    cipher,
                    key,
                    leaveRequestAssociatedData(access, generation, id),
                    Json.encodeToString(change),
                ),
            )
    }
    return ReplicaSnapshot(
        generation,
        sealRequestValue(
            cipher,
            key,
            leaveRequestAssociatedData(access, generation, "checkpoint"),
            "v1",
        ),
        documents,
    )
}

private suspend fun sealRequestValue(
    cipher: ContentCipher,
    key: ByteArray,
    associatedData: ByteArray,
    value: String,
): String {
    val bytes = value.encodeToByteArray()
    return try {
        val ciphertext = cipher.encrypt(key, associatedData, bytes)
        currentCoroutineContext().ensureActive()
        Base64.encode(ciphertext)
    } finally {
        bytes.fill(0)
    }
}
