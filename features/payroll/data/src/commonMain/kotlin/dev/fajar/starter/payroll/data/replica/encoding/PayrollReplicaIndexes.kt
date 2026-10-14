package dev.fajar.starter.payroll.data.replica.encoding

import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.replica.entities.PayrollRecord
import dev.fajar.starter.securestorage.contentIndexes
import dev.fajar.starter.sync.domain.entities.SyncCollection
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

fun payrollRecordId(collection: SyncCollection, id: String) =
    when (collection) {
        SyncCollection.PAYSLIPS -> "payslip/$id"
        SyncCollection.PAYROLL_PAYMENTS -> "payments/$id"
        else -> error("Unsupported payroll collection.")
    }

fun PayrollRecord.collection() =
    when (this) {
        is PayrollRecord.Slip -> SyncCollection.PAYSLIPS
        is PayrollRecord.Payments -> SyncCollection.PAYROLL_PAYMENTS
    }

/** Year equality is keyed; the visible ordering is month descending and stable ID ascending. */
fun payrollSummarySortKey(month: YearMonth, id: String) =
    "summary/" + (13 - month.month.number).toString().padStart(2, '0') + "/" + id

fun payrollSummaryAfter(selection: PayslipSelection): String? =
    selection.after?.let { payrollSummarySortKey(YearMonth.parse(it.take(7)), it.drop(8)) }

suspend fun payrollReplicaTag(
    key: ByteArray,
    access: PayrollAccess,
    kind: String,
    value: String,
): String {
    val bytes =
        Json.encodeToString(
                listOf(
                    "hris-payroll-index-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    kind,
                    value,
                )
            )
            .encodeToByteArray()
    return try {
        contentIndexes(key, listOf(bytes)).single()
    } finally {
        bytes.fill(0)
    }
}
