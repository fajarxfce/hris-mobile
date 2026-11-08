package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ActivateCollaborationDeviceTest {
    @Test
    fun setupPageObservesAutomaticPreparationWithoutAnotherPollerOrOwningItsJob() = runTest {
        val f = CollaborationActivationFixture()
        f.device = f.device!!.copy(state = CollaborationDeviceState.PROVISIONING)
        val operation = f.operation()
        val automatic = async { operation(f.command.owner).last() }
        runCurrent()
        assertEquals(1, f.command.access.calls)
        val seen = mutableListOf<AppResult<CollaborationActivationProgress>>()
        val page = launch {
            operation("selection", CollaborationActivationMode.INSPECT).toList(seen)
        }
        runCurrent()
        assertEquals(
            CollaborationActivationStep.WAITING,
            assertIs<AppResult.Success<CollaborationActivationProgress>>(seen.last()).value.step,
        )
        assertEquals(1, f.command.access.calls)
        page.cancelAndJoin()
        assertTrue(automatic.isActive)
        f.device = f.command.access.device
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(
            CollaborationActivationStep.READY,
            assertIs<AppResult.Success<CollaborationActivationProgress>>(automatic.await())
                .value
                .step,
        )
        assertEquals(1, f.installations.size)
    }

    @Test
    fun automaticProgressReachesTheOpenSetupPageThroughCompletion() = runTest {
        val f = CollaborationActivationFixture()
        f.device = f.device!!.copy(state = CollaborationDeviceState.PROVISIONING)
        val operation = f.operation()
        val automatic = async { operation(f.command.owner).last() }
        runCurrent()
        val page = async { operation("selection", CollaborationActivationMode.INSPECT).last() }
        runCurrent()
        f.device = f.command.access.device
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(automatic.await(), page.await())
        assertEquals(1, f.installations.size)
    }

    @Test
    fun automaticEntryRejectsAReplacedObservedOwnerBeforeRemoteWork() = runTest {
        val f = CollaborationActivationFixture()
        val old = f.command.owner.copy(nativeSessionId = "replaced-native-parent")
        assertIs<AppResult.Failed>(f.operation()(old).last())
        assertEquals(0, f.command.access.calls)
        assertTrue(f.command.calls.isEmpty())
        assertNull(f.command.record)
    }

    @Test
    fun concurrentActivationSharesTheOriginalCommandsAndPublishedRuntime() = runTest {
        val f = CollaborationActivationFixture()
        f.device = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val normal = f.command.response
        f.command.response = {
            entered.complete(Unit)
            release.await()
            normal()
        }
        val operation = f.operation()
        val first = async { operation("selection", CollaborationActivationMode.AUTOMATIC).last() }
        entered.await()
        val second = async { operation("selection", CollaborationActivationMode.AUTOMATIC).last() }
        release.complete(Unit)
        for (job in listOf(first, second)) assertEquals(
            CollaborationActivationStep.READY,
            assertIs<AppResult.Success<CollaborationActivationProgress>>(job.await()).value.step,
        )
        assertEquals(2, f.command.calls.size)
        assertEquals(1, f.installations.size)
        assertEquals(1, f.publications)
    }

    @Test
    fun staleResumeDoesNotInventANewRegistration() = runTest {
        val f = CollaborationActivationFixture()
        f.device = null
        val result = f.operation()("selection", CollaborationActivationMode.RESUME).last()
        assertEquals(
            CollaborationActivationStep.REGISTER,
            assertIs<AppResult.Success<CollaborationActivationProgress>>(result).value.step,
        )
        assertTrue(f.command.calls.isEmpty())
        assertNull(f.command.record)
    }

    @Test
    fun freshDeviceRegistersAndInstallsOneBootstrapWithoutOpeningAChat() = runTest {
        val f = CollaborationActivationFixture()
        f.device = null
        val outcomes = f.operation()("selection", CollaborationActivationMode.AUTOMATIC).toList()
        val result =
            assertIs<AppResult.Success<CollaborationActivationProgress>>(outcomes.last()).value
        assertEquals(CollaborationActivationStep.READY, result.step)
        assertEquals(2, f.command.calls.size)
        assertIs<CollaborationRegistration>(f.command.calls[0])
        val bootstrap = assertIs<CollaborationBootstrap>(f.command.calls[1])
        assertEquals(bootstrap.operationId, f.installations.single().command.id)
        assertEquals(1, f.publications)
        assertNull(f.command.record)
        assertNotNull(f.wake)
        assertEquals(listOf(CollaborationRecoveryWake.WORK_KEY), f.scheduled)
    }

    @Test
    fun firstProvisioningWaitIsFiniteAndNeverCreatesRetries() = runTest {
        val f = CollaborationActivationFixture()
        f.device = f.device!!.copy(state = CollaborationDeviceState.PROVISIONING)
        val result = f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last()
        assertEquals(
            CollaborationActivationStep.WAITING,
            assertIs<AppResult.Success<CollaborationActivationProgress>>(result).value.step,
        )
        assertEquals(7, f.command.access.calls)
        assertEquals(90_000, testScheduler.currentTime)
        assertTrue(f.command.calls.isEmpty())
        advanceTimeBy(600_000)
        assertEquals(7, f.command.access.calls)
    }

    @Test
    fun uncertainRegistrationResumesTheOriginalAcrossWorkflowRecreation() = runTest {
        val f = CollaborationActivationFixture()
        f.device = null
        val original = f.command.retain()
        f.command.response = { AppResult.Failed(Failure(FailureKind.Timeout, "Lost response")) }
        assertIs<AppResult.Failed>(
            f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last()
        )
        assertEquals(original.command, f.command.record!!.command)
        f.command.response = {
            f.device = f.command.access.device
            AppResult.Success(CollaborationSetupReceipt("device", 1))
        }
        val done = f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last()
        assertEquals(
            CollaborationActivationStep.READY,
            assertIs<AppResult.Success<CollaborationActivationProgress>>(done).value.step,
        )
        assertEquals(f.command.calls[0], f.command.calls[1])
        assertEquals(3, f.command.calls.size)
        assertNull(f.command.record)
    }

    @Test
    fun missingOriginalKeysRequireRecoveryWithoutBootstrapOrSdkConstruction() = runTest {
        for (missingCredential in listOf(false, true)) {
            val f = CollaborationActivationFixture()
            f.retainPrivateAccount()
            f.inventory =
                f.inventory.copy(
                    cryptoDatabasePresent = false,
                    credential = if (missingCredential) null else f.inventory.credential,
                )
            f.privateIdentity = null
            val result = f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last()
            assertEquals(
                CollaborationActivationStep.RECOVERY_REQUIRED,
                assertIs<AppResult.Success<CollaborationActivationProgress>>(result).value.step,
            )
            assertTrue(f.command.calls.isEmpty())
            assertTrue(f.installations.isEmpty())
            assertEquals(0, f.restores)
            assertNull(f.wake)
        }
    }

    @Test
    fun activeGatewayWithoutLocalKeysCannotInitializeAnotherAccount() = runTest {
        val f = CollaborationActivationFixture()
        f.device = f.device!!.copy(gateway = CollaborationGatewayStatus("head", 0, 5, 1, 2, null))
        val result = f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last()
        assertEquals(
            CollaborationActivationStep.RECOVERY_REQUIRED,
            assertIs<AppResult.Success<CollaborationActivationProgress>>(result).value.step,
        )
        assertTrue(f.command.calls.isEmpty())
        assertTrue(f.installations.isEmpty())
    }

    @Test
    fun privateReadFailureDoesNotAuthorizeReplacement() = runTest {
        val f = CollaborationActivationFixture()
        f.retainPrivateAccount()
        f.possess = { f.command.storageFailure }
        assertEquals(
            f.command.storageFailure,
            f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last(),
        )
        assertEquals(0, f.restores)
        assertTrue(f.command.calls.isEmpty())
    }

    @Test
    fun originalPrivateAccountRestoresAndPublishesBeforeWakingRecovery() = runTest {
        val f = CollaborationActivationFixture()
        f.retainPrivateAccount()
        val result = f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last()
        assertEquals(
            CollaborationActivationStep.READY,
            assertIs<AppResult.Success<CollaborationActivationProgress>>(result).value.step,
        )
        assertEquals(1, f.restores)
        assertEquals(1, f.publications)
        assertEquals(0, f.closes)
        assertTrue(f.command.calls.isEmpty())
        assertNotNull(f.wake)
    }

    @Test
    fun unsupportedHostDoesNotReadOrEnroll() = runTest {
        val f = CollaborationActivationFixture()
        f.supported = false
        assertEquals(
            "client.collaboration_runtime_unavailable",
            assertIs<AppResult.Failed>(
                    f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last()
                )
                .failure
                .code,
        )
        assertEquals(0, f.command.access.calls)
        assertTrue(f.command.calls.isEmpty())
    }

    @Test
    fun cancellationDuringProvisioningStopsTheFiniteObserver() = runTest {
        val f = CollaborationActivationFixture()
        f.device = f.device!!.copy(state = CollaborationDeviceState.PROVISIONING)
        val job = launch {
            f.operation()("selection", CollaborationActivationMode.AUTOMATIC).collect()
        }
        runCurrent()
        assertEquals(1, f.command.access.calls)
        job.cancelAndJoin()
        advanceTimeBy(600_000)
        assertEquals(1, f.command.access.calls)
        assertTrue(f.command.calls.isEmpty())
    }

    @Test
    fun lateBootstrapAfterOwnerChangePreservesOriginalAndCannotInstall() = runTest {
        val f = CollaborationActivationFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val normal = f.command.gatewayResponse
        f.command.gatewayResponse = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                normal()
            }
        }
        val job = async { f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last() }
        entered.await()
        val original = assertNotNull(f.command.record).command
        f.command.access.session = null
        release.complete(Unit)
        assertIs<AppResult.Failed>(job.await())
        assertEquals(original, f.command.record!!.command)
        assertIs<CollaborationSetupOutcome.Unconfirmed>(f.command.record!!.outcome)
        assertTrue(f.installations.isEmpty())
        assertNull(f.wake)
    }

    @Test
    fun lateRestoreOwnsAndClosesTheLeaseOnCancellation() = runTest {
        val f = CollaborationActivationFixture()
        f.retainPrivateAccount()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onRestore = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                AppResult.Success(f.lease)
            }
        }
        val job = launch {
            f.operation()("selection", CollaborationActivationMode.AUTOMATIC).collect()
        }
        entered.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals(1, f.closes)
        assertEquals(0, f.publications)
        assertNull(f.wake)
    }

    @Test
    fun failedProvisioningNeverCreatesAutomaticRetryCommands() = runTest {
        val f = CollaborationActivationFixture()
        f.device =
            f.device!!.copy(
                state = CollaborationDeviceState.PROVISIONING,
                provisioning =
                    CollaborationProvisioning(
                        1,
                        "job",
                        CollaborationProvisioningState.FAILED,
                        1,
                        null,
                        null,
                    ),
            )
        repeat(3) {
            val result = f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last()
            assertEquals(
                CollaborationActivationStep.RETRY_PROVISIONING,
                assertIs<AppResult.Success<CollaborationActivationProgress>>(result).value.step,
            )
        }
        assertTrue(f.command.calls.isEmpty())
        assertEquals(3, f.command.access.calls)
    }

    @Test
    fun explicitRetryRespectsTheServerDeadline() = runTest {
        val f = CollaborationActivationFixture()
        f.device =
            f.device!!.copy(
                state = CollaborationDeviceState.PROVISIONING,
                provisioning =
                    CollaborationProvisioning(
                        1,
                        "job",
                        CollaborationProvisioningState.FAILED,
                        1,
                        null,
                        f.command.clock.now().toEpochMilliseconds() + 60_000,
                    ),
            )
        val result = f.operation()("selection", CollaborationActivationMode.CONTINUE).last()
        assertEquals(
            CollaborationActivationStep.RETRY_PROVISIONING,
            assertIs<AppResult.Success<CollaborationActivationProgress>>(result).value.step,
        )
        assertTrue(f.command.calls.isEmpty())
    }

    @Test
    fun inspectorNeverReplaysAnUncertainCommand() = runTest {
        val f = CollaborationActivationFixture()
        val original = f.command.retain()
        val result = f.operation()("selection", CollaborationActivationMode.INSPECT).last()
        assertEquals(
            CollaborationActivationStep.RESUME,
            assertIs<AppResult.Success<CollaborationActivationProgress>>(result).value.step,
        )
        assertEquals(original, f.command.record)
        assertTrue(f.command.calls.isEmpty())
    }

    @Test
    fun knownDenialSurvivesFailedAcquisitionAndDoesNotAllowEnrollment() = runTest {
        val f = CollaborationActivationFixture()
        val denied = Failure(FailureKind.AccessDenied, "Denied")
        f.command.access.admission.deny(denied)
        f.command.access.onRead = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
        assertEquals(
            AppResult.Failed(denied),
            f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last(),
        )
        assertTrue(f.command.calls.isEmpty())
    }

    @Test
    fun failedSchedulingRetainsTheDurableWakeAndDoesNotDuplicateBootstrap() = runTest {
        val f = CollaborationActivationFixture()
        val failed = AppResult.Failed(Failure(FailureKind.Unavailable, "Scheduler unavailable"))
        f.schedule = { failed }
        val waiting =
            assertIs<AppResult.Success<CollaborationActivationProgress>>(
                    f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last()
                )
                .value
        assertEquals(CollaborationActivationStep.READY, waiting.step)
        assertEquals(failed.failure, waiting.recoveryFailure)
        assertNotNull(f.wake)
        assertNull(f.command.record)
        assertEquals(1, f.installations.size)
        f.schedule = { AppResult.Success(Unit) }
        assertIs<AppResult.Success<CollaborationActivationProgress>>(
            f.operation()("selection", CollaborationActivationMode.AUTOMATIC).last()
        )
        assertEquals(1, f.installations.size)
        assertEquals(1, f.command.calls.size)
    }
}
