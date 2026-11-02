package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryRepository
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationRecoveryTest {
    @Test
    fun discoveryAndAdmissionIgnoreForegroundSelectionAndNeverReopenLocalReads() = runTest {
        val f = Fixture()
        f.setup.workspace = null
        f.setup.beforeWorkspace = { error("Recovery must not inspect workspace selection") }
        f.setup.admission.deny(f.denied)
        val page = assertIs<AppResult.Success<CollaborationDevicePage>>(f.page()).value
        assertEquals(f.parent, page.parent)
        val grant = assertIs<AppResult.Success<CollaborationBackgroundAdmission>>(f.admit()).value
        assertEquals(f.parent.owner(f.device.companyId), grant.owner)
        assertEquals(f.setup.now + 60.seconds, grant.expiresAt)
        assertEquals(f.denied, f.setup.admission.gate.denial)
        assertNull(f.setup.workspace)
        assertEquals(1, f.pageCalls)
        assertEquals(1, f.admissionCalls)
    }

    @Test
    fun invalidPagesAndUnsupportedDevicesStopBeforeIo() = runTest {
        val f = Fixture()
        for (limit in listOf(-1, 0, 33, Int.MAX_VALUE)) assertIs<AppResult.Failed>(
            f.page(limit = limit)
        )
        assertIs<AppResult.Failed>(f.page(afterCompanyId = f.device.companyId))
        assertIs<AppResult.Failed>(f.page(f.parent, "invalid"))
        for (device in
            listOf(
                f.device.copy(companyId = "invalid"),
                f.device.copy(version = -1),
                f.device.copy(state = CollaborationDeviceState.REVOKED),
                f.device.copy(platform = CollaborationNativePlatform.IOS),
            )) assertIs<AppResult.Failed>(f.admit(f.parent, device))
        assertEquals(0, f.pageCalls)
        assertEquals(0, f.admissionCalls)
    }

    @Test
    fun missingForeignOrExpiredParentCannotBeginRecovery() = runTest {
        for (mode in 0..4) {
            val f = Fixture()
            f.setup.session =
                when (mode) {
                    0 -> null
                    1 -> f.session.copy(id = "replacement")
                    2 ->
                        f.session.copy(
                            binding = f.session.binding.copy(serverId = f.device.deviceId)
                        )
                    3 ->
                        f.session.copy(
                            binding =
                                f.session.binding.copy(audience = "https://foreign.example.test/")
                        )
                    else ->
                        f.session.copy(
                            binding =
                                f.session.binding.copy(
                                    expiresAtEpochMillis = f.setup.now.toEpochMilliseconds()
                                )
                        )
                }
            assertIs<AppResult.Failed>(f.page(f.parent))
            assertIs<AppResult.Failed>(f.admit())
            assertEquals(0, f.pageCalls)
            assertEquals(0, f.admissionCalls)
        }
    }

    @Test
    fun lateReplacementAndBackwardClockCannotPublishEitherResult() = runTest {
        for (mode in 0..2) for (admission in listOf(false, true)) {
            val f = Fixture()
            val mutate = {
                when (mode) {
                    0 -> f.setup.session = f.session.copy(id = "replacement")
                    1 -> f.setup.now -= 1.seconds
                    else -> f.setup.now += 16.seconds
                }
            }
            f.onPage = {
                mutate()
                AppResult.Success(f.resultPage)
            }
            f.onAdmission = {
                mutate()
                AppResult.Success(f.observation)
            }
            assertIs<AppResult.Failed>(if (admission) f.admit() else f.page())
        }
    }

    @Test
    fun admissionLifetimeChargesTransportTimeAndHonorsShorterParentExpiry() = runTest {
        val f = Fixture()
        val start = f.setup.now
        f.setup.session =
            f.session.copy(
                binding =
                    f.session.binding.copy(
                        expiresAtEpochMillis = (start + 30.seconds).toEpochMilliseconds()
                    )
            )
        f.onAdmission = {
            f.setup.now += 7.seconds
            AppResult.Success(f.observation)
        }
        val grant = assertIs<AppResult.Success<CollaborationBackgroundAdmission>>(f.admit()).value
        assertEquals(start, grant.requestedAt)
        assertEquals(start + 30.seconds, grant.expiresAt)
        f.onAdmission = {
            f.setup.now += 4.seconds
            AppResult.Success(f.observation.copy(expiresAt = f.observation.admittedAt + 3.seconds))
        }
        assertEquals(
            "client.collaboration_revalidation_required",
            assertIs<AppResult.Failed>(f.admit()).failure.code,
        )
    }

    @Test
    fun malformedOwnershipVersionsLifetimeAndCursorCannotBecomeAuthority() = runTest {
        val f = Fixture()
        for (observation in
            listOf(
                f.observation.copy(owner = f.observation.owner.copy(companyId = f.device.deviceId)),
                f.observation.copy(deviceId = f.parent.accountId),
                f.observation.copy(deviceVersion = 0),
                f.observation.copy(nativeSessionVersion = -1),
                f.observation.copy(credentialVersion = -1),
                f.observation.copy(expiresAt = f.observation.admittedAt),
                f.observation.copy(expiresAt = f.observation.admittedAt + 61.seconds),
            )) {
            f.onAdmission = { AppResult.Success(observation) }
            assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(f.admit()).failure.kind)
        }
        for (page in
            listOf(
                f.resultPage.copy(parent = f.parent.copy(accountId = f.device.deviceId)),
                f.resultPage.copy(devices = listOf(f.device, f.device)),
                f.resultPage.copy(nextAfterCompanyId = f.device.companyId),
                f.resultPage.copy(devices = listOf(f.device.copy(version = -1))),
            )) {
            f.onPage = { AppResult.Success(page) }
            assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(f.page()).failure.kind)
        }
        f.onPage = { AppResult.Success(f.resultPage.copy(nextAfterCompanyId = f.device.companyId)) }
        assertIs<AppResult.Success<*>>(f.page(limit = 1))
        assertIs<AppResult.Failed>(f.page(f.parent, f.device.companyId, 1))
    }

    @Test
    fun confirmedCompanyDenialPersistsDespiteLocalFailureWhileTransportDoesNotCloseFacts() =
        runTest {
            for (denied in listOf(false, true)) {
                val f = Fixture()
                f.setup.admission.restrictFailure = f.storage
                val failure = if (denied) f.denied else f.storage
                f.onAdmission = {
                    f.setup.sessionFailure = f.storage
                    AppResult.Failed(failure)
                }
                assertEquals(AppResult.Failed(failure), f.admit())
                assertEquals(if (denied) f.denied else null, f.setup.admission.gate.denial)
            }
        }

    @Test
    fun pendingRecoveryHasNoWaitingQueueAndCallerCancellationReleasesCapacity() = runTest {
        for (admission in listOf(false, true)) {
            val f = Fixture()
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            val wait: suspend () -> Nothing = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            f.onPage = { wait() }
            f.onAdmission = { wait() }
            suspend fun invoke(): AppResult<*> = if (admission) f.admit() else f.page()
            val first = async { invoke() }
            entered.await()
            assertEquals(
                "client.collaboration_recovery_busy",
                assertIs<AppResult.Failed>(invoke()).failure.code,
            )
            first.cancelAndJoin()
            assertTrue(stopped)
            f.onPage = { AppResult.Success(f.resultPage) }
            f.onAdmission = { AppResult.Success(f.observation) }
            assertIs<AppResult.Success<*>>(invoke())
        }
    }

    @Test
    fun finiteDeadlineCancelsPendingAdmissionWithoutRetry() = runTest {
        val f = Fixture()
        var stopped = false
        f.onAdmission = {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val pending = async { f.admit() }
        advanceTimeBy(15_001)
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(pending.await()).failure.kind)
        assertTrue(stopped)
        assertEquals(1, f.admissionCalls)
    }

    private class Fixture {
        val setup = CollaborationSetupFixture()
        val parent =
            CollaborationNativeParent(
                "local",
                setup.initial.binding.audience,
                "10000000-0000-4000-8000-000000000001",
                "20000000-0000-4000-8000-000000000001",
            )
        val device =
            CollaborationDeviceReference(
                "30000000-0000-4000-8000-000000000001",
                "40000000-0000-4000-8000-000000000001",
                1,
                CollaborationDeviceState.ACTIVE,
                CollaborationNativePlatform.ANDROID,
            )
        val session =
            setup.initial.copy(
                user = setup.initial.user.copy(id = parent.accountId),
                binding = setup.initial.binding.copy(serverId = parent.nativeSessionId),
            )
        val resultPage = CollaborationDevicePage(parent, listOf(device), null)
        val observation =
            CollaborationAdmissionObservation(
                parent.owner(device.companyId),
                device.deviceId,
                1,
                0,
                0,
                setup.now,
                setup.now + 60.seconds,
            )
        val denied = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
        val storage = Failure(FailureKind.Storage, "Unavailable", code = "fixture.storage")
        var pageCalls = 0
        var admissionCalls = 0
        var onPage: suspend () -> AppResult<CollaborationDevicePage> = {
            AppResult.Success(resultPage)
        }
        var onAdmission: suspend () -> AppResult<CollaborationAdmissionObservation> = {
            AppResult.Success(observation)
        }

        init {
            setup.session = session
        }

        private val repository =
            object : CollaborationRecoveryRepository {
                override suspend fun devices(
                    parent: CollaborationNativeParent,
                    afterCompanyId: String?,
                    limit: Int,
                ): AppResult<CollaborationDevicePage> {
                    assertEquals(this@Fixture.parent, parent)
                    pageCalls++
                    return onPage()
                }

                override suspend fun admission(
                    owner: CollaborationOwner
                ): AppResult<CollaborationAdmissionObservation> {
                    assertEquals(parent.owner(device.companyId), owner)
                    admissionCalls++
                    return onAdmission()
                }
            }
        private val clock =
            object : Clock {
                override fun now() = setup.now
            }
        private val build = AppBuild(AppPlatform.Android, 5, "test", null)
        private val server = IdentityServer(parent.audience, "Fictional Android")
        val page = LoadCollaborationDevicePage(repository, setup.sessions, server, build, clock)
        private val acquire =
            AcquireCollaborationBackgroundAdmission(
                repository,
                setup.admission.repository,
                setup.sessions,
                server,
                build,
                clock,
            )

        suspend fun admit(
            parent: CollaborationNativeParent = this.parent,
            device: CollaborationDeviceReference = this.device,
        ) = acquire(parent, device)
    }
}
