@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime.working.encoding

import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.database.ReplicaSnapshot
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.data.overtime.mappers.overtimeReplicaAssociatedData
import dev.fajar.starter.workforce.data.overtime.mappers.overtimeReplicaScope
import dev.fajar.starter.workforce.data.overtime.working.dto.OvertimePlanWorkingCopyDto
import dev.fajar.starter.workforce.data.overtime.working.mappers.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimePlanWorkingCopy
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun overtimePlanWorkingPartition(access: WorkforceAccess, employeeId: String): String {
    Uuid.parse(employeeId)
    return "overtime-replica/${access.companyId}/working/plan/$employeeId"
}

suspend fun encodeOvertimePlanWorkingCopy(
    access: WorkforceAccess,
    copy: OvertimePlanWorkingCopy,
    cipher: ContentCipher,
    key: ByteArray,
): ReplicaSnapshot {
    val bytes = Json.encodeToString(copy.toDto()).encodeToByteArray()
    return try {
        require(bytes.size <= 32_768)
        ReplicaSnapshot(
            copy.revision,
            Base64.encode(
                cipher.encrypt(
                    key,
                    overtimeReplicaAssociatedData(
                        access,
                        copy.revision,
                        "working/plan/${copy.employeeId}",
                        0,
                    ),
                    bytes,
                )
            ),
            emptyList(),
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeOvertimePlanWorkingCopy(
    access: WorkforceAccess,
    employeeId: String,
    checkpoint: ReplicaCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
): OvertimePlanWorkingCopy {
    require(
        checkpoint.handle.scope == overtimeReplicaScope(access) &&
            checkpoint.handle.partition == overtimePlanWorkingPartition(access, employeeId)
    )
    require(
        checkpoint.published && checkpoint.revision == 0L && checkpoint.payload.length in 1..65_536
    )
    val bytes =
        cipher.decrypt(
            key,
            overtimeReplicaAssociatedData(
                access,
                checkpoint.handle.generation,
                "working/plan/$employeeId",
                0,
            ),
            Base64.decode(checkpoint.payload),
        )
    return try {
        require(bytes.size <= 32_768)
        Json.decodeFromString<OvertimePlanWorkingCopyDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toWorkingCopy()
            .also {
                require(it.employeeId == employeeId && it.revision == checkpoint.handle.generation)
            }
    } finally {
        bytes.fill(0)
    }
}
