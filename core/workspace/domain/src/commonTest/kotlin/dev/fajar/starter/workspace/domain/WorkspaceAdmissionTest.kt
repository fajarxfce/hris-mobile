@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workspace.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.*
import kotlin.test.*
import kotlin.time.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class WorkspaceAdmissionTest {
    @Test
    fun credentialsExpiringDuringRenewalDoNotRevokeTheStoredSelection() = runTest {
        val origin = Instant.parse("2026-10-11T00:00:00Z")
        for (phase in listOf("account", "permissions")) {
            val base = origin + testScheduler.currentTime.milliseconds
            val f = AdmissionFixture { origin + testScheduler.currentTime.milliseconds }
            val session =
                f.session.copy(
                    binding =
                        f.session.binding.copy(
                            expiresAtEpochMillis = (base + 1.seconds).toEpochMilliseconds()
                        )
                )
            f.identity.value = AppResult.Success(session)
            val selected =
                f.workspace.copy(
                    admission = f.workspace.admission.copy(expiresAt = base + 1.seconds)
                )
            f.selected.value = AppResult.Success(selected)
            if (phase == "account")
                f.accountCall = {
                    delay(2.seconds)
                    AppResult.Success(f.account)
                }
            else
                f.permissionCall = {
                    delay(2.seconds)
                    AppResult.Success(selected.permissions)
                }
            val result = assertIs<SyncResult.Blocked>(f.refresh())
            assertEquals("client.authentication_required", result.failure.code)
            assertEquals(FailureKind.Unavailable, result.failure.kind)
            assertEquals(AppResult.Success(selected), f.selected.value)
            assertEquals(AppResult.Success(session), f.identity.value)
        }
    }

    @Test
    fun commandAuthorizationCannotOutliveMfaOrSessionAndRejectsAClockRollback() = runTest {
        val fixture = AdmissionFixture()
        val start = fixture.clock.now()
        val mfa =
            SessionAssurance(true, true, true, (start + 10.minutes).toEpochMilliseconds(), null)
        val granted =
            assertIs<AppResult.Success<WorkspaceAdmission>>(
                    workspaceAdmission(fixture.session, mfa, start, start)
                )
                .value
        assertEquals(start + 10.minutes, granted.expiresAt)
        val shortSession =
            fixture.session.copy(
                binding =
                    fixture.session.binding.copy(
                        expiresAtEpochMillis = (start + 5.minutes).toEpochMilliseconds()
                    )
            )
        assertEquals(
            start + 5.minutes,
            assertIs<AppResult.Success<WorkspaceAdmission>>(
                    workspaceAdmission(shortSession, mfa, start, start)
                )
                .value
                .expiresAt,
        )
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(
                    workspaceAdmission(fixture.session, mfa, start, start + 11.minutes)
                )
                .failure
                .code,
        )
        assertEquals(
            "client.workspace_expired",
            assertIs<AppResult.Failed>(
                    workspaceAdmission(
                        fixture.session,
                        fixture.account.assurance,
                        start,
                        start - 1.seconds,
                    )
                )
                .failure
                .code,
        )
    }

    @Test
    fun localScopeSurvivesExpiredAuthorityAndRejectsAnotherFamily() = runTest {
        val fixture = AdmissionFixture {
            Instant.parse("2026-10-11T00:00:00Z") + testScheduler.currentTime.milliseconds
        }
        fixture.selected.value =
            AppResult.Success(
                fixture.workspace.copy(
                    admission =
                        fixture.workspace.admission.copy(
                            expiresAt = fixture.clock.now() + 1.minutes
                        )
                )
            )
        val results = mutableListOf<AppResult<Workspace?>>()
        val observing =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ObserveWorkspace(fixture.sessions, fixture.workspaces)().toList(results)
            }
        assertNotNull(assertIs<AppResult.Success<Workspace?>>(results.last()).value)
        fixture.identity.value =
            AppResult.Success(
                fixture.session.copy(tokens = fixture.session.tokens.copy(accessToken = "rotated"))
            )
        runCurrent()
        assertNotNull(assertIs<AppResult.Success<Workspace?>>(results.last()).value)
        advanceTimeBy(3 * 24 * 60 * 60_000L)
        runCurrent()
        assertNotNull(assertIs<AppResult.Success<Workspace?>>(results.last()).value)
        assertEquals(1, results.size)
        fixture.selected.value =
            AppResult.Success(
                fixture.workspace.copy(
                    admission = fixture.workspace.admission.copy(familyId = "other-family")
                )
            )
        runCurrent()
        assertNull(assertIs<AppResult.Success<Workspace?>>(results.last()).value)
        observing.cancel()
    }

    @Test
    fun renewalPreservesIdentityUntilTheActualCompanyOrPermissionsChange() = runTest {
        var now = Instant.parse("2026-10-11T00:00:00Z")
        val fixture = AdmissionFixture { now }
        now += 5.minutes
        assertEquals(SyncResult.Complete, fixture.refresh())
        val renewed = assertIs<AppResult.Success<Workspace?>>(fixture.selected.value).value!!
        assertEquals(fixture.workspace.id, renewed.id)
        assertEquals(now + 1.hours, renewed.admission.expiresAt)
        fixture.permissionCall = { AppResult.Success(setOf("announcements.read")) }
        assertEquals(SyncResult.Complete, fixture.refresh())
        val changed = assertIs<AppResult.Success<Workspace?>>(fixture.selected.value).value!!
        assertNotEquals(renewed.id, changed.id)
        assertEquals(setOf("announcements.read"), changed.permissions)
    }

    @Test
    fun anOfflineRefreshDoesNotExtendTheLeaseAndAnAuthoritativeDenialRemovesIt() = runTest {
        val fixture = AdmissionFixture()
        fixture.accountCall = { AppResult.Failed(Failure(FailureKind.Network, "offline")) }
        assertIs<SyncResult.Retry>(fixture.refresh())
        assertEquals(AppResult.Success(fixture.workspace), fixture.selected.value)
        fixture.accountCall = { AppResult.Success(fixture.account) }
        fixture.permissionCall = { workspaceFailure("company_access_denied") }
        assertIs<SyncResult.Blocked>(fixture.refresh())
        assertEquals(AppResult.Success(null), fixture.selected.value)
    }

    @Test
    fun lateRefreshAndLateDenialCannotReplaceAnotherSelectedCompany() = runTest {
        for (denied in listOf(false, true)) {
            val fixture = AdmissionFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.permissionCall = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                if (denied) workspaceFailure("company_access_denied")
                else AppResult.Success(fixture.workspace.permissions)
            }
            val pending = async { fixture.refresh() }
            entered.await()
            val replacement =
                fixture.workspace.copy(
                    id = "replacement",
                    company = fixture.workspace.company.copy(id = "another-company"),
                )
            fixture.selected.value = AppResult.Success(replacement)
            release.complete(Unit)
            assertIs<SyncResult.Blocked>(pending.await())
            assertEquals(AppResult.Success(replacement), fixture.selected.value)
        }
    }

    @Test
    fun cancellingAPendingRefreshCannotPublishALateSuccess() = runTest {
        val fixture = AdmissionFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.permissionCall = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(emptySet())
        }
        val pending = async { fixture.refresh() }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(AppResult.Success(fixture.workspace), fixture.selected.value)
    }

    @Test
    fun mfaExpiryDuringAnAccessLookupCannotCreateOrRenewAnAdmission() = runTest {
        var now = Instant.parse("2026-10-11T00:00:00Z")
        val fixture = AdmissionFixture { now }
        fixture.account =
            fixture.account.copy(
                assurance =
                    SessionAssurance(
                        true,
                        true,
                        true,
                        (now + 5.seconds).toEpochMilliseconds(),
                        null,
                    )
            )
        fixture.permissionCall = {
            now += 6.seconds
            AppResult.Success(fixture.workspace.permissions)
        }
        val result =
            SelectWorkspace(fixture.sessions, fixture.accounts, fixture.workspaces, fixture.clock)(
                fixture.session.id,
                fixture.workspace.company.id,
            )
        assertEquals("mfa_required", assertIs<AppResult.Failed>(result).failure.code)
        assertEquals(AppResult.Success(fixture.workspace), fixture.selected.value)
    }

    @Test
    fun storageFailureDuringInvalidationIsPreservedAndNetworkWorkHasADeadline() = runTest {
        val fixture = AdmissionFixture()
        val storage = Failure(FailureKind.Storage, "storage", code = "fixture.storage")
        fixture.casFailure = storage
        fixture.permissionCall = { workspaceFailure("company_access_denied") }
        assertEquals(storage, assertIs<SyncResult.Blocked>(fixture.refresh()).failure)
        fixture.casFailure = null
        fixture.accountCall = {
            delay(31.seconds)
            AppResult.Success(fixture.account)
        }
        assertEquals(
            FailureKind.Timeout,
            assertIs<SyncResult.Retry>(fixture.refresh()).failure?.kind,
        )
        assertEquals(AppResult.Success(fixture.workspace), fixture.selected.value)
    }
}

