@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.policies.DOCUMENT_MAXIMUM_BYTES
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class ExpenseReceiptExportTest {
    @Test
    fun ownedReceiptExportNeedsExpenseReadAccessWithoutTheDocumentsModuleOrCatalog() = runTest {
        val f = ExpenseReceiptExportFixture()
        val modules = f.base.policy.enabledModules - CompanyModule.DOCUMENTS
        val workspace = f.base.workspace.copy(permissions = setOf("expenses.read"))
        f.base.workspaceState.value = AppResult.Success(workspace)
        f.base.policyCall = { AppResult.Success(f.base.policy.copy(enabledModules = modules)) }
        f.snapshot =
            f.snapshot.copy(access = f.snapshot.access.copy(permissions = workspace.permissions))
        assertEquals(AppResult.Success(true), f.save())
        assertEquals(3, f.inspections)
        assertEquals(3, f.base.policyReads)
        assertEquals(1, f.selections)
        assertEquals(1, f.streams)
        assertEquals(1, f.completions)
        assertEquals(1, f.releases)
        assertEquals(listOf(0L, EXPENSE_RECEIPT_CHUNK_BYTES.toLong()), f.written.map { it.first })
        assertTrue(f.written[0].second.all { it == 42.toByte() })
        assertTrue(f.written[1].second.all { it == 43.toByte() })
        assertTrue(f.acquired.all { bytes -> bytes.all { it == 0.toByte() } })
        assertEquals(f.receipt.fileName, f.declaration?.name)
        assertEquals(f.receipt.size, f.declaration?.size)
    }

    @Test
    fun unknownReceiptsForeignSubmissionsAndExpiredViewsCannotSelectADestination() = runTest {
        for (case in 0..6) {
            val f = ExpenseReceiptExportFixture()
            val view = f.snapshot.value
            f.snapshot =
                when (case) {
                    0 -> f.snapshot.copy(value = view.copy(submission = null))
                    1 ->
                        f.snapshot.copy(
                            value =
                                view.copy(submission = f.submission.copy(claimId = f.receipt.id))
                        )
                    2 ->
                        f.snapshot.copy(
                            value =
                                view.copy(submission = f.submission.copy(employeeId = f.receipt.id))
                        )
                    3 ->
                        f.snapshot.copy(
                            value = view.copy(submission = f.submission.copy(claimVersion = 2))
                        )
                    4 -> f.snapshot.copy(access = f.snapshot.access.copy(companyId = f.receipt.id))
                    5 -> f.snapshot.copy(expiresAt = f.base.clock.now() - 1.seconds)
                    else -> f.snapshot
                }
            assertIs<AppResult.Failed>(f.save(if (case == 6) "unknown" else f.receipt.id))
            assertEquals(0, f.selections + f.inspections + f.streams)
        }
    }

    @Test
    fun receiptDeclarationsMatchServerSizeBoundsAndRejectUnsafeMetadata() {
        val receipt = ExpenseReceiptExportFixture().receipt
        assertIs<AppResult.Success<Unit>>(
            validateExpenseReceipt(receipt.copy(size = DOCUMENT_MAXIMUM_BYTES))
        )
        for (invalid in
            listOf(
                receipt.copy(size = 0),
                receipt.copy(size = DOCUMENT_MAXIMUM_BYTES + 1),
                receipt.copy(fileName = "../receipt.pdf"),
                receipt.copy(fileName = "receipt.exe"),
                receipt.copy(mediaType = "text/html"),
                receipt.copy(sha256 = "A".repeat(64)),
            )) assertIs<AppResult.Failed>(validateExpenseReceipt(invalid))
    }

    @Test
    fun conflictingDeclarationsForTheSameRevisionAreRejected() = runTest {
        val f = ExpenseReceiptExportFixture()
        val line = f.submission.lines.single()
        val conflicting = line.copy(receipts = listOf(f.receipt.copy(sha256 = "b".repeat(64))))
        val submission = f.submission.copy(lines = listOf(line, conflicting))
        f.snapshot = f.snapshot.copy(value = f.snapshot.value.copy(submission = submission))
        assertIs<AppResult.Failed>(f.save())
        assertEquals(0, f.selections + f.streams)
    }

    @Test
    fun denialBeforeSelectionAndExplicitPickerCancellationNeverAcquireContent() = runTest {
        val denied = ExpenseReceiptExportFixture()
        val failure = expenseFailure("mfa_required", FailureKind.AccessDenied)
        denied.inspectCall = { failure }
        assertEquals(failure, denied.save())
        assertEquals(0, denied.selections + denied.streams)
        val cancelled = ExpenseReceiptExportFixture()
        cancelled.selectCall = { AppResult.Success(false) }
        assertEquals(AppResult.Success(false), cancelled.save())
        assertEquals(0, cancelled.streams + cancelled.completions)
        assertEquals(1, cancelled.releases)
    }

    @Test
    fun replacedWorkspaceOrRevokedSessionDuringSelectionCannotDiscloseContent() = runTest {
        for (replaced in listOf(false, true)) {
            val f = ExpenseReceiptExportFixture()
            f.selectCall = {
                if (replaced)
                    f.base.workspaceState.value =
                        AppResult.Success(f.base.workspace.copy(id = "replacement"))
                else f.base.sessionState.value = AppResult.Success(null)
                AppResult.Success(true)
            }
            assertIs<AppResult.Failed>(f.save())
            assertEquals(0, f.streams + f.completions)
            assertEquals(1, f.releases)
        }
    }

    @Test
    fun resourceAuthorityIsCheckedAgainAfterTheSaveDialogAndBeforeCompletion() = runTest {
        for (deniedInspection in listOf(2, 3)) {
            val f = ExpenseReceiptExportFixture()
            val denied = expenseFailure("mfa_required", FailureKind.AccessDenied)
            f.inspectCall = {
                if (f.inspections == deniedInspection) denied else AppResult.Success(Unit)
            }
            assertEquals(denied, f.save())
            assertEquals(if (deniedInspection == 2) 0 else 2, f.written.size)
            assertEquals(0, f.completions)
            assertEquals(1, f.releases)
        }
    }

    @Test
    fun orderedExactChunksAreRequiredAndAnIncompleteStreamCannotCommit() = runTest {
        for (case in 0..4) {
            val f = ExpenseReceiptExportFixture()
            val bytes = ByteArray(if (case == 2) 1 else EXPENSE_RECEIPT_CHUNK_BYTES) { 42 }
            f.parts = {
                flow {
                    if (case != 4)
                        emit(AppResult.Success(ExpenseReceiptChunk(if (case == 1) 1 else 0, bytes)))
                    if (case == 3) emit(AppResult.Success(ExpenseReceiptChunk(0, bytes)))
                }
            }
            assertIs<AppResult.Failed>(f.save())
            assertEquals(0, f.completions)
            assertEquals(1, f.releases)
            if (case != 4) assertTrue(bytes.all { it == 0.toByte() })
        }
    }

    @Test
    fun failedStreamAndFailedWriteKeepTheirClassificationDespiteCleanupFailure() = runTest {
        for (source in listOf(false, true)) {
            val f = ExpenseReceiptExportFixture()
            val failed =
                expenseFailure(
                    "fixture.read",
                    if (source) FailureKind.Network else FailureKind.Storage,
                )
            if (source) f.parts = { flowOf(failed) } else f.writeCall = { failed }
            f.releaseCall = { expenseFailure("fixture.cleanup", FailureKind.Storage) }
            assertEquals(failed, f.save())
            assertEquals(0, f.completions)
            assertEquals(1, f.releases)
            assertTrue(f.acquired.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    @Test
    fun latePolicyAndStreamFailuresCannotReplaceARevokedSession() = runTest {
        for (policy in listOf(false, true)) {
            val f = ExpenseReceiptExportFixture()
            val failed = expenseFailure("fixture.network", FailureKind.Network)
            if (policy)
                f.base.policyCall = {
                    f.base.sessionState.value = AppResult.Success(null)
                    failed
                }
            else
                f.parts = {
                    flow {
                        f.base.sessionState.value = AppResult.Success(null)
                        emit(failed)
                    }
                }
            assertEquals("session_revoked", assertIs<AppResult.Failed>(f.save()).failure.code)
            assertEquals(0, f.written.size + f.completions)
        }
    }

    @Test
    fun revocationDuringWritePreventsTheNextReadAndFinalCompletion() = runTest {
        val f = ExpenseReceiptExportFixture()
        f.writeCall = {
            f.base.sessionState.value = AppResult.Success(null)
            expenseFailure("fixture.write", FailureKind.Storage)
        }
        assertEquals("session_revoked", assertIs<AppResult.Failed>(f.save()).failure.code)
        assertEquals(1, f.written.size)
        assertEquals(1, f.acquired.size)
        assertEquals(0, f.completions)
        assertTrue(f.acquired.single().all { it == 0.toByte() })
    }

    @Test
    fun cancellationDuringSelectionOrWritingReleasesTheDestinationAndCurrentBuffer() = runTest {
        for (picker in listOf(false, true)) {
            val f = ExpenseReceiptExportFixture()
            val entered = CompletableDeferred<Unit>()
            if (picker)
                f.selectCall = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            else
                f.writeCall = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            val task = async { f.save() }
            entered.await()
            task.cancelAndJoin()
            assertEquals(1, f.releases)
            assertEquals(0, f.completions)
            assertTrue(f.acquired.all { bytes -> bytes.all { it == 0.toByte() } })
        }
    }

    @Test
    fun selectionContentAndCleanupHaveBoundedLifetimes() = runTest {
        for (picker in listOf(false, true)) {
            val f = ExpenseReceiptExportFixture()
            if (picker) f.selectCall = { awaitCancellation() }
            else f.parts = { flow { awaitCancellation() } }
            f.releaseCall = { awaitCancellation() }
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.save()).failure.kind)
            assertEquals(1, f.releases)
            assertEquals(0, f.completions)
        }
    }
}
