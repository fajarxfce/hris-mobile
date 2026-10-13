package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayslipDto(
    val summary: PayslipSummaryDto,
    val companyCode: String,
    val companyName: String,
    val calculation: PayslipCalculationDto,
)
