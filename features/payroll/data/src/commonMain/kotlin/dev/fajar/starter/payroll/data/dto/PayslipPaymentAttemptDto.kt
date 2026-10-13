package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayslipPaymentAttemptDto(
    val batchId: String,
    val itemId: String,
    val status: String,
    val createdAt: String,
    val releasedAt: String?,
    val resolvedAt: String?,
)
