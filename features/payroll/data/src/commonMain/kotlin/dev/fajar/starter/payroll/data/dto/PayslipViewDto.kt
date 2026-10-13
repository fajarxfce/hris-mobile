package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable data class PayslipViewDto(val payslip: PayslipDto, val payments: PayslipPaymentsDto)
