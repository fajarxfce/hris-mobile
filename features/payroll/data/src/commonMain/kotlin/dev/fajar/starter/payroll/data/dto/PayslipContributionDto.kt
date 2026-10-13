package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayslipContributionDto(
    val program: String,
    val base: String,
    val employeeAmount: String,
    val employerAmount: String,
)
