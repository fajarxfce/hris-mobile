@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.expenses.domain.working.usecases.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ExpenseWorkingCopyTest {
    @Test
    fun ownedInputReopensLocallyWithoutAgeOrCredentialDeadlines() = runTest {
        var now = Instant.parse("2026-10-13T00:00:00Z")
        val p = WorkingFixture(ExpenseFixture { now })
        assertIs<AppResult.Success<*>>(p.f.load())
        val reads = p.f.directoryReads to p.f.policyReads
        val copy = p.open()
        val item =
            ExpenseWorkingItem(
                ExpenseLineInput(Uuid.random().toString(), "", "2026-10-13", "12.", "Unfinished")
            )
        val proposal =
            copy.copy(
                input = copy.input.copy(title = "", description = "Local input"),
                item = item,
                revision = Uuid.random().toString(),
            )
        val stored =
            assertIs<AppResult.Success<ExpenseWorkingCopy>>(
                    p.save(p.f.workspace.id, p.slot, copy, proposal)
                )
                .value
        now += 73.hours
        p.f.directoryCall = { error("No remote acquisition for stored input") }
        assertEquals(stored, p.open())
        assertEquals(reads, p.f.directoryReads to p.f.policyReads)
        assertEquals("12.", p.open().item?.input?.amount)
        val edited =
            stored.copy(
                input = stored.input.copy(title = "Offline edit"),
                revision = Uuid.random().toString(),
            )
        assertIs<AppResult.Success<ExpenseWorkingCopy>>(
            p.save(p.f.workspace.id, p.slot, stored, edited)
        )
        assertEquals("Offline edit", p.open().input.title)
    }

    @Test
    fun openingAClaimUsesItsSavedVersionAndCannotSilentlyRebaseIt() = runTest {
        val p = WorkingFixture()
        p.f.load()
        val slot = ExpenseWorkingSlot.Claim(p.f.claim.id)
        val copy =
            assertIs<AppResult.Success<ExpenseWorkingCopy>>(
                    p.open(p.f.workspace.id, p.f.employee.id, slot)
                )
                .value
        assertEquals(p.f.claim.version, copy.expectedVersion)
        assertEquals(p.f.claim.draft.lines, copy.input.lines)
        val edited = copy.copy(expectedVersion = 5, revision = Uuid.random().toString())
        assertEquals(
            "client.expense_working_copy_changed",
            assertIs<AppResult.Failed>(p.save(p.f.workspace.id, slot, copy, edited)).failure.code,
        )
        assertEquals(
            copy,
            assertIs<AppResult.Success<ExpenseWorkingCopy?>>(
                    p.copies.observe(p.f.access, slot).first()
                )
                .value,
        )
    }

    @Test
    fun concurrentWritesAndReviewedDiscardCannotOverwriteNewerInput() = runTest {
        val p = WorkingFixture()
        p.f.load()
        val copy = p.open()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first =
            copy.copy(
                input = copy.input.copy(title = "First editor"),
                revision = Uuid.random().toString(),
            )
        val second =
            copy.copy(
                input = copy.input.copy(title = "Second editor"),
                revision = Uuid.random().toString(),
            )
        p.copies.beforeSave = {
            if (it.revision == first.revision) {
                entered.complete(Unit)
                release.await()
            }
        }
        val pending = async { p.save(p.f.workspace.id, p.slot, copy, first) }
        entered.await()
        assertIs<AppResult.Success<ExpenseWorkingCopy>>(
            p.save(p.f.workspace.id, p.slot, copy, second)
        )
        release.complete(Unit)
        assertEquals(
            "client.expense_working_copy_changed",
            assertIs<AppResult.Failed>(pending.await()).failure.code,
        )
        assertIs<AppResult.Failed>(p.discard(p.f.workspace.id, p.slot, copy.revision))
        assertEquals("Second editor", p.open().input.title)
    }

    @Test
    fun denialDuringAFailedWriteKeepsInputRetainedAndTheProjectionGated() = runTest {
        val p = WorkingFixture()
        p.f.load()
        val copy = p.open()
        p.copies.failure = expenseFailure("fixture.storage", FailureKind.Storage)
        p.f.replicas.failAdmissionWrite = true
        p.copies.beforeSave = {
            p.f.replicas.restrict(
                p.f.access,
                expenseFailure("mfa_required", FailureKind.AccessDenied).failure,
            )
        }
        val result =
            p.save(
                p.f.workspace.id,
                p.slot,
                copy,
                copy.copy(
                    revision = Uuid.random().toString(),
                    input = copy.input.copy(title = "Pending"),
                ),
            )
        assertEquals("mfa_required", assertIs<AppResult.Failed>(result).failure.code)
        assertEquals(copy, p.copies.values.value.values.single())
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(p.observe(p.f.workspace.id, p.slot).first()).failure.code,
        )
        p.copies.failure = null
        p.f.replicas.failAdmissionWrite = false
        assertIs<AppResult.Success<*>>(p.f.load())
        assertEquals(copy, p.open())
    }

    @Test
    fun aLateAdmissionReadCannotPublishUnderAReplacementAccount() = runTest {
        val p = WorkingFixture()
        p.f.load()
        val copy = p.open()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        p.f.replicas.beforeAdmissionRead = {
            entered.complete(Unit)
            release.await()
        }
        val pending = async {
            p.save(
                p.f.workspace.id,
                p.slot,
                copy,
                copy.copy(
                    revision = Uuid.random().toString(),
                    input = copy.input.copy(title = "Late"),
                ),
            )
        }
        entered.await()
        p.f.sessionState.value = AppResult.Success(null)
        release.complete(Unit)
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(pending.await()).failure.kind,
        )
        assertEquals(copy, p.copies.values.value.values.single())
    }

    @Test
    fun observationStopsPrivateReadsAfterDenialAndReleasesOnDisposal() = runTest {
        val p = WorkingFixture()
        p.f.load()
        val copy = p.open()
        val seen = mutableListOf<AppResult<ExpenseWorkingCopy?>>()
        val job = launch { p.observe(p.f.workspace.id, p.slot).toList(seen) }
        runCurrent()
        assertEquals(AppResult.Success(copy), seen.last())
        assertEquals(1, p.copies.observers)
        p.f.replicas.restrict(
            p.f.access,
            expenseFailure("mfa_required", FailureKind.AccessDenied).failure,
        )
        runCurrent()
        assertEquals(0, p.copies.observers)
        assertIs<AppResult.Failed>(seen.last())
        job.cancelAndJoin()
        assertEquals(0, p.f.replicas.admissionObservers)
    }

    @Test
    fun pickerDenialsSurviveFailedCleanupAndDoNotReopenOnANetworkFailure() = runTest {
        for (kind in ExpensePreparationKind.entries) {
            val p = ExpensePreparationFixture()
            p.f.replicas.failAdmissionWrite = true
            p.failure = expenseFailure("mfa_required", FailureKind.AccessDenied)
            assertEquals("mfa_required", assertIs<AppResult.Failed>(p.load(kind)).failure.code)
            assertEquals("mfa_required", p.f.replicas.readAdmission.value.denial?.code)
            p.failure = expenseFailure("fixture.network", FailureKind.Network)
            p.load(kind)
            assertEquals("mfa_required", p.f.replicas.readAdmission.value.denial?.code)
        }
    }

    @Test
    fun reservedClaimIdentitySurvivesRepeatedPreparationAndRejectsADifferentDraft() = runTest {
        val p = ExpenseCommandFixture()
        val reserved = Uuid.random().toString()
        val first =
            assertIs<AppResult.Success<ExpenseCommandRecord>>(
                    p.prepare(p.f.workspace.id, p.preparation, p.input, reservedClaimId = reserved)
                )
                .value
        val again = p.prepare(p.f.workspace.id, p.preparation, p.input, reservedClaimId = reserved)
        assertEquals(reserved, first.command.claimId)
        assertEquals(AppResult.Success(first), again)
        assertIs<AppResult.Failed>(
            p.prepare(
                p.f.workspace.id,
                p.preparation,
                p.input,
                reservedClaimId = Uuid.random().toString(),
            )
        )
        assertEquals(1, p.insertions)
    }

    @Test
    fun confirmedCleanupRemovesOnlyTheAcknowledgedInputAndRetainsNewerChanges() = runTest {
        for (newer in listOf(false, true)) {
            val p = ExpenseCommandFixture()
            val record =
                assertIs<AppResult.Success<ExpenseCommandRecord>>(p.save(edited = true)).value
            val slot = ExpenseWorkingSlot.Claim(record.command.claimId)
            val input = if (newer) p.input.copy(title = "Next change") else p.input
            val copy =
                ExpenseWorkingCopy(
                    record.command.claimId,
                    p.f.employee,
                    p.f.access.timezone,
                    input,
                    0,
                    null,
                    Uuid.random().toString(),
                    p.f.clock.now(),
                )
            p.copies.seed(p.f.access, slot, copy)
            p.replay(p.f.workspace.id, record.command.id)
            val snapshot = p.details()
            p.f.seedDetails(
                snapshot.copy(value = snapshot.value.copy(claim = p.f.claim.copy(version = 1)))
            )
            assertEquals(AppResult.Success(true), p.review(p.f.workspace.id, record.command.id))
            assertTrue(p.values.value.isEmpty())
            assertEquals(
                if (newer) listOf(copy) else emptyList(),
                p.copies.values.value.values.toList(),
            )
        }
    }
}

private class WorkingFixture(val f: ExpenseFixture = ExpenseFixture()) {
    val copies = ExpenseWorkingCopyFake()
    val slot = ExpenseWorkingSlot.Creation(f.employee.id)
    val open =
        OpenExpenseWorkingCopy(copies, f.replicas, f.sessions, f.workspaces, f.clock, f.build)
    val save =
        SaveExpenseWorkingCopy(copies, f.replicas, f.sessions, f.workspaces, f.clock, f.build)
    val observe =
        ObserveExpenseWorkingCopy(copies, f.replicas, f.sessions, f.workspaces, f.clock, f.build)
    val discard =
        DiscardExpenseWorkingCopy(copies, f.replicas, f.sessions, f.workspaces, f.clock, f.build)

    suspend fun open() =
        assertIs<AppResult.Success<ExpenseWorkingCopy>>(open(f.workspace.id, f.employee.id, slot))
            .value
}
