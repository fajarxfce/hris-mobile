package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ActivateCollaborationPusherTest {
    @Test
    fun repeatedActivationUsesOnlyTheCurrentOwnedDeviceWithoutRestoringOrEnrolling() = runTest {
        val f = CollaborationDeviceOperationFixture()
        repeat(2) { assertEquals(AppResult.Success(Unit), f.activate()) }
        assertEquals(2, f.calls)
        assertEquals(2, f.setup.calls)
        assertEquals(f.setup.initial, f.setup.session)
        assertEquals(f.setup.selected, f.setup.workspace)
    }

    @Test
    fun invalidInputAndUnavailableDeviceCannotActivate() = runTest {
        val f = CollaborationDeviceOperationFixture()
        for (invalid in listOf("", "device", f.device.uppercase(), f.device + "/")) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.useCase("selection", invalid)).failure.kind,
            )
        }
        assertEquals(0, f.setup.calls)
        for (device in
            listOf(
                null,
                f.setup.device,
                f.registered.copy(state = CollaborationDeviceState.PROVISIONING),
                f.registered.copy(platform = CollaborationNativePlatform.IOS),
            )) {
            f.setup.onRead = { AppResult.Success(device) }
            assertIs<AppResult.Failed>(f.activate())
        }
        assertEquals(0, f.calls)
    }

    @Test
    fun expiredOrDeniedWorkspaceAndRetainedReadDenialStopBeforeRemoteWork() = runTest {
        for (mode in 0..3) {
            val f = CollaborationDeviceOperationFixture()
            when (mode) {
                0 -> f.setup.now += 3.hours
                1 -> f.setup.workspace = f.setup.selected.copy(permissions = emptySet())
                2 -> f.setup.workspace = null
                3 -> f.setup.admission.deny(f.denied)
            }
            assertIs<AppResult.Failed>(f.activate())
            assertEquals(0, f.setup.calls)
            assertEquals(0, f.calls)
        }
    }

    @Test
    fun lateOwnerReplacementCannotPublishSuccessOrStartAfterDeviceLookup() = runTest {
        for (duringActivation in listOf(false, true)) {
            for (replacement in 0..3) {
                val f = CollaborationDeviceOperationFixture()
                val replace: suspend () -> Unit = {
                    when (replacement) {
                        0 -> f.setup.session = f.setup.initial.copy(id = "replacement")
                        1 ->
                            f.setup.session =
                                f.setup.initial.copy(
                                    binding =
                                        f.setup.initial.binding.copy(serverId = "other-family")
                                )
                        2 -> f.setup.workspace = f.setup.selected.copy(id = "other-selection")
                        3 -> f.setup.now += 3.hours
                    }
                }
                if (duringActivation)
                    f.onActivate = {
                        replace()
                        AppResult.Success(Unit)
                    }
                else
                    f.setup.onRead = {
                        replace()
                        AppResult.Success(f.registered)
                    }
                assertIs<AppResult.Failed>(f.activate())
                assertEquals(if (duringActivation) 1 else 0, f.calls)
            }
        }
    }

    @Test
    fun currentDenialWinsOverLateSuccessOrTransportFailureAndIsNeverClearedByActivation() =
        runTest {
            for (remoteDenied in listOf(false, true)) {
                val f = CollaborationDeviceOperationFixture()
                f.setup.admission.restrictFailure = f.storage
                f.onActivate = {
                    if (remoteDenied) {
                        f.setup.sessionFailure = f.storage
                        AppResult.Failed(f.denied)
                    } else {
                        f.setup.admission.deny(f.denied)
                        AppResult.Success(Unit)
                    }
                }
                assertEquals(f.denied, assertIs<AppResult.Failed>(f.activate()).failure)
                assertEquals(f.denied, f.setup.admission.gate.denial)
            }
        }

    @Test
    fun sdkTokenExpiryStopsActivationWithoutClosingOwnedLocalChatFacts() = runTest {
        val f = CollaborationDeviceOperationFixture()
        val expired =
            Failure(
                FailureKind.Unauthorized,
                "Expired",
                code = "client.collaboration_sdk_unauthorized",
            )
        f.onActivate = { AppResult.Failed(expired) }
        assertEquals(AppResult.Failed(expired), f.activate())
        assertNull(f.setup.admission.gate.denial)
        assertEquals(1, f.calls)
    }

    @Test
    fun pendingActivationHasNoWaitingCallQueueAndCancellationAllowsAnExplicitRetry() = runTest {
        val f = CollaborationDeviceOperationFixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.onActivate = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val first = async { f.activate() }
        entered.await()
        assertEquals(
            "client.collaboration_pusher_busy",
            assertIs<AppResult.Failed>(f.activate()).failure.code,
        )
        assertEquals(1, f.calls)
        first.cancelAndJoin()
        stopped.await()
        f.onActivate = { AppResult.Success(Unit) }
        assertEquals(AppResult.Success(Unit), f.activate())
        assertEquals(2, f.calls)
    }

    @Test
    fun wholeOperationBudgetCancelsPendingWorkWithoutImplicitRetry() = runTest {
        val f = CollaborationDeviceOperationFixture()
        var stopped = false
        f.onActivate = {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val result = async { f.activate() }
        advanceTimeBy(15_001)
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(result.await()).failure.kind)
        assertTrue(stopped)
        assertEquals(1, f.calls)
        f.onActivate = { AppResult.Success(Unit) }
        assertEquals(AppResult.Success(Unit), f.activate())
    }

    @Test
    fun backgroundActivationUsesItsAdmittedCompanyWithoutSelectingAWorkspace() = runTest {
        val f = CollaborationDeviceOperationFixture()
        val scope = f.background()
        assertEquals(AppResult.Success(Unit), f.useCase(scope, f.device))
        assertNull(f.setup.workspace)
        assertEquals(1, f.calls)
    }

    @Test
    fun backgroundDeviceAndObservedVersionCannotBeReplaced() = runTest {
        val f = CollaborationDeviceOperationFixture()
        val scope = f.background()
        assertIs<AppResult.Failed>(f.useCase(scope, "a0000000-0000-4000-8000-000000000002"))
        assertEquals(0, f.setup.calls)
        val newer =
            scope.copy(
                admission =
                    scope.admission.copy(
                        observation =
                            scope.admission.observation.copy(
                                deviceVersion = f.registered.version + 1
                            )
                    )
            )
        assertIs<AppResult.Failed>(f.useCase(newer, f.device))
        assertEquals(0, f.calls)
    }

    @Test
    fun backgroundExpiryOrNativeReplacementDuringIoCannotPublishSuccess() = runTest {
        for (afterSend in listOf(false, true)) {
            val f = CollaborationDeviceOperationFixture()
            val scope = f.background()
            if (afterSend)
                f.onActivate = {
                    f.setup.session =
                        f.setup.session!!.copy(
                            binding = f.setup.session!!.binding.copy(serverId = "replaced")
                        )
                    AppResult.Success(Unit)
                }
            else
                f.setup.onRead = {
                    f.setup.now += 61.seconds
                    AppResult.Success(f.registered)
                }
            assertIs<AppResult.Failed>(f.useCase(scope, f.device))
            assertEquals(if (afterSend) 1 else 0, f.calls)
        }
    }
}
