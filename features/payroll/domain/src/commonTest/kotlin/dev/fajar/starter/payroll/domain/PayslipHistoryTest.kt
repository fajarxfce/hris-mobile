@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.payroll.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.YearMonth

class PayslipHistoryTest {
    @Test
    fun aCanonicalResponseCannotChangeAnAlreadyPublishedRevision() = runTest {
        val f = PayrollFixture()
        f.publishHead(0)
        val before = f.snapshot()
        f.slips[f.summary.id] = f.slip.copy(companyName = "A different retained issuer")
        f.changesCall = {
            AppResult.Success(
                f.changePage(
                    listOf(
                        SyncChange(
                            SyncReference(SyncCollection.PAYSLIPS, f.summary.id, 0),
                            SyncOperation.UPSERT,
                        )
                    )
                )
            )
        }
        assertEquals("client.invalid_response", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertEquals(before, f.snapshot())
    }

    @Test
    fun aHistoryStorageFailureCannotAdvanceTheFeedOrReplaceTheCurrentStatement() = runTest {
        val f = PayrollFixture()
        f.publishHead(0)
        val before = f.snapshot()
        val checkpoint = f.replicas.current
        f.slips[f.summary.id] = f.slip.copy(summary = f.summary.copy(version = 1))
        f.changesCall = {
            AppResult.Success(
                f.changePage(
                    listOf(
                        SyncChange(
                            SyncReference(SyncCollection.PAYSLIPS, f.summary.id, 1),
                            SyncOperation.UPSERT,
                        )
                    )
                )
            )
        }
        f.revisions.writeResult = payslipFailure("fixture.storage", FailureKind.Storage)
        assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(f.load()).failure.kind)
        assertEquals(before, f.snapshot())
        assertEquals(checkpoint, f.replicas.current)
        assertNull(f.replicas.readAdmission.value.denial)
    }

    @Test
    fun ownershipIsRecheckedAfterTheFinalAdmissionReadBeforeAnyHistoryAcquisition() = runTest {
        val f = PayrollFixture()
        f.publishHead(2)
        var admissionReads = 0
        f.replicas.beforeAdmissionRead = {
            admissionReads++
            if (admissionReads == 2)
                f.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
        }
        val policies = f.policyReads
        assertIs<AppResult.Failed>(f.loadRevision(f.workspace.id, f.summary.id, 0))
        assertEquals(policies, f.policyReads)
        assertTrue(f.revisionReads.isEmpty())
    }

    @Test
    fun missingHistoryKeepsTheKnownHeadThenOpensOfflineWithoutRenewingPaymentsOrTimestamps() =
        runTest {
            var now = Instant.parse("2026-10-13T00:00:00Z")
            val f = PayrollFixture { now }
            f.publishHead(2)
            val stored = f.snapshot()
            val first = f.readRevision(0)!!
            assertEquals(2L, first.currentRevision)
            assertNull(first.snapshot)
            assertEquals(AppResult.Success(Unit), f.loadRevision(f.workspace.id, f.summary.id, 0))
            val acquired = f.readRevision(0)!!
            assertEquals(f.slip, acquired.snapshot!!.value.payslip)
            assertEquals(f.payment, acquired.snapshot!!.value.payments)
            val policyReads = f.policyReads
            now += 90.days
            f.policyCall = { payslipFailure("offline", FailureKind.Network) }
            f.revisionCall = { _, _ -> error("An acquired revision must not use HTTP.") }
            repeat(3) {
                assertEquals(
                    AppResult.Success(Unit),
                    f.loadRevision(f.workspace.id, f.summary.id, 0),
                )
                assertEquals(acquired, f.readRevision(0))
            }
            assertEquals(stored, f.snapshot())
            assertEquals(policyReads, f.policyReads)
            assertEquals(listOf(f.summary.id to 0L), f.revisionReads)
            assertEquals(1, f.detailReads)
            assertEquals(1, f.paymentReads)
        }

