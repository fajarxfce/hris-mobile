@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.leaveFailure
import dev.fajar.starter.leave.domain.working.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate

class LeaveWorkingCopyTest {
    @Test
    fun unfinishedInputRemainsOwnedAfterCredentialAndFreshnessExpiry() = runTest {
        var now = Instant.parse("2026-10-12T01:00:00Z")
        val f = LeaveWorkingFixture(LeaveFixture { now })
        LeaveSyncFixture(f.base).run()
        val initial =
            assertIs<AppResult.Success<LeaveWorkingCopy>>(
                    f.open(f.base.workspace.id, f.base.employee.id, LocalDate(2026, 10, 12))
                )
                .value
        val edited =
            initial.copy(
                revision = Uuid.random().toString(),
                input = initial.input.copy(reason = "Unfinished reason"),
            )
        assertIs<AppResult.Success<*>>(f.save(f.base.workspace.id, initial, edited))
        val stored = requireNotNull(f.stored)
        val remoteReads = f.base.policyReads
        now += 3.days
        f.base.policyCall = { error("Local draft must not request network admission") }
        val restored = f.open(f.base.workspace.id, f.base.employee.id, LocalDate(2026, 10, 15))
        assertEquals(AppResult.Success(stored), restored)
        assertNull(stored.input.type)
        assertEquals(2, f.writes)
        assertEquals(remoteReads, f.base.policyReads)
        assertEquals(0, f.base.replica.directoryObservers)
    }

    @Test
    fun knownDenialPreventsDraftReadWriteAndRemoval() = runTest {
        val f = LeaveWorkingFixture()
        LeaveSyncFixture(f.base).run()
        val initial =
            assertIs<AppResult.Success<LeaveWorkingCopy>>(
                    f.open(f.base.workspace.id, f.base.employee.id, f.base.directory.asOf)
                )
                .value
        val denied = leaveFailure("mfa_required", FailureKind.AccessDenied)
        f.base.replica.failAdmissionWrite = true
        f.base.replica.restrict(f.access(), denied.failure)
        assertEquals(denied, f.open(f.base.workspace.id, f.base.employee.id, f.base.directory.asOf))
        assertEquals(
            denied,
            f.save(f.base.workspace.id, initial, initial.copy(revision = Uuid.random().toString())),
        )
        assertEquals(denied, f.discard(f.base.workspace.id, f.base.employee.id, initial.revision))
        assertEquals(1, f.observations)
        assertEquals(1, f.writes)
        assertEquals(0, f.removals)
        assertEquals(initial, f.stored)
    }

    @Test
    fun changedEmploymentCannotReadAPreviouslyOwnedCopy() = runTest {
        val f = LeaveWorkingFixture()
        LeaveSyncFixture(f.base).run()
        val original =
            assertIs<AppResult.Success<LeaveWorkingCopy>>(
                    f.open(f.base.workspace.id, f.base.employee.id, f.base.directory.asOf)
                )
                .value
        val published = (f.base.replica.published.value as AppResult.Success).value
        f.base.replica.published.value =
            AppResult.Success(
                published.copy(directory = f.base.directory.copy(items = emptyList()))
            )
        assertEquals(
            "employee_not_found",
            assertIs<AppResult.Failed>(
                    f.open(f.base.workspace.id, f.base.employee.id, f.base.directory.asOf)
                )
                .failure
                .code,
        )
        assertEquals(1, f.observations)
        assertEquals(original, f.stored)
    }

    @Test
    fun denialDuringALocalReadCannotPublishInput() = runTest {
        val f = LeaveWorkingFixture()
        LeaveSyncFixture(f.base).run()
        f.open(f.base.workspace.id, f.base.employee.id, f.base.directory.asOf)
        val denied = leaveFailure("mfa_required", FailureKind.AccessDenied)
        f.beforeRead = { f.base.replica.restrict(f.access(), denied.failure) }
        assertEquals(denied, f.open(f.base.workspace.id, f.base.employee.id, f.base.directory.asOf))
        assertEquals(1, f.writes)
    }

    @Test
    fun tokenRenewalDuringSaveDoesNotChangeLocalOwnership() = runTest {
        val f = LeaveWorkingFixture()
        LeaveSyncFixture(f.base).run()
        val initial =
            assertIs<AppResult.Success<LeaveWorkingCopy>>(
                    f.open(f.base.workspace.id, f.base.employee.id, f.base.directory.asOf)
                )
                .value
        f.beforeWrite = {
            f.base.sessionState.value =
                AppResult.Success(
                    f.base.session.copy(
                        binding =
                            f.base.session.binding.copy(
                                expiresAtEpochMillis =
                                    (f.base.clock.now() + 48.hours).toEpochMilliseconds()
                            )
                    )
                )
        }
        val edited =
            initial.copy(
                revision = Uuid.random().toString(),
                input = initial.input.copy(reason = "Retained during renewal"),
            )
        assertIs<AppResult.Success<LeaveWorkingCopy>>(f.save(f.base.workspace.id, initial, edited))
        assertEquals(edited.input, f.stored?.input)
    }

    @Test
    fun cancellationWhileOwnershipIsPendingReleasesItsObservation() = runTest {
        val f = LeaveWorkingFixture()
        LeaveSyncFixture(f.base).run()
        val release = CompletableDeferred<Unit>()
        f.base.replica.readGate = release
        val pending = async {
            f.open(f.base.workspace.id, f.base.employee.id, f.base.directory.asOf)
        }
        runCurrent()
        assertEquals(1, f.base.replica.directoryObservers)
        pending.cancelAndJoin()
        assertEquals(0, f.base.replica.directoryObservers)
        assertEquals(0, f.writes)
        release.complete(Unit)
    }
}
