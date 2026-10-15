package dev.fajar.starter.payroll.domain.replica.entities

import dev.fajar.starter.payroll.domain.entities.*

sealed interface PayrollRecord {
    val id: String
    val version: Long

    data class Slip(val value: Payslip) : PayrollRecord {
        override val id
            get() = value.summary.id

        override val version
            get() = value.summary.version
    }

    data class Payments(val value: PayslipPayments) : PayrollRecord {
        override val id
            get() = value.payslipId

        override val version
            get() = value.version
    }
}
