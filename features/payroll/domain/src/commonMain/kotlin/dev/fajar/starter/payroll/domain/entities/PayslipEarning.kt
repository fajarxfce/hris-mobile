package dev.fajar.starter.payroll.domain.entities

import dev.fajar.starter.common.decimal.DecimalAmount

data class PayslipEarning(
    val kind: PayslipEarningKind,
    val code: String,
    val name: String?,
    val amount: DecimalAmount,
    val taxable: Boolean,
)
