package dev.fajar.starter.people.data.profile.datasources

import dev.fajar.starter.people.data.profile.dto.SelfProfileReadAdmissionDto
import kotlinx.coroutines.flow.Flow

/** Process-owned write buffer; the repository coordinates its encrypted durable counterpart. */
interface SelfProfileReadAdmissionDataSource {
    fun observe(key: String): Flow<SelfProfileReadAdmissionDto?>

    fun current(key: String): SelfProfileReadAdmissionDto?

    fun write(key: String, value: SelfProfileReadAdmissionDto?)
}
