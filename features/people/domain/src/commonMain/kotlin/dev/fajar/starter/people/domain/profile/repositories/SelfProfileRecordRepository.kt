package dev.fajar.starter.people.domain.profile.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.people.domain.profile.entities.*

interface SelfProfileRecordRepository {
    suspend fun record(access: SelfProfileAccess, id: String): AppResult<SelfProfileRecord>
}
