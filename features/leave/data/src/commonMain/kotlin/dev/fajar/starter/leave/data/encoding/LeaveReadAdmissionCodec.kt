@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.encoding

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.leave.data.dto.LeaveReadAdmissionDto
import dev.fajar.starter.leave.data.mappers.leaveReplicaAssociatedData
import dev.fajar.starter.leave.data.mappers.leaveReplicaScope
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun leaveAdmissionPartition(access: LeaveAccess) =
    "leave-replica/${access.companyId}/read-admission"

/** Pending writes belong to their requesting session; durable records belong to the account. */
fun leaveAdmissionBufferKey(access: LeaveAccess) =
    Json.encodeToString(
        listOf(access.sessionId, access.audience, access.accountId, access.companyId)
    )

fun LeaveReadAdmissionDto.toReadAdmission(): LeaveReadAdmission {
    require(Uuid.parse(revision).toString() == revision)
    require(code == null || code.length in 1..128)
    return LeaveReadAdmission(
        revision,
        Failure(
            FailureKind.valueOf(kind),
            "Access to leave data requires verification.",
            code = code,
        ),
    )
}

suspend fun encodeLeaveReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: LeaveAccess,
    dto: LeaveReadAdmissionDto,
): String {
    dto.toReadAdmission()
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                leaveReplicaAssociatedData(access, dto.revision, "read-admission", 0),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeLeaveReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: LeaveAccess,
    raw: ReplicaCheckpoint,
): LeaveReadAdmissionDto {
    require(
        raw.handle.scope == leaveReplicaScope(access) &&
            raw.handle.partition == leaveAdmissionPartition(access)
    )
    require(raw.published && raw.revision == 0L && raw.payload.length in 1..4_096)
    val bytes =
        cipher.decrypt(
            key,
            leaveReplicaAssociatedData(access, raw.handle.generation, "read-admission", 0),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<LeaveReadAdmissionDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .also {
                require(it.revision == raw.handle.generation)
                it.toReadAdmission()
            }
    } finally {
        bytes.fill(0)
    }
}
