package dev.fajar.starter.payroll.data.replica.dto

import kotlinx.serialization.Serializable

@Serializable
data class PayrollReadAdmissionDto(val revision: String, val kind: String, val code: String?)
