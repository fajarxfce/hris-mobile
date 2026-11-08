package dev.fajar.starter.sync.data.repositories

import dev.fajar.starter.observability.PerformanceOperation
import dev.fajar.starter.observability.measureAppResult
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import dev.fajar.starter.sync.data.errors.safeWorkCall
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import org.koin.core.annotation.Single

@Single
class PlatformSyncScheduleRepository(private val scheduler: Lazy<WorkScheduler>) :
    SyncScheduleRepository {
    // Platform registration may inspect the tasks which themselves request subsequent work.
    // Resolve the scheduler for an operation, after those task constructors have completed.
    override suspend fun request(key: String) =
        measureAppResult(PerformanceOperation.WorkScheduling) {
            safeWorkCall { scheduler.value.enqueue(key) }
        }
}
