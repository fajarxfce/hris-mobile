package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ActivateCollaborationPusher
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ActivateCollaborationPusherTest {
    @Test
    fun repeatedActivationUsesOnlyTheCurrentOwnedDeviceWithoutRestoringOrEnrolling() = runTest {
        val f = Fixture()
        repeat(2) { assertEquals(AppResult.Success(Unit), f.activate()) }
        assertEquals(2, f.calls)
        assertEquals(2, f.setup.calls)
        assertEquals(f.setup.initial, f.setup.session)
        assertEquals(f.setup.selected, f.setup.workspace)
    }

    @Test
    fun invalidInputAndUnavailableDeviceCannotActivate() = runTest {
        val f = Fixture()
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
            val f = Fixture()
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
                val f = Fixture()
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
                val f = Fixture()
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
        val f = Fixture()
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
        val f = Fixture()
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
        val f = Fixture()
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
        val f = Fixture()
        val scope = f.background()
        assertEquals(AppResult.Success(Unit), f.useCase(scope, f.device))
        assertNull(f.setup.workspace)
        assertEquals(1, f.calls)
    }

    @Test
    fun backgroundDeviceAndObservedVersionCannotBeReplaced() = runTest {
        val f = Fixture()
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
            val f = Fixture()
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

    private class Fixture {
        val setup = CollaborationSetupFixture()
        val device = "a0000000-0000-4000-8000-000000000001"
        val registered = setup.device.copy(id = device)
        val denied = Failure(FailureKind.AccessDenied, "Denied", code = "permission_denied")
        val storage = Failure(FailureKind.Storage, "Unavailable", code = "fixture.storage")
        var expectedOwner =
            CollaborationOwner(
                setup.initial.id,
                setup.initial.binding.audience,
                setup.initial.user.id,
                setup.selected.company.id,
                setup.initial.binding.serverId,
            )

        fun background(): CollaborationOperationScope.Background {
            val session =
                setup.initial.copy(
                    user = setup.initial.user.copy(id = "a1000000-0000-4000-8000-000000000001"),
                    binding =
                        setup.initial.binding.copy(
                            serverId = "a1000000-0000-4000-8000-000000000002"
                        ),
                )
            setup.session = session
            setup.workspace = null
            expectedOwner =
                CollaborationOwner(
                    session.id,
                    session.binding.audience,
                    session.user.id,
                    "a1000000-0000-4000-8000-000000000003",
                    session.binding.serverId,
                )
            return CollaborationOperationScope.Background(
                CollaborationBackgroundAdmission(
                    CollaborationAdmissionObservation(
                        expectedOwner,
                        device,
                        registered.version,
                        0,
                        0,
                        setup.now,
                        setup.now + 60.seconds,
                    ),
                    setup.now,
                    setup.now + 60.seconds,
                    TestTimeSource().markNow() + 60.seconds,
                )
            )
        }

        var calls = 0
        var onActivate: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }

        init {
            setup.onRead = { AppResult.Success(registered) }
        }

        val runtime =
            object : CollaborationRuntimeRepository by CollaborationDeliveryFixture().runtimes {
                override suspend fun updateRetention(
                    retention:
                        dev.fajar.starter.collaboration.domain.entities.CollaborationRuntimeRetention
                ): AppResult<Unit> = closeOtherOwners(retention.foreground)

                override suspend fun acquireBackground(
                    owner: CollaborationOwner,
                    deviceId: String,
                    lifetimeMillis: Long,
                ): AppResult<
                    dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease?
                > = error("No implicit background runtime")

                override suspend fun activatePusher(
                    owner: CollaborationOwner,
                    deviceId: String,
                ): AppResult<Unit> {
                    assertEquals(device, deviceId)
                    assertEquals(expectedOwner, owner)
                    calls++
                    return onActivate()
                }
            }
        val useCase =
            ActivateCollaborationPusher(
                runtime,
                setup.remote,
                setup.admission.repository,
                setup.sessions,
                setup.workspaces,
                IdentityServer(setup.initial.binding.audience, "Fictional Android"),
                AppBuild(AppPlatform.Android, 5, "test", null),
                object : Clock {
                    override fun now() = setup.now
                },
            )

        suspend fun activate() = useCase("selection", device)
    }
}
