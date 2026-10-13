package dev.fajar.starter.fixtures

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.repositories.WorkCalendarCacheRepository
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A single-company cache for controlled read-denial and stale-data integration scenarios. */
class NativeCalendarCacheUi : WorkCalendarCacheRepository {
    private val snapshots = MutableStateFlow<WorkCalendarSnapshot?>(null)
    @Volatile var rejectClear = false

    override fun observe(access: WorkforceAccess) = snapshots.map { AppResult.Success(it) }

    override suspend fun generation(access: WorkforceAccess) =
        AppResult.Success(snapshots.value?.generation)

    override suspend fun replace(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: WorkCalendarSnapshot?,
    ): AppResult<Boolean> {
        if (snapshot == null && rejectClear)
            return AppResult.Failed(
                Failure(FailureKind.Storage, "Storage unavailable", code = "client.storage")
            )
        val previous = snapshots.value
        return AppResult.Success(
            previous?.generation == expectedGeneration &&
                snapshots.compareAndSet(previous, snapshot)
        )
    }

    fun subscribers() = snapshots.subscriptionCount.value

    fun markStale() {
        val previous = checkNotNull(snapshots.value)
        check(
            snapshots.compareAndSet(
                previous,
                previous.copy(
                    fetchedAt = previous.fetchedAt - 2.hours,
                    refreshAfter = previous.fetchedAt - 1.hours,
                ),
            )
        )
    }
}