    @Test
    fun aNewHeadPreservesThePreUpgradeStatementAndHistoryDoesNotRollBackCurrentPaymentFacts() =
        runTest {
            val f = PayrollFixture()
            f.publishHead(0)
            // Model an installed version that has a current replica but no history partition yet.
            f.revisions.records.value = emptyMap()
            f.payments[f.summary.id] = f.payment.copy(version = 3)
            f.publishHead(2)
            val acquired = f.readRevision(0)!!
            assertEquals(2L, acquired.currentRevision)
            assertEquals(f.slip, acquired.snapshot!!.value.payslip)
            assertEquals(3L, acquired.snapshot!!.value.payments.version)
            f.policyCall = { payslipFailure("offline", FailureKind.Network) }
            assertEquals(AppResult.Success(Unit), f.loadRevision(f.workspace.id, f.summary.id, 0))
            assertTrue(f.revisionReads.isEmpty())
            assertEquals(2L, f.readRevision(null)!!.snapshot!!.value.payslip.summary.version)
        }

    @Test
    fun concurrentRequestsShareAnOwnerAndOnlyTheFirstMissingRevisionUsesTransport() = runTest {
        val f = PayrollFixture()
        f.publishHead(2)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.revisionCall = { _, _ ->
            entered.complete(Unit)
            release.await()
            AppResult.Success(f.slip)
        }
        val first = async { f.loadRevision(f.workspace.id, f.summary.id, 0) }
        entered.await()
        val second = async { f.loadRevision(f.workspace.id, f.summary.id, 0) }
        runCurrent()
        assertEquals(1, f.revisionReads.size)
        release.complete(Unit)
        assertEquals(AppResult.Success(Unit), first.await())
        assertEquals(AppResult.Success(Unit), second.await())
        assertEquals(1, f.revisionReads.size)
        assertEquals(0, f.observers)
    }

    @Test
    fun invalidSelectionsStopBeforeTransportAndMalformedHistoryCannotEnterStorage() = runTest {
        val f = PayrollFixture()
        assertIs<AppResult.Failed>(f.loadRevision(f.workspace.id, "../payslips", 0))
        assertIs<AppResult.Failed>(f.loadRevision(f.workspace.id, f.summary.id, -1))
        assertIs<AppResult.Failed>(f.observeDetail(f.workspace.id, f.summary.id, -1).first())
        assertEquals(0, f.policyReads + f.observers)
        f.publishHead(2)
        assertIs<AppResult.Failed>(f.loadRevision(f.workspace.id, f.summary.id, 3))
        assertTrue(f.revisionReads.isEmpty())
        val writes = f.revisions.writes
        val invalid =
            listOf(
                f.summary.copy(id = "55555555-5555-5555-5555-555555555555"),
                f.summary.copy(employeeId = "55555555-5555-5555-5555-555555555555"),
                f.summary.copy(month = YearMonth(2026, 8)),
                f.summary.copy(currency = "USD"),
                f.summary.copy(version = 1),
            )
        for (summary in invalid) {
            f.revisionCall = { _, _ -> AppResult.Success(f.slip.copy(summary = summary)) }
            assertEquals(
                "client.invalid_response",
                assertIs<AppResult.Failed>(f.loadRevision(f.workspace.id, f.summary.id, 0))
                    .failure
                    .code,
            )
        }
        assertEquals(writes, f.revisions.writes)
        assertNull(f.readRevision(0)!!.snapshot)
    }

