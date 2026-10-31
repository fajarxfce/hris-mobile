package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationSetupCommandTest {
    @Test
    fun gatewayReceiptIsRetainedForSdkInstallationAndNeverRetiredByDeviceObservation() = runTest {
        val f = CollaborationCommandFixture()
        val action = CollaborationSetupAction.Bootstrap("device", 1, null)
        val prepared =
            assertIs<AppResult.Success<CollaborationSetupCommandRecord>>(
                    f.prepare()("selection", action)
                )
                .value
        val gateway =
            CollaborationGateway(
                f.owner,
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
        f.gatewayResponse = { AppResult.Success(gateway) }
        val completed =
            assertIs<AppResult.Success<CollaborationSetupCommandRecord>>(
                    f.replay()("selection", prepared.command.id)
                )
                .value
        assertSame(
            gateway,
            assertIs<CollaborationSetupOutcome.Bootstrapped>(completed.outcome).gateway,
        )
        assertEquals(
            CollaborationBootstrap(prepared.command.id, "device", 1, null),
            f.calls.single(),
        )
        val reads = f.access.calls
        assertEquals(AppResult.Success(false), f.reconcile()("selection", prepared.command.id))
        assertEquals(reads, f.access.calls)
        assertEquals(completed, f.record)
    }

    @Test
    fun foreignBootstrapOwnershipOrGenerationCannotReplaceTheDurableUnconfirmedState() = runTest {
        repeat(4) { scenario ->
            val f = CollaborationCommandFixture()
            val original = f.retain(CollaborationSetupAction.Bootstrap("device", 1, null))
            f.gatewayResponse = {
                AppResult.Success(
                    CollaborationGateway(
                        if (scenario == 0) f.owner.copy(accountId = "other") else f.owner,
                        if (scenario == 1) "other-device" else "device",
                        "head",
                        if (scenario == 2) 1 else 0,
                        "user",
                        "matrix-device",
                        "access",
                        "refresh",
                        if (scenario == 3) 3 else 1,
                        2,
                    )
                )
            }
            assertIs<AppResult.Failed>(f.replay()("selection", original.command.id))
            assertIs<CollaborationSetupOutcome.Unconfirmed>(f.record!!.outcome)
        }
    }

    @Test
    fun competingManualAttemptsShareOneDeliveryOwner() = runTest {
        val f = CollaborationCommandFixture()
        val original = f.retain()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.response = {
            entered.complete(Unit)
            release.await()
            AppResult.Success(CollaborationSetupReceipt("device", 0))
        }
        val replay = f.replay()
        val first = async { replay("selection", original.command.id) }
        entered.await()
        val second = async { replay("selection", original.command.id) }
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, f.calls.size)
        assertEquals(1, f.record!!.attempts)
    }

    @Test
    fun preparationPersistsOneImmutableIntentBeforeAnyMutationTransport() = runTest {
        val f = CollaborationCommandFixture()
        f.access.onRead = { AppResult.Success(null) }
        val action = CollaborationSetupAction.Register("Fictional phone")
        val first =
            assertIs<AppResult.Success<CollaborationSetupCommandRecord>>(
                    f.prepare()("selection", action)
                )
                .value
        assertEquals(first, f.record)
        assertEquals(f.owner.setupBinding(), first.command.binding)
        assertEquals(5, first.command.clientBuild)
        assertEquals(CollaborationSetupOutcome.Pending, first.outcome)
        assertTrue(f.calls.isEmpty())
        assertEquals(AppResult.Success(first), f.prepare()("selection", action))
        assertEquals(1, f.access.calls)
        assertIs<AppResult.Failed>(
            f.prepare()("selection", CollaborationSetupAction.Register("Different"))
        )
        assertEquals(first, f.record)
    }

    @Test
    fun changedSetupOrCompanyDuringPreparationCannotPublishAnotherIntent() = runTest {
        val f = CollaborationCommandFixture()
        assertIs<AppResult.Failed>(
            f.prepare()("selection", CollaborationSetupAction.Register("Fictional phone"))
        )
        assertNull(f.record)
        f.access.onRead = {
            f.access.workspace = f.access.selected.copy(id = "replacement-selection")
            AppResult.Success(null)
        }
        assertIs<AppResult.Failed>(
            f.prepare()("selection", CollaborationSetupAction.Register("Fictional phone"))
        )
        assertNull(f.record)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun lostResponseAndRecreatedDeliveryOwnerReuseTheExactRetainedOperation() = runTest {
        val f = CollaborationCommandFixture()
        val original = f.retain()
        f.response = {
            assertIs<CollaborationSetupOutcome.Unconfirmed>(f.record!!.outcome)
            assertEquals(1, f.record!!.attempts)
            AppResult.Failed(Failure(FailureKind.Timeout, "Response was lost"))
        }
        assertIs<AppResult.Failed>(f.replay()("selection", original.command.id))
        assertEquals(original.command, f.record!!.command)
        f.response = {
            f.access.session =
                f.access.initial.copy(
                    tokens = f.access.initial.tokens.copy(accessToken = "renewed")
                )
            AppResult.Success(CollaborationSetupReceipt("device", 0))
        }
        val recovered =
            assertIs<AppResult.Success<CollaborationSetupCommandRecord>>(
                    f.replay()("selection", original.command.id)
                )
                .value
        assertIs<CollaborationSetupOutcome.Registered>(recovered.outcome)
        assertEquals(2, recovered.attempts)
        assertEquals(f.calls[0], f.calls[1])
        assertEquals(AppResult.Success(recovered), f.replay()("selection", original.command.id))
        assertEquals(2, f.calls.size)
    }

    @Test
    fun failedPreflightPersistenceOrLostRevisionCannotStartTransport() = runTest {
        for (lostRevision in listOf(false, true)) {
            val f = CollaborationCommandFixture()
            val original = f.retain()
            if (lostRevision) f.loseRevision = true else f.failUpdate = 1
            assertIs<AppResult.Failed>(f.replay()("selection", original.command.id))
            assertEquals(original, f.record)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test
    fun failedReceiptPersistenceRetainsUncertaintyAndRetriesTheOriginalRequest() = runTest {
        val f = CollaborationCommandFixture()
        val original = f.retain()
        f.failUpdate = 2
        assertEquals(f.storageFailure, f.replay()("selection", original.command.id))
        assertIs<CollaborationSetupOutcome.Unconfirmed>(f.record!!.outcome)
        f.failUpdate = null
        assertIs<AppResult.Success<CollaborationSetupCommandRecord>>(
            f.replay()("selection", original.command.id)
        )
        assertEquals(f.calls[0], f.calls[1])
    }

    @Test
    fun cancellationOfPendingTransportKeepsTheDurableAttemptForExplicitRecovery() = runTest {
        val f = CollaborationCommandFixture()
        val original = f.retain()
        val entered = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        f.response = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                closed.complete(Unit)
            }
        }
        val pending = async { f.replay()("selection", original.command.id) }
        entered.await()
        pending.cancel()
        assertFailsWith<CancellationException> { pending.await() }
        closed.await()
        assertEquals(original.command, f.record!!.command)
        assertIs<CollaborationSetupOutcome.Unconfirmed>(f.record!!.outcome)
        assertEquals(1, f.record!!.attempts)
    }

    @Test
    fun latePermissionDenialOverridesSuccessAndFailedTransportWithoutPublishingReceipt() = runTest {
        for (succeeded in listOf(true, false)) {
            val f = CollaborationCommandFixture()
            val original = f.retain()
            f.response = {
                f.access.workspace = f.access.selected.copy(permissions = emptySet())
                if (succeeded) AppResult.Success(CollaborationSetupReceipt("device", 0))
                else AppResult.Failed(Failure(FailureKind.Network, "Network unavailable"))
            }
            val denied = assertIs<AppResult.Failed>(f.replay()("selection", original.command.id))
            assertEquals(FailureKind.AccessDenied, denied.failure.kind)
            assertIs<CollaborationSetupOutcome.Unconfirmed>(f.record!!.outcome)
        }
    }

    @Test
    fun knownRemoteDenialSurvivesFailedOutcomePersistenceAndIsNotHiddenByStorageError() = runTest {
        val f = CollaborationCommandFixture()
        val original = f.retain()
        val denied =
            AppResult.Failed(
                Failure(
                    FailureKind.AccessDenied,
                    "Unavailable",
                    code = "collaboration_access_denied",
                )
            )
        f.response = { denied }
        f.failUpdate = 2
        assertEquals(denied, f.replay()("selection", original.command.id))
        assertEquals(original.command, f.record!!.command)
    }

    @Test
    fun buildChangeAndAttemptLimitKeepTheOriginalIntentWithoutTransport() = runTest {
        val f = CollaborationCommandFixture()
        val original = f.retain()
        assertIs<AppResult.Failed>(
            f.replay(f.build.copy(number = 6))("selection", original.command.id)
        )
        assertEquals(original, f.record)
        f.record =
            original.copy(attempts = 1_000, outcome = CollaborationSetupOutcome.Unconfirmed())
        val retained = f.record
        assertIs<AppResult.Failed>(f.replay()("selection", original.command.id))
        assertEquals(retained, f.record)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun scopeReplacementDuringDurablePreflightPreventsTheRequest() = runTest {
        val f = CollaborationCommandFixture()
        val original = f.retain()
        f.beforeUpdate = { f.access.workspace = f.access.selected.copy(id = "replacement") }
        assertIs<AppResult.Failed>(f.replay()("selection", original.command.id))
        assertTrue(f.calls.isEmpty())
        assertIs<CollaborationSetupOutcome.Unconfirmed>(f.record!!.outcome)
    }

    @Test
    fun onlyMatchingObservedReceiptsCanRetireAndUncertainCommandsRemain() = runTest {
        val f = CollaborationCommandFixture()
        val original = f.retain()
        assertEquals(AppResult.Success(false), f.reconcile()("selection", original.command.id))
        assertEquals(0, f.access.calls)
        f.record =
            original.copy(
                attempts = 1,
                revision = 2,
                outcome =
                    CollaborationSetupOutcome.Registered(
                        CollaborationSetupReceipt("other-device", 0)
                    ),
            )
        assertEquals(AppResult.Success(false), f.reconcile()("selection", original.command.id))
        assertNotNull(f.record)
        f.record =
            original.copy(
                attempts = 1,
                revision = 2,
                outcome =
                    CollaborationSetupOutcome.Registered(CollaborationSetupReceipt("device", 0)),
            )
        assertEquals(AppResult.Success(true), f.reconcile()("selection", original.command.id))
        assertNull(f.record)
    }

    @Test
    fun retryUsesObservedProvisioningGenerationAndNewJobReceipt() = runTest {
        val f = CollaborationCommandFixture()
        val action = CollaborationSetupAction.Retry("device", 0, 7)
        val provisioning =
            CollaborationProvisioning(
                7,
                "old-job",
                CollaborationProvisioningState.FAILED,
                3,
                "provider_unavailable",
                null,
            )
        val device =
            f.access.device.copy(
                version = 0,
                state = CollaborationDeviceState.PROVISIONING,
                provisioning = provisioning,
            )
        f.access.onRead = { AppResult.Success(device) }
        val prepared =
            assertIs<AppResult.Success<CollaborationSetupCommandRecord>>(
                    f.prepare()("selection", action)
                )
                .value
        f.response = { AppResult.Success(CollaborationSetupReceipt("new-job", 0)) }
        assertIs<AppResult.Success<CollaborationSetupCommandRecord>>(
            f.replay()("selection", prepared.command.id)
        )
        assertEquals(
            CollaborationProvisioningRetry(prepared.command.id, "device", 0, 7),
            f.calls.single(),
        )
        assertEquals(AppResult.Success(false), f.reconcile()("selection", prepared.command.id))
        f.access.onRead = {
            AppResult.Success(
                device.copy(provisioning = provisioning.copy(version = 8, jobId = "new-job"))
            )
        }
        assertEquals(AppResult.Success(true), f.reconcile()("selection", prepared.command.id))
    }
}
