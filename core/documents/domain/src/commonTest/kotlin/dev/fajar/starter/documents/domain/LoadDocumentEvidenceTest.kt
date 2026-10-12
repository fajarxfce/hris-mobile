package dev.fajar.starter.documents.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentEvidenceSnapshot
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class LoadDocumentEvidenceTest {
    @Test
    fun readNeedsOnlyDocumentsAndKeepsTheFreshPolicyDeadline() = runTest {
        val f = DocumentFixture()
        val snapshot =
            assertIs<AppResult.Success<DocumentEvidenceSnapshot>>(f.load("workspace", f.query))
                .value
        assertEquals(f.page, snapshot.page)
        assertEquals(f.query, snapshot.query)
        assertEquals(f.policy.validUntil, snapshot.expiresAt)
        assertEquals(1, f.policyReads)
        assertEquals(1, f.evidenceReads)
        val maintenance = CompanyMaintenance(f.now + 20.seconds, f.now + 40.seconds)
        f.policyCall = { AppResult.Success(f.policy.copy(maintenance = maintenance)) }
        val bounded =
            assertIs<AppResult.Success<DocumentEvidenceSnapshot>>(f.load("workspace", f.query))
                .value
        assertEquals(maintenance.startsAt, bounded.expiresAt)
    }

    @Test
    fun ordinaryPolicyExpiryStillAllowsFreshAdmittedAcquisition() = runTest {
        val f = DocumentFixture()
        f.now += 61.seconds
        f.policyCall = {
            AppResult.Success(f.policy.copy(serverTime = f.now, validUntil = f.now + 60.seconds))
        }
        val snapshot =
            assertIs<AppResult.Success<DocumentEvidenceSnapshot>>(f.load("workspace", f.query))
                .value
        assertEquals(f.now + 60.seconds, snapshot.expiresAt)
        assertEquals(1, f.policyReads)
        assertEquals(1, f.evidenceReads)
    }

    @Test
    fun invalidSelectorsAndBroadAdministrativeGrantsCannotAcquireEvidence() = runTest {
        val f = DocumentFixture()
        for (query in
            listOf(
                f.query.copy(employmentId = "../other"),
                f.query.copy(after = "../next"),
                f.query.copy(after = ""),
            )) {
            assertIs<AppResult.Failed>(f.load("workspace", query))
        }
        f.workspaces.value =
            AppResult.Success(
                f.workspace.copy(permissions = setOf("documents.read", "people.profile.read"))
            )
        assertEquals(
            "access_denied",
            assertIs<AppResult.Failed>(f.load("workspace", f.query)).failure.code,
        )
        assertEquals(0, f.policyReads)
        assertEquals(0, f.evidenceReads)
    }

    @Test
    fun companyAvailabilityAndVersionGatesRunBeforeDocumentTransport() = runTest {
        for (mode in listOf("disabled", "maintenance", "version", "unavailable")) {
            val f = DocumentFixture()
            f.policyCall = {
                when (mode) {
                    "disabled" -> AppResult.Success(f.policy.copy(enabledModules = emptySet()))
                    "maintenance" ->
                        AppResult.Success(
                            f.policy.copy(
                                maintenance =
                                    CompanyMaintenance(f.now - 1.seconds, f.now + 30.seconds),
                                maintenanceActive = true,
                            )
                        )
                    "version" ->
                        AppResult.Success(
                            f.policy.copy(minimumBuilds = CompanyMinimumBuilds(2, 0, 0))
                        )
                    else -> AppResult.Failed(Failure(FailureKind.Network, "Unavailable"))
                }
            }
            assertIs<AppResult.Failed>(f.load("workspace", f.query), mode)
            assertEquals(0, f.evidenceReads, mode)
        }
    }

    @Test
    fun lateResultsAndFailuresCannotEscapeAChangedScope() = runTest {
        for (stage in listOf("policy", "evidence")) for (denied in
            listOf(false, true)) for (change in listOf("company", "account", "permission")) {
            val f = DocumentFixture()
            val release = CompletableDeferred<Unit>()
            val failure =
                AppResult.Failed(
                    Failure(FailureKind.AccessDenied, "Denied", code = "access_denied")
                )
            if (stage == "policy")
                f.policyCall = {
                    release.await()
                    if (denied) failure else AppResult.Success(f.policy)
                }
            else
                f.evidenceCall = {
                    release.await()
                    if (denied) failure else AppResult.Success(f.page)
                }
            val pending = async { f.load("workspace", f.query) }
            runCurrent()
            when (change) {
                "company" ->
                    f.workspaces.value = AppResult.Success(f.workspace.copy(id = "replacement"))
                "account" -> f.sessions.value = AppResult.Success(null)
                else ->
                    f.workspaces.value =
                        AppResult.Success(f.workspace.copy(permissions = emptySet()))
            }
            release.complete(Unit)
            val result = assertIs<AppResult.Failed>(pending.await())
            assertEquals(
                when (change) {
                    "company" -> "client.workspace_changed"
                    "account" -> "client.session_changed"
                    else -> "access_denied"
                },
                result.failure.code,
            )
        }
    }

    @Test
    fun pendingReadCannotOutliveWorkspaceAdmission() = runTest {
        val f = DocumentFixture()
        f.workspaces.value =
            AppResult.Success(
                f.workspace.copy(
                    admission = f.workspace.admission.copy(expiresAt = f.now + 1.seconds)
                )
            )
        f.evidenceCall = {
            f.now += 2.seconds
            AppResult.Success(f.page)
        }
        assertEquals(
            "client.workspace_expired",
            assertIs<AppResult.Failed>(f.load("workspace", f.query)).failure.code,
        )
    }

    @Test
    fun cancellationRejectsLateResultsAndAStalledRequestTerminates() = runTest {
        val f = DocumentFixture()
        val release = CompletableDeferred<Unit>()
        f.evidenceCall = {
            withContext(NonCancellable) { release.await() }
            AppResult.Success(f.page)
        }
        val pending = async { f.load("workspace", f.query) }
        runCurrent()
        pending.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        var active = 0
        f.evidenceCall = {
            active++
            try {
                awaitCancellation()
            } finally {
                active--
            }
        }
        val stalled = async { f.load("workspace", f.query) }
        runCurrent()
        assertEquals(1, active)
        advanceTimeBy(30_001)
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(stalled.await()).failure.kind)
        assertEquals(0, active)
    }
}
