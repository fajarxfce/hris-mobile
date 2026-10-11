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

/** Data encoding only. Storage publication and DTO-to-domain mapping remain in the repository. */
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

suspend fun decodeLeaveRequestCache(
    page: ReplicaPage,
    cipher: ContentCipher,
    key: ByteArray,
    access: LeaveAccess,
): LeaveRequestSnapshotDto {
    val checkpoint = requireNotNull(page.checkpoint)
    require(checkpoint.published && checkpoint.revision == 0L && page.documents.size in 2..23)
    require(page.documents.all { it.version == 0L && it.id == it.sortKey })
    require(page.documents.map { it.id }.toSet().size == page.documents.size)
    val generation = checkpoint.handle.generation
    require(
        unsealRequestValue(
            cipher,
            key,
            leaveRequestAssociatedData(access, generation, "checkpoint"),
            checkpoint.payload,
        ) == "v1"
    )
    val header =
        Json.decodeFromString<LeaveRequestCacheHeaderDto>(
            unsealRequestValue(
                cipher,
                key,
                leaveRequestAssociatedData(access, generation, "request"),
                requireNotNull(page.documents.single { it.id == "request" }.payload),
            )
        )
    require(header.dayBatches in 1..12 && header.historyCount in 0..10)
    require(page.documents.size == 1 + header.dayBatches + header.historyCount)
    require(
        header.snapshot.request.days.isEmpty() && header.snapshot.request.history.items.isEmpty()
    )
    val days =
        (0 until header.dayBatches).flatMap { index ->
            val id = "day/" + index.toString().padStart(2, '0')
            val decoded =
                Json.decodeFromString<List<LeaveDayDto>>(
                    unsealRequestValue(
                        cipher,
                        key,
                        leaveRequestAssociatedData(access, generation, id),
                        requireNotNull(page.documents.single { it.id == id }.payload),
                    )
                )
            require(decoded.size in 1..32 && (index == header.dayBatches - 1 || decoded.size == 32))
            decoded
        }
    val history =
        (0 until header.historyCount).map { index ->
            val id = "history/" + index.toString().padStart(2, '0')
            Json.decodeFromString<LeaveChangeDto>(
                unsealRequestValue(
                    cipher,
                    key,
                    leaveRequestAssociatedData(access, generation, id),
                    requireNotNull(page.documents.single { it.id == id }.payload),
                )
            )
        }
    return header.snapshot.copy(
        request =
            header.snapshot.request.copy(
                days = days,
                history = header.snapshot.request.history.copy(items = history),
            )
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

private suspend fun unsealRequestValue(
    cipher: ContentCipher,
    key: ByteArray,
    associatedData: ByteArray,
    value: String,
): String {
    val bytes = cipher.decrypt(key, associatedData, Base64.decode(value))
    return try {
        currentCoroutineContext().ensureActive()
        bytes.decodeToString(throwOnInvalidSequence = true)
    } finally {
        bytes.fill(0)
    }
}
