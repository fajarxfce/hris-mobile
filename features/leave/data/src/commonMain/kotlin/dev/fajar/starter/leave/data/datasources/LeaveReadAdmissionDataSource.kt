package dev.fajar.starter.leave.data.datasources

import dev.fajar.starter.leave.data.dto.LeaveReadAdmissionDto
import kotlinx.coroutines.flow.Flow

/** Process-owned write buffer for admission records whose durable write may still fail. */
interface LeaveReadAdmissionDataSource {
    fun observe(key: String): Flow<LeaveReadAdmissionDto?>

    fun current(key: String): LeaveReadAdmissionDto?

    fun write(key: String, value: LeaveReadAdmissionDto?)
}
