package dev.fajar.starter.people.domain.replica.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.people.domain.replica.entities.*

interface DirectoryRecordRepository {
    suspend fun record(access: DirectoryAccess, id: String): AppResult<DirectoryRecord>
}
