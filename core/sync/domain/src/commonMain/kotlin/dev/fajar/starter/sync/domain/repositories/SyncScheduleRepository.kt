package dev.fajar.starter.sync.domain.repositories

import dev.fajar.starter.common.result.AppResult

interface SyncScheduleRepository {
    /** Requests execution; durable application data stays in the database outbox. */
    suspend fun request(key: String): AppResult<Unit>
}
