@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.work.NativePushWorker
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.usecases.ObserveSessionIdentity
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class NativePushWorkerTest {
    @Test
    fun failedOrStalledIdentityReadsDoNotAcquireSdkDependencies() = runTest {
        val sessions = Sessions()
        val worker =
            NativePushWorker(ObserveSessionIdentity(sessions), lazy { error("No SDK acquisition") })
        val denied = Failure(FailureKind.AccessDenied, "Denied")
        sessions.read = { flowOf(AppResult.Failed(denied)) }
        assertEquals(SyncResult.Blocked(denied), worker())
        sessions.read = { flow { awaitCancellation() } }
        val result = async { worker() }
        runCurrent()
        advanceTimeBy(35_001)
        assertEquals(FailureKind.Timeout, assertIs<SyncResult.Retry>(result.await()).failure?.kind)
        assertEquals(0, sessions.observers)
    }

    @Test
    fun cancelledWorkerDropsALateIdentityAndClosesItsSubscription() = runTest {
        val sessions = Sessions()
        val release = CompletableDeferred<Unit>()
        sessions.read = {
            flow {
                withContext(NonCancellable) { release.await() }
                emit(AppResult.Success(null))
            }
        }
        val worker =
            NativePushWorker(ObserveSessionIdentity(sessions), lazy { error("No SDK acquisition") })
        val operation = launch { worker() }
        runCurrent()
        assertEquals(1, sessions.observers)
        operation.cancel()
        release.complete(Unit)
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(0, sessions.observers)
    }

    private class Sessions : SessionRepository {
        override val persistent = true
        var observers = 0
        var read: () -> Flow<AppResult<Session?>> = { flowOf(AppResult.Success(null)) }

        override fun observe(): Flow<AppResult<Session?>> = flow {
            observers++
            try {
                emitAll(read())
            } finally {
                observers--
            }
        }

        override suspend fun current(): AppResult<Session?> = error("Not used")

        override suspend fun compareAndSet(
            expected: Session?,
            updated: Session?,
        ): AppResult<Boolean> = error("No session mutation")

        override suspend fun clear(sessionId: String): AppResult<Boolean> =
            error("No session mutation")
    }
}
