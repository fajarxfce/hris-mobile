package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayslipCalculationDto(
    val earnings: List<PayslipEarningDto>,
    val contributions: List<PayslipContributionDto>,
    val tax: PayslipTaxDto,
    val taxInput: PayslipTaxInputDto,
    val employeeDeductions: String,
)
