package dev.fajar.starter.people.data.replica.datasources

import dev.fajar.starter.people.data.replica.dto.DirectoryRecordDto

interface DirectoryRecordDataSource {
    suspend fun record(sessionId: String, companyId: String, id: String): DirectoryRecordDto
}
