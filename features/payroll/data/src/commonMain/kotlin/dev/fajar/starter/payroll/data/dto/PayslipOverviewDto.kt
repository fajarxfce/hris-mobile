package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayslipOverviewDto(val year: Int, val after: String?, val page: PayslipPageDto)
