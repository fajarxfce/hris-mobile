package dev.fajar.starter.fixtures

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceSnapshot
import dev.fajar.starter.workforce.domain.attendance.repositories.AttendanceCacheRepository
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A single-company cache whose expiry can be advanced during an assembled UI request. */
class NativeAttendanceCacheUi : AttendanceCacheRepository {
    private val snapshot = MutableStateFlow<AttendanceSnapshot?>(null)

    override fun observe(access: WorkforceAccess) = snapshot.map { AppResult.Success(it) }

    override suspend fun generation(access: WorkforceAccess) =
        AppResult.Success(snapshot.value?.generation)

    override suspend fun replace(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: AttendanceSnapshot?,
    ): AppResult<Boolean> {
        val previous = this.snapshot.value
        return AppResult.Success(
            previous?.generation == expectedGeneration &&
                this.snapshot.compareAndSet(previous, snapshot)
        )
    }

    fun expire() {
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
