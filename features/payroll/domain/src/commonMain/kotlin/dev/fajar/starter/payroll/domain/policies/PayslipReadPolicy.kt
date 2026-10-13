@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.payroll.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import kotlin.uuid.Uuid

const val PAYSLIP_PAGE_SIZE = 20
const val PAYSLIP_PDF_MAXIMUM_BYTES = 1_048_576L

fun payslipFailure(code: String, kind: FailureKind = FailureKind.Validation) =
    AppResult.Failed(Failure(kind, "The payslip could not be loaded.", code = code))

fun payslipFailureInvalidatesCache(failure: Failure): Boolean =
    failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied) ||
        failure.code in
            setOf(
                "payroll_payslip_not_found",
                "company_maintenance",
                "client_update_required",
                "client_version_required",
            )

fun validatePayslipId(id: String): AppResult<Unit> =
    if (id.length == 36 && Uuid.parseOrNull(id)?.toString() == id) AppResult.Success(Unit)
    else payslipFailure("payroll_payslip_not_found")

fun validatePayslipSelection(selection: PayslipSelection): AppResult<Unit> {
    if (selection.year != null && selection.year !in 2024..2100)
        return payslipFailure("invalid_payroll_payslip_range")
    val after = selection.after ?: return AppResult.Success(Unit)
    if (selection.year == null || after.length != 44 || after[7] != ':')
        return payslipFailure("invalid_page")
    val month = after.take(7)
    if (
        !Regex("[0-9]{4}-(0[1-9]|1[0-2])").matches(month) ||
            month.take(4).toInt() != selection.year ||
            validatePayslipId(after.drop(8)) is AppResult.Failed
    )
        return payslipFailure("invalid_page")
    return AppResult.Success(Unit)
}

fun validatePayslipPdf(metadata: PayslipPdfMetadata): AppResult<Unit> =
    if (
        validatePayslipId(metadata.payslipId) is AppResult.Success &&
            metadata.size in 1..PAYSLIP_PDF_MAXIMUM_BYTES &&
            Regex("\"[0-9a-f]{64}\"").matches(metadata.entityTag)
    )
        AppResult.Success(Unit)
    else payslipFailure("client.invalid_response", FailureKind.Unexpected)
