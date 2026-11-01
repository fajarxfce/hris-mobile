package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.InstallCollaborationRuntime
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class CollaborationRuntimeInstallationTest {
    @Test
    fun deniedInstallationSurvivesTheFollowingFailedOwnerRead() = runTest {
        for (kind in listOf(FailureKind.Unauthorized, FailureKind.AccessDenied)) {
            val f = InstallationFixture()
            val denied = AppResult.Failed(Failure(kind, "Fixture SDK denial"))
            f.onInstall = {
                f.setup.access.sessionFailure = f.setup.storageFailure.failure
                denied
            }
            assertEquals(denied, f.install()())
            assertEquals(f.record, f.setup.record)
            assertEquals(0, f.removals)
            assertEquals(0, f.lease.closes)
        }
    }

    @Test
    fun anOwnedRuntimeDenialWinsOverFailedAdmissionReadsAndStillClosesTheResource() = runTest {
        for (late in listOf(false, true)) for (kind in
            listOf(FailureKind.Unauthorized, FailureKind.AccessDenied)) {
            val f = InstallationFixture()
            val denied = Failure(kind, "Fixture credential denial")
            f.lease.closeResult = f.setup.storageFailure
            val revoke = {
                f.lease.credentialFailures.value = denied
                f.setup.access.sessionFailure = f.setup.storageFailure.failure
            }
            if (late) f.beforeRemove = { revoke() }
            else
                f.onInstall = {
                    revoke()
                    AppResult.Success(f.lease)
                }
            assertEquals(AppResult.Failed(denied), f.install()())
            assertEquals(1, f.lease.closes)
            assertEquals(if (late) 1 else 0, f.removals)
        }
    }

    @Test
    fun confirmedFactsRetireOnlyAfterProtectedInstallationAndTransferThePausedOwner() = runTest {
        val f = InstallationFixture()
        f.beforeRemove = { assertTrue(f.installed) }
        val result = assertIs<AppResult.Success<CollaborationRuntimeLease>>(f.install()()).value
        assertSame(f.lease, result)
        assertNull(f.setup.record)
        assertEquals(1, f.installations.size)
        assertEquals(0, f.lease.closes)
        assertTrue(f.setup.calls.isEmpty())
        // Old gateway deadline facts do not delete local ownership or grant sending authority.
        assertEquals(2, f.gateway.expiresAtEpochMillis)
        result.close()
        assertEquals(1, f.lease.closes)
    }

    @Test
    fun pendingUncertainOrUnattemptedResultsCannotBeAcknowledgedByTheInstaller() = runTest {
        for (outcome in
            listOf(
                CollaborationSetupOutcome.Pending,
                CollaborationSetupOutcome.Unconfirmed(),
                CollaborationSetupOutcome.Registered(CollaborationSetupReceipt("device", 1)),
                CollaborationSetupOutcome.Retried(CollaborationSetupReceipt("job", 1)),
            )) {
            val f = InstallationFixture()
            f.setup.record = f.record.copy(outcome = outcome)
            assertIs<AppResult.Failed>(f.install()())
            assertTrue(f.installations.isEmpty())
            assertEquals(0, f.removals)
        }
        val f = InstallationFixture()
        f.setup.record = f.record.copy(attempts = 0)
        assertIs<AppResult.Failed>(f.install()())
        assertEquals(0, f.removals)
    }

    @Test
    fun changedCommandBuildPlatformOrGatewayOwnershipCannotOpenTheSdk() = runTest {
        repeat(7) { scenario ->
            val f = InstallationFixture()
            val c = f.record.command
            f.setup.record =
                when (scenario) {
                    0 -> f.record.copy(command = c.copy(id = "another-operation"))
                    1 ->
                        f.record.copy(
                            command = c.copy(binding = c.binding.copy(companyId = "another"))
                        )
                    2 -> f.record.copy(command = c.copy(clientBuild = c.clientBuild + 1))
                    3 -> f.record.copy(command = c.copy(platform = CollaborationNativePlatform.IOS))
                    4 ->
                        f.record.copy(
                            outcome =
                                CollaborationSetupOutcome.Bootstrapped(
                                    f.gateway(owner = f.setup.owner.copy(accountId = "another"))
                                )
                        )
                    5 ->
                        f.record.copy(
                            outcome =
                                CollaborationSetupOutcome.Bootstrapped(
                                    f.gateway(deviceId = "another")
                                )
                        )
                    else ->
                        f.record.copy(
                            outcome = CollaborationSetupOutcome.Bootstrapped(f.gateway(version = 1))
                        )
                }
            assertIs<AppResult.Failed>(f.install()())
            assertTrue(f.installations.isEmpty())
            assertNotNull(f.setup.record)
        }
    }

    @Test
    fun aLateSessionChangeDuringCommandReadPreventsSdkAcquisition() = runTest {
        val f = InstallationFixture()
        f.setup.beforeCurrent = { f.setup.access.session = null }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.install()()).failure.kind,
        )
        assertTrue(f.installations.isEmpty())
        assertEquals(f.record, f.setup.record)
    }

    @Test
    fun aLateAccountCompanyOrPermissionChangeClosesTheOwnedSdkAndKeepsTheCommand() = runTest {
        repeat(3) { scenario ->
            val f = InstallationFixture()
            f.onInstall = {
                when (scenario) {
                    0 -> f.setup.access.session = null
                    1 ->
                        f.setup.access.workspace =
                            f.setup.access.selected.copy(id = "new-selection")
                    else ->
                        f.setup.access.workspace =
                            f.setup.access.selected.copy(permissions = emptySet())
                }
                AppResult.Success(f.lease)
            }
            assertIs<AppResult.Failed>(f.install()())
            assertEquals(1, f.lease.closes)
            assertEquals(f.record, f.setup.record)
            assertEquals(0, f.removals)
        }
    }

    @Test
    fun confirmedDenialOverridesAConcurrentInstallationOrCleanupFailure() = runTest {
        val f = InstallationFixture()
        f.onInstall = {
            f.setup.access.session = null
            f.setup.storageFailure
        }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.install()()).failure.kind,
        )
        assertEquals(f.record, f.setup.record)
        val second = InstallationFixture()
        second.lease.closeResult = second.setup.storageFailure
        second.onInstall = {
            second.setup.access.session = null
            AppResult.Success(second.lease)
        }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(second.install()()).failure.kind,
        )
        assertEquals(1, second.lease.closes)
        assertEquals(second.record, second.setup.record)
    }

    @Test
    fun mismatchedLeaseOrCredentialWriteFailureCannotRetireTheCommand() = runTest {
        repeat(4) { scenario ->
            val f = InstallationFixture()
            when (scenario) {
                0 -> f.lease.owner = f.setup.owner.copy(nativeSessionId = "another-family")
                1 -> f.lease.deviceId = "another-device"
                2 -> f.lease.installedOperationId = "another-operation"
                else -> f.lease.credentialFailures.value = f.setup.storageFailure.failure
            }
            assertIs<AppResult.Failed>(f.install()())
            assertEquals(1, f.lease.closes)
            assertEquals(f.record, f.setup.record)
            assertEquals(0, f.removals)
        }
    }

    @Test
    fun failedRetirementPreservesTheExactCommandForAnExplicitInstallationRetry() = runTest {
        val f = InstallationFixture()
        f.removeResult = f.setup.storageFailure
        assertEquals(f.setup.storageFailure, f.install()())
        assertEquals(f.record, f.setup.record)
        assertEquals(1, f.lease.closes)
        f.lease = InstallationLease(f.setup.owner)
        f.removeResult = null
        assertIs<AppResult.Success<CollaborationRuntimeLease>>(f.install()())
        assertEquals(listOf(f.record, f.record), f.installations)
        assertNull(f.setup.record)
        f.lease.close()
    }

    @Test
    fun aCompetingCommandRevisionIsNeverRemoved() = runTest {
        val f = InstallationFixture()
        val changed = f.record.copy(revision = f.record.revision + 1)
        f.beforeRemove = { f.setup.record = changed }
        assertIs<AppResult.Failed>(f.install()())
        assertEquals(changed, f.setup.record)
        assertEquals(1, f.lease.closes)
    }

    @Test
    fun cancellationAfterLateAcquisitionClosesBeforeReturningAndPreservesTheCommand() = runTest {
        val f = InstallationFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onInstall = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(f.lease)
        }
        val task = async { f.install()() }
        entered.await()
        task.cancel()
        release.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, f.lease.closes)
        assertEquals(f.record, f.setup.record)
    }

    @Test
    fun lostRetirementResponseOrLateDenialNeverDiscardsInstalledFactsOrLeaksTheLease() = runTest {
        repeat(3) { scenario ->
            val f = InstallationFixture()
            f.afterRemove = {
                when (scenario) {
                    0 -> currentCoroutineContext().cancel()
                    1 -> f.setup.access.session = null
                    else -> f.lease.credentialFailures.value = f.setup.storageFailure.failure
                }
            }
            val task = async { f.install()() }
            if (scenario == 0) {
                task.join()
                assertTrue(task.isCancelled)
            } else assertIs<AppResult.Failed>(task.await())
            assertNull(f.setup.record)
            assertTrue(f.installed)
            assertEquals(1, f.lease.closes)
        }
    }

    @Test
    fun concurrentInstallerCallsCannotTransferTheSameExclusiveLeaseTwice() = runTest {
        val f = InstallationFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onInstall = {
            entered.complete(Unit)
            release.await()
            AppResult.Success(f.lease)
        }
        val install = f.install()
        val first = async { install() }
        entered.await()
        val second = async { install() }
        release.complete(Unit)
        assertIs<AppResult.Success<CollaborationRuntimeLease>>(first.await())
        assertIs<AppResult.Failed>(second.await())
        assertEquals(1, f.installations.size)
        assertEquals(0, f.lease.closes)
        f.lease.close()
    }
}