    @Test
    fun cancellationAndOwnerChangesRejectLateSuccessAndFailureWithoutDenialOrPublication() =
        runTest {
            for (cancel in listOf(false, true)) for (fails in listOf(false, true)) {
                val f = PayrollFixture()
                f.publishHead(2)
                val stored = f.snapshot()
                val writes = f.revisions.writes
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                f.revisionCall = { _, _ ->
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    if (fails) payslipFailure("mfa_required", FailureKind.AccessDenied)
                    else AppResult.Success(f.slip)
                }
                val request = async { f.loadRevision(f.workspace.id, f.summary.id, 0) }
                entered.await()
                if (cancel) request.cancel()
                else
                    f.workspaceState.value =
                        AppResult.Success(f.workspace.copy(id = "another-selection"))
                release.complete(Unit)
                if (cancel) assertFailsWith<CancellationException> { request.await() }
                else
                    assertNotEquals(
                        "mfa_required",
                        assertIs<AppResult.Failed>(request.await()).failure.code,
                    )
                assertEquals(stored, f.snapshot())
                assertEquals(writes, f.revisions.writes)
                assertEquals(0, f.replicas.clears)
                assertNull(f.replicas.readAdmission.value.denial)
                assertEquals(0, f.observers)
            }
        }

    @Test
    fun cancellationWhileWaitingForStorageDoesNotRetainTheRevision() = runTest {
        val f = PayrollFixture()
        f.publishHead(2)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.revisions.beforeWrite = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        val request = async { f.loadRevision(f.workspace.id, f.summary.id, 0) }
        entered.await()
        request.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { request.await() }
        assertNull(f.readRevision(0)!!.snapshot)
    }

