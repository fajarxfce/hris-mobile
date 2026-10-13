package dev.fajar.starter.payroll.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class PayslipExportTest {
    @Test
    fun explicitExportReauthorizesAfterSelectionAndBeforeCompletion() = runTest {
        val f = PayslipExportFixture()
        val expected = f.bytes.copyOf()
        assertEquals(AppResult.Success(true), f.save())
        assertEquals(2, f.inspections)
        assertEquals(3, f.base.policyReads)
        assertEquals(1, f.downloads)
        assertEquals(1, f.writes)
        assertEquals(1, f.completions)
        assertEquals(1, f.releases)
        assertContentEquals(expected, f.written)
        assertTrue(f.bytes.all { it == 0.toByte() })
        assertEquals("application/pdf", f.declaration?.mediaType)
        assertEquals(expected.size.toLong(), f.declaration?.size)
        assertFalse(f.declaration!!.name.contains(f.base.summary.employeeName))
    }

    @Test
    fun aDeniedReadDoesNotOpenThePickerAndPickerCancellationDoesNotDownload() = runTest {
        val denied = PayslipExportFixture()
        denied.inspectCall = { payslipFailure("mfa_required", FailureKind.AccessDenied) }
        assertIs<AppResult.Failed>(denied.save())
        assertEquals(0, denied.selections + denied.downloads + denied.writes)
        val cancelled = PayslipExportFixture()
        cancelled.selectCall = { AppResult.Success(false) }
        assertEquals(AppResult.Success(false), cancelled.save())
        assertEquals(0, cancelled.downloads + cancelled.writes + cancelled.completions)
        assertEquals(1, cancelled.releases)
    }

    @Test
    fun cancellationWhileThePickerIsPendingReleasesItsOwner() = runTest {
        val f = PayslipExportFixture()
        val entered = CompletableDeferred<Unit>()
        f.selectCall = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val request = async { f.save() }
        entered.await()
        request.cancelAndJoin()
        assertEquals(1, f.releases)
        assertEquals(0, f.downloads + f.writes + f.completions)
    }

    @Test
    fun companyChangeDuringTheSaveDialogCannotDiscloseTheOldSlip() = runTest {
        val f = PayslipExportFixture()
        f.selectCall = {
            f.base.workspaceState.value =
                AppResult.Success(f.base.workspace.copy(id = "replacement"))
            AppResult.Success(true)
        }
        assertIs<AppResult.Failed>(f.save())
        assertEquals(0, f.downloads + f.writes + f.completions)
        assertEquals(1, f.releases)
    }

    @Test
    fun lateDownloadIsClearedAfterCancellationOrRevocationBeforeAnyWrite() = runTest {
        for (cancel in listOf(false, true)) {
            val f = PayslipExportFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.downloadCall = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                AppResult.Success(f.bytes)
            }
            val request = async { f.save() }
            entered.await()
            if (cancel) request.cancel() else f.base.sessionState.value = AppResult.Success(null)
            release.complete(Unit)
            if (cancel) assertFailsWith<CancellationException> { request.await() }
            else assertIs<AppResult.Failed>(request.await())
            assertTrue(f.bytes.all { it == 0.toByte() })
            assertEquals(0, f.writes + f.completions)
            assertEquals(1, f.releases)
        }
    }

    @Test
    fun failedWriteAndChangedServerMetadataCannotReportCompletedExport() = runTest {
        for (failedWrite in listOf(false, true)) {
            val f = PayslipExportFixture()
            if (failedWrite)
                f.writeCall = { payslipFailure("fixture.storage", FailureKind.Storage) }
            else
                f.inspectCall = {
                    AppResult.Success(
                        if (f.inspections == 1) f.metadata
                        else f.metadata.copy(entityTag = "\"${"b".repeat(64)}\"")
                    )
                }
            assertIs<AppResult.Failed>(f.save())
            assertEquals(1, f.writes)
            assertEquals(0, f.completions)
            assertEquals(1, f.releases)
            assertTrue(f.bytes.all { it == 0.toByte() })
        }
    }

    @Test
    fun exportDenialIsNotReplacedBySlowCleanupOrAFutureSuccess() = runTest {
        val f = PayslipExportFixture()
        val denial = payslipFailure("mfa_required", FailureKind.AccessDenied)
        f.inspectCall = { if (f.inspections == 1) AppResult.Success(f.metadata) else denial }
        f.releaseCall = { awaitCancellation() }
        assertEquals(denial, f.save())
        assertEquals(0, f.completions)
        assertTrue(f.bytes.all { it == 0.toByte() })
    }

    @Test
    fun saveDialogAndDownloadHaveSeparateBoundedLifetimes() = runTest {
        for (picker in listOf(false, true)) {
            val f = PayslipExportFixture()
            if (picker) f.selectCall = { awaitCancellation() }
            else f.downloadCall = { awaitCancellation() }
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.save()).failure.kind)
            assertEquals(1, f.releases)
            assertEquals(0, f.writes + f.completions)
        }
    }
}
