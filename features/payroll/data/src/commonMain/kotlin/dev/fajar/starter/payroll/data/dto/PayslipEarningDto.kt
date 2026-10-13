package dev.fajar.starter.payroll.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayslipEarningDto(
    val kind: String,
    val code: String,
    val name: String?,
    val amount: String,
    val taxable: Boolean,
)
