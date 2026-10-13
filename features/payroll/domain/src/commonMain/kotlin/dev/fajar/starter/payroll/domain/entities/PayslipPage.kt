package dev.fajar.starter.payroll.domain.entities

data class PayslipPage(val items: List<PayslipSummary>, val nextCursor: String? = null)
