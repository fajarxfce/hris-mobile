package dev.fajar.starter.workforce.data.overtime.datasources

import dev.fajar.starter.workforce.data.overtime.dto.OvertimeReadAdmissionDto
import kotlinx.coroutines.flow.Flow

/** Process-owned write buffer for admission records whose durable write may still fail. */
interface OvertimeReadAdmissionDataSource {
    fun observe(key: String): Flow<OvertimeReadAdmissionDto?>

    fun current(key: String): OvertimeReadAdmissionDto?

    fun write(key: String, value: OvertimeReadAdmissionDto?)
}