    @Test
    fun aConfirmedDenialSurvivesFailedCleanupAndRetryUntilFullAdmittedSynchronization() = runTest {
        val f = PayrollFixture()
        f.publishHead(2)
        f.replicas.failCleanup = true
        f.replicas.failAdmissionWrite = true
        f.revisionCall = { _, _ -> payslipFailure("mfa_required", FailureKind.AccessDenied) }
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.loadRevision(f.workspace.id, f.summary.id, 0)).failure.code,
        )
        assertNotNull(f.snapshot())
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.observeDetail(f.workspace.id, f.summary.id, 2).first())
                .failure
                .code,
        )
        val reads = f.revisionReads.size
        f.revisionCall = { _, _ -> AppResult.Success(f.slip) }
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.loadRevision(f.workspace.id, f.summary.id, 0)).failure.code,
        )
        assertEquals(reads, f.revisionReads.size)
        f.policyCall = { payslipFailure("offline", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.load())
        assertNotNull(f.replicas.readAdmission.value.denial)
        f.policyCall = { AppResult.Success(f.policy) }
        f.replicas.failAdmissionWrite = false
        assertTrue(assertIs<AppResult.Success<PayrollSyncOutcome>>(f.load()).value.caughtUp)
        assertEquals(AppResult.Success(Unit), f.loadRevision(f.workspace.id, f.summary.id, 0))
        assertNotNull(f.readRevision(0)!!.snapshot)
    }

    @Test
    fun aDenialArrivingDuringAHistoryWriteCannotBeClearedByItsSuccess() = runTest {
        val f = PayrollFixture()
        f.publishHead(2)
        f.revisions.beforeWrite = {
            f.replicas.restrict(
                f.access,
                payslipFailure("mfa_required", FailureKind.AccessDenied).failure,
            )
        }
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.loadRevision(f.workspace.id, f.summary.id, 0)).failure.code,
        )
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.observeDetail(f.workspace.id, f.summary.id, 0).first())
                .failure
                .code,
        )
        assertNotNull(f.replicas.readAdmission.value.denial)
    }

    @Test
    fun deletingTheCurrentParentHidesRetainedHistoryAndDoesNotStartAnotherRead() = runTest {
        val f = PayrollFixture()
        f.publishHead(2)
        f.loadRevision(f.workspace.id, f.summary.id, 0)
        val seen = mutableListOf<AppResult<PayslipRevisionRead?>>()
        val observer =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observeDetail(f.workspace.id, f.summary.id, 0).collect { seen += it }
            }
        val previous = f.replicas.current!!
        f.replicas.commit(
            previous,
            previous.copy(revision = previous.revision + 1),
            listOf(
                PayrollReplicaEntry(SyncReference(SyncCollection.PAYSLIPS, f.summary.id, 2), null)
            ),
        )
        runCurrent()
        assertEquals(AppResult.Success(null), seen.last())
        assertTrue(f.revisions.records.value.isNotEmpty())
        val reads = f.revisionReads.size
        assertEquals(
            "client.payslip_revision_not_saved",
            assertIs<AppResult.Failed>(f.loadRevision(f.workspace.id, f.summary.id, 0)).failure.code,
        )
        assertEquals(reads, f.revisionReads.size)
        observer.cancelAndJoin()
        assertEquals(0, f.observers)
    }

    @Test
    fun backgroundHeadAndPaymentChangesKeepTheHistoricalStatementSelected() = runTest {
        val f = PayrollFixture()
        f.publishHead(2)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.revisionCall = { _, _ ->
            entered.complete(Unit)
            release.await()
            AppResult.Success(f.slip)
        }
        val pending = async { f.loadRevision(f.workspace.id, f.summary.id, 0) }
        entered.await()
        f.payments[f.summary.id] = f.payment.copy(version = 7)
        f.publishHead(4)
        release.complete(Unit)
        assertEquals(AppResult.Success(Unit), pending.await())
        val value = f.readRevision(0)!!
        assertEquals(4L, value.currentRevision)
        assertEquals(0L, value.snapshot!!.value.payslip.summary.version)
        assertEquals(7L, value.snapshot!!.value.payments.version)
    }

    @Test
    fun historyIsOwnedByTheAccountAcrossReauthenticationAndStillRequiresTheCurrentCompany() =
        runTest {
            val f = PayrollFixture()
            f.publishHead(2)
            f.loadRevision(f.workspace.id, f.summary.id, 0)
            val session =
                f.session.copy(
                    id = "renewed",
                    binding = f.session.binding.copy(expiresAtEpochMillis = 0),
                )
            f.sessionState.value = AppResult.Success(session)
            f.workspaceState.value = AppResult.Success(f.workspace.copy(sessionId = session.id))
            assertEquals(AppResult.Success(Unit), f.loadRevision(f.workspace.id, f.summary.id, 0))
            assertEquals(session.id, f.readRevision(0)!!.snapshot!!.access.sessionId)
            val reads = f.revisionReads.size
            f.workspaceState.value =
                AppResult.Success(f.workspace.copy(id = "other", sessionId = session.id))
            assertIs<AppResult.Failed>(f.loadRevision(f.workspace.id, f.summary.id, 0))
            assertIs<AppResult.Failed>(f.observeDetail(f.workspace.id, f.summary.id, 0).first())
            assertEquals(reads, f.revisionReads.size)
        }

    @Test
    fun theDeadlineIncludesLockAndStorageWaitsAndLeavesNoSubscriptionBehind() = runTest {
        val start = Instant.parse("2026-10-13T00:00:00Z")
        val f = PayrollFixture { start + testScheduler.currentTime.milliseconds }
        f.publishHead(2)
        val entered = CompletableDeferred<Unit>()
        f.revisionCall = { _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        }
        val first = async { f.loadRevision(f.workspace.id, f.summary.id, 0) }
        entered.await()
        val second = async { f.loadRevision(f.workspace.id, f.summary.id, 0) }
        for (result in listOf(first.await(), second.await())) {
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(result).failure.kind)
        }
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(0, f.observers)
        assertNull(f.readRevision(0)!!.snapshot)
    }
}

private suspend fun PayrollFixture.publishHead(version: Long) {
    slips[summary.id] = slip.copy(summary = summary.copy(version = version))
    if (replicas.current != null)
        changesCall = {
            AppResult.Success(
                changePage(
                    listOf(
                        SyncChange(
                            SyncReference(SyncCollection.PAYSLIPS, summary.id, version),
                            SyncOperation.UPSERT,
                        )
                    )
                )
            )
        }
    assertTrue(assertIs<AppResult.Success<PayrollSyncOutcome>>(load()).value.caughtUp)
}

private suspend fun PayrollFixture.readRevision(revision: Long?) =
    assertIs<AppResult.Success<PayslipRevisionRead?>>(
            observeDetail(workspace.id, summary.id, revision).first()
        )
        .value
