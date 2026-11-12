package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.SdkRuntimeMonitor
import dev.fajar.starter.collaboration.data.repositories.SdkRuntimeRegistry
import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SdkRuntimeMonitorTest {
    @Test
    fun nestedOwnershipTransitionReturnsAndReleasesTheMonitorAfterFailure() {
        val monitor = SdkRuntimeMonitor()
        val failure = IllegalStateException("fixture")
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                monitor.locked { monitor.locked { throw failure } }
            },
        )
        assertEquals("available", monitor.locked { monitor.locked { "available" } })
    }

    @Test
    fun competingReservationsNeverExceedTheSharedCapacity() = runTest {
        val registry = SdkRuntimeRegistry<String>({ true }, {})
        val owner =
            CollaborationNativeParent("local", "https://fixture.test/", "account", "native")
                .owner("company")
        val start = CompletableDeferred<Unit>()
        val attempts =
            (0 until 24).map { index ->
                async(Dispatchers.Default) {
                    start.await()
                    registry.reserveForeground("store-$index", owner, "device-$index")
                }
            }
        start.complete(Unit)
        val results = attempts.awaitAll()
        val accepted = results.filterIsInstance<AppResult.Success<*>>()
        assertEquals(5, accepted.size)
        val rejected = results.filterIsInstance<AppResult.Failed>()
        assertEquals(19, rejected.size)
        assertTrue(rejected.all { it.failure.code == "client.collaboration_runtime_capacity" })
        assertTrue(registry.resources().isEmpty())
    }

    @Test
    fun competingInspectionRetainsOneUnfinishedConstruction() = runTest {
        val registry = SdkRuntimeRegistry<String>({ true }, {})
        val owner =
            CollaborationNativeParent("local", "https://fixture.test/", "account", "native")
                .owner("company")
        val start = CompletableDeferred<Unit>()
        val attempts =
            (0 until 24).map {
                async(Dispatchers.Default) {
                    start.await()
                    registry.reserveInspection("same-store", owner, "device")
                }
            }
        start.complete(Unit)
        val results = attempts.awaitAll()
        assertEquals(1, results.count { it is AppResult.Success })
        val rejected = results.filterIsInstance<AppResult.Failed>()
        assertEquals(23, rejected.size)
        assertTrue(rejected.all { it.failure.code == "client.collaboration_runtime_opening" })
        assertTrue(registry.resources().isEmpty())
    }
}
