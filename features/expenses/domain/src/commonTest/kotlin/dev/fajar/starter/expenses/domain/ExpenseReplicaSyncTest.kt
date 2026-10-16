@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.expenses.domain.replica.policies.initialExpenseCheckpoint
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.YearMonth

class ExpenseReplicaSyncTest {
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publishedStatusRetainsPreviousCompletionWhileStagingAndClosesOnDenial() = runTest {
        val f = ExpenseFixture()
        val observation: ObserveCompanyData = f.observe
        val acquisition: SyncCompanyData = f.sync
        val workspaceId = f.workspace.id
        assertEquals(CompanyDataSet.Expenses, observation.dataSet)
        assertEquals(observation.dataSet, acquisition.dataSet)
        val values = mutableListOf<AppResult<CompanyDataStatus>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observation.observeStatus(workspaceId).collect { values += it }
            }
        runCurrent()
        assertEquals(AppResult.Success(CompanyDataStatus(null)), values.last())
        assertTrue(
            assertIs<AppResult.Success<CompanyDataSyncOutcome>>(acquisition.refresh(workspaceId))
                .value
                .caughtUp
        )
        runCurrent()
        val published = requireNotNull(f.replicas.current)
        val expected = AppResult.Success(CompanyDataStatus(assertNotNull(published.refreshedAt)))
        assertEquals(expected, values.last())
        val replacement =
            initialExpenseCheckpoint(published.access, "status-replacement", published.refreshedAt)
        assertEquals(AppResult.Success(true), f.replicas.begin(replacement, published.generation))
        runCurrent()
        assertEquals(expected, values.last())
        val denial =
            Failure(FailureKind.AccessDenied, "Verification required", code = "mfa_required")
        f.replicas.restrict(published.access, denial)
        runCurrent()
        assertEquals(AppResult.Failed(denial), values.last())
        assertEquals(AppResult.Failed(denial), observation.observeStatus(workspaceId).first())
        assertEquals(0, f.replicas.published.subscriptionCount.value)
        reader.cancelAndJoin()
        assertEquals(0, f.replicas.published.subscriptionCount.value)
    }

    @Test
    fun anApprovalInvalidationAtTheSameClaimVersionReplacesTheRecordedApproval() = runTest {
        val f = ExpenseFixture()
        val submission = ExpenseReceiptExportFixture(f)
        val record =
            ExpenseClaimRecord(
                submission.claim,
                submission.submission,
                ExpensePage(listOf(f.change.copy(version = 1, status = ExpenseStatus.PENDING))),
            )
        f.recordCall = { _, _ -> AppResult.Success(record) }
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val before = details(f)
        val revised =
            record.copy(
                submission =
                    record.submission!!.copy(
                        approval =
                            record.submission.approval.copy(
                                version = 1,
                                stages = listOf(setOf(f.employee.id)),
                            )
                    )
            )
        f.recordCall = { _, _ -> AppResult.Success(revised) }
        f.changesCall = { AppResult.Success(page(f, SyncCollection.EXPENSE_CLAIMS, version = 1)) }
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val after = details(f)
        assertEquals(before.value.claim.version, after.value.claim.version)
        assertEquals(1, after.value.submission?.approval?.version)
        assertNotEquals(before.generation, after.generation)
        assertEquals(2, f.recordReads)
        assertEquals(
            setOf(SyncCollection.EXPENSE_CLAIMS, SyncCollection.EXPENSE_PAYMENTS),
            f.requestedCollections.singleDistinct(),
        )
        assertTrue(f.feedLimits.all { it == 2 })
    }

    @Test
    fun aPaymentInvalidationAcquiresAMissingApprovedHeadButNeverRenewsAnUnchangedOne() = runTest {
        var now = kotlin.time.Instant.parse("2026-10-13T00:00:00Z")
        val f = ExpenseFixture { now }
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val pending = ExpenseReceiptExportFixture(f)
        val approved =
            ExpenseClaimRecord(
                pending.claim.copy(version = 2, status = ExpenseStatus.APPROVED),
                pending.submission.copy(
                    claimVersion = 2,
                    claimStatus = ExpenseStatus.APPROVED,
                    approval =
                        pending.submission.approval.copy(
                            version = 1,
                            status = ExpenseApprovalStatus.APPROVED,
                        ),
                ),
                ExpensePage(listOf(f.change.copy(version = 2, status = ExpenseStatus.APPROVED))),
            )
        f.recordCall = { _, _ -> AppResult.Success(approved) }
        val payment =
            ExpensePaymentAttempt(
                f.employee.id,
                f.line.id,
                ExpensePaymentStatus.PENDING,
                now,
                now,
                null,
            )
        f.paymentCall = { AppResult.Success(ExpenseClaimPayments(f.claim.id, 1, listOf(payment))) }
        f.changesCall = { AppResult.Success(page(f, SyncCollection.EXPENSE_PAYMENTS, 1)) }
        now += 10.seconds
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val first = details(f)
        assertEquals(ExpenseStatus.APPROVED, first.value.claim.status)
        assertEquals(2, f.recordReads)
        f.paymentCall = {
            AppResult.Success(
                ExpenseClaimPayments(
                    f.claim.id,
                    2,
                    listOf(payment.copy(status = ExpensePaymentStatus.SUCCEEDED, settledAt = now)),
                )
            )
        }
        f.changesCall = { AppResult.Success(page(f, SyncCollection.EXPENSE_PAYMENTS, 2)) }
        now += 10.seconds
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val after = details(f)
        assertEquals(2, f.recordReads)
        assertEquals(first.fetchedAt, after.fetchedAt)
        assertEquals(first.refreshAfter, after.refreshAfter)
        assertEquals(first.generation, after.generation)
        assertEquals(ExpensePaymentStatus.SUCCEEDED, after.value.payments.single().status)
    }

    @Test
    fun failedHydrationDoesNotPublishPartialClaimsOrAdvanceTheCursor() = runTest {
        val f = ExpenseFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val checkpoint = f.replicas.current
        val before = f.replicas.published.value
        f.changesCall = { AppResult.Success(page(f, SyncCollection.EXPENSE_CLAIMS, 0)) }
        f.paymentCall = { expenseFailure("client.network", FailureKind.Network) }
        assertEquals(
            "client.network",
            assertIs<AppResult.Failed>(f.sync(f.workspace.id)).failure.code,
        )
        assertEquals(checkpoint, f.replicas.current)
        assertEquals(before, f.replicas.published.value)
    }

    @Test
    fun cursorRetentionRebuildsWithoutHidingOwnedFactsWhenTheReplacementFails() = runTest {
        val f = ExpenseFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val saved = f.observe(f.workspace.id).first()
        val bootstrap = f.bootstrapCall
        f.changesCall = { expenseFailure("sync_cursor_expired", FailureKind.Conflict) }
        f.bootstrapCall = { _, _ -> expenseFailure("client.network", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.sync(f.workspace.id))
        assertEquals(saved, f.observe(f.workspace.id).first())
        assertNull(f.replicas.readAdmission.value.denial)
        f.bootstrapCall = bootstrap
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(emptyList(), "replacement", false, false, 5, f.clock.now())
            )
        }
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        assertNotEquals(
            (saved as AppResult.Success).value?.generation,
            (f.observe(f.workspace.id).first() as AppResult.Success).value?.generation,
        )
    }

    @Test
    fun confirmedDenialSurvivesLocalClaimAcquisitionAndFailedCleanupUntilFullAdmission() = runTest {
        val f = ExpenseFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val denial = expenseFailure("mfa_required", FailureKind.AccessDenied)
        f.replicas.failCleanup = true
        f.replicas.failAdmissionWrite = true
        f.directoryCall = { denial }
        assertEquals(denial, f.sync(f.workspace.id))
        assertEquals(denial, f.observe(f.workspace.id).first())
        val calls = f.recordReads
        assertEquals(denial, f.refreshDetail(f.workspace.id, f.claim.id))
        assertEquals(calls, f.recordReads)
        f.directoryCall = { AppResult.Success(f.directory) }
        assertIs<AppResult.Failed>(f.sync(f.workspace.id))
        assertEquals(denial, f.observe(f.workspace.id).first())
        f.replicas.failAdmissionWrite = false
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        assertNotNull((f.observe(f.workspace.id).first() as AppResult.Success).value)
    }

    @Test
    fun aDenialArrivingDuringAdmissionCannotBeClearedByThatEarlierAcquisition() = runTest {
        val f = ExpenseFixture()
        f.replicas.beforeAdmit = {
            f.replicas.restrict(
                f.access,
                expenseFailure("mfa_required", FailureKind.AccessDenied).failure,
            )
        }
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.sync(f.workspace.id)).failure.code,
        )
        assertEquals(
            "mfa_required",
            assertIs<AppResult.Failed>(f.observe(f.workspace.id).first()).failure.code,
        )
        f.replicas.beforeAdmit = {}
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        assertNull(f.replicas.readAdmission.value.denial)
    }

    @Test
    fun oldLocalFactsAndAllAcquiredMonthsSurviveReauthenticationWithoutRenewingReview() = runTest {
        var now = kotlin.time.Instant.parse("2026-10-13T00:00:00Z")
        val f = ExpenseFixture { now }
        assertIs<AppResult.Success<*>>(f.sync(f.workspace.id))
        val previous = details(f)
        now += 73.hours
        val next =
            f.session.copy(
                id = "next-login",
                binding =
                    f.session.binding.copy(
                        expiresAtEpochMillis = (now + 24.hours).toEpochMilliseconds()
                    ),
            )
        val workspace =
            f.workspace.copy(
                sessionId = next.id,
                admission = f.workspace.admission.copy(expiresAt = now + 1.hours),
            )
        f.sessionState.value = AppResult.Success(next)
        f.workspaceState.value = AppResult.Success(workspace)
        assertEquals(previous, details(f))
        val admission =
            (admitExpenses(workspace.id, workspace, next, f.build, now) as AppResult.Success).value
        assertEquals(
            "client.expense_review_required",
            assertIs<AppResult.Failed>(validateExpenseActionSnapshot(admission, previous, now))
                .failure
                .code,
        )
        assertEquals(0, f.listReads)
        assertEquals(1, f.recordReads)
        assertNotNull(
            (f.observe(f.workspace.id, ExpenseSelection(month = YearMonth(2026, 9))).first()
                    as AppResult.Success)
                .value
        )
    }

    @Test
    fun localHistoryAcquisitionDoesNotFetchTheHeadOrRenewItsReviewDeadline() = runTest {
        val f = ExpenseFixture()
        f.recordCall = { _, _ ->
            AppResult.Success(
                ExpenseClaimRecord(
                    f.claim.copy(version = 2),
                    null,
                    ExpensePage(listOf(f.change.copy(version = 2)), "2"),
                )
            )
        }
        assertIs<AppResult.Success<*>>(f.refreshDetail(f.workspace.id, f.claim.id))
        val original = details(f)
        f.historyCall = { AppResult.Success(ExpensePage(listOf(f.change.copy(version = 1)), null)) }
        assertIs<AppResult.Success<*>>(
            f.refreshDetail(f.workspace.id, f.claim.id, 2, ExpenseClaimAcquisition.LOCAL_FIRST)
        )
        val historical =
            (f.observeDetail(f.workspace.id, f.claim.id, 2).first() as AppResult.Success).value!!
        assertEquals(original.fetchedAt, historical.fetchedAt)
        assertEquals(original.refreshAfter, historical.refreshAfter)
        assertEquals(1, f.recordReads)
        assertEquals(1, f.historyReads)
        assertIs<AppResult.Success<*>>(
            f.refreshDetail(f.workspace.id, f.claim.id, 2, ExpenseClaimAcquisition.LOCAL_FIRST)
        )
        assertEquals(1, f.recordReads)
        assertEquals(1, f.historyReads)
    }

    @Test
    fun boundedHistoryBackfillPublishesCurrentFactsAndResumesTheRemainingPages() = runTest {
        val f = ExpenseFixture()
        f.recordCall = { _, _ ->
            AppResult.Success(
                ExpenseClaimRecord(
                    f.claim.copy(version = 8),
                    null,
                    ExpensePage(listOf(f.change.copy(version = 8)), "8"),
                )
            )
        }
        f.historyCall = { after ->
            val version = requireNotNull(after) - 1
            AppResult.Success(
                ExpensePage(
                    listOf(f.change.copy(version = version)),
                    version.takeIf { it > 0 }?.toString(),
                )
            )
        }
        val first = assertIs<AppResult.Success<ExpenseSyncOutcome>>(f.sync(f.workspace.id)).value
        assertFalse(first.caughtUp)
        assertEquals(4, f.historyReads)
        assertNotNull(details(f))
        assertNull(f.replicas.readAdmission.value.denial)
        assertIs<AppResult.Success<*>>(
            f.refreshDetail(
                f.workspace.id,
                f.claim.id,
                acquisition = ExpenseClaimAcquisition.LOCAL_FIRST,
            )
        )
        val next = assertIs<AppResult.Success<ExpenseSyncOutcome>>(f.sync(f.workspace.id)).value
        assertTrue(next.caughtUp)
        assertEquals(8, f.historyReads)
        assertEquals(1, f.recordReads)
    }

    @Test
    fun unendingChangePagesStopAtTheBoundedRunAndKeepThePublishedCursor() = runTest {
        val f = ExpenseFixture()
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(emptyList(), "page-${f.changeReads}", true, false, 5, f.clock.now())
            )
        }
        assertEquals(AppResult.Success(ExpenseSyncOutcome(false, 5)), f.sync(f.workspace.id))
        assertEquals(9, f.changeReads)
        assertEquals(10, f.replicas.writes)
        assertNull((f.observe(f.workspace.id).first() as AppResult.Success).value)
    }

    @Test
    fun changedCredentialsRejectLateWorkWithoutRevokingOwnedLocalFacts() = runTest {
        val f = ExpenseFixture()
        assertIs<AppResult.Success<*>>(f.refreshDetail(f.workspace.id, f.claim.id))
        val saved = details(f)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.claimCall = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Success(f.claim)
        }
        val pending = async { f.refreshDetail(f.workspace.id, f.claim.id) }
        entered.await()
        f.workspaceState.value =
            AppResult.Success(
                f.workspace.copy(permissions = f.workspace.permissions + "people.self.read")
            )
        release.complete(Unit)
        val failed = assertIs<AppResult.Failed>(pending.await())
        assertEquals(FailureKind.Conflict, failed.failure.kind)
        assertFalse(expenseFailureInvalidatesCache(failed.failure))
        assertNull(f.replicas.readAdmission.value.denial)
        assertEquals(saved, details(f))
    }

    private suspend fun details(f: ExpenseFixture) =
        assertNotNull(
            assertIs<AppResult.Success<ExpenseSnapshot<ExpenseView>?>>(
                    f.observeDetail(f.workspace.id, f.claim.id).first()
                )
                .value
        )

    private fun page(f: ExpenseFixture, collection: SyncCollection, version: Long) =
        SyncChangePage(
            listOf(
                SyncChange(SyncReference(collection, f.claim.id, version), SyncOperation.UPSERT)
            ),
            "change-${f.changeReads}",
            false,
            false,
            5,
            f.clock.now(),
        )

    private fun <T> List<T>.singleDistinct() = distinct().single()
}
