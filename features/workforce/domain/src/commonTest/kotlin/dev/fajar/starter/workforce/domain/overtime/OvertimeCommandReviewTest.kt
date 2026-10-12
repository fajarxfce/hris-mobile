package dev.fajar.starter.workforce.domain.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OvertimeCommandReviewTest {
    @Test
    fun unconfirmedInputCannotBeDiscardedEvenWhenSimilarServerFactsExist() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        f.transmit = { AppResult.Failed(Failure(FailureKind.Network, "Unknown outcome")) }
        f.send()
        f.publish(10)
        assertEquals(
            "client.overtime_unconfirmed",
            assertIs<AppResult.Failed>(f.clear()).failure.code,
        )
        assertEquals(1, f.records.value.size)
    }

    @Test
    fun aConfirmedActionRequiresAnAdmittedRequestAtLeastAsNewAsItsReceipt() = runTest {
        val f = OvertimeCommandFixture()
        f.queueAction()
        f.send()
        assertEquals(
            "client.overtime_updates_pending",
            assertIs<AppResult.Failed>(f.clear()).failure.code,
        )
        f.publish(0)
        assertEquals(
            "client.overtime_updates_pending",
            assertIs<AppResult.Failed>(f.clear()).failure.code,
        )
        f.publish(1)
        val fresh =
            assertIs<AppResult.Success<OvertimeSnapshot<OvertimeRequestView>?>>(
                    f.overtime.detailRecords.value
                )
                .value!!
        f.overtime.detailRecords.value =
            AppResult.Success(fresh.copy(expiresAt = f.base.clock.now()))
        assertIs<AppResult.Failed>(f.clear())
        f.overtime.detailRecords.value =
            AppResult.Success(
                fresh.copy(
                    value =
                        fresh.value.copy(
                            selection =
                                fresh.value.selection.copy(
                                    employeeId = "99999999-9999-9999-9999-999999999999"
                                )
                        )
                )
            )
        assertIs<AppResult.Failed>(f.clear())
        f.overtime.detailRecords.value = AppResult.Success(fresh)
        assertEquals(AppResult.Success(true), f.clear())
        assertTrue(f.records.value.isEmpty())
    }

    @Test
    fun discardingAnUnsentCommandCannotRemoveAConcurrentDelivery() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        f.beforeRemove = { f.send() }
        assertEquals(AppResult.Success(false), f.clear())
        f.beforeRemove = {}
        assertIs<OvertimeCommandOutcome.Confirmed>(f.records.value.single().outcome)
        f.publish()
        assertEquals(AppResult.Success(true), f.clear())
    }

    @Test
    fun unsentCommandsCanBeDiscardedWithoutTransportOrRequestAcquisition() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        assertEquals(AppResult.Success(true), f.clear())
        assertTrue(f.transmitted.isEmpty())
        assertEquals(0, f.overtime.detailReads)
        assertEquals(0, f.overtime.observers)
    }

    @Test
    fun commandObservationExpiresAndReleasesItsScopeOnSignOutOrDisposal() = runTest {
        val origin = Instant.parse("2026-10-12T13:00:00Z")
        val f = OvertimeCommandFixture { origin + testScheduler.currentTime.milliseconds }
        f.queue()
        val states = mutableListOf<AppResult<OvertimeCommandRecord?>>()
        val observing =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id, OvertimeCommandSlot.Plan(f.base.employee.id))
                    .toList(states)
            }
        assertEquals(1, f.observers)
        assertIs<AppResult.Success<OvertimeCommandRecord?>>(states.last())
        advanceTimeBy(1.hours)
        runCurrent()
        assertIs<AppResult.Failed>(states.last())
        f.base.sessionState.value = AppResult.Success(null)
        runCurrent()
        assertEquals(0, f.observers)
        observing.cancelAndJoin()
    }

    @Test
    fun invalidSlotsAndReadOnlyPermissionNeverOpenCommandStorage() = runTest {
        val f = OvertimeCommandFixture()
        assertIs<AppResult.Failed>(
            f.observe(f.workspace.id, OvertimeCommandSlot.Request("../other")).first()
        )
        f.base.workspaceState.value =
            AppResult.Success(f.workspace.copy(permissions = setOf("overtime.read")))
        assertEquals(
            "access_denied",
            assertIs<AppResult.Failed>(
                    f.observe(f.workspace.id, OvertimeCommandSlot.Plan(f.base.employee.id)).first()
                )
                .failure
                .code,
        )
        assertEquals(0, f.observers)
    }

    @Test
    fun reviewCancellationAfterAPendingReadKeepsTheCommand() = runTest {
        val f = OvertimeCommandFixture()
        f.queue()
        val release = CompletableDeferred<Unit>()
        f.beforeFind = { withContext(NonCancellable) { release.await() } }
        val pending = async { f.clear() }
        runCurrent()
        pending.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(1, f.records.value.size)
    }

    @Test
    fun preparingACommandHasAFiniteStorageDeadline() = runTest {
        val f = OvertimeCommandFixture()
        f.beforeObserve = { awaitCancellation() }
        val start = testScheduler.currentTime
        assertEquals("client.overtime_timeout", assertIs<AppResult.Failed>(f.queue()).failure.code)
        assertEquals(10.seconds.inWholeMilliseconds, testScheduler.currentTime - start)
        assertEquals(0, f.observers)
        assertEquals(0, f.inserts)
    }
}
