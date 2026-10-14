@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.payroll.data.replica.encoding

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.payroll.data.replica.dto.PayrollReadAdmissionDto
import dev.fajar.starter.payroll.data.replica.mappers.payrollReplicaAssociatedData
import dev.fajar.starter.payroll.data.replica.mappers.payrollReplicaScope
import dev.fajar.starter.payroll.domain.entities.PayrollAccess
import dev.fajar.starter.payroll.domain.replica.entities.PayrollReadAdmission
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun payrollAdmissionPartition(access: PayrollAccess) = "payroll-access/" + access.companyId

fun payrollAdmissionBufferKey(access: PayrollAccess) =
    Json.encodeToString(
        listOf(access.sessionId, access.audience, access.accountId, access.companyId)
    )

fun PayrollReadAdmissionDto.toReadAdmission(): PayrollReadAdmission {
    Uuid.parse(revision)
    require(code == null || code.length in 1..128)
    return PayrollReadAdmission(
        revision,
        Failure(
            FailureKind.valueOf(kind),
            "Access to these payslips requires verification.",
            code = code,
        ),
    )
}

suspend fun encodePayrollReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: PayrollAccess,
    dto: PayrollReadAdmissionDto,
): String {
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                payrollReplicaAssociatedData(access, dto.revision, "read-admission", 0),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodePayrollReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: PayrollAccess,
    raw: ReplicaCheckpoint,
): PayrollReadAdmissionDto {
    require(
        raw.handle.scope == payrollReplicaScope(access) &&
            raw.handle.partition == payrollAdmissionPartition(access)
    )
    require(raw.revision == 0L)
    val bytes =
        cipher.decrypt(
            key,
            payrollReplicaAssociatedData(access, raw.handle.generation, "read-admission", 0),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<PayrollReadAdmissionDto>(
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
