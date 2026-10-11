package dev.fajar.starter.fixtures

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.repositories.AttendanceCacheRepository
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A single-company cache whose expiry can be advanced during an assembled UI request. */
class NativeAttendanceCacheUi : AttendanceCacheRepository {
    private val snapshots =
        AttendanceWindow.entries.associateWith { MutableStateFlow<AttendanceSnapshot?>(null) }

    override fun observe(access: WorkforceAccess, window: AttendanceWindow) =
        snapshots.getValue(window).map { AppResult.Success(it) }

    override suspend fun generation(access: WorkforceAccess, window: AttendanceWindow) =
        AppResult.Success(snapshots.getValue(window).value?.generation)

    override suspend fun replace(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: AttendanceSnapshot?,
        window: AttendanceWindow,
    ): AppResult<Boolean> {
        val destination = snapshots.getValue(window)
        val previous = destination.value
        return AppResult.Success(
            previous?.generation == expectedGeneration &&
                destination.compareAndSet(previous, snapshot)
        )
    }

    fun subscribers(window: AttendanceWindow) = snapshots.getValue(window).subscriptionCount.value

    fun expire(window: AttendanceWindow = AttendanceWindow.RECENT) {
        val snapshot = snapshots.getValue(window)
        val previous = checkNotNull(snapshot.value)
        check(
            snapshot.compareAndSet(
                previous,
                previous.copy(
                    fetchedAt = previous.fetchedAt - 2.hours,
                    expiresAt = previous.fetchedAt - 1.hours,
                ),
            )
        )
    }
}
