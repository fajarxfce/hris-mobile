@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.availability.domain

import dev.fajar.starter.availability.domain.entities.*
import dev.fajar.starter.availability.domain.repositories.AvailabilityRepository
import dev.fajar.starter.availability.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*

class MaintenanceExpiryTest {
    @Test
    fun cachedMaintenanceEndsWithoutNetworkAndANewerPolicyCancelsTheOldDeadline() = runTest {
        val snapshots =
            MutableStateFlow<AppResult<AppPolicy>>(
                AppResult.Success(
                    AppPolicy(maintenanceUntilEpochMillis = 1100, fetchedAtEpochMillis = 1000)
                )
            )
        val repository =
            object : AvailabilityRepository {
                override fun observe() = snapshots

                override suspend fun current() = snapshots.value

                override suspend fun dismiss(recommendedBuild: Long) = error("unused")
            }
        val clock =
            object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(1000 + testScheduler.currentTime)
            }
        val emitted = mutableListOf<AppAvailability>()
        backgroundScope.launch {
            ObserveAvailability(repository, AppBuild(AppPlatform.Android, 1, "1.0", null), clock)()
                .collect { emitted += (it as AppResult.Success).value }
        }
        runCurrent()
        assertIs<AppAvailability.Maintenance>(emitted.last())
        advanceTimeBy(50)
        snapshots.value =
            AppResult.Success(
                AppPolicy(maintenanceUntilEpochMillis = 1200, fetchedAtEpochMillis = 1000)
            )
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(AppAvailability.Maintenance(1200), emitted.last())
        advanceTimeBy(100)
        runCurrent()
        assertEquals(AppAvailability.Available, emitted.last())
    }
}
