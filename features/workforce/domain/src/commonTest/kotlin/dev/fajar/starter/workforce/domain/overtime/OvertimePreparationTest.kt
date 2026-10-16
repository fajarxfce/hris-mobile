package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*

@OptIn(ExperimentalCoroutinesApi::class)
class OvertimePreparationTest {
    @Test
    fun reviewPinsTheOwnedEmploymentScheduleAndFinitePolicyLeaseWithoutWriting() = runTest {
        val f = OvertimePlanningFixture()
        val prepared = assertIs<AppResult.Success<OvertimePreparation>>(f.load()).value
        assertEquals(f.selection, prepared.selection)
        assertEquals(f.review, prepared.review)
        assertEquals(f.base.clock.now() + 60.seconds, prepared.expiresAt)
        assertEquals(1, f.base.directoryReads)
        assertEquals(1, f.calls)
        assertEquals(0, f.base.calendarReads)
        assertEquals(0, f.overtime.writes)
        assertEquals(0, f.base.writes)
    }

    @Test
    fun readOnlyPermissionCannotStartPreparation() = runTest {
        val f = OvertimePlanningFixture()
        f.base.workspaceState.value =
            AppResult.Success(f.overtime.workspace.copy(permissions = setOf("overtime.read")))
        assertEquals("access_denied", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertEquals(0, f.base.policyReads)
        assertEquals(0, f.calls)
    }

    @Test
    fun invalidDatesAndForeignOwnedReferencesStopBeforeReview() = runTest {
        val f = OvertimePlanningFixture()
        assertIs<AppResult.Failed>(f.load(f.selection.copy(employeeId = "invalid")))
        assertIs<AppResult.Failed>(f.load(f.selection.copy(workDate = LocalDate(1999, 12, 31))))
        assertEquals(0, f.base.policyReads)
        assertEquals(
            "overtime_date_out_of_range",
            assertIs<AppResult.Failed>(
                    f.load(
                        f.selection.copy(workDate = f.selection.workDate.plus(91, DateTimeUnit.DAY))
                    )
                )
                .failure
                .code,
        )
        f.base.directoryCall = {
            AppResult.Success(f.base.directory.copy(accountId = "other-account"))
        }
        assertEquals("client.invalid_response", assertIs<AppResult.Failed>(f.load()).failure.code)
        f.base.directoryCall = { AppResult.Success(f.base.directory.copy(items = emptyList())) }
        assertEquals("employee_not_found", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertEquals(0, f.calls)
    }

    @Test
    fun companyReplacementDominatesALateTechnicalFailure() = runTest {
        val f = OvertimePlanningFixture()
        f.reviewCall = {
            f.base.workspaceState.value =
                AppResult.Success(f.overtime.workspace.copy(id = "replacement"))
            overtimeFailure("fixture.network", FailureKind.Network)
        }
        assertEquals(FailureKind.AccessDenied, assertIs<AppResult.Failed>(f.load()).failure.kind)
    }

    @Test
    fun sessionReplacementDuringPolicyOrDirectoryAcquisitionPreventsReview() = runTest {
        for (atDirectory in listOf(false, true)) {
            val f = OvertimePlanningFixture()
            fun replace() {
                f.base.sessionState.value =
                    AppResult.Success(f.base.session.copy(id = "replacement"))
            }
            if (atDirectory)
                f.base.directoryCall = {
                    replace()
                    AppResult.Success(f.base.directory)
                }
            else
                f.base.policyCall = {
                    replace()
                    AppResult.Success(f.base.policy)
                }
            assertIs<AppResult.Failed>(f.load())
            assertEquals(0, f.calls)
        }
    }

    @Test
    fun cancellationDiscardsANonCooperativeLateReview() = runTest {
        val f = OvertimePlanningFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        f.reviewCall = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(f.review)
        }
        val running = launch {
            f.load()
            published = true
        }
        entered.await()
        running.cancel()
        release.complete(Unit)
        running.join()
        assertFalse(published)
        f.reviewCall = { AppResult.Success(f.review) }
        assertIs<AppResult.Success<*>>(f.load())
    }

    @Test
    fun acquisitionTimeoutIncludesPendingEmploymentDiscoveryAndReleasesIt() = runTest {
        val f = OvertimePlanningFixture()
        var disposed = false
        f.base.directoryCall = {
            try {
                awaitCancellation()
            } finally {
                disposed = true
            }
        }
        val running = async { f.load() }
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(
            "client.overtime_timeout",
            assertIs<AppResult.Failed>(running.await()).failure.code,
        )
        assertTrue(disposed)
        assertEquals(0, f.calls)
    }

    @Test
    fun latePermissionLossAndExpiredAssuranceCannotPublishPreparation() = runTest {
        var now = Instant.parse("2026-10-12T01:00:00Z")
        val f = OvertimePlanningFixture { now }
        f.reviewCall = {
            now += 1.hours
            AppResult.Success(f.review)
        }
        assertIs<AppResult.Failed>(f.load())
        now -= 1.hours
        f.reviewCall = {
            f.base.workspaceState.value =
                AppResult.Success(f.overtime.workspace.copy(permissions = emptySet()))
            AppResult.Success(f.review)
        }
        assertEquals("access_denied", assertIs<AppResult.Failed>(f.load()).failure.code)
    }

    @Test
    fun changedProtocolFactsAndStretchedOrForeignReviewAreRejected() = runTest {
        val f = OvertimePlanningFixture()
        val original = f.review
        for (review in
            listOf(
                original.copy(employee = original.employee.copy(id = "other")),
                original.copy(workDate = original.workDate.plus(1, DateTimeUnit.DAY)),
                original.copy(revision = "invalid"),
            )) {
            f.review = review
            assertEquals(
                "client.invalid_response",
                assertIs<AppResult.Failed>(f.load()).failure.code,
            )
        }
        f.review = original
        val prepared = assertIs<AppResult.Success<OvertimePreparation>>(f.load()).value
        val now = f.base.clock.now()
        assertIs<AppResult.Failed>(
            validateOvertimePreparation(
                prepared.copy(expiresAt = now + 6.minutes),
                prepared.access,
                now,
            )
        )
        assertIs<AppResult.Failed>(
            validateOvertimePreparation(
                prepared.copy(fetchedAt = now + 1.seconds),
                prepared.access,
                now,
            )
        )
        assertIs<AppResult.Failed>(
            validateOvertimePreparation(prepared, prepared.access.copy(companyId = "other"), now)
        )
        assertIs<AppResult.Failed>(
            validateOvertimePreparation(prepared, prepared.access, prepared.expiresAt)
        )
    }

    @Test
    fun freshMaintenanceAndSlowAcquisitionDoNotExtendReviewAuthority() = runTest {
        var now = Instant.parse("2026-10-12T01:00:00Z")
        val f = OvertimePlanningFixture { now }
        f.base.policyCall = {
            AppResult.Success(
                f.base.policy.copy(
                    maintenance = CompanyMaintenance(now + 25.seconds, now + 1.hours)
                )
            )
        }
        f.reviewCall = {
            now += 10.seconds
            AppResult.Success(f.review)
        }
        val prepared = assertIs<AppResult.Success<OvertimePreparation>>(f.load()).value
        assertEquals(now + 15.seconds, prepared.expiresAt)
        assertEquals(prepared.expiresAt, prepared.access.authorizationExpiresAt)
    }
}
