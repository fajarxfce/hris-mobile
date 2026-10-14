package dev.fajar.starter.payroll.data.replica.datasources

import dev.fajar.starter.payroll.data.replica.dto.PayrollReadAdmissionDto
import kotlinx.coroutines.flow.Flow

/** Process-owned write buffer; the repository coordinates its encrypted durable counterpart. */
interface PayrollReadAdmissionDataSource {
    fun observe(key: String): Flow<PayrollReadAdmissionDto?>

    fun current(key: String): PayrollReadAdmissionDto?

    fun write(key: String, value: PayrollReadAdmissionDto?)
}