internal class AdmissionFixture(now: () -> Instant = { Instant.parse("2026-10-11T00:00:00Z") }) {
    val clock =
        object : Clock {
            override fun now() = now()
        }
    val session =
        Session(
            "session",
            User("account", "Alex", "alex@example.invalid"),
            SessionTokens("access", "refresh", Long.MAX_VALUE),
            SessionBinding(
                "family",
                "https://hris.example.invalid/",
                (clock.now() + 4.hours).toEpochMilliseconds(),
            ),
        )
    val workspace =
        Workspace(
            "scope",
            session.id,
            session.binding.audience,
            CompanyMembership("company", "Company", "CO", "Asia/Jakarta"),
            setOf("people.self.read", "announcements.read"),
            WorkspaceAdmission(
                session.user.id,
                session.binding.serverId,
                clock.now(),
                clock.now() + 1.hours,
            ),
        )
    var account =
        AccountSnapshot(
            session.user,
            listOf(workspace.company),
            emptySet(),
            SessionAssurance(false, false, false, null, null),
            false,
        )
    val identity = MutableStateFlow<AppResult<Session?>>(AppResult.Success(session))
    val selected = MutableStateFlow<AppResult<Workspace?>>(AppResult.Success(workspace))
    var casFailure: Failure? = null
    var accountCall: suspend () -> AppResult<AccountSnapshot> = { AppResult.Success(account) }
    var permissionCall: suspend () -> AppResult<Set<String>> = {
        AppResult.Success(workspace.permissions)
    }
    var policyCall: suspend () -> AppResult<CompanyPolicy> = {
        AppResult.Success(testCompanyPolicy(clock.now()))
    }
    val sessions =
        object : SessionRepository {
            override val persistent = true

            override fun observe() = identity

            override suspend fun current() = identity.value

            override suspend fun compareAndSet(expected: Session?, updated: Session?) =
                AppResult.Success(
                    identity.compareAndSet(AppResult.Success(expected), AppResult.Success(updated))
                )

            override suspend fun clear(sessionId: String) =
                compareAndSet(
                    (identity.value as? AppResult.Success)?.value?.takeIf { it.id == sessionId },
                    null,
                )
        }
    val accounts =
        object : AccountRepository {
            override suspend fun load(sessionId: String) = accountCall()
        }
    val workspaces =
        object : WorkspaceRepository {
            override suspend fun clientPolicy(sessionId: String, companyId: String) = policyCall()

            override fun observe(sessionId: String) = selected

            override suspend fun current(sessionId: String) = selected.value

            override suspend fun compareAndSet(
                sessionId: String,
                expected: Workspace?,
                updated: Workspace?,
            ): AppResult<Boolean> =
                casFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(
                        selected.compareAndSet(
                            AppResult.Success(expected),
                            AppResult.Success(updated),
                        )
                    )

            override suspend fun permissions(sessionId: String, companyId: String) =
                permissionCall()
        }
    val refresh = RefreshWorkspace(sessions, accounts, workspaces, clock)
}
