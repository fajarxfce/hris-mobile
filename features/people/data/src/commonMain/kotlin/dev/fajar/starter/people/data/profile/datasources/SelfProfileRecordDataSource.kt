package dev.fajar.starter.people.data.profile.datasources

import dev.fajar.starter.people.data.profile.dto.SelfProfileRecordDto

interface SelfProfileRecordDataSource {
    suspend fun record(sessionId: String, companyId: String, id: String): SelfProfileRecordDto
}
