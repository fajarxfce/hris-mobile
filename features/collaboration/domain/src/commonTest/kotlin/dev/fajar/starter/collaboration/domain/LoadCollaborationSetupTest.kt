package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class LoadCollaborationSetupTest {
    @Test
    fun confirmedSetupDenialSurvivesFailedRetryAndReopensOnlyAfterAdmission() = runTest {
        val f = CollaborationSetupFixture()
        val denied =
            Failure(
                FailureKind.AccessDenied,
                "Fixture denial",
                code = "collaboration_access_denied",
            )
        f.onRead = { AppResult.Failed(denied) }
        assertEquals(denied, assertIs<AppResult.Failed>(f.loader()("selection")).failure)
        assertEquals(denied, f.admission.gate.denial)
        f.onRead = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
        assertEquals(denied, assertIs<AppResult.Failed>(f.loader()("selection")).failure)
        f.onRead = { AppResult.Success(f.device) }
        assertIs<AppResult.Success<*>>(f.loader()("selection"))
        assertNull(f.admission.gate.denial)
    }

    @Test
    fun cancelledReceivedDenialStillClosesSharedContent() = runTest {
        val f = CollaborationSetupFixture()
        val denied = Failure(FailureKind.AccessDenied, "Fixture denied")
        f.onRead = {
            currentCoroutineContext().cancel()
            AppResult.Failed(denied)
        }
        val job = async { f.loader()("selection") }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(denied, f.admission.gate.denial)
    }

    @Test
    fun newerSharedDenialWinsOverSuccessfulSetupRead() = runTest {
        val f = CollaborationSetupFixture()
        val denied = Failure(FailureKind.AccessDenied, "New fixture denial")
        f.onRead = {
            f.admission.deny(denied)
            AppResult.Success(f.device)
        }
        assertEquals(denied, assertIs<AppResult.Failed>(f.loader()("selection")).failure)
        assertEquals(denied, f.admission.gate.denial)
    }

    @Test
    fun failedDenialPersistenceCannotMakeTheRetryAdmitted() = runTest {
        val f = CollaborationSetupFixture()
        val denied = Failure(FailureKind.AccessDenied, "Fixture denied")
        f.admission.restrictFailure = Failure(FailureKind.Storage, "Fixture storage")
        f.onRead = { AppResult.Failed(denied) }
        assertEquals(denied, assertIs<AppResult.Failed>(f.loader()("selection")).failure)
        f.onRead = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
        assertEquals(denied, assertIs<AppResult.Failed>(f.loader()("selection")).failure)
    }

    @Test
    fun finalAdmissionMustStillBelongToTheCapturedAccount() = runTest {
        val f = CollaborationSetupFixture()
        f.admission.beforeAdmit = { f.session = null }
        assertIs<AppResult.Failed>(f.loader()("selection"))
        assertEquals(1, f.calls)
    }

    @Test
    fun aFailedPostRequestReadCannotEraseReceivedServerDenial() = runTest {
        for (remote in
            listOf(
                FailureKind.Unauthorized,
                FailureKind.AccessDenied,
                FailureKind.Network,
            )) for (local in listOf(FailureKind.Storage, FailureKind.Network)) {
            val f = CollaborationSetupFixture()
            val received = Failure(remote, "Fixture response", code = "fixture.remote")
            val later = Failure(local, "Fixture read", code = "fixture.local")
            f.onRead = {
                f.sessionFailure = later
                AppResult.Failed(received)
            }
            val expected = if (remote == FailureKind.Network) later else received
            assertEquals(expected, assertIs<AppResult.Failed>(f.loader()("selection")).failure)
            assertEquals(1, f.calls)
        }
    }

    @Test
    fun aNewCurrentDenialStillSupersedesReceivedDenial() = runTest {
        for (kind in listOf(FailureKind.Unauthorized, FailureKind.AccessDenied)) {
            val f = CollaborationSetupFixture()
            val current = Failure(kind, "Fixture current denial", code = "fixture.current")
            f.onRead = {
                f.sessionFailure = current
                AppResult.Failed(Failure(FailureKind.AccessDenied, "Fixture server denial"))
            }
            assertEquals(current, assertIs<AppResult.Failed>(f.loader()("selection")).failure)
            assertEquals(1, f.calls)
        }
    }

    @Test
    fun loadsTheCapturedOwnerAndAllowsOrdinaryTokenRefreshWithoutChangingDeviceIdentity() =
        runTest {
            val fixture = CollaborationSetupFixture()
            fixture.onRead = {
                fixture.session =
                    fixture.initial.copy(
                        tokens = fixture.initial.tokens.copy(accessToken = "renewed")
                    )
                AppResult.Success(fixture.device)
            }
            val result =
                assertIs<AppResult.Success<CollaborationSetup>>(fixture.loader()("selection")).value
            assertEquals(fixture.observed, result.owner)
            assertEquals("native-family", result.owner.nativeSessionId)
            assertEquals(fixture.device, result.device)
            assertEquals(1, fixture.calls)
        }

    @Test
    fun missingRegistrationIsAnAdmittedSetupStateAndDoesNotAutomaticallyRegister() = runTest {
        val fixture = CollaborationSetupFixture()
        fixture.onRead = { AppResult.Success(null) }
        assertNull(
            assertIs<AppResult.Success<CollaborationSetup>>(fixture.loader()("selection"))
                .value
                .device
        )
        assertEquals(1, fixture.calls)
    }

    @Test
    fun rejectsAccountOrSameCompanySelectionReplacementAfterAResponse() = runTest {
        for (replaceSession in listOf(true, false)) {
            val fixture = CollaborationSetupFixture()
            fixture.onRead = {
                if (replaceSession) fixture.session = fixture.initial.copy(id = "new-local-session")
                else fixture.workspace = fixture.selected.copy(id = "new-selection")
                AppResult.Success(fixture.device)
            }
            assertIs<AppResult.Failed>(fixture.loader()("selection"))
            assertEquals(1, fixture.calls)
        }
    }

    @Test
    fun parentReplacementCannotPublishEvenWhenTheLocalScopeWasReused() = runTest {
        val fixture = CollaborationSetupFixture()
        fixture.onRead = {
            fixture.session =
                fixture.initial.copy(
                    binding = fixture.initial.binding.copy(serverId = "new-family")
                )
            fixture.workspace =
                fixture.selected.copy(
                    admission = fixture.selected.admission.copy(familyId = "new-family")
                )
            AppResult.Success(fixture.device)
        }
        assertEquals(
            "client.session_changed",
            assertIs<AppResult.Failed>(fixture.loader()("selection")).failure.code,
        )
    }

    @Test
    fun currentPermissionLossOverridesBothSuccessfulAndFailedTransportResults() = runTest {
        for (failed in listOf(true, false)) {
            val fixture = CollaborationSetupFixture()
            fixture.onRead = {
                fixture.workspace = fixture.selected.copy(permissions = emptySet())
                if (failed) AppResult.Failed(Failure(FailureKind.Network, "Unavailable"))
                else AppResult.Success(fixture.device)
            }
            assertEquals(
                "permission_denied",
                assertIs<AppResult.Failed>(fixture.loader()("selection")).failure.code,
            )
        }
    }

    @Test
    fun setupReadChecksPlatformModulePolicyAndDeadlinesWithoutDeletingFacts() = runTest {
        val fixture = CollaborationSetupFixture()
        assertIs<AppResult.Failed>(fixture.loader(AppPlatform.Web)("selection"))
        fixture.workspace =
            fixture.selected.copy(
                policy =
                    fixture.selected.policy!!.copy(
                        policy = fixture.selected.policy!!.policy.copy(enabledModules = emptySet())
                    )
            )
        assertEquals(
            "company_module_disabled",
            assertIs<AppResult.Failed>(fixture.loader()("selection")).failure.code,
        )
        fixture.workspace = fixture.selected
        fixture.onRead = {
            fixture.now += 6.minutes
            AppResult.Success(fixture.device)
        }
        assertEquals(
            "client.company_policy_expired",
            assertIs<AppResult.Failed>(fixture.loader()("selection")).failure.code,
        )
        assertEquals(1, fixture.calls)
        assertEquals(fixture.selected, fixture.workspace)
    }

    @Test
    fun accountReplacementDuringWorkspaceAcquisitionStopsBeforeNetwork() = runTest {
        val fixture = CollaborationSetupFixture()
        fixture.beforeWorkspace = { fixture.session = null }
        assertIs<AppResult.Failed>(fixture.loader()("selection"))
        assertEquals(0, fixture.calls)
    }

    @Test
    fun cancellationWhileTheRemoteRequestIsPendingDoesNotPublishOrContinue() = runTest {
        val fixture = CollaborationSetupFixture()
        val entered = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        fixture.onRead = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                closed.complete(Unit)
            }
        }
        val job = async { fixture.loader()("selection") }
        entered.await()
        job.cancelAndJoin()
        assertTrue(closed.isCompleted)
        assertEquals(1, fixture.calls)
    }
}
