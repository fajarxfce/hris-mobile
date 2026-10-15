package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayslipSettlementDto(
    val assessmentRevision: Long,
    val entitlement: String,
    val confirmedPaid: String,
    val confirmedRecovered: String,
    val payable: String,
    val recoverable: String,
    val currency: String,
)
