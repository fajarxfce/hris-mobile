package dev.fajar.starter.people.data.replica.datasources

import dev.fajar.starter.people.data.replica.dto.DirectoryReadAdmissionDto
import kotlinx.coroutines.flow.Flow

/** Process-owned write buffer; the repository coordinates its encrypted durable counterpart. */
interface DirectoryReadAdmissionDataSource {
    fun observe(key: String): Flow<DirectoryReadAdmissionDto?>

    fun current(key: String): DirectoryReadAdmissionDto?

    fun write(key: String, value: DirectoryReadAdmissionDto?)
}
