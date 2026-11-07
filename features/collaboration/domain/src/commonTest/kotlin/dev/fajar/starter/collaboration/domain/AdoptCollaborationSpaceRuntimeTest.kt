package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRuntimeLease
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class AdoptCollaborationSpaceRuntimeTest {
    @Test
    fun normalTokenRotationKeepsTheOriginalBootstrapEligibleForRestore() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        f.setup.status = f.setup.status.copy(gateway = f.setup.status.gateway!!.copy(version = 3))
        assertIs<AppResult.Success<*>>(f.run())
        assertEquals(1, f.installs)
        assertEquals(1, f.publications)
        assertEquals(f.record, f.setup.saved())
    }

    @Test
    fun aFailedPrivateAcquisitionReturnsWithoutAnotherRemoteRequest() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        f.beforeInstall = { AppResult.Failed(f.setup.base.storageFailure) }
        assertEquals(f.setup.base.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(1, f.setup.registration.statusReads)
        assertEquals(0, f.closes)
        assertEquals(0, f.publications)
    }

    @Test
    fun originalStoreIsPublishedOnlyAfterSecondCurrentDeviceAndDurableSetupRead() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        f.beforePublish = {
            assertEquals(2, f.setup.registration.statusReads)
            assertEquals(f.record, f.setup.saved())
            AppResult.Success(Unit)
        }
        val ready = assertIs<AppResult.Success<CollaborationSpaceRuntimeReady>>(f.run()).value
        assertEquals(
            CollaborationSpaceRuntimeReady(f.setup.owner, f.request.deviceId, f.operation),
            ready,
        )
        assertEquals(1, f.installs)
        assertEquals(1, f.publications)
        assertEquals(0, f.closes)
        assertTrue(f.open)
        assertEquals(0, f.setup.base.admits)
        assertTrue(f.setup.sent.isEmpty())
        assertTrue(f.setup.registration.sent.isEmpty())
    }

    @Test
    fun anAlreadyPublishedExactOperationRevalidatesWithoutReopeningThePrivateStore() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        f.open = true
        assertIs<AppResult.Success<*>>(f.run())
        assertEquals(0, f.installs)
        assertEquals(0, f.publications)
        assertEquals(2, f.setup.registration.statusReads)
        assertTrue(f.open)
    }

    @Test
    fun aRetainedDenialStopsTheExactOrganizationEvenWhenCleanupFails() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        f.open = true
        f.setup.base.deny()
        f.beforeSpaceClose = { AppResult.Failed(f.setup.base.storageFailure) }
        assertEquals(f.setup.base.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(1, f.spaceCloses)
        assertEquals(0, f.installs)
        assertEquals(0, f.setup.registration.statusReads)
        assertEquals(0, f.setup.base.admits)
    }

    @Test
    fun nativeParentReplacementAfterRestoreClosesTheLateLeaseWithoutPublication() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        f.beforeInstall = {
            f.transfer.replaceSession()
            AppResult.Success(f.lease)
        }
        assertEquals(FailureKind.Unauthorized, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(1, f.closes)
        assertEquals(1, f.spaceCloses)
        assertEquals(0, f.publications)
        assertEquals(1, f.setup.registration.statusReads)
    }

    @Test
    fun aSupersededDurableBootstrapCannotPublishAfterThePrivateRestore() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        f.beforeInstall = {
            f.setup.records.value =
                AppResult.Success(
                    f.record.copy(
                        command =
                            f.record.command.copy(
                                request =
                                    f.request.copy(
                                        operationId = "30000000-0000-4000-8000-000000000099"
                                    )
                            )
                    )
                )
            AppResult.Success(f.lease)
        }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(1, f.closes)
        assertEquals(0, f.publications)
        assertNotEquals(f.record, f.setup.saved())
    }

    @Test
    fun confirmedDenialAfterRestoreRemainsAuthoritativeThroughFailedRestrictionAndCleanup() =
        runTest {
            val f = SpaceRuntimeAdoptionFixture()
            f.setup.base.afterRestrict = { AppResult.Failed(f.setup.base.storageFailure) }
            f.beforeClose = { AppResult.Failed(f.setup.base.storageFailure) }
            f.beforeSpaceClose = { error("Cleanup unavailable") }
            f.beforeInstall = {
                f.setup.registration.beforeStatus = { AppResult.Failed(f.setup.base.denied) }
                AppResult.Success(f.lease)
            }
            assertEquals(f.setup.base.denied, assertIs<AppResult.Failed>(f.run()).failure)
            assertEquals(1, f.closes)
            assertEquals(1, f.spaceCloses)
            assertEquals(0, f.publications)
            assertEquals(0, f.setup.base.admits)
        }

    @Test
    fun cancellationOwnsAndClosesANonCooperativeLateSuccessfulLease() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeInstall = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(f.lease)
        }
        val job = launch { f.run() }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, f.closes)
        assertEquals(0, f.publications)
        assertEquals(1, f.setup.registration.statusReads)
    }

    @Test
    fun aCredentialWriteFailureCannotPublishTheRestoredClient() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        f.beforeInstall = {
            f.credentialFailures.value = f.setup.base.storageFailure
            AppResult.Success(f.lease)
        }
        assertEquals(f.setup.base.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(0, f.publications)
        assertEquals(1, f.closes)
    }

    @Test
    fun aWrongReturnedOwnerDeviceOrOperationIsClosed() = runTest {
        for (field in 0..2) {
            val f = SpaceRuntimeAdoptionFixture()
            when (field) {
                0 -> f.leaseOwner = f.setup.owner.copy(spaceId = "foreign")
                1 -> f.leaseDevice = "foreign"
                else -> f.leaseOperation = "foreign"
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(1, f.closes)
            assertEquals(0, f.publications)
        }
    }

    @Test
    fun expiryElapsedDeadlineAndWallRollbackDuringRestoreNeverPublish() = runTest {
        for (mode in 0..2) {
            val f = SpaceRuntimeAdoptionFixture()
            f.beforeInstall = {
                when (mode) {
                    0 -> f.setup.native.setup.now += 15.seconds
                    1 -> f.setup.native.time += 15.seconds
                    else -> f.setup.native.setup.now -= 1.seconds
                }
                AppResult.Success(f.lease)
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(1, f.closes)
            assertEquals(0, f.publications)
        }
    }

    @Test
    fun theGatewayHardIntervalIncludesTheWholeStatusRequestAndSubsequentLocalReads() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        f.setup.status =
            f.setup.status.copy(
                gateway =
                    f.setup.status.gateway!!.copy(
                        accessExpiresAt = f.setup.native.setup.now,
                        expiresAt = f.setup.native.setup.now + 1.seconds,
                    )
            )
        f.setup.records.value =
            AppResult.Success(
                f.record.copy(
                    outcome =
                        CollaborationSpaceSetupOutcome.Bootstrapped(
                            f.gateway.copy(
                                accessExpiresAtEpochMillis =
                                    f.setup.native.setup.now.toEpochMilliseconds(),
                                expiresAtEpochMillis =
                                    (f.setup.native.setup.now + 1.seconds).toEpochMilliseconds(),
                            )
                        )
                )
            )
        f.setup.beforeRead = { f.setup.native.time += 1.seconds }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.installs)
    }

    @Test
    fun remoteClockSkewAndOldInitialAccessExpiryDoNotHideOwnedBootstrapFacts() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        val oldGateway =
            f.gateway.copy(
                expiresAtEpochMillis = f.gateway.expiresAtEpochMillis + 120_000,
                accessExpiresAtEpochMillis =
                    (f.setup.native.setup.now - 1.seconds).toEpochMilliseconds(),
            )
        f.setup.records.value =
            AppResult.Success(
                f.record.copy(outcome = CollaborationSpaceSetupOutcome.Bootstrapped(oldGateway))
            )
        val current = f.setup.status
        f.setup.status =
            current.copy(
                observedAt = current.observedAt + 120.seconds,
                gateway =
                    current.gateway!!.copy(
                        accessExpiresAt = current.gateway.accessExpiresAt + 120.seconds,
                        expiresAt = current.gateway.expiresAt + 120.seconds,
                    ),
            )
        // This scenario admits old source facts; the real adapter owns refreshed protected tokens.
        f.beforeInstall = { AppResult.Success(f.lease) }
        val useCase =
            dev.fajar.starter.collaboration.domain.usecases.AdoptCollaborationSpaceRuntime(
                f.setup.commands,
                f.setup.registration.commands,
                f.setup.registration.devices,
                f.transfer.runtime,
                object :
                    dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRuntimeRepository by f
                        .runtimes {
                    override suspend fun installOriginal(
                        owner: CollaborationSpaceOwner,
                        original: CollaborationExistingRuntimeHandover,
                        setup: CollaborationSpaceSetupRecord,
                    ): AppResult<CollaborationSpaceRuntimeLease> {
                        assertEquals(
                            oldGateway,
                            (setup.outcome as CollaborationSpaceSetupOutcome.Bootstrapped).gateway,
                        )
                        return AppResult.Success(f.lease)
                    }
                },
                f.setup.base.directory,
                f.setup.base.sessions,
                f.setup.native.server,
                f.setup.build,
                f.setup.native.clock,
                f.setup.native.time,
            )
        assertIs<AppResult.Success<*>>(useCase(f.setup.owner, f.setup.registrationId, f.operation))
        assertEquals(1, f.publications)
    }

    @Test
    fun aCurrentRevokedDeviceStopsAnAlreadyOpenRuntime() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        f.open = true
        f.setup.status = f.setup.status.copy(state = CollaborationDeviceState.REVOKED)
        assertEquals(FailureKind.AccessDenied, assertIs<AppResult.Failed>(f.run()).failure.kind)
        assertEquals(0, f.installs)
        assertEquals(1, f.spaceCloses)
        assertFalse(f.open)
    }

    @Test
    fun wrongDeviceVersionGatewayGenerationOrProviderOwnerNeverAcquiresTheSdk() = runTest {
        for (mode in 0..2) {
            val f = SpaceRuntimeAdoptionFixture()
            when (mode) {
                0 -> f.setup.status = f.setup.status.copy(version = 2)
                1 -> {
                    f.setup.status =
                        f.setup.status.copy(gateway = f.setup.status.gateway!!.copy(version = 1))
                    f.setup.records.value =
                        AppResult.Success(
                            f.record.copy(
                                command =
                                    f.record.command.copy(
                                        request = f.request.copy(gatewayVersion = 1)
                                    ),
                                outcome =
                                    CollaborationSpaceSetupOutcome.Bootstrapped(
                                        f.gateway.copy(version = 2)
                                    ),
                            )
                        )
                }
                else -> f.setup.metadataOwner = f.setup.owner.copy(spaceId = "foreign")
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.installs)
        }
    }

    @Test
    fun aRegistrationOrBootstrapChangedWhileStatusWasPendingNeverAcquiresTheSdk() = runTest {
        for (registration in listOf(false, true)) {
            val f = SpaceRuntimeAdoptionFixture()
            f.setup.registration.beforeStatus = {
                if (registration) f.setup.registration.records.value = AppResult.Success(null)
                else f.setup.records.value = AppResult.Success(null)
                AppResult.Success(
                    CollaborationSpaceDeviceObservation(f.setup.owner, f.setup.status)
                )
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.installs)
        }
    }

    @Test
    fun anUnconfirmedOrUnrelatedOperationCannotOpenTheOriginalStore() = runTest {
        for (mode in 0..2) {
            val f = SpaceRuntimeAdoptionFixture()
            when (mode) {
                0 ->
                    f.setup.records.value =
                        AppResult.Success(
                            f.record.copy(outcome = CollaborationSpaceSetupOutcome.Unconfirmed())
                        )
                1 -> f.setup.records.value = AppResult.Success(null)
                else -> f.transfer.retained = null
            }
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.installs)
        }
    }

    @Test
    fun failedPublicationClosesOnlyTheUnacceptedLease() = runTest {
        val f = SpaceRuntimeAdoptionFixture()
        f.beforePublish = { AppResult.Failed(f.setup.base.storageFailure) }
        assertEquals(f.setup.base.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertEquals(1, f.closes)
        assertFalse(f.open)
        assertEquals(0, f.spaceCloses)
    }
}
