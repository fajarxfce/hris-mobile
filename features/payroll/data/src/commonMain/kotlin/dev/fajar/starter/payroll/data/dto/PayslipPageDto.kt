package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable data class PayslipPageDto(val items: List<PayslipSummaryDto>, val nextCursor: String?)
