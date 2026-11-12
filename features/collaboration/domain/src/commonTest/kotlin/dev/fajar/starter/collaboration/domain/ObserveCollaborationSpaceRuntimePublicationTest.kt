@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceRuntimePublication
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationSpaceRuntimePublicationTest {
    @Test
    fun unsupportedRuntimeIsAbsentWithoutAcquisition() = runTest {
        val f = Fixture()
        assertEquals(
            listOf(AppResult.Success(null)),
            ObserveCollaborationSpaceRuntimePublication(f.base.runtimes)(f.runtime).toList(),
        )
        assertEquals(0, f.base.installs)
        assertEquals(0, f.base.spaceCloses)
    }

    @Test
    fun emptyRuntimeStaysAbsentWithoutPollingOrOpening() = runTest {
        val f = Fixture()
        assertEquals(AppResult.Success(null), f.observe().first())
        assertEquals(1, f.starts)
        assertEquals(1, f.stops)
        assertEquals(0, f.base.installs)
        assertEquals(0, f.base.publications)
        assertEquals(0, f.base.closes)
    }

    @Test
    fun invalidRequestedIdentityNeverSubscribes() = runTest {
        val f = Fixture()
        for (runtime in
            listOf(
                f.runtime.copy(deviceId = "invalid"),
                f.runtime.copy(operationId = "invalid"),
                f.runtime.copy(owner = f.runtime.owner.copy(spaceId = "invalid")),
            )) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.useCase(runtime).single()).failure.kind,
            )
        }
        assertEquals(0, f.starts)
    }

    @Test
    fun lateSubscriberReceivesTheExistingInstance() = runTest {
        val f = Fixture()
        f.values.value = AppResult.Success(f.publication)
        assertEquals(AppResult.Success(f.publication), f.observe().first())
    }

    @Test
    fun sameLogicalRuntimeWithNewInstanceIsNotDeduplicated() = runTest {
        val f = Fixture()
        val values = mutableListOf<AppResult<CollaborationSpaceRuntimePublication?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        f.values.value = AppResult.Success(f.publication)
        runCurrent()
        val next = f.publication.copy(instanceId = "20000000-0000-4000-8000-000000000002")
        f.values.value = AppResult.Success(next)
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceRuntimePublication?>>(
                AppResult.Success(null),
                AppResult.Success(f.publication),
                AppResult.Success(next),
            ),
            values,
        )
        job.cancelAndJoin()
        assertEquals(1, f.stops)
    }

    @Test
    fun retirementRemovesReadyIdentity() = runTest {
        val f = Fixture()
        f.values.value = AppResult.Success(f.publication)
        val values = mutableListOf<AppResult<CollaborationSpaceRuntimePublication?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        f.values.value = AppResult.Success(null)
        runCurrent()
        assertEquals(
            listOf<AppResult<CollaborationSpaceRuntimePublication?>>(
                AppResult.Success(f.publication),
                AppResult.Success(null),
            ),
            values,
        )
        job.cancelAndJoin()
    }

    @Test
    fun foreignPublishedCoordinatesNeverBecomeReady() = runTest {
        val f = Fixture()
        for (runtime in
            listOf(
                f.runtime.copy(deviceId = f.runtime.operationId),
                f.runtime.copy(operationId = f.runtime.deviceId),
                f.runtime.copy(owner = f.runtime.owner.copy(spaceId = f.runtime.operationId)),
            )) {
            assertNotEquals(f.runtime, runtime)
            f.values.value = AppResult.Success(f.publication.copy(runtime = runtime))
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.observe().first()).failure.kind,
            )
        }
    }

    @Test
    fun malformedInstanceCannotMasqueradeAsPublication() = runTest {
        val f = Fixture()
        f.values.value = AppResult.Success(f.publication.copy(instanceId = "invalid"))
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.observe().first()).failure.kind,
        )
    }

    @Test
    fun credentialFailureAndLaterRecoveryBothRemainObservable() = runTest {
        val f = Fixture()
        val values = mutableListOf<AppResult<CollaborationSpaceRuntimePublication?>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        val failed =
            AppResult.Failed(Failure(FailureKind.Unauthorized, "fixture", code = "fixture.auth"))
        f.values.value = failed
        runCurrent()
        f.values.value = AppResult.Success(f.publication)
        runCurrent()
        assertEquals(
            listOf(AppResult.Success(null), failed, AppResult.Success(f.publication)),
            values,
        )
        assertEquals(1, f.starts)
        job.cancelAndJoin()
    }

    @Test
    fun cancellingOneObserverDoesNotCloseTheRuntimeOrOtherObserver() = runTest {
        val f = Fixture()
        val first =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().collect()
            }
        val second =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().collect()
            }
        first.cancelAndJoin()
        assertTrue(second.isActive)
        assertEquals(1, f.stops)
        assertEquals(0, f.base.closes)
        second.cancelAndJoin()
        assertEquals(2, f.stops)
    }

    @Test
    fun finiteRepositoryCompletionNeverStartsAnObservationLoop() = runTest {
        val f = Fixture()
        var starts = 0
        val repo =
            object : CollaborationSpaceRuntimeRepository by f.base.runtimes {
                override fun observePublication(runtime: CollaborationSpaceRuntimeReady) = flow {
                    starts++
                    emit(AppResult.Success(f.publication))
                }
            }
        assertEquals(
            listOf(AppResult.Success(f.publication)),
            ObserveCollaborationSpaceRuntimePublication(repo)(f.runtime).toList(),
        )
        assertEquals(1, starts)
    }

    @Test
    fun publicationDiagnosticsDoNotPrintAccountOrInstanceIdentity() {
        val f = Fixture()
        val text = f.publication.toString()
        assertFalse(text.contains(f.publication.instanceId))
        assertFalse(text.contains(f.runtime.owner.parent.accountId))
    }

    private class Fixture {
        val base = SpaceRuntimeAdoptionFixture()
        val runtime =
            CollaborationSpaceRuntimeReady(base.setup.owner, base.leaseDevice, base.leaseOperation)
        val publication =
            CollaborationSpaceRuntimePublication(runtime, "20000000-0000-4000-8000-000000000001")
        val values =
            MutableStateFlow<AppResult<CollaborationSpaceRuntimePublication?>>(
                AppResult.Success(null)
            )
        var starts = 0
        var stops = 0
        val repo =
            object : CollaborationSpaceRuntimeRepository by base.runtimes {
                override fun observePublication(runtime: CollaborationSpaceRuntimeReady) = flow {
                    assertEquals(this@Fixture.runtime, runtime)
                    starts++
                    try {
                        emitAll(values)
                    } finally {
                        stops++
                    }
                }
            }
        val useCase = ObserveCollaborationSpaceRuntimePublication(repo)

        fun observe() = useCase(runtime)
    }
}
