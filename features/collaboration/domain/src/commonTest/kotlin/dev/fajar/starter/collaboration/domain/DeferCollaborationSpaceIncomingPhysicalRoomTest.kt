package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DeferCollaborationSpaceIncomingPhysicalRoomTest {
    private val stages = listOf("demand", "write", "committed", "readback")

    @Test
    fun dependencyDeferralPersistsOneRetryAndPreservesWakeCursorAndInventory() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val before = assertNotNull(f.request.snapshot).demand
        val wake = f.request.wake
        assertEquals(AppResult.Success(true), f.defer(f.runtime, f.physical))
        val after = assertNotNull(f.request.snapshot).demand
        assertEquals(
            before,
            after.copy(nextPosition = before.nextPosition, physicalRooms = before.physicalRooms),
        )
        assertNotNull(after.physicalRooms.single().retry)
        assertEquals(wake, f.request.wake)
        assertEquals(1, f.request.writes)
        assertEquals(0, f.request.schedules)
        assertFalse("registration" in f.base.calls || "binding" in f.base.calls)
    }

    @Test
    fun repeatedCallbackAfterLostResponseDoesNotWriteAgain() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.request.responseLoss = true
        assertEquals(AppResult.Success(true), f.defer(f.runtime, f.physical))
        val retained = f.request.snapshot
        assertEquals(AppResult.Success(true), f.defer(f.runtime, f.physical))
        assertEquals(retained, f.request.snapshot)
        assertEquals(1, f.request.writes)
    }

    @Test
    fun lostReplyWithLaterProducerCursorStillConfirmsExactDeferredTask() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.request.responseLoss = true
        f.base.hooks["committed"] = {
            val current = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                current.copy(
                    generation = f.id(901),
                    demand =
                        current.demand.copy(
                            sources =
                                listOf(
                                    current.demand.sources
                                        .single()
                                        .copy(position = "later", inventoryGeneration = f.id(902))
                                )
                        ),
                )
        }
        assertEquals(AppResult.Success(true), f.defer(f.runtime, f.physical))
        assertEquals("later", f.request.snapshot?.demand?.sources?.single()?.position)
        assertEquals(1, f.request.writes)
    }

    @Test
    fun concurrentProducerBeforeCasReturnsShortRetryWithoutAnotherWrite() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.base.hooks["write"] = {
            val current = assertNotNull(f.request.snapshot)
            f.request.snapshot =
                current.copy(
                    generation = f.id(901),
                    demand =
                        current.demand.copy(
                            sources =
                                listOf(current.demand.sources.single().copy(position = "later"))
                        ),
                )
        }
        val result = assertIs<AppResult.Failed>(f.defer(f.runtime, f.physical))
        assertTrue(isCollaborationSpaceIncomingQueueContention(result.failure))
        assertEquals(1L, result.failure.retryAfterSeconds)
        assertEquals(1, f.request.writes)
        assertEquals(listOf(f.physical), f.request.snapshot?.demand?.physicalRooms)
    }

    @Test
    fun busyConversionDoesNotBecomeADurableThirtySecondDelay() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val busy = collaborationSpaceIncomingQueueContention()
        assertEquals(busy, f.defer(f.runtime, f.physical, busy.failure))
        assertEquals(0, f.request.reads)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun obsoleteCaptureCannotModifyNewPhysicalGeneration() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        val current = assertNotNull(f.request.snapshot)
        f.request.snapshot =
            current.copy(
                demand =
                    current.demand.copy(
                        physicalRooms = listOf(f.physical.copy(generation = f.id(999)))
                    )
            )
        val retained = f.request.snapshot
        assertEquals(AppResult.Success(false), f.defer(f.runtime, f.physical))
        assertEquals(retained, f.request.snapshot)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun originalStorageFailureCannotBeConfirmedWithoutExactDeferredTask() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.base.failures["write"] = f.base.directory.storageFailure
        assertEquals(
            f.base.directory.storageFailure,
            assertIs<AppResult.Failed>(f.defer(f.runtime, f.physical)).failure,
        )
        assertEquals(1, f.request.writes)
    }

    @Test
    fun confirmedDenialWinsOverLateFailureAtEveryStorageBoundary() = runTest {
        for (stage in stages) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            f.base.hooks[stage] = { f.base.directory.deny() }
            f.base.failures[stage] = f.base.directory.storageFailure
            assertEquals(
                f.base.directory.denied,
                assertIs<AppResult.Failed>(f.defer(f.runtime, f.physical)).failure,
                stage,
            )
        }
    }

    @Test
    fun runtimeSourceAndNativeParentReplacementFencePendingDeferrals() = runTest {
        for (stage in stages) for (native in listOf(false, true)) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            f.base.hooks[stage] = {
                if (native) {
                    val setup = f.base.native.setup
                    val current = assertNotNull(setup.session)
                    setup.session =
                        current.copy(binding = current.binding.copy(serverId = f.id(999)))
                } else f.installed = f.source.copy(matrixDeviceId = "OTHER")
            }
            assertEquals(
                if (native) FailureKind.Unauthorized else FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.defer(f.runtime, f.physical)).failure.kind,
                stage,
            )
        }
    }

    @Test
    fun initialDenialWinsEvenIfTheOriginalSourceClosedOrBusyWasReported() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.base.directory.deny()
        f.installed = null
        assertEquals(
            f.base.directory.denied,
            assertIs<AppResult.Failed>(
                    f.defer(
                        f.runtime,
                        f.physical,
                        collaborationSpaceIncomingQueueContention().failure,
                    )
                )
                .failure,
        )
        assertEquals(0, f.request.reads)
    }

    @Test
    fun ordinaryCredentialAgeDoesNotHideOwnedDeferral() = runTest {
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.base.native.setup.now += 365.days
        assertEquals(AppResult.Success(true), f.defer(f.runtime, f.physical))
    }

    @Test
    fun everyPendingStorageStageHasAFiveSecondBudgetAndPropagatesCancellation() = runTest {
        for (stage in stages) {
            val f = SpaceIncomingPhysicalResolutionFixture()
            var stopped = false
            f.base.hooks[stage] = {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            val started = currentTime
            assertEquals(
                FailureKind.Timeout,
                assertIs<AppResult.Failed>(f.defer(f.runtime, f.physical)).failure.kind,
            )
            assertEquals(5000L, currentTime - started)
            assertTrue(stopped)
            assertEquals(0, f.request.schedules)
        }
        val f = SpaceIncomingPhysicalResolutionFixture()
        f.base.hooks["write"] = { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { f.defer(f.runtime, f.physical) }
        assertEquals(1, f.request.writes)
    }
}