private class InstallationFixture {
    val setup = CollaborationCommandFixture()

    fun gateway(
        owner: CollaborationOwner = setup.owner,
        deviceId: String = "device",
        version: Long = 0,
    ) =
        CollaborationGateway(
            owner,
            deviceId,
            "head",
            version,
            "user",
            "matrix-device",
            "access",
            "refresh",
            1,
            2,
        )

    val gateway =
        CollaborationGateway(
            setup.owner,
            "device",
            "head",
            0,
            "user",
            "matrix-device",
            "access",
            "refresh",
            1,
            2,
        )
    val record =
        setup
            .retain(CollaborationSetupAction.Bootstrap("device", 1, null))
            .copy(
                revision = 2,
                attempts = 1,
                outcome = CollaborationSetupOutcome.Bootstrapped(gateway),
            )
            .also { setup.record = it }
    var lease = InstallationLease(setup.owner)
    val installations = mutableListOf<CollaborationSetupCommandRecord>()
    var installed = false
    var removals = 0
    var onInstall: suspend () -> AppResult<CollaborationRuntimeLease> = { AppResult.Success(lease) }
    var beforeRemove: suspend () -> Unit = {}
    var afterRemove: suspend () -> Unit = {}
    var removeResult: AppResult<Boolean>? = null
    private val commands =
        object : CollaborationSetupCommandRepository by setup.commands {
            override suspend fun remove(
                owner: CollaborationOwner,
                expected: CollaborationSetupCommandRecord,
            ): AppResult<Boolean> {
                removals++
                beforeRemove()
                val result = removeResult ?: setup.commands.remove(owner, expected)
                afterRemove()
                return result
            }
        }
    private val runtimes =
        object : CollaborationRuntimeRepository {
            override suspend fun enterTimeline(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationTimelineLease> = error("No implicit conversation entry")

            override suspend fun closeOtherOwners(
                retainedOwner: CollaborationOwner?
            ): AppResult<Unit> = error("Installation cannot close unrelated owners")

            override suspend fun install(
                owner: CollaborationOwner,
                command: CollaborationSetupCommandRecord,
            ): AppResult<CollaborationRuntimeLease> {
                assertEquals(setup.owner, owner)
                installations.add(command)
                return onInstall().also { if (it is AppResult.Success) installed = true }
            }

            override suspend fun restore(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationRuntimeLease?> = error("No implicit restore")
        }

    fun install(): suspend () -> AppResult<CollaborationRuntimeLease> {
        val useCase =
            InstallCollaborationRuntime(
                commands,
                runtimes,
                setup.access.sessions,
                setup.access.workspaces,
                setup.server,
                setup.build,
                setup.clock,
            )
        return { useCase("selection", "operation") }
    }
}

private class InstallationLease(override var owner: CollaborationOwner) :
    CollaborationRuntimeLease {
    override var deviceId = "device"
    override var installedOperationId = "operation"
    override val credentialFailures = MutableStateFlow<Failure?>(null)
    var closes = 0
    var closeResult: AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun retryCredentialWrite(): AppResult<Unit> =
        error("No implicit credential retry")

    override suspend fun close(): AppResult<Unit> {
        assertTrue(currentCoroutineContext().isActive)
        closes++
        return closeResult
    }
}
