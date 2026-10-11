package dev.fajar.starter.workforce.data.attendance.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.data.attendance.dto.*
import dev.fajar.starter.workforce.data.attendance.mappers.attendanceAssociatedData
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceWindow
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

/**
 * Data-format encoding only; repositories own storage and domain mapping. No acquisition policy.
 */
suspend fun decodeAttendanceCache(
    page: ReplicaPage,
    cipher: ContentCipher,
    key: ByteArray,
    access: WorkforceAccess,
    window: AttendanceWindow,
): AttendanceSnapshotDto {
    val checkpoint = requireNotNull(page.checkpoint)
    require(checkpoint.published && checkpoint.revision == 0L && page.documents.size in 1..32)
    require(page.documents.all { it.version == 0L && it.id == it.sortKey })
    val generation = checkpoint.handle.generation
    val version =
        decryptCacheValue(
            cipher,
            key,
            attendanceAssociatedData(access, generation, "checkpoint", window),
            checkpoint.payload,
        )
    require(version == "v2" || (version == "v1" && window == AttendanceWindow.RECENT))
    val header = page.documents.single { it.id == "attendance" }
    val metadata =
        Json.decodeFromString<AttendanceSnapshotDto>(
            decryptCacheValue(
                cipher,
                key,
                attendanceAssociatedData(access, generation, header.id, window),
                requireNotNull(header.payload),
            )
        )
    if (version == "v1") {
        require(window == AttendanceWindow.RECENT && page.documents.size == 1)
        return metadata
    }
    require(version == "v2" && metadata.days.isEmpty())
    val days =
        page.documents
            .filter { it.id != header.id }
            .mapIndexed { index, document ->
                require(document.id == "day/" + index.toString().padStart(2, '0'))
                Json.decodeFromString<AttendanceDayDto>(
                    decryptCacheValue(
                        cipher,
                        key,
                        attendanceAssociatedData(access, generation, document.id, window),
                        requireNotNull(document.payload),
                    )
                )
            }
    return metadata.copy(days = days)
}

/** Each day has its own bounded ciphertext; metadata and all days publish atomically. */
suspend fun encodeAttendanceCache(
    snapshot: AttendanceSnapshotDto,
    generation: String,
    cipher: ContentCipher,
    key: ByteArray,
    access: WorkforceAccess,
    window: AttendanceWindow,
): ReplicaSnapshot {
    val documents = mutableListOf<ReplicaDocument>()
    val metadata =
        sealCacheValue(
            cipher,
            key,
            attendanceAssociatedData(access, generation, "attendance", window),
            Json.encodeToString(snapshot.copy(days = emptyList())),
        )
    documents += ReplicaDocument("attendance", 0, "attendance", metadata)
    for ((index, day) in snapshot.days.withIndex()) {
        val id = "day/" + index.toString().padStart(2, '0')
        documents +=
            ReplicaDocument(
                id,
                0,
                id,
                sealCacheValue(
                    cipher,
                    key,
                    attendanceAssociatedData(access, generation, id, window),
                    Json.encodeToString(day),
                ),
            )
    }
    return ReplicaSnapshot(
        generation,
        sealCacheValue(
            cipher,
            key,
            attendanceAssociatedData(access, generation, "checkpoint", window),
            "v2",
        ),
        documents,
    )
}

private suspend fun decryptCacheValue(
    cipher: ContentCipher,
    key: ByteArray,
    associatedData: ByteArray,
    payload: String,
): String {
    val bytes = cipher.decrypt(key, associatedData, Base64.decode(payload))
    return try {
        currentCoroutineContext().ensureActive()
        bytes.decodeToString(throwOnInvalidSequence = true)
    } finally {
        bytes.fill(0)
    }
}

private suspend fun sealCacheValue(
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
