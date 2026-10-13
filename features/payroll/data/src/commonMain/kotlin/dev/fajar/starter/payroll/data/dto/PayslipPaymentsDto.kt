package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayslipPaymentsDto(
    val assessmentId: String,
    val version: Long,
    val attempts: List<PayslipPaymentAttemptDto>,
)
