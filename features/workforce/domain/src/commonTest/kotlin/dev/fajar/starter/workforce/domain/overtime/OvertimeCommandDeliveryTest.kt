package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.expectedReceiptVersion
import dev.fajar.starter.workforce.domain.overtime.usecases.ReplayOvertimeCommands
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OvertimeCommandDeliveryTest {
    @Test
    fun transportStartsOnlyAfterTheOriginalInputIsMarkedUnconfirmed() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        f.transmit = {
            val stored = f.records.value.single()
            assertEquals(it, stored.command)
            assertEquals(1, stored.attempts)
            assertIs<OvertimeCommandOutcome.Unconfirmed>(stored.outcome)
            AppResult.Success(OvertimeCommandReceipt(it.requestId, 0))
        }
        assertEquals(
            OvertimeCommandOutcome.Confirmed(0),
            assertIs<AppResult.Success<OvertimeCommandRecord>>(f.send()).value.outcome,
        )
    }

    @Test
    fun aLostCommitResponseRecoversEveryPayloadUsingTheOriginalOperationAndBody() = runTest {
        for (kind in listOf("plan", "actual", "withdrawal")) {
            val f = OvertimeCommandFixture()
            when (kind) {
                "plan" -> f.queue()
                "actual" -> f.queueAction(OvertimeRequestedAction.Actual(f.interval))
                else -> f.queueAction()
            }
            val receipts = mutableSetOf<String>()
            var commits = 0
            f.transmit = {
                if (receipts.add(it.id)) {
                    commits++
                    AppResult.Failed(Failure(FailureKind.Network, "Response lost"))
                } else
                    AppResult.Success(
                        OvertimeCommandReceipt(it.requestId, it.expectedReceiptVersion())
                    )
            }
            assertIs<OvertimeCommandOutcome.Unconfirmed>(
                assertIs<AppResult.Success<OvertimeCommandRecord>>(f.send()).value.outcome
            )
            val command = f.records.value.single().command
            val restored =
                ReplayOvertimeCommands(
                    f.commands,
                    f.remote,
                    f.overtime.cache,
                    f.base.sessions,
                    f.base.workspaces,
                    f.base.clock,
                    f.base.build,
                )
            val result =
                assertIs<AppResult.Success<OvertimeCommandRecord>>(
                        restored(f.workspace.id, command.id)
                    )
                    .value
            assertEquals(
                OvertimeCommandOutcome.Confirmed(command.expectedReceiptVersion()),
                result.outcome,
            )
            assertEquals(listOf(command, command), f.transmitted)
            assertEquals(1, commits)
        }
    }

    @Test
    fun aLaterRejectionCannotDisproveAnEarlierUncertainCommit() = runTest {
        for (code in
            listOf(
                "overtime_planning_changed",
                "mfa_required",
                "stale_version",
                "work_period_locked",
            )) {
            for (ambiguous in listOf(false, true)) {
                val f = OvertimeCommandFixture()
                f.queue()
                if (ambiguous) {
                    f.transmit = { AppResult.Failed(Failure(FailureKind.Timeout, "Response lost")) }
                    f.send()
                }
                f.transmit = {
                    AppResult.Failed(Failure(FailureKind.Conflict, "Rejected", code = code))
                }
                val outcome =
                    assertIs<AppResult.Success<OvertimeCommandRecord>>(f.send()).value.outcome
                if (ambiguous) {
                    assertIs<OvertimeCommandOutcome.Unconfirmed>(outcome)
                    assertEquals(
                        "client.overtime_unconfirmed",
                        assertIs<AppResult.Failed>(f.clear()).failure.code,
                    )
                    assertEquals(f.transmitted.first(), f.transmitted.last())
                } else {
                    assertIs<OvertimeCommandOutcome.Rejected>(outcome)
                    assertEquals(AppResult.Success(true), f.clear())
                }
            }
        }
    }

    @Test
    fun unknownFailuresAndMismatchedReceiptsRemainUnconfirmed() = runTest {
        for (response in
            listOf(
                AppResult.Failed(
                    Failure(FailureKind.Conflict, "Unknown conflict", code = "new_server_code")
                ),
                AppResult.Success(
                    OvertimeCommandReceipt("99999999-9999-9999-9999-999999999999", 0)
                ),
                AppResult.Success(OvertimeCommandReceipt("66666666-6666-6666-6666-666666666666", 9)),
            )) {
            val f = OvertimeCommandFixture()
            f.queueAction()
            f.transmit = { response }
            assertIs<OvertimeCommandOutcome.Unconfirmed>(
                assertIs<AppResult.Success<OvertimeCommandRecord>>(f.send()).value.outcome
            )
            assertIs<AppResult.Failed>(f.clear())
        }
    }

    @Test
    fun manualAndWorkerDeliverySerializeWithoutSendingAgainAfterConfirmation() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        val release = CompletableDeferred<Unit>()
        f.transmit = {
            release.await()
            AppResult.Success(OvertimeCommandReceipt(it.requestId, 0))
        }
        val manual = async { f.send() }
        runCurrent()
        val worker = async { f.replay() }
        runCurrent()
        assertEquals(1, f.transmitted.size)
        release.complete(Unit)
        assertIs<AppResult.Success<OvertimeCommandRecord>>(manual.await())
        assertEquals(SyncResult.Complete, worker.await())
        assertEquals(1, f.transmitted.size)
    }

    @Test
    fun aReceiptAfterCompanyReplacementStaysInItsOriginalScope() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        f.transmit = {
            f.base.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
            AppResult.Success(OvertimeCommandReceipt(it.requestId, 0))
        }
        assertIs<AppResult.Failed>(f.send())
        assertEquals(OvertimeCommandOutcome.Confirmed(0), f.records.value.single().outcome)
        f.base.workspaceState.value = AppResult.Success(f.workspace)
        assertIs<AppResult.Success<OvertimeCommandRecord>>(f.send())
        assertEquals(1, f.transmitted.size)
    }

    @Test
    fun cancellationAndTimeoutRetainTheAttemptAndIgnoreLateConfirmation() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        val release = CompletableDeferred<Unit>()
        f.transmit = {
            withContext(NonCancellable) { release.await() }
            AppResult.Success(OvertimeCommandReceipt(it.requestId, 0))
        }
        val pending = async { f.send() }
        runCurrent()
        pending.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertIs<OvertimeCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
        f.transmit = { awaitCancellation() }
        assertEquals("client.overtime_timeout", assertIs<AppResult.Failed>(f.send()).failure.code)
        assertEquals(2, f.records.value.single().attempts)
        assertEquals(f.transmitted.first(), f.transmitted.last())
    }

    @Test
    fun theAcquisitionDeadlineIncludesWaitingForAnotherDelivery() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        val release = CompletableDeferred<Unit>()
        f.transmit = {
            withContext(NonCancellable) { release.await() }
            AppResult.Success(OvertimeCommandReceipt(it.requestId, 0))
        }
        val first = async { f.send() }
        runCurrent()
        val waiting = async { f.send() }
        runCurrent()
        advanceTimeBy(30.seconds)
        runCurrent()
        assertTrue(waiting.isCompleted)
        assertEquals(
            "client.overtime_timeout",
            assertIs<AppResult.Failed>(waiting.await()).failure.code,
        )
        assertEquals(1, f.transmitted.size)
        release.complete(Unit)
        assertIs<AppResult.Failed>(first.await())
        assertIs<OvertimeCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
    }

    @Test
    fun currentPolicyAndFailedAttemptStorageStopBeforeTransport() = runTest {
        for (storage in listOf(false, true)) {
            val f = OvertimeCommandFixture()
            f.queue()
            if (storage) f.updateFailure = Failure(FailureKind.Storage, "Unavailable")
            else
                f.base.policyCall = {
                    AppResult.Success(
                        f.base.policy.copy(enabledModules = setOf(CompanyModule.PEOPLE))
                    )
                }
            assertIs<AppResult.Failed>(f.send())
            assertEquals(OvertimeCommandOutcome.Pending, f.records.value.single().outcome)
            assertEquals(0, f.records.value.single().attempts)
            assertTrue(f.transmitted.isEmpty())
        }
    }

    @Test
    fun failedReceiptStorageDoesNotInventConfirmationAndRetryKeepsTheInput() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        f.beforeUpdate = {
            if (it is OvertimeCommandOutcome.Confirmed)
                f.updateFailure = Failure(FailureKind.Storage, "Unavailable")
        }
        assertIs<AppResult.Failed>(f.send())
        assertIs<OvertimeCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
        f.beforeUpdate = {}
        f.updateFailure = null
        assertEquals(
            OvertimeCommandOutcome.Confirmed(0),
            assertIs<AppResult.Success<OvertimeCommandRecord>>(f.send()).value.outcome,
        )
        assertEquals(f.transmitted.first(), f.transmitted.last())
    }

    @Test
    fun revokedWritePermissionDuringAttemptStorageStopsTheRequest() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        f.beforeUpdate = {
            f.base.workspaceState.value =
                AppResult.Success(f.workspace.copy(permissions = setOf("overtime.read")))
        }
        assertEquals("access_denied", assertIs<AppResult.Failed>(f.send()).failure.code)
        assertTrue(f.transmitted.isEmpty())
        assertIs<OvertimeCommandOutcome.Unconfirmed>(f.records.value.single().outcome)
    }

    @Test
    fun automaticAttemptsAndTotalAttemptsAreBounded() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        f.transmit = { AppResult.Failed(Failure(FailureKind.Network, "Offline")) }
        repeat(5) { f.replay() }
        assertEquals(3, f.transmitted.size)
        f.records.value = listOf(f.records.value.single().copy(attempts = 1_000))
        assertEquals(
            "client.overtime_retry_limit",
            assertIs<AppResult.Failed>(f.send()).failure.code,
        )
        assertEquals(3, f.transmitted.size)
    }

    @Test
    fun workerBatchSizeAndCursorProgressAreFinite() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        val first = f.records.value.single()
        f.records.value =
            (1..12).map { index ->
                val id = "10000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
                first.copy(command = first.command.copy(id = id, employeeId = id, requestId = id))
            }
        assertIs<SyncResult.Retry>(f.replay())
        assertEquals(10, f.transmitted.size)
        assertEquals(SyncResult.Complete, f.replay())
        assertEquals(12, f.transmitted.size)
        f.pageReads = 0
        f.pageCall = { AppResult.Success(OvertimePage(emptyList(), "same")) }
        assertEquals(
            "client.invalid_command_page",
            assertIs<SyncResult.Blocked>(f.replay()).failure.code,
        )
        assertEquals(2, f.pageReads)
        f.pageReads = 0
        f.pageCall = { cursor ->
            AppResult.Success(OvertimePage(emptyList(), (cursor?.toInt()?.plus(1) ?: 1).toString()))
        }
        assertIs<SyncResult.Retry>(f.replay())
        assertEquals(3, f.pageReads)
    }

    @Test
    fun aLateReadFailureCannotBePublishedAsBelongingToTheReplacementScope() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        val release = CompletableDeferred<Unit>()
        f.beforeFind = { release.await() }
        f.findFailure = Failure(FailureKind.Storage, "Old scope failed")
        val pending = async { f.send() }
        runCurrent()
        f.base.workspaceState.value = AppResult.Success(f.workspace.copy(id = "replacement"))
        release.complete(Unit)
        assertEquals(
            "client.workspace_changed",
            assertIs<AppResult.Failed>(pending.await()).failure.code,
        )
        assertTrue(f.transmitted.isEmpty())
    }
}
