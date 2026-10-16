@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.leaveFailure
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate

class SyncLeaveCarryMigrationTest {
    @Test
    fun anUpgradeHydratesSameVersionCarryOnceAndKeepsPublishedBalancesUntilCatchUp() = runTest {
        val f = LeaveSyncFixture()
        val old = previousFormat(f)
        val gate = CompletableDeferred<Unit>()
        val nextBalance = carriedBalance(f)
        f.balanceCall = {
            gate.await()
            AppResult.Success(nextBalance)
        }
        val updating = async { f.run() }
        runCurrent()
        assertEquals(LeaveCheckpoint.CURRENT_RECORD_SCHEMA, f.replica.current?.recordSchema)
        assertEquals(old, f.replica.published.value)
        val oldView =
            assertIs<AppResult.Success<LeaveOverview?>>(f.base.observe(f.base.workspace.id).first())
                .value!!
        assertNull(oldView.balances.items.single().carry)
        assertEquals(f.base.balance.version, oldView.balances.items.single().version)
        gate.complete(Unit)
        assertTrue(assertIs<AppResult.Success<LeaveSyncOutcome>>(updating.await()).value.caughtUp)
        val current =
            assertIs<AppResult.Success<LeaveOverview?>>(f.base.observe(f.base.workspace.id).first())
                .value!!
        assertEquals(nextBalance.value, current.balances.items.single())
        assertEquals(0, f.replica.clears)
        val bootstraps = f.bootstrapReads
        assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run())
        assertEquals(bootstraps, f.bootstrapReads)
        assertEquals(0, f.replica.directoryObservers)
        assertEquals(0, f.replica.pageObservers)
    }

    @Test
    fun failedOrCancelledUpgradeRetainsOwnedDataAndResumesTheSameStagingGeneration() = runTest {
        for (cancelled in listOf(false, true)) {
            val f = LeaveSyncFixture()
            val old = previousFormat(f)
            val gate = CompletableDeferred<Unit>()
            f.balanceCall = {
                withContext(NonCancellable) { gate.await() }
                if (cancelled) AppResult.Success(carriedBalance(f))
                else leaveFailure("client.network", FailureKind.Network)
            }
            val updating = async { f.run() }
            runCurrent()
            val generation = f.replica.current?.generation
            if (cancelled) updating.cancel()
            gate.complete(Unit)
            if (cancelled) {
                updating.join()
                assertTrue(updating.isCancelled)
            } else assertIs<AppResult.Failed>(updating.await())
            assertEquals(old, f.replica.published.value)
            assertEquals(0, f.replica.clears)
            f.balanceCall = { AppResult.Success(carriedBalance(f)) }
            assertTrue(assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run()).value.caughtUp)
            assertEquals(generation, f.replica.current?.generation)
            val current =
                assertIs<AppResult.Success<LeaveOverview?>>(
                        f.base.observe(f.base.workspace.id).first()
                    )
                    .value!!
            assertNotNull(current.balances.items.single().carry)
            assertEquals(f.base.balance.version, current.balances.items.single().version)
        }
    }
}

private suspend fun previousFormat(f: LeaveSyncFixture): AppResult<LeaveReplicaFixture.Published> {
    assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run())
    val old = requireNotNull(f.replica.current).copy(recordSchema = 2)
    val published =
        assertIs<AppResult.Success<LeaveReplicaFixture.Published>>(f.replica.published.value).value
    f.replica.current = old
    val result = AppResult.Success(published.copy(checkpoint = old))
    f.replica.published.value = result
    return result
}

private fun carriedBalance(f: LeaveSyncFixture) =
    LeaveReplicaRecord.Balance(
        f.base.employee.id,
        f.base.balance.copy(
            carry =
                LeaveCarryBalance(
                    "77777777-7777-4777-8777-777777777777",
                    LeaveDays(4),
                    LeaveDays(4),
                    LeaveDays(0),
                    LeaveDays(0),
                    LeaveDays(0),
                    LeaveDays(0),
                    LeaveDays(0),
                    LocalDate(2026, 3, 31),
                    Instant.parse("2026-03-31T17:00:00Z"),
                    "Asia/Jakarta",
                    0,
                )
        ),
    )
