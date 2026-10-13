package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayslipTaxInputDto(
    val retirementContributions: String,
    val qualifiedDonations: String,
    val otherNetDeductions: String,
    val nonCashTaxable: String,
)
