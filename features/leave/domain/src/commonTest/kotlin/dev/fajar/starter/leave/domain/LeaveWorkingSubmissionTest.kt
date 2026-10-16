@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.uuid.ExperimentalUuidApi::class,
)

package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.working.entities.*
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class LeaveWorkingSubmissionTest {
    @Test
    fun submissionRetainsInputUntilCanonicalConfirmationAndDraftCleanup() = runTest {
        val f = LeaveSubmissionFixture()
        val copy = f.reviewedInput()
        f.working.stored = copy
        val queued =
            assertIs<AppResult.Success<LeaveSubmissionRecord>>(
                    f.prepare(
                        f.base.workspace.id,
                        f.prepared,
                        copy.input.reason,
                        workingRevision = copy.revision,
                    )
                )
                .value
        assertEquals(copy.revision, queued.command.workingRevision)
        assertEquals(copy, f.working.stored)
        f.send()
        assertEquals(copy, f.working.stored)
        f.publish()
        f.working.removeFailure = Failure(FailureKind.Storage, "Fixture save failure")
        assertIs<AppResult.Failed>(f.clear())
        assertEquals(queued.command, f.records.value.single().command)
        assertEquals(copy, f.working.stored)
        f.working.removeFailure = null
        assertEquals(AppResult.Success(true), f.clear())
        assertNull(f.working.stored)
        assertTrue(f.records.value.isEmpty())
        assertEquals(1, f.transmitted.size)
    }

    @Test
    fun anOldReceiptNeverDeletesANewerEdit() = runTest {
        val f = LeaveSubmissionFixture()
        val copy = f.reviewedInput()
        f.working.stored = copy
        f.prepare(
            f.base.workspace.id,
            f.prepared,
            copy.input.reason,
            workingRevision = copy.revision,
        )
        f.send()
        f.publish()
        val newer =
            copy.copy(
                revision = Uuid.random().toString(),
                input = copy.input.copy(reason = "Next request"),
            )
        f.working.stored = newer
        assertEquals(AppResult.Success(true), f.clear())
        assertEquals(newer, f.working.stored)
    }

    @Test
    fun unconfirmedOrRejectedDeliveryDoesNotDiscardWorkingInput() = runTest {
        for (kind in listOf(FailureKind.Network, FailureKind.Conflict)) {
            val f = LeaveSubmissionFixture()
            val copy = f.reviewedInput()
            f.working.stored = copy
            f.prepare(
                f.base.workspace.id,
                f.prepared,
                copy.input.reason,
                workingRevision = copy.revision,
            )
            f.transmit = {
                AppResult.Failed(
                    Failure(
                        kind,
                        "Fixture delivery failure",
                        code = if (kind == FailureKind.Conflict) "leave_preview_changed" else null,
                    )
                )
            }
            f.send()
            if (kind == FailureKind.Network) assertIs<AppResult.Failed>(f.clear())
            else assertEquals(AppResult.Success(true), f.clear())
            assertEquals(copy, f.working.stored)
            assertEquals(0, f.working.removals)
        }
    }

    @Test
    fun changedInputOrRevisionCannotBecomeTheReviewedCommand() = runTest {
        val f = LeaveSubmissionFixture()
        val copy = f.reviewedInput()
        for (candidate in
            listOf(
                copy.copy(revision = Uuid.random().toString()),
                copy.copy(input = copy.input.copy(reason = "Other input")),
                copy.copy(input = copy.input.copy(portion = LeavePortion.FULL)),
                copy.copy(input = copy.input.copy(type = null)),
            )) {
            f.working.stored = candidate
            val result =
                f.prepare(
                    f.base.workspace.id,
                    f.prepared,
                    copy.input.reason,
                    workingRevision = copy.revision,
                )
            assertEquals(
                "client.leave_working_copy_changed",
                assertIs<AppResult.Failed>(result).failure.code,
            )
        }
        assertEquals(0, f.inserts)
    }

    @Test
    fun aPendingDraftReadCannotPublishAfterTheCompanyChanges() = runTest {
        val f = LeaveSubmissionFixture()
        val copy = f.reviewedInput()
        f.working.stored = copy
        val release = CompletableDeferred<Unit>()
        f.working.beforeRead = { release.await() }
        val pending = async {
            f.prepare(
                f.base.workspace.id,
                f.prepared,
                copy.input.reason,
                workingRevision = copy.revision,
            )
        }
        runCurrent()
        f.base.workspaceState.value = AppResult.Success(f.base.workspace.copy(id = "other"))
        release.complete(Unit)
        assertIs<AppResult.Failed>(pending.await())
        assertEquals(0, f.inserts)
    }

    @Test
    fun lostCommandCleanupAcknowledgementDoesNotRecreateSubmittedInput() = runTest {
        val f = LeaveSubmissionFixture()
        val copy = f.reviewedInput()
        f.working.stored = copy
        f.prepare(
            f.base.workspace.id,
            f.prepared,
            copy.input.reason,
            workingRevision = copy.revision,
        )
        f.send()
        f.publish()
        f.beforeRemove = {
            throw CancellationException("Fixture termination before command removal")
        }
        assertFailsWith<CancellationException> { f.clear() }
        assertNull(f.working.stored)
        assertEquals(1, f.records.value.size)
        f.beforeRemove = {}
        assertEquals(AppResult.Success(true), f.clear())
        assertEquals(1, f.transmitted.size)
    }
}

private fun LeaveSubmissionFixture.reviewedInput(): LeaveWorkingCopy {
    val day = prepared.selection.days.single()
    return LeaveWorkingCopy(
        base.employee.id,
        LeaveWorkingInput(day.date, day.date, preparation.type, day.portion, "Personal leave"),
        Uuid.random().toString(),
        base.clock.now(),
    )
}
