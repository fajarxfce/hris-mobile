@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime.encoding

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.data.overtime.dto.OvertimeReadAdmissionDto
import dev.fajar.starter.workforce.data.overtime.mappers.overtimeReplicaAssociatedData
import dev.fajar.starter.workforce.data.overtime.mappers.overtimeReplicaScope
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun overtimeAdmissionPartition(access: WorkforceAccess) =
    "overtime-replica/${access.companyId}/read-admission"

/** Pending and durable records share account, audience and company ownership. */
fun overtimeAdmissionBufferKey(access: WorkforceAccess) =
    Json.encodeToString(listOf(access.audience, access.accountId, access.companyId))

fun OvertimeReadAdmissionDto.toReadAdmission(): OvertimeReadAdmission {
    require(Uuid.parse(revision).toString() == revision)
    require(code == null || code.length in 1..128)
    return OvertimeReadAdmission(
        revision,
        Failure(
            FailureKind.valueOf(kind),
            "Access to overtime data requires verification.",
            code = code,
        ),
    )
}

suspend fun encodeOvertimeReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: WorkforceAccess,
    dto: OvertimeReadAdmissionDto,
): String {
    dto.toReadAdmission()
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                overtimeReplicaAssociatedData(access, dto.revision, "read-admission", 0),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeOvertimeReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: WorkforceAccess,
    raw: ReplicaCheckpoint,
): OvertimeReadAdmissionDto {
    require(
        raw.handle.scope == overtimeReplicaScope(access) &&
            raw.handle.partition == overtimeAdmissionPartition(access)
    )
    require(raw.published && raw.revision == 0L && raw.payload.length in 1..4_096)
    val bytes =
        cipher.decrypt(
            key,
            overtimeReplicaAssociatedData(access, raw.handle.generation, "read-admission", 0),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<OvertimeReadAdmissionDto>(
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
