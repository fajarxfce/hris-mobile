package dev.fajar.starter.sync.data.repositories

import dev.fajar.starter.sync.data.datasources.WorkScheduler
import dev.fajar.starter.sync.data.errors.safeWorkCall
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import org.koin.core.annotation.Single

@Single
class PlatformSyncScheduleRepository(private val scheduler: WorkScheduler) :
    SyncScheduleRepository {
    override suspend fun request(key: String) = safeWorkCall { scheduler.enqueue(key) }
}
