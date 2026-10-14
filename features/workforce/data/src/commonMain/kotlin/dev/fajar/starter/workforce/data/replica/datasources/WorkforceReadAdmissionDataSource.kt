package dev.fajar.starter.workforce.data.replica.datasources

import dev.fajar.starter.workforce.data.replica.dto.WorkforceReadAdmissionDto
import kotlinx.coroutines.flow.Flow

/** Process-owned write buffer; the repository coordinates its encrypted durable counterpart. */
interface WorkforceReadAdmissionDataSource {
    fun observe(key: String): Flow<WorkforceReadAdmissionDto?>

    fun current(key: String): WorkforceReadAdmissionDto?

    fun write(key: String, value: WorkforceReadAdmissionDto?)
}
