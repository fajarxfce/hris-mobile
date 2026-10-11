package dev.fajar.starter.workforce.data.datasources

import dev.fajar.starter.workforce.data.dto.*

interface WorkforceRemoteDataSource {
    suspend fun selfEmployments(
        sessionId: String,
        companyId: String,
        after: String?,
    ): WorkforceDirectoryDto

    suspend fun calendar(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
    ): WorkCalendarDto
}
