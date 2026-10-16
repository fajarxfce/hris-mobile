@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.working.encoding

import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.database.ReplicaSnapshot
import dev.fajar.starter.leave.data.mappers.leaveReplicaAssociatedData
import dev.fajar.starter.leave.data.mappers.leaveReplicaScope
import dev.fajar.starter.leave.data.working.dto.LeaveWorkingCopyDto
import dev.fajar.starter.leave.data.working.mappers.*
import dev.fajar.starter.leave.domain.entities.LeaveAccess
import dev.fajar.starter.leave.domain.working.entities.LeaveWorkingCopy
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun leaveWorkingPartition(access: LeaveAccess, employeeId: String): String {
    Uuid.parse(employeeId)
    return "leave-replica/${access.companyId}/working/$employeeId"
}

suspend fun encodeLeaveWorkingCopy(
    access: LeaveAccess,
    copy: LeaveWorkingCopy,
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
                    leaveReplicaAssociatedData(
                        access,
                        copy.revision,
                        "working/${copy.employeeId}",
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

suspend fun decodeLeaveWorkingCopy(
    access: LeaveAccess,
    employeeId: String,
    checkpoint: ReplicaCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
): LeaveWorkingCopy {
    require(
        checkpoint.handle.scope == leaveReplicaScope(access) &&
            checkpoint.handle.partition == leaveWorkingPartition(access, employeeId)
    )
    require(
        checkpoint.published && checkpoint.revision == 0L && checkpoint.payload.length in 1..65_536
    )
    val bytes =
        cipher.decrypt(
            key,
            leaveReplicaAssociatedData(
                access,
                checkpoint.handle.generation,
                "working/$employeeId",
                0,
            ),
            Base64.decode(checkpoint.payload),
        )
    return try {
        require(bytes.size <= 32_768)
        Json.decodeFromString<LeaveWorkingCopyDto>(
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
