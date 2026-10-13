package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayslipSummaryDto(
    val id: String,
    val employeeId: String,
    val employeeNumber: String,
    val employeeName: String,
    val taxMonth: String,
    val plannedPaymentDate: String,
    val currency: String,
    val taxableGross: String,
    val withheld: String,
    val takeHome: String,
    val publishedAt: String,
    val version: Long,
)
