package dev.fajar.starter.payroll.domain.entities

import dev.fajar.starter.common.decimal.DecimalAmount

data class PayslipContribution(
    val program: PayslipInsuranceProgram,
    val base: DecimalAmount,
    val employeeAmount: DecimalAmount,
    val employerAmount: DecimalAmount,
)
