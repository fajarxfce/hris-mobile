package dev.fajar.starter.payroll.data.replica.encoding

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.ReplicaDocument
import dev.fajar.starter.payroll.data.mappers.*
import dev.fajar.starter.payroll.data.replica.mappers.*
import dev.fajar.starter.payroll.domain.policies.validatePayslipId
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.sync.domain.entities.SyncCollection
import kotlinx.serialization.json.Json

/** Maps one canonical record to its encrypted detail and, for a slip, indexed summary. */
suspend fun encodePayrollReplicaEntry(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: PayrollCheckpoint,
    entry: PayrollReplicaEntry,
): List<ReplicaDocument> {
    val reference = entry.reference
    require(validatePayslipId(reference.id) is AppResult.Success)
    require(reference.version >= 0)
    val id = payrollRecordId(reference.collection, reference.id)
    val record = entry.record
    if (record == null) {
        return buildList {
            add(ReplicaDocument(id, reference.version, "deleted/$id", null))
            if (reference.collection == SyncCollection.PAYSLIPS)
                add(
                    ReplicaDocument(
                        "summary/${reference.id}",
                        reference.version,
                        "deleted/summary/${reference.id}",
                        null,
                    )
                )
        }
    }
    require(
        record.id == reference.id &&
            record.version == reference.version &&
            record.collection() == reference.collection
    )
    val json =
        when (record) {
            is PayrollRecord.Slip -> {
                val dto = record.value.toDto()
                require(dto.toPayslip(record.id) == record.value)
                Json.encodeToString(dto)
            }
            is PayrollRecord.Payments -> {
                val dto = record.value.toDto()
                val mapped =
                    if (checkpoint.recordVersion >= 2) dto.toPayments(record.id)
                    else dto.toStoredPayments(record.id)
                require(mapped == record.value)
                Json.encodeToString(dto)
            }
        }
    val access = checkpoint.access
    return buildList {
        add(
            encodePayrollDocument(
                cipher,
                key,
                checkpoint,
                id,
                record.version,
                id,
                setOf(payrollReplicaTag(key, access, "payslip", record.id)),
                json,
            )
        )
        if (record is PayrollRecord.Slip) {
            val summary = record.value.summary
            add(
                encodePayrollDocument(
                    cipher,
                    key,
                    checkpoint,
                    "summary/${record.id}",
                    summary.version,
                    payrollSummarySortKey(summary.month, summary.id),
                    setOf(payrollReplicaTag(key, access, "year", summary.month.year.toString())),
                    Json.encodeToString(summary.toDto()),
                )
            )
        }
    }
}
