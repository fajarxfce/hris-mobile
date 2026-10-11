package dev.fajar.starter.location.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.location.data.datasources.LocationDataSource
import dev.fajar.starter.location.data.dto.*
import dev.fajar.starter.location.data.errors.*
import dev.fajar.starter.location.data.repositories.DeviceLocationRepository
import dev.fajar.starter.location.domain.entities.LocationSettings
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class DeviceLocationRepositoryTest {
    @Test
    fun cancellationAndLateExceptionsRemainCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reports = 0
        val work = async {
            safeLocationCall(onException = { reports++ }) {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    throw LocationUnavailableException()
                }
            }
        }
        entered.await()
        work.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { work.await() }
        assertEquals(0, reports)
    }

    @Test
    fun technicalErrorsAreClassifiedWithoutRawMessages() = runTest {
        val permission =
            assertIs<AppResult.Failed>(
                safeLocationCall(onException = {}) { throw LocationPermissionException() }
            )
        assertEquals(FailureKind.Permission, permission.failure.kind)
        val failure =
            assertIs<AppResult.Failed>(
                safeLocationCall(onException = {}) {
                    throw IllegalStateException("PRIVATE COORDINATES")
                }
            )
        assertFalse(failure.failure.message.contains("PRIVATE"))
    }

    @Test
    fun invalidFixAndSettingsFalseAreMappedWithoutRequestingAccess() = runTest {
        var requested = 0
        val source =
            object : LocationDataSource {
                override suspend fun access() =
                    LocationAccessDto(LocationPermissionDto.Granted, true)

                override suspend fun requestPermission(): LocationAccessDto {
                    requested++
                    return access()
                }

                override suspend fun current() = LocationFixDto(Double.NaN, 1.0, 1.0, 1, false)

                override suspend fun openSettings(target: LocationSettingsDto) = false
            }
        val repository = DeviceLocationRepository(source)
        assertIs<AppResult.Failed>(repository.current())
        assertEquals(
            "client.location_settings_unavailable",
            assertIs<AppResult.Failed>(repository.openSettings(LocationSettings.Application))
                .failure
                .code,
        )
        assertEquals(0, requested)
    }
}
