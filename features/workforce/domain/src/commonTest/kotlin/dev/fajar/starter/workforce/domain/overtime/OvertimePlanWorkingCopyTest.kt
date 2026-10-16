@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.working.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

class OvertimePlanWorkingCopyTest {
    @Test
    fun unfinishedInputRestoresAndEditsWithoutLiveAuthorityAfterThreeDays() = runTest {
        var now = Instant.parse("2026-10-12T01:00:00Z")
        val f = OvertimePlanWorkingFixture(OvertimeFixture { now })
        f.seedOwnership()
        val original =
            assertIs<AppResult.Success<OvertimePlanWorkingCopy>>(
                    f.open(f.overtime.workspace.id, f.base.employee.id, f.base.directory.asOf)
                )
                .value
        val edited =
            original.copy(
                revision = Uuid.random().toString(),
                input =
                    original.input.copy(
                        startsAt = LocalTime(17, 30),
                        breakMinutes = "",
                        reason = "Unfinished inventory plan",
                    ),
            )
        val saved =
            assertIs<AppResult.Success<OvertimePlanWorkingCopy>>(
                    f.save(f.overtime.workspace.id, original, edited)
                )
                .value
        now += 73.hours
        assertEquals(
            saved,
            assertIs<AppResult.Success<OvertimePlanWorkingCopy>>(
                    f.open(f.overtime.workspace.id, f.base.employee.id, LocalDate(2026, 10, 15))
                )
                .value,
        )
        assertIs<AppResult.Success<OvertimePlanWorkingCopy>>(
            f.save(
                f.overtime.workspace.id,
                saved,
                saved.copy(
                    revision = Uuid.random().toString(),
                    input = saved.input.copy(reason = "Edited offline"),
                ),
            )
        )
        assertEquals(0, f.base.policyReads)
        assertEquals(0, f.overtime.canonicalReads)
        assertEquals(0, f.observers)
    }

    @Test
    fun retainedDenialBlocksOpenSaveAndDiscardBeforePrivateReads() = runTest {
        val f = OvertimePlanWorkingFixture()
        f.seedOwnership()
        val copy =
            assertIs<AppResult.Success<OvertimePlanWorkingCopy>>(
                    f.open(f.overtime.workspace.id, f.base.employee.id, f.base.directory.asOf)
                )
                .value
        val denial = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
        f.overtime.replicas.restrict(f.overtime.access, denial)
        val observations = f.observations
        for (result in
            listOf(
                f.open(f.overtime.workspace.id, f.base.employee.id, f.base.directory.asOf),
                f.save(
                    f.overtime.workspace.id,
                    copy,
                    copy.copy(revision = Uuid.random().toString()),
                ),
                f.discard(f.overtime.workspace.id, copy.employeeId, copy.revision),
            )) assertEquals(denial, assertIs<AppResult.Failed>(result).failure)
        assertEquals(observations, f.observations)
        assertEquals(copy, f.stored)
        assertEquals(0, f.removals)
    }

