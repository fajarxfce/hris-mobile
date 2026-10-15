@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.payroll.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class PayrollRevisionSyncTest {
    @Test
    fun hydrationStoresTheActualRevisionWhenPublicationAdvancesBeyondTheFeedReference() = runTest {
        val f = PayrollFixture()
        f.load()
        f.slips[f.summary.id] = f.slip.copy(summary = f.summary.copy(version = 3))
        f.payments[f.summary.id] =
            f.payment.copy(
                version = 4,
                settlement = f.payment.settlement!!.copy(assessmentRevision = 3),
            )
        f.changesCall = {
            AppResult.Success(
                f.changePage(
                    listOf(
                        SyncChange(
                            SyncReference(SyncCollection.PAYSLIPS, f.summary.id, 1),
                            SyncOperation.UPSERT,
                        ),
                        SyncChange(
                            SyncReference(SyncCollection.PAYSLIPS, f.summary.id, 2),
                            SyncOperation.UPSERT,
                        ),
                        f.changedPayment(3),
                    )
                )
            )
        }
        assertTrue(assertIs<AppResult.Success<PayrollSyncOutcome>>(f.load()).value.caughtUp)
        val entries = f.snapshot()!!.entries
        val slip = entries.single { it.record is PayrollRecord.Slip }
        assertEquals(3L, slip.reference.version)
        assertEquals(3L, slip.record!!.version)
        assertEquals(4L, entries.single { it.record is PayrollRecord.Payments }.reference.version)
        assertEquals(2, f.detailReads)
        assertEquals(2, f.paymentReads)
    }

    @Test
    fun aCanonicalReadOlderThanTheChangeCannotAdvanceTheCursorOrOverwriteSavedFacts() = runTest {
        val f = PayrollFixture()
        f.load()
        val saved = f.snapshot()
        f.changesCall = {
            AppResult.Success(
                f.changePage(
                    listOf(
                        SyncChange(
                            SyncReference(SyncCollection.PAYSLIPS, f.summary.id, 2),
                            SyncOperation.UPSERT,
                        )
                    )
                )
            )
        }
        f.detailCall = { AppResult.Success(f.slip.copy(summary = f.summary.copy(version = 1))) }
        assertEquals("client.invalid_response", assertIs<AppResult.Failed>(f.load()).failure.code)
        assertEquals(saved, f.snapshot())
        assertEquals(saved!!.checkpoint, f.replicas.current)
        assertEquals(1, f.paymentReads)
        assertNull(f.replicas.readAdmission.value.denial)
    }

    @Test
    fun formatUpgradeKeepsPublishedFactsThroughFailedRetriesAndRebuildsOnlyOnce() = runTest {
        val f = PayrollFixture()
        f.load()
        val old =
            f.snapshot()!!.let { it.copy(checkpoint = it.checkpoint!!.copy(recordVersion = 1)) }
        f.replicas.current = old.checkpoint
        f.replicas.published.value = AppResult.Success(old)
        f.detailCall = { payslipFailure("fixture.offline", FailureKind.Network) }
        repeat(2) {
            assertIs<AppResult.Failed>(f.load())
            assertEquals(old, f.snapshot())
            assertFalse(f.replicas.current!!.published)
            assertEquals(
                PayrollCheckpoint.CURRENT_RECORD_VERSION,
                f.replicas.current!!.recordVersion,
            )
            assertEquals(2, f.replicas.begins)
            assertEquals(0, f.replicas.clears)
            assertNull(f.replicas.readAdmission.value.denial)
        }
        val replacement = f.replicas.current!!.generation
        f.detailCall = { AppResult.Success(f.slip) }
        assertTrue(assertIs<AppResult.Success<PayrollSyncOutcome>>(f.load()).value.caughtUp)
        assertEquals(replacement, f.snapshot()!!.checkpoint!!.generation)
        val acquired = f.detailReads
        f.load()
        assertEquals(2, f.replicas.begins)
        assertEquals(acquired, f.detailReads)
    }

    @Test
    fun anUnfinishedOldFormatIsReplacedButItsSuccessorResumesRatherThanLooping() = runTest {
        val f = PayrollFixture()
        var page = 0
        val cursors = mutableListOf<String?>()
        f.bootstrapCall = { cursor, _ ->
            cursors += cursor
            AppResult.Success(
                SyncBootstrapPage(
                    PayrollFixture.collections,
                    emptyList(),
                    "page/${++page}",
                    null,
                    f.clock.now(),
                )
            )
        }
        assertFalse(assertIs<AppResult.Success<PayrollSyncOutcome>>(f.load()).value.caughtUp)
        f.replicas.current = f.replicas.current!!.copy(recordVersion = 1)
        assertFalse(assertIs<AppResult.Success<PayrollSyncOutcome>>(f.load()).value.caughtUp)
        assertNull(cursors[10])
        val resumed = f.replicas.current!!.cursor
        assertFalse(assertIs<AppResult.Success<PayrollSyncOutcome>>(f.load()).value.caughtUp)
        assertEquals(resumed, cursors[20])
        assertEquals(2, f.replicas.begins)
        assertNull(f.snapshot())
    }

    @Test
    fun cancellationDuringUpgradeRetainsTheOriginalViewAndRejectsALateRevision() = runTest {
        val f = PayrollFixture()
        f.load()
        val old =
            f.snapshot()!!.let { it.copy(checkpoint = it.checkpoint!!.copy(recordVersion = 1)) }
        f.replicas.current = old.checkpoint
        f.replicas.published.value = AppResult.Success(old)
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        f.detailCall = {
            entered.complete(Unit)
            withContext(NonCancellable) { released.await() }
            AppResult.Success(f.slip.copy(summary = f.summary.copy(version = 2)))
        }
        val pending = async { f.load() }
        entered.await()
        pending.cancel()
        released.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(old, f.snapshot())
        assertEquals(0L, f.replicas.current!!.revision)
        assertEquals(1, f.paymentReads)
        f.detailCall = { AppResult.Success(f.slip.copy(summary = f.summary.copy(version = 2))) }
        assertTrue(assertIs<AppResult.Success<PayrollSyncOutcome>>(f.load()).value.caughtUp)
        assertEquals(
            2L,
            f.snapshot()!!.entries.single { it.record is PayrollRecord.Slip }.reference.version,
        )
        assertEquals(2, f.replicas.begins)
    }
}
