@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.location.data

import dev.fajar.starter.location.data.datasources.AppleLocationDataSource
import dev.fajar.starter.location.data.errors.LocationUnavailableException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager

class AppleLocationDataSourceTest {
    @Test
    fun aCompletedFixCannotStopTheNextAcquisition() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val manager = RecordingLocationManager()
        val source = AppleLocationDataSource(manager)
        try {
            val capture =
                launch(Dispatchers.Main) {
                    source.current()
                    source.current()
                }
            assertEquals(1, manager.starts)

            source.locationManager(
                manager,
                didUpdateLocations = listOf(CLLocation(latitude = -6.17, longitude = 106.82)),
            )

            assertEquals(2, manager.starts)
            assertEquals(1, manager.stops)
            assertTrue(capture.isActive)
            source.close()
            capture.join()
            assertTrue(capture.isCancelled)
        } finally {
            source.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun closingTheSourceCancelsItsFixAndRejectsAnotherAcquisition() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val manager = RecordingLocationManager()
        val source = AppleLocationDataSource(manager)
        try {
            val capture = launch(Dispatchers.Main) { source.current() }
            assertEquals(1, manager.starts)
            source.close()
            capture.join()
            assertTrue(capture.isCancelled)
            assertFailsWith<LocationUnavailableException> { source.current() }
            assertFailsWith<LocationUnavailableException> { source.requestPermission() }
            assertEquals(1, manager.starts)
        } finally {
            source.close()
            Dispatchers.resetMain()
        }
    }
}

private class RecordingLocationManager : CLLocationManager() {
    var starts = 0
        private set

    var stops = 0
        private set

    override fun startUpdatingLocation() {
        starts++
    }

    override fun stopUpdatingLocation() {
        stops++
    }
}
