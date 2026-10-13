package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayslipTaxDto(
    val ruleId: String,
    val taxableGross: String,
    val taxAllowance: String,
    val deductionAllowance: String,
    val withheld: String,
    val takeHome: String,
)
