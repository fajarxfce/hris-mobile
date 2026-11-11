@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceSyncRepository
import dev.fajar.starter.collaboration.domain.usecases.SynchronizeCollaborationSpaceDevice
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SynchronizeCollaborationSpaceDeviceTest {
    @Test
    fun exactPublishedOriginalSyncsOnceWithoutRegistrationBootstrapOrKeyAcquisition() = runTest {
        val f = Fixture()
        f.setup.status = f.setup.status.copy(gateway = f.setup.status.gateway!!.copy(version = 3))
        assertEquals(AppResult.Success(Unit), f.run())
        assertEquals(1, f.calls)
        assertEquals(1, f.setup.registration.statusReads)
        assertEquals(0, f.original.installs)
        assertEquals(0, f.original.publications)
        assertTrue(f.setup.sent.isEmpty())
        assertTrue(f.setup.registration.sent.isEmpty())
        assertEquals(f.original.record, f.setup.saved())
    }

    @Test
    fun transportFailureDoesNotStartAnImplicitSecondSyncOrMetadataRequest() = runTest {
        val f = Fixture()
        f.beforeSync = { AppResult.Failed(f.setup.base.storageFailure) }
        assertEquals(f.setup.base.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(1, f.calls)
        assertEquals(1, f.setup.registration.statusReads)
        assertEquals(0, f.original.spaceCloses)
    }

    @Test
    fun retainedDenialWinsEvenWhenRuntimeCleanupFails() = runTest {
        val f = Fixture()
        f.setup.base.deny()
        f.original.beforeSpaceClose = { error("Fixture cleanup failure") }
        assertEquals(f.setup.base.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(1, f.original.spaceCloses)
        assertEquals(0, f.calls)
        assertEquals(0, f.setup.registration.statusReads)
    }

    @Test
    fun remoteDenialSurvivesFailedRestrictionAndCleanup() = runTest {
        val f = Fixture()
        f.setup.base.afterRestrict = { AppResult.Failed(f.setup.base.storageFailure) }
        f.original.beforeSpaceClose = { AppResult.Failed(f.setup.base.storageFailure) }
        f.setup.registration.beforeStatus = { AppResult.Failed(f.setup.base.denied) }
        assertEquals(f.setup.base.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(1, f.original.spaceCloses)
        assertEquals(0, f.calls)
    }

    @Test
    fun changedParentAfterRemoteStatusCannotStartSync() = runTest {
        val f = Fixture()
        f.setup.registration.beforeStatus = {
            f.original.transfer.replaceSession()
            AppResult.Success(f.observed())
        }
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(0, f.calls)
    }

    @Test
    fun denialDuringNativeSyncCannotPublishLateSuccess() = runTest {
        val f = Fixture()
        f.beforeSync = {
            f.setup.base.deny()
            AppResult.Success(Unit)
        }
        assertEquals(f.setup.base.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(1, f.calls)
        assertEquals(1, f.original.spaceCloses)
    }

    @Test
    fun supersededBootstrapDuringNativeSyncCannotAcknowledgeItsResponse() = runTest {
        val f = Fixture()
        f.beforeSync = {
            f.setup.records.value = AppResult.Success(f.original.record.copy(successorId = ID))
            AppResult.Success(Unit)
        }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(1, f.calls)
        assertEquals(ID, f.setup.saved()?.successorId)
    }

    @Test
    fun retiredRuntimeDuringSyncDoesNotBecomeReadyAgain() = runTest {
        val f = Fixture()
        f.beforeSync = {
            f.original.open = false
            AppResult.Success(Unit)
        }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(1, f.calls)
        assertFalse(f.original.open)
        assertEquals(0, f.original.installs)
    }

    @Test
    fun cancelledWaiterRejectsNonCooperativeLateSuccessWithoutAbandoningThePublishedParent() =
        runTest {
            val f = Fixture()
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            f.beforeSync = {
                entered.complete(Unit)
                withContext(NonCancellable) { finish.await() }
                AppResult.Success(Unit)
            }
            val waiting = async { f.run() }
            entered.await()
            waiting.cancel()
            finish.complete(Unit)
            assertFailsWith<CancellationException> { waiting.await() }
            assertEquals(1, f.calls)
            assertEquals(0, f.original.spaceCloses)
            assertTrue(f.original.open)
        }

    @Test
    fun concurrentWaiterIsBoundedAndTheDeadlineReleasesExecutionForAnExplicitLaterCall() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        f.beforeSync = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val first = async { f.run() }
        entered.await()
        assertEquals(
            "client.collaboration_space_sync_busy",
            assertIs<AppResult.Failed>(f.run()).failure.code,
        )
        assertEquals(1, f.calls)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(first.await()).failure.kind)
        f.beforeSync = { AppResult.Success(Unit) }
        assertEquals(AppResult.Success(Unit), f.run())
        assertEquals(2, f.calls)
    }

    @Test
    fun elapsedExpiryAndWallRollbackAfterSyncRejectTheResponse() = runTest {
        for (mode in 0..2) {
            val f = Fixture()
            f.beforeSync = {
                when (mode) {
                    0 -> f.setup.native.setup.now += 15.seconds
                    1 -> f.setup.native.time += 15.seconds
                    else -> f.setup.native.setup.now -= 1.seconds
                }
                AppResult.Success(Unit)
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(1, f.calls)
        }
    }

    @Test
    fun missingSupersededOrFailedLocalSetupCannotTriggerMetadataOrNativeIo() = runTest {
        for (mode in 0..2) {
            val f = Fixture()
            f.setup.records.value =
                when (mode) {
                    0 -> AppResult.Success(null)
                    1 -> AppResult.Success(f.original.record.copy(successorId = ID))
                    else -> AppResult.Failed(f.setup.base.storageFailure)
                }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.calls)
            assertEquals(0, f.setup.registration.statusReads)
        }
    }

    @Test
    fun onlyTheExactCurrentDeviceOrGatewayCanSupplyAnAuthoritativeRevocation() {
        val f = Fixture()
        val revoked = f.setup.status.copy(state = CollaborationDeviceState.REVOKED)
        assertEquals(
            FailureKind.AccessDenied,
            assertIs<AppResult.Failed>(f.admit(f.observed(revoked))).failure.kind,
        )
        val foreign = revoked.copy(id = ID)
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(f.admit(f.observed(foreign))).failure.kind,
        )
        val gateway = f.setup.status.gateway!!
        val stopped =
            f.setup.status.copy(gateway = gateway.copy(revokedAt = f.setup.status.observedAt))
        assertEquals(
            FailureKind.AccessDenied,
            assertIs<AppResult.Failed>(f.admit(f.observed(stopped))).failure.kind,
        )
        assertIs<AppResult.Failed>(
            f.admit(f.observed().copy(owner = f.runtime.owner.copy(spaceId = ID)))
        )
        assertIs<AppResult.Failed>(
            f.admit(f.observed(), platform = CollaborationNativePlatform.IOS)
        )
    }

    @Test
    fun remoteFamilyIntervalAllowsInitialAccessExpiryAndRejectsItsExactHardBoundary() {
        val f = Fixture()
        val gateway = f.setup.status.gateway!!
        val status =
            f.setup.status.copy(gateway = gateway.copy(accessExpiresAt = f.setup.status.observedAt))
        val observation = f.observed(status)
        val remaining = gateway.expiresAt - status.observedAt
        assertEquals(AppResult.Success(Unit), f.admit(observation))
        assertEquals(AppResult.Success(Unit), f.admit(observation, remaining - 1.seconds))
        assertIs<AppResult.Failed>(f.admit(observation, remaining))
        assertIs<AppResult.Failed>(f.admit(observation, -1.seconds))
    }

    @Test
    fun anExpiredRemoteFamilyCannotBeExtendedByAPhoneClockOrALateInitialResponse() = runTest {
        val f = Fixture()
        val now = f.setup.status.observedAt
        f.setup.status =
            f.setup.status.copy(
                gateway =
                    f.setup.status.gateway!!.copy(
                        accessExpiresAt = now,
                        expiresAt = now + 1.seconds,
                    )
            )
        f.setup.records.value =
            AppResult.Success(
                f.original.record.copy(
                    outcome =
                        CollaborationSpaceSetupOutcome.Bootstrapped(
                            f.original.gateway.copy(
                                accessExpiresAtEpochMillis = now.toEpochMilliseconds(),
                                expiresAtEpochMillis = (now + 1.seconds).toEpochMilliseconds(),
                            )
                        )
                )
            )
        f.beforeSync = {
            f.setup.native.time += 1.seconds
            AppResult.Success(Unit)
        }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(1, f.calls)
    }

    @Test
    fun nativeAccessDenialKeepsItsClassificationWhenRestrictionStorageFails() = runTest {
        val f = Fixture()
        f.setup.base.afterRestrict = { AppResult.Failed(f.setup.base.storageFailure) }
        f.beforeSync = { AppResult.Failed(f.setup.base.denied) }
        assertEquals(f.setup.base.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(1, f.original.spaceCloses)
    }

    private class Fixture {
        val original = SpaceRuntimeAdoptionFixture().also { it.open = true }
        val setup = original.setup
        val runtime =
            CollaborationSpaceRuntimeReady(
                setup.owner,
                original.request.deviceId,
                original.operation,
            )
        var calls = 0
        var beforeSync: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
        private val sync =
            object : CollaborationSpaceSyncRepository {
                override fun incomingSource(
                    runtime: CollaborationSpaceRuntimeReady
                ): CollaborationSpaceIncomingSource? =
                    error("No incoming observation during synchronization")

                override fun observeBatches(
                    runtime: CollaborationSpaceRuntimeReady
                ): kotlinx.coroutines.flow.Flow<AppResult<CollaborationSpaceSyncBatch>> =
                    error("No incoming observation during synchronization")

                override suspend fun synchronize(
                    runtime: CollaborationSpaceRuntimeReady
                ): AppResult<Unit> {
                    assertEquals(this@Fixture.runtime, runtime)
                    calls++
                    return beforeSync()
                }
            }
        private val useCase =
            SynchronizeCollaborationSpaceDevice(
                setup.commands,
                setup.registration.devices,
                original.runtimes,
                sync,
                setup.base.directory,
                setup.base.sessions,
                setup.native.server,
                setup.build,
                setup.native.clock,
                setup.native.time,
            )

        suspend fun run() = useCase(runtime)

        fun observed(status: CollaborationSpaceDeviceStatus = setup.status) =
            CollaborationSpaceDeviceObservation(setup.owner, status)

        fun admit(
            observed: CollaborationSpaceDeviceObservation,
            elapsed: Duration = Duration.ZERO,
            platform: CollaborationNativePlatform = CollaborationNativePlatform.ANDROID,
        ) =
            admitCollaborationSpaceInstalledDevice(
                runtime,
                original.record,
                observed,
                platform,
                elapsed,
            )
    }

    private companion object {
        const val ID = "90000000-0000-4000-8000-000000000099"
    }
}