    @Test
    fun denialDuringPendingReadCannotReturnSavedInput() = runTest {
        val f = OvertimePlanWorkingFixture()
        f.seedOwnership()
        assertIs<AppResult.Success<OvertimePlanWorkingCopy>>(
            f.open(f.overtime.workspace.id, f.base.employee.id, f.base.directory.asOf)
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeRead = {
            entered.complete(Unit)
            release.await()
        }
        val pending = async {
            f.open(f.overtime.workspace.id, f.base.employee.id, f.base.directory.asOf)
        }
        entered.await()
        f.overtime.replicas.restrict(
            f.overtime.access,
            Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required"),
        )
        release.complete(Unit)
        assertEquals("mfa_required", assertIs<AppResult.Failed>(pending.await()).failure.code)
        assertEquals(0, f.observers)
    }

    @Test
    fun cancellationReleasesThePendingReadWithoutCreatingInput() = runTest {
        val f = OvertimePlanWorkingFixture()
        f.seedOwnership()
        val entered = CompletableDeferred<Unit>()
        f.beforeRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = async {
            f.open(f.overtime.workspace.id, f.base.employee.id, f.base.directory.asOf)
        }
        entered.await()
        pending.cancelAndJoin()
        assertEquals(0, f.observers)
        assertEquals(0, f.writes)
        assertNull(f.stored)
    }

    @Test
    fun replacementSessionDuringAReadCannotReceiveThePreviousOwnersInput() = runTest {
        val f = OvertimePlanWorkingFixture()
        f.seedOwnership()
        assertIs<AppResult.Success<OvertimePlanWorkingCopy>>(
            f.open(f.overtime.workspace.id, f.base.employee.id, f.base.directory.asOf)
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeRead = {
            entered.complete(Unit)
            release.await()
        }
        val pending = async {
            f.open(f.overtime.workspace.id, f.base.employee.id, f.base.directory.asOf)
        }
        entered.await()
        f.base.sessionState.value =
            AppResult.Success(f.base.session.copy(id = "replacement-session"))
        release.complete(Unit)
        assertIs<AppResult.Failed>(pending.await())
        assertEquals(0, f.observers)
    }

    @Test
    fun tokenRenewalDoesNotRejectAnAcknowledgedLocalWrite() = runTest {
        val f = OvertimePlanWorkingFixture()
        f.seedOwnership()
        val original =
            assertIs<AppResult.Success<OvertimePlanWorkingCopy>>(
                    f.open(f.overtime.workspace.id, f.base.employee.id, f.base.directory.asOf)
                )
                .value
        f.beforeWrite = {
            val session = f.base.session
            f.base.sessionState.value =
                AppResult.Success(
                    session.copy(
                        binding =
                            session.binding.copy(
                                expiresAtEpochMillis =
                                    session.binding.expiresAtEpochMillis +
                                        1.hours.inWholeMilliseconds
                            )
                    )
                )
        }
        val edited =
            original.copy(
                revision = Uuid.random().toString(),
                input = original.input.copy(reason = "New text"),
            )
        assertIs<AppResult.Success<OvertimePlanWorkingCopy>>(
            f.save(f.overtime.workspace.id, original, edited)
        )
        assertEquals(edited.input, f.stored!!.input)
    }

    @Test
    fun competingRevisionAndDiscardCannotOverwriteNewerInput() = runTest {
        val f = OvertimePlanWorkingFixture()
        f.seedOwnership()
        val original =
            assertIs<AppResult.Success<OvertimePlanWorkingCopy>>(
                    f.open(f.overtime.workspace.id, f.base.employee.id, f.base.directory.asOf)
                )
                .value
        val next =
            original.copy(
                revision = Uuid.random().toString(),
                input = original.input.copy(reason = "Retained newer edit"),
            )
        assertIs<AppResult.Success<OvertimePlanWorkingCopy>>(
            f.save(f.overtime.workspace.id, original, next)
        )
        assertEquals(
            "client.overtime_working_copy_changed",
            assertIs<AppResult.Failed>(
                    f.save(
                        f.overtime.workspace.id,
                        original,
                        original.copy(revision = Uuid.random().toString()),
                    )
                )
                .failure
                .code,
        )
        assertEquals(
            AppResult.Success(false),
            f.discard(f.overtime.workspace.id, original.employeeId, original.revision),
        )
        assertEquals(next.input, f.stored!!.input)
    }

    @Test
    fun foreignEmploymentAndOversizedInputNeverCreateASlot() = runTest {
        val f = OvertimePlanWorkingFixture()
        f.seedOwnership()
        assertIs<AppResult.Failed>(
            f.open(f.overtime.workspace.id, Uuid.random().toString(), f.base.directory.asOf)
        )
        assertEquals(0, f.observations)
        val copy =
            OvertimePlanWorkingCopy(
                f.base.employee.id,
                OvertimePlanWorkingInput(f.base.directory.asOf),
                Uuid.random().toString(),
                f.base.clock.now(),
            )
        assertIs<AppResult.Failed>(
            f.save(
                f.overtime.workspace.id,
                copy,
                copy.copy(
                    revision = Uuid.random().toString(),
                    input = copy.input.copy(reason = "a".repeat(1_002)),
                ),
            )
        )
        assertEquals(0, f.writes)
    }
}
