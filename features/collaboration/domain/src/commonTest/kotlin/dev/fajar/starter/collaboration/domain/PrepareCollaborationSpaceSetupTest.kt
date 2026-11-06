@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class PrepareCollaborationSpaceSetupTest {
    @Test
    fun retryAndBootstrapRetainOriginalRegistrationWithoutCompanyOrSdkAcquisition() = runTest {
        for (kind in CollaborationSpaceSetupKind.entries) {
            val f = SpaceSetupFixture()
            f.native.setup.workspace = null
            val record = f.prepared(kind)
            assertEquals(f.registrationId, record.command.registrationId)
            assertEquals(f.registered.command.binding, record.command.binding)
            assertEquals(5L, record.command.clientBuild)
            assertEquals(4L, f.registered.command.clientBuild)
            assertIs<CollaborationSpaceSetupOutcome.Pending>(record.outcome)
            when (val request = record.command.request) {
                is CollaborationSpaceBootstrapReplacement ->
                    error("Ordinary preparation cannot replace uncertainty")
                is CollaborationSpaceProvisioningRetry -> {
                    assertEquals(0L, request.deviceVersion)
                    assertEquals(1L, request.provisioningRevision)
                }
                is CollaborationSpaceBootstrap -> {
                    assertEquals(f.registration.provider, request.provider)
                    assertEquals(f.registration.sdkIdentity.matrixDeviceId, request.matrixDeviceId)
                    assertEquals(1L, request.deviceVersion)
                }
            }
            val otherKind =
                if (kind == CollaborationSpaceSetupKind.RETRY) CollaborationSpaceSetupKind.BOOTSTRAP
                else CollaborationSpaceSetupKind.RETRY
            assertEquals(
                AppResult.Success(record),
                f.prepare()(f.owner, f.registrationId, otherKind),
            )
            assertEquals(1, f.inserts)
            assertEquals(0, f.registration.sdkReads)
            assertEquals(0, f.base.admits)
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test
    fun missingUnconfirmedForeignAndSpaceRegistrationCannotPrepare() = runTest {
        for (mode in 0..4) {
            val f = SpaceSetupFixture()
            val original = f.registered
            f.registration.records.value =
                AppResult.Success(
                    when (mode) {
                        0 -> null
                        1 ->
                            original.copy(
                                revision = 1,
                                outcome = CollaborationSpaceRegistrationOutcome.Unconfirmed(),
                            )
                        2 -> original.copy(command = original.command.copy(id = f.owner.spaceId))
                        3 ->
                            original.copy(
                                command =
                                    original.command.copy(
                                        binding =
                                            original.command.binding.copy(
                                                nativeSessionId = f.owner.spaceId
                                            )
                                    )
                            )
                        else ->
                            original.copy(
                                command =
                                    original.command.copy(
                                        origin = CollaborationSpaceDeviceOrigin.SPACE,
                                        legacy = null,
                                    )
                            )
                    }
                )
            assertIs<AppResult.Failed>(
                f.prepare()(f.owner, f.registrationId, CollaborationSpaceSetupKind.RETRY)
            )
            assertEquals(0, f.inserts)
            assertEquals(0, f.registration.identityReads)
        }
    }

    @Test
    fun changedSelectionDevicePlatformAndUnsupportedBuildCannotPrepare() = runTest {
        for (mode in 0..5) {
            val f = SpaceSetupFixture()
            when (mode) {
                0 -> f.registration.beforeIdentity = {
                        AppResult.Success(CollaborationSpaceIdentityObservation(f.owner, null))
                    }
                1 -> f.registration.beforeIdentity = {
                        AppResult.Success(
                            CollaborationSpaceIdentityObservation(
                                f.owner,
                                f.registration.provider.copy(
                                    matrixUserId = "@other:matrix.hris.internal"
                                ),
                            )
                        )
                    }
                2 -> f.registration.beforeStatus = {
                        AppResult.Success(CollaborationSpaceDeviceObservation(f.owner, null))
                    }
                3 -> f.status = f.status.copy(id = f.owner.spaceId)
                4 -> f.status = f.status.copy(platform = CollaborationNativePlatform.IOS)
                5 -> f.status = f.status.copy(state = CollaborationDeviceState.REVOKED)
            }
            assertIs<AppResult.Failed>(
                f.prepare()(f.owner, f.registrationId, CollaborationSpaceSetupKind.RETRY)
            )
            assertNull(f.saved())
        }
        for (build in
            listOf(
                AppBuild(AppPlatform.Web, 5, "test", null),
                AppBuild(AppPlatform.Android, 1_000_000_000, "test", null),
            )) {
            val f = SpaceSetupFixture()
            assertIs<AppResult.Failed>(
                f.prepare(build)(f.owner, f.registrationId, CollaborationSpaceSetupKind.RETRY)
            )
            assertEquals(0, f.inserts)
        }
    }

    @Test
    fun pendingProvisioningWaitsUntilItsObservedExpiryAndRetryTime() = runTest {
        val f = SpaceSetupFixture()
        f.status =
            f.status.copy(
                provisioning =
                    f.status.provisioning.copy(
                        state = CollaborationProvisioningState.RUNNING,
                        expiresAt = f.native.setup.now + 30.seconds,
                        lastOutcome = f.status.provisioning.lastOutcome?.copy(revision = 0),
                        retryNotBefore = f.native.setup.now + 30.seconds,
                    )
            )
        val early =
            assertIs<AppResult.Failed>(
                f.prepare()(f.owner, f.registrationId, CollaborationSpaceSetupKind.RETRY)
            )
        assertEquals("collaboration_provisioning_retry_too_soon", early.failure.code)
        assertEquals(30L, early.failure.retryAfterSeconds)
        assertNull(f.saved())
        f.native.setup.now += 31.seconds
        val record = f.prepared()
        assertEquals(
            1L,
            assertIs<CollaborationSpaceProvisioningRetry>(record.command.request)
                .provisioningRevision,
        )
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun localReadAndMetadataCancellationHaveFiniteOwnedLifetimes() = runTest {
        for (local in listOf(false, true)) {
            val f = SpaceSetupFixture()
            var stopped = false
            if (local)
                f.beforeRead = {
                    try {
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            else
                f.registration.beforeIdentity = {
                    try {
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            val result = f.prepare()(f.owner, f.registrationId, CollaborationSpaceSetupKind.RETRY)
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(result).failure.kind)
            assertTrue(stopped)
            assertNull(f.saved())
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test
    fun originalHardDeadlineRollbackMonotonicExpiryAndLogoutRejectLateMetadata() = runTest {
        for (mode in 0..3) {
            val f = SpaceSetupFixture()
            val initial =
                f.native.session.copy(
                    binding =
                        f.native.session.binding.copy(
                            expiresAtEpochMillis =
                                (f.native.setup.now + 5.seconds).toEpochMilliseconds()
                        )
                )
            f.registration.session(initial)
            f.registration.beforeStatus = {
                when (mode) {
                    0 -> {
                        f.native.setup.now += 6.seconds
                        f.registration.session(
                            initial.copy(
                                binding =
                                    initial.binding.copy(
                                        expiresAtEpochMillis =
                                            (f.native.setup.now + 100.seconds).toEpochMilliseconds()
                                    )
                            )
                        )
                    }
                    1 -> f.native.setup.now -= 1.seconds
                    2 -> f.native.time += 16.seconds
                    else -> f.native.setup.session = null
                }
                AppResult.Success(CollaborationSpaceDeviceObservation(f.owner, f.status))
            }
            assertIs<AppResult.Failed>(
                f.prepare()(f.owner, f.registrationId, CollaborationSpaceSetupKind.RETRY)
            )
            assertNull(f.saved())
        }
    }

    @Test
    fun cancelledDenialWithFailedPersistenceSurvivesFailedMetadataRetry() = runTest {
        val f = SpaceSetupFixture()
        f.base.afterRestrict = { AppResult.Failed(f.base.storageFailure) }
        f.registration.beforeIdentity = {
            currentCoroutineContext().cancel()
            AppResult.Failed(f.base.denied)
        }
        supervisorScope {
            val task = async {
                f.prepare()(f.owner, f.registrationId, CollaborationSpaceSetupKind.RETRY)
            }
            assertFailsWith<CancellationException> { task.await() }
        }
        f.registration.beforeIdentity = { AppResult.Failed(f.base.unavailable) }
        assertEquals(
            AppResult.Failed(f.base.denied),
            f.prepare()(f.owner, f.registrationId, CollaborationSpaceSetupKind.RETRY),
        )
        assertNull(f.saved())
        assertEquals(0, f.base.admits)
    }

    @Test
    fun newerDenialAndLostInsertResponseNeverCreateAReplacementOperation() = runTest {
        for (duringInsert in listOf(false, true)) {
            val f = SpaceSetupFixture()
            var reads = 0
            if (duringInsert)
                f.beforeInsert = {
                    f.base.deny()
                    null
                }
            else f.base.beforeAdmission = { if (++reads == 2) f.base.deny() }
            assertEquals(
                AppResult.Failed(f.base.denied),
                f.prepare()(f.owner, f.registrationId, CollaborationSpaceSetupKind.RETRY),
            )
            assertEquals(if (duringInsert) 1 else 0, f.inserts)
            assertEquals(duringInsert, f.saved() != null)
        }
        val f = SpaceSetupFixture()
        f.beforeInsert = {
            currentCoroutineContext().cancel()
            null
        }
        supervisorScope {
            val task = async {
                f.prepare()(f.owner, f.registrationId, CollaborationSpaceSetupKind.RETRY)
            }
            assertFailsWith<CancellationException> { task.await() }
        }
        val retained = assertNotNull(f.saved())
        f.beforeInsert = { null }
        assertEquals(
            AppResult.Success(retained),
            f.prepare()(f.owner, f.registrationId, CollaborationSpaceSetupKind.BOOTSTRAP),
        )
        assertEquals(1, f.inserts)
    }
}
