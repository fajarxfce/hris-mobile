@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class OvertimeActionWorkingCopyTest {
    @Test
    fun bothActionsRestoreIncompleteInputWithoutLiveAuthorityAfterThreeDays() = runTest {
        var now = Instant.parse("2026-10-12T13:00:00Z")
        val f = OvertimeActionWorkingFixture(OvertimeFixture { now })
        f.seedOwnership()
        val saved =
            listOf(OvertimeAction.SUBMIT_ACTUAL, OvertimeAction.WITHDRAW).map { action ->
                val copy =
                    assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
                            f.open(f.overtime.workspace.id, f.overtime.selection, action)
                        )
                        .value
                val next =
                    copy.copy(
                        revision = Uuid.random().toString(),
                        input =
                            copy.input.copy(
                                actual = copy.input.actual?.copy(breakMinutes = ""),
                                reason = "Unfinished $action",
                            ),
                    )
                assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
                        f.save(f.overtime.workspace.id, f.overtime.selection, copy, next)
                    )
                    .value
            }
        now += 73.hours
        for (copy in saved) {
            assertEquals(
                AppResult.Success(copy),
                f.open(f.overtime.workspace.id, f.overtime.selection, copy.action),
            )
            assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
                f.save(
                    f.overtime.workspace.id,
                    f.overtime.selection,
                    copy,
                    copy.copy(
                        revision = Uuid.random().toString(),
                        input = copy.input.copy(reason = "Edited offline"),
                    ),
                )
            )
        }
        assertEquals(2, f.records.size)
        assertEquals(0, f.base.policyReads)
        assertEquals(0, f.overtime.detailReads)
        assertEquals(0, f.observers)
    }

    @Test
    fun knownDenialBlocksPrivateDraftReadSaveAndDiscard() = runTest {
        val f = OvertimeActionWorkingFixture()
        f.seedOwnership()
        val copy =
            assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
                    f.open(f.overtime.workspace.id, f.overtime.selection, OvertimeAction.WITHDRAW)
                )
                .value
        val denial = Failure(FailureKind.AccessDenied, "Denied", code = "mfa_required")
        f.overtime.replicas.restrict(f.overtime.access, denial)
        val reads = f.observations
        for (result in
            listOf(
                f.open(f.overtime.workspace.id, f.overtime.selection, copy.action),
                f.save(
                    f.overtime.workspace.id,
                    f.overtime.selection,
                    copy,
                    copy.copy(revision = Uuid.random().toString()),
                ),
                f.discard(f.overtime.workspace.id, f.overtime.selection, copy.action, copy.revision),
            )) assertEquals(denial, assertIs<AppResult.Failed>(result).failure)
        assertEquals(reads, f.observations)
        assertEquals(copy, f.records.values.single())
        assertEquals(0, f.removals)
    }

    @Test
    fun denialDuringAPendingReadWinsOverSavedInput() = runTest {
        val f = OvertimeActionWorkingFixture()
        f.seedOwnership()
        f.open(f.overtime.workspace.id, f.overtime.selection, OvertimeAction.WITHDRAW)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeRead = {
            entered.complete(Unit)
            release.await()
        }
        val pending = async {
            f.open(f.overtime.workspace.id, f.overtime.selection, OvertimeAction.WITHDRAW)
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
    fun cancellationReleasesPendingObservationWithoutCreatingADraft() = runTest {
        val f = OvertimeActionWorkingFixture()
        f.seedOwnership()
        val entered = CompletableDeferred<Unit>()
        f.beforeRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = async {
            f.open(f.overtime.workspace.id, f.overtime.selection, OvertimeAction.WITHDRAW)
        }
        entered.await()
        pending.cancelAndJoin()
        assertEquals(0, f.observers)
        assertEquals(0, f.writes)
        assertTrue(f.records.isEmpty())
    }

    @Test
    fun replacementSessionCannotReceiveALateDraftRead() = runTest {
        val f = OvertimeActionWorkingFixture()
        f.seedOwnership()
        f.open(f.overtime.workspace.id, f.overtime.selection, OvertimeAction.WITHDRAW)
        f.beforeRead = {
            f.base.sessionState.value =
                AppResult.Success(f.base.session.copy(id = "replacement-session"))
        }
        assertIs<AppResult.Failed>(
            f.open(f.overtime.workspace.id, f.overtime.selection, OvertimeAction.WITHDRAW)
        )
        assertEquals(0, f.observers)
    }

    @Test
    fun tokenRenewalDoesNotRejectAnAcknowledgedEdit() = runTest {
        val f = OvertimeActionWorkingFixture()
        f.seedOwnership()
        val copy =
            assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
                    f.open(f.overtime.workspace.id, f.overtime.selection, OvertimeAction.WITHDRAW)
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
        assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
            f.save(
                f.overtime.workspace.id,
                f.overtime.selection,
                copy,
                copy.copy(
                    revision = Uuid.random().toString(),
                    input = copy.input.copy(reason = "Changed"),
                ),
            )
        )
    }

    @Test
    fun competingWritesAndDiscardPreserveNewerAndOtherActionInput() = runTest {
        val f = OvertimeActionWorkingFixture()
        f.seedOwnership()
        val copy =
            assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
                    f.open(f.overtime.workspace.id, f.overtime.selection, OvertimeAction.WITHDRAW)
                )
                .value
        val other =
            assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
                    f.open(
                        f.overtime.workspace.id,
                        f.overtime.selection,
                        OvertimeAction.SUBMIT_ACTUAL,
                    )
                )
                .value
        val next =
            copy.copy(
                revision = Uuid.random().toString(),
                input = copy.input.copy(reason = "Newer"),
            )
        assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
            f.save(f.overtime.workspace.id, f.overtime.selection, copy, next)
        )
        assertEquals(
            "client.overtime_working_copy_changed",
            assertIs<AppResult.Failed>(
                    f.save(
                        f.overtime.workspace.id,
                        f.overtime.selection,
                        copy,
                        copy.copy(revision = Uuid.random().toString()),
                    )
                )
                .failure
                .code,
        )
        assertEquals(
            AppResult.Success(false),
            f.discard(f.overtime.workspace.id, f.overtime.selection, copy.action, copy.revision),
        )
        assertEquals(
            AppResult.Success(true),
            f.discard(f.overtime.workspace.id, f.overtime.selection, next.action, next.revision),
        )
        assertEquals(other, f.records.values.single())
    }

    @Test
    fun refreshedRequestVersionDoesNotRewriteUnfinishedInput() = runTest {
        val f = OvertimeActionWorkingFixture()
        f.seedOwnership()
        val copy =
            assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
                    f.open(f.overtime.workspace.id, f.overtime.selection, OvertimeAction.WITHDRAW)
                )
                .value
        val snapshot =
            assertIs<AppResult.Success<OvertimeSnapshot<OvertimeRequestView>?>>(
                    f.overtime.detailRecords.value
                )
                .value!!
        f.overtime.detailRecords.value =
            AppResult.Success(
                snapshot.copy(
                    value =
                        snapshot.value.copy(
                            details =
                                f.overtime.details.copy(
                                    request = f.overtime.request.copy(version = 1)
                                )
                        )
                )
            )
        assertEquals(
            AppResult.Success(copy),
            f.open(f.overtime.workspace.id, f.overtime.selection, copy.action),
        )
        assertIs<AppResult.Failed>(
            f.save(
                f.overtime.workspace.id,
                f.overtime.selection,
                copy,
                copy.copy(
                    revision = Uuid.random().toString(),
                    input = copy.input.copy(requestVersion = 1),
                ),
            )
        )
        assertEquals(copy, f.records.values.single())
    }

    @Test
    fun foreignRequestUnsupportedActionAndOversizedInputNeverAcquireADraft() = runTest {
        val f = OvertimeActionWorkingFixture()
        f.seedOwnership()
        assertIs<AppResult.Failed>(
            f.open(
                f.overtime.workspace.id,
                f.overtime.selection.copy(requestId = Uuid.random().toString()),
                OvertimeAction.WITHDRAW,
            )
        )
        assertIs<AppResult.Failed>(
            f.open(f.overtime.workspace.id, f.overtime.selection, OvertimeAction.APPROVE)
        )
        assertEquals(0, f.observations)
        val copy =
            assertIs<AppResult.Success<OvertimeActionWorkingCopy>>(
                    f.open(f.overtime.workspace.id, f.overtime.selection, OvertimeAction.WITHDRAW)
                )
                .value
        assertIs<AppResult.Failed>(
            f.save(
                f.overtime.workspace.id,
                f.overtime.selection,
                copy,
                copy.copy(
                    revision = Uuid.random().toString(),
                    input = copy.input.copy(reason = "a".repeat(1_002)),
                ),
            )
        )
        assertEquals(1, f.writes)
    }
}
