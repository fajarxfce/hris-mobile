@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDeviceRepository
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceDeviceStatus
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceDeviceStatusTest {
    @Test
    fun oneReadWithoutSelectedCompanyKeepsAbsentDeviceAndExpiredMetadataDistinct() = runTest {
        val f = Fixture()
        f.native.setup.workspace = null
        assertEquals(AppResult.Success(f.observation), f.read()(f.owner))
        f.response = { AppResult.Success(f.observation.copy(device = null)) }
        assertNull(
            assertIs<AppResult.Success<CollaborationSpaceDeviceObservation>>(f.read()(f.owner))
                .value
                .device
        )
        advanceTimeBy(60_000)
        assertEquals(2, f.calls)
        assertEquals(0, f.directory.admits)
    }

    @Test
    fun invalidOwnerNativePlatformBuildAndExpiredParentNeverReachTransport() = runTest {
        for (variant in 0..5) {
            val f = Fixture()
            var owner = f.owner
            var platform = AppPlatform.Android
            var build = 5L
            when (variant) {
                0 -> owner = owner.copy(spaceId = "../space")
                1 -> owner = owner.copy(parent = owner.parent.copy(accountId = owner.spaceId))
                2 -> owner = owner.copy(parent = owner.parent.copy(sessionId = "old-local"))
                3 -> platform = AppPlatform.Web
                4 -> build = 1_000_000_000L
                5 -> f.native.setup.now += 10_000.seconds
            }
            assertIs<AppResult.Failed>(f.read(platform, build)(owner))
            assertEquals(0, f.calls)
        }
    }

    @Test
    fun cancellationDisposesPendingReadAndNonCooperativeLateSuccessIsRejected() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.response = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val pending = async { f.read()(f.owner) }
        entered.await()
        pending.cancelAndJoin()
        stopped.await()
        assertTrue(pending.isCancelled)
        f.response = {
            withContext(NonCancellable) { delay(20_000) }
            AppResult.Success(f.observation)
        }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.read()(f.owner)).failure.kind,
        )
        assertEquals(2, f.calls)
    }

    @Test
    fun replacementLocalSessionAndNativeFamilyDiscardLateStatus() = runTest {
        for (replaceAccount in listOf(false, true)) {
            val f = Fixture()
            f.response = {
                f.native.setup.session =
                    f.native.session.copy(
                        id = "fresh-local",
                        binding = f.native.session.binding.copy(serverId = f.owner.spaceId),
                        user =
                            f.native.session.user.copy(
                                id =
                                    if (replaceAccount) f.owner.spaceId
                                    else f.owner.parent.accountId
                            ),
                    )
                AppResult.Success(f.observation)
            }
            assertIs<AppResult.Failed>(f.read()(f.owner))
            assertEquals(1, f.calls)
            assertIs<AppResult.Failed>(f.read()(f.owner))
            assertEquals(1, f.calls)
        }
    }

    @Test
    fun freshNativeLoginCanReadAbsenceWithoutInferringOldDeviceReplacement() = runTest {
        val f = Fixture()
        val current =
            f.owner.copy(
                parent =
                    f.owner.parent.copy(
                        sessionId = "fresh-local",
                        nativeSessionId = f.owner.spaceId,
                    )
            )
        f.native.setup.session =
            f.native.session.copy(
                id = current.parent.sessionId,
                binding = f.native.session.binding.copy(serverId = current.parent.nativeSessionId),
            )
        f.response = { AppResult.Success(CollaborationSpaceDeviceObservation(current, null)) }
        assertEquals(
            AppResult.Success(CollaborationSpaceDeviceObservation(current, null)),
            f.read()(current),
        )
        assertEquals(current, f.requestedOwner)
        assertEquals(1, f.calls)
        assertEquals(0, f.directory.admits)
    }

    @Test
    fun originalHardDeadlineCannotBeExtendedBySameFamilyRenewal() = runTest {
        val f = Fixture()
        f.native.setup.session =
            f.native.session.copy(
                binding =
                    f.native.session.binding.copy(
                        expiresAtEpochMillis =
                            (f.native.setup.now + 2.seconds).toEpochMilliseconds()
                    )
            )
        f.response = {
            f.native.setup.now += 3.seconds
            f.native.setup.session = f.native.session
            AppResult.Success(f.observation)
        }
        assertEquals(
            "client.collaboration_revalidation_required",
            assertIs<AppResult.Failed>(f.read()(f.owner)).failure.code,
        )
    }

    @Test
    fun wallRollbackAndMonotonicExpiryRejectCompletedResponses() = runTest {
        for (rollback in listOf(false, true)) {
            val f = Fixture()
            f.response = {
                if (rollback) f.native.setup.now -= 1.seconds else f.native.time += 15.seconds
                AppResult.Success(f.observation)
            }
            assertEquals(
                "client.collaboration_revalidation_required",
                assertIs<AppResult.Failed>(f.read()(f.owner)).failure.code,
            )
        }
    }

    @Test
    fun wrongOwnerPlatformAndContradictoryRepositoryMetadataCannotPublish() = runTest {
        for (variant in 0..2) {
            val f = Fixture()
            val invalid =
                when (variant) {
                    0 ->
                        f.observation.copy(owner = f.owner.copy(spaceId = f.owner.parent.accountId))
                    1 ->
                        f.observation.copy(
                            device = f.device.copy(platform = CollaborationNativePlatform.IOS)
                        )
                    else ->
                        f.observation.copy(
                            device = f.device.copy(state = CollaborationDeviceState.PROVISIONING)
                        )
                }
            f.response = { AppResult.Success(invalid) }
            assertEquals(
                "client.collaboration_invalid_response",
                assertIs<AppResult.Failed>(f.read()(f.owner)).failure.code,
            )
        }
    }

    @Test
    fun receivedDenialSurvivesCancellationAndFailedRestrictionPersistence() = runTest {
        val f = Fixture()
        f.directory.afterRestrict = { AppResult.Failed(f.directory.storageFailure) }
        f.response = {
            currentCoroutineContext().cancel()
            AppResult.Failed(f.directory.denied)
        }
        val pending = async { f.read()(f.owner) }
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(1, f.directory.restrictions)
        f.response = { AppResult.Failed(f.directory.unavailable) }
        assertEquals(f.directory.denied, assertIs<AppResult.Failed>(f.read()(f.owner)).failure)
        assertEquals(0, f.directory.admits)
    }

    @Test
    fun existingDenialRemainsThroughTimeoutFailedRetryAndSuccessfulStatus() = runTest {
        val f = Fixture()
        f.directory.deny()
        f.response = { awaitCancellation() }
        assertEquals(f.directory.denied, assertIs<AppResult.Failed>(f.read()(f.owner)).failure)
        f.response = { AppResult.Failed(f.directory.unavailable) }
        assertEquals(f.directory.denied, assertIs<AppResult.Failed>(f.read()(f.owner)).failure)
        f.response = { AppResult.Success(f.observation) }
        assertIs<AppResult.Success<*>>(f.read()(f.owner))
        assertEquals(
            f.directory.denied,
            assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                    f.directory.gate.value
                )
                .value
                .denial,
        )
        assertEquals(0, f.directory.admits)
    }

    @Test
    fun newerDenialWhileStatusIsPendingWinsOverSuccess() = runTest {
        val f = Fixture()
        f.response = {
            f.directory.deny()
            AppResult.Success(f.observation)
        }
        assertEquals(f.directory.denied, assertIs<AppResult.Failed>(f.read()(f.owner)).failure)
        assertEquals(0, f.directory.admits)
    }

    @Test
    fun pendingLocalAdmissionIsBoundedAndOwnerIsCheckedAgainBeforeHttp() = runTest {
        val f = Fixture()
        f.directory.beforeAdmission = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.read()(f.owner)).failure.kind,
        )
        assertEquals(0, f.calls)
        f.directory.beforeAdmission = { f.native.setup.session = null }
        assertIs<AppResult.Failed>(f.read()(f.owner))
        assertEquals(0, f.calls)
    }

    @Test
    fun finalAdmissionReadCannotPublishAfterDisposalOrTheAttemptDeadline() = runTest {
        for (logout in listOf(false, true)) {
            val f = Fixture()
            var reads = 0
            f.directory.beforeAdmission = {
                if (++reads == 2) {
                    if (logout) f.native.setup.session = null else f.native.time += 15.seconds
                }
            }
            assertIs<AppResult.Failed>(f.read()(f.owner))
            assertEquals(1, f.calls)
        }
    }

    private class Fixture {
        val directory = SpaceDirectoryUseCaseFixture()
        val native = directory.native
        val owner = native.owner
        val device =
            CollaborationSpaceDeviceStatus(
                "80000000-0000-4000-8000-000000000001",
                1,
                CollaborationDeviceState.ACTIVE,
                CollaborationNativePlatform.ANDROID,
                CollaborationSpaceDeviceOrigin.LEGACY,
                "Phone",
                CollaborationSpaceProvisioningStatus(
                    0,
                    CollaborationProvisioningState.SUCCEEDED,
                    1,
                    native.setup.now - 60.seconds,
                    CollaborationSpaceProvisioningOutcome(
                        0,
                        CollaborationProvisioningState.SUCCEEDED,
                        native.setup.now - 90.seconds,
                        null,
                    ),
                    null,
                ),
                native.setup.now,
                null,
            )
        val observation = CollaborationSpaceDeviceObservation(owner, device)
        var calls = 0
        var requestedOwner: CollaborationSpaceOwner? = null
        var response: suspend () -> AppResult<CollaborationSpaceDeviceObservation> = {
            AppResult.Success(observation)
        }
        val repository =
            object : CollaborationSpaceDeviceRepository {
                override suspend fun register(
                    owner: CollaborationSpaceOwner,
                    command: CollaborationSpaceRegistrationCommand,
                ): AppResult<CollaborationSetupReceipt> =
                    error("Status reads must not register devices")

                override suspend fun status(
                    owner: CollaborationSpaceOwner
                ): AppResult<CollaborationSpaceDeviceObservation> {
                    requestedOwner = owner
                    calls++
                    return response()
                }
            }

        fun read(platform: AppPlatform = AppPlatform.Android, number: Long = 5) =
            ReadCollaborationSpaceDeviceStatus(
                repository,
                directory.directory,
                native.setup.sessions,
                native.server,
                AppBuild(platform, number, "test", null),
                native.clock,
                native.time,
            )
    }
}
