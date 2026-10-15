package dev.fajar.starter.payroll.data.replica.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.payroll.data.dto.PayslipDto
import dev.fajar.starter.payroll.data.mappers.*
import dev.fajar.starter.payroll.data.replica.mappers.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

fun payrollRevisionPartition(access: PayrollAccess) = "payroll-history/" + access.companyId

fun payrollRevisionRecordId(id: String, revision: Long) = "history/$id/$revision"

private fun revisionCheckpointFields(access: PayrollAccess, generation: String, revision: Long) =
    listOf(
        "hris-payroll-history-v1",
        access.audience,
        access.accountId,
        access.companyId,
        generation,
        revision.toString(),
    )

suspend fun encodePayrollRevisionCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: PayrollAccess,
    generation: String,
    revision: Long,
): String {
    val bytes =
        Json.encodeToString(revisionCheckpointFields(access, generation, revision))
            .encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                payrollReplicaAssociatedData(access, generation, "history-checkpoint", revision),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun validatePayrollRevisionCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: PayrollAccess,
    checkpoint: ReplicaCheckpoint,
) {
    require(
        checkpoint.handle.scope == payrollReplicaScope(access) &&
            checkpoint.handle.partition == payrollRevisionPartition(access) &&
            checkpoint.published &&
            checkpoint.revision >= 0
    )
    val bytes =
        cipher.decrypt(
            key,
            payrollReplicaAssociatedData(
                access,
                checkpoint.handle.generation,
                "history-checkpoint",
                checkpoint.revision,
            ),
            Base64.decode(checkpoint.payload),
        )
    try {
        require(
            Json.decodeFromString<List<String>>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            ) == revisionCheckpointFields(access, checkpoint.handle.generation, checkpoint.revision)
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun encodePayrollRevision(
    cipher: ContentCipher,
    key: ByteArray,
    access: PayrollAccess,
    generation: String,
    payslip: Payslip,
): ReplicaDocument {
    val dto = payslip.toDto()
    require(dto.toPayslip(payslip.summary.id) == payslip)
    val id = payrollRevisionRecordId(payslip.summary.id, payslip.summary.version)
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    return try {
        require(bytes.size <= 96 * 1024 - 64)
        val sealed =
            cipher.encrypt(
                key,
                payrollReplicaAssociatedData(
                    access,
                    generation,
                    id,
                    payslip.summary.version,
                    id,
                    emptySet(),
                ),
                bytes,
            )
        ReplicaDocument(id, payslip.summary.version, id, Base64.encode(sealed))
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodePayrollRevision(
    cipher: ContentCipher,
    key: ByteArray,
    access: PayrollAccess,
    generation: String,
    document: ReplicaDocument,
    id: String,
    revision: Long,
): Payslip {
    require(
        document.id == payrollRevisionRecordId(id, revision) &&
            document.version == revision &&
            document.sortKey == document.id &&
            document.tags.isEmpty()
    )
    val bytes =
        cipher.decrypt(
            key,
            payrollReplicaAssociatedData(
                access,
                generation,
                document.id,
                revision,
                document.sortKey,
                document.tags,
            ),
            Base64.decode(requireNotNull(document.payload)),
        )
    return try {
        Json.decodeFromString<PayslipDto>(bytes.decodeToString(throwOnInvalidSequence = true))
            .toPayslip(id)
            .also { require(it.summary.version == revision) }
    } finally {
        bytes.fill(0)
    }
}
