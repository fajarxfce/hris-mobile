package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.SdkShutdownAttempt
import dev.fajar.starter.collaboration.data.repositories.SdkStoreShutdown
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SdkStoreShutdownTest {
    @Test
    fun concurrentCallersStopWaitingWithoutCancellingTheOwnedDrain() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        var released = false
        val closer =
            SdkStoreShutdown(backgroundScope) {
                attempts++
                entered.complete(Unit)
                release.await()
                released = true
                SdkShutdownAttempt(AppResult.Success(Unit), true)
            }
        val first = async { closer.close() }
        entered.await()
        val second = async { closer.close() }
        for (result in listOf(first.await(), second.await())) {
            assertEquals(
                "client.collaboration_close_pending",
                assertIs<AppResult.Failed>(result).failure.code,
            )
        }
        assertEquals(1, attempts)
        assertFalse(released)
        release.complete(Unit)
        assertEquals(AppResult.Success(Unit), closer.close())
        assertTrue(released)
        assertEquals(AppResult.Success(Unit), closer.close())
        assertEquals(1, attempts)
    }

    @Test
    fun failedDrainRetainsOwnershipAndOnlyExplicitCloseRetriesIt() = runTest {
        var attempts = 0
        var releases = 0
        val failure = AppResult.Failed(Failure(FailureKind.Storage, "Fictional drain failure"))
        val closer =
            SdkStoreShutdown(backgroundScope) {
                attempts++
                if (attempts == 1) SdkShutdownAttempt(failure, false)
                else {
                    releases++
                    SdkShutdownAttempt(AppResult.Success(Unit), true)
                }
            }
        assertEquals(failure, closer.close())
        assertEquals(1, attempts)
        assertEquals(0, releases)
        assertEquals(AppResult.Success(Unit), closer.close())
        assertEquals(AppResult.Success(Unit), closer.close())
        assertEquals(2, attempts)
        assertEquals(1, releases)
    }

    @Test
    fun cancelledCallerCannotCancelTheNativeResourceOwner() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var released = false
        val closer =
            SdkStoreShutdown(backgroundScope) {
                entered.complete(Unit)
                release.await()
                released = true
                SdkShutdownAttempt(AppResult.Success(Unit), true)
            }
        val caller = async { closer.close() }
        entered.await()
        caller.cancel()
        assertFalse(released)
        release.complete(Unit)
        assertFailsWith<CancellationException> { caller.await() }
        assertEquals(AppResult.Success(Unit), closer.close())
        assertTrue(released)
    }

    @Test
    fun failureReportedAfterReleaseDoesNotCloseTheSameHandlesAgain() = runTest {
        var releases = 0
        val failure = AppResult.Failed(Failure(FailureKind.Storage, "Fictional close failure"))
        val closer =
            SdkStoreShutdown(backgroundScope) {
                releases++
                SdkShutdownAttempt(failure, true)
            }
        assertEquals(failure, closer.close())
        assertEquals(failure, closer.close())
        assertEquals(1, releases)
    }
}
