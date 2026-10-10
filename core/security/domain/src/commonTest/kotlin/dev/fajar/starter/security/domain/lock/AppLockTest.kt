@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.security.domain.lock

import dev.fajar.starter.common.result.*
import dev.fajar.starter.common.time.ElapsedClock
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.lock.entities.*
import dev.fajar.starter.security.domain.lock.repositories.*
import dev.fajar.starter.security.domain.lock.usecases.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class AppLockTest {
    @Test
    fun observationScopesGrantsAndLocksOnInactivityWithoutPollingOrRepeatedUiEmissions() = runTest {
        val sessions = LockSessions()
        val locks = LockStore()
        val clock = ElapsedClock { testScheduler.currentTime }
        val values = mutableListOf<AppLockStatus>()
        backgroundScope.launch {
            ObserveAppLock(sessions, locks, clock)().collect {
                values += (it as AppResult.Success).value
            }
        }
        runCurrent()
        assertTrue(values.last().locked)
        locks.authorize(DeviceAuthorization("a", APP_LOCK_TIMEOUT_MILLIS))
        runCurrent()
        assertFalse(values.last().locked)
        advanceTimeBy(APP_LOCK_ACTIVITY_INTERVAL_MILLIS)
        repeat(30) { RecordAppInteraction(sessions, locks, clock)() }
        runCurrent()
        assertEquals(1, locks.renewals)
        assertEquals(2, values.size) // Renewal does not change the visible status.
        advanceTimeBy(APP_LOCK_TIMEOUT_MILLIS)
        runCurrent()
        assertTrue(values.last().locked)
        RecordAppInteraction(sessions, locks, clock)()
        assertEquals(1, locks.renewals) // Expired grants cannot be extended.
        locks.authorize(DeviceAuthorization("a", clock.milliseconds() + APP_LOCK_TIMEOUT_MILLIS))
        sessions.value.value = AppResult.Success(lockSession("b"))
        runCurrent()
        assertTrue(values.last().locked)
        assertEquals("b", values.last().sessionId)
    }

    @Test
    fun cancelledOrDifferentSessionAuthenticationCannotPublishAGrant() = runTest {
        val sessions = LockSessions()
        val locks = LockStore()
        val pending = CompletableDeferred<Unit>()
        val device =
            object : DeviceAuthenticationRepository {
                override suspend fun available() = AppResult.Success(true)

                override suspend fun authenticate(): AppResult<Unit> {
                    withContext(NonCancellable) { pending.await() }
                    return AppResult.Success(Unit)
                }
            }
        val cancelled = launch { UnlockApp(sessions, locks, device)() }
        val changed = async { UnlockApp(sessions, locks, device)() }
        runCurrent()
        cancelled.cancel()
        sessions.value.value = AppResult.Success(lockSession("b"))
        pending.complete(Unit)
        cancelled.join()
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(changed.await()).failure.kind,
        )
        assertNull(locks.value.value.authorization)
    }

    @Test
    fun enablingRequiresAuthenticationAndRecoveryClearsTheSessionFirst() = runTest {
        val sessions = LockSessions()
        val locks = LockStore().also { it.value.value = AppLockSnapshot(false, null) }
        val denied = AppResult.Failed(Failure(FailureKind.Permission, "Denied"))
        val device =
            object : DeviceAuthenticationRepository {
                override suspend fun available() = AppResult.Success(true)

                override suspend fun authenticate() = denied
            }
        assertSame(denied, SetAppLockEnabled(sessions, locks, device)(true))
        assertFalse(locks.value.value.enabled)
        locks.onDisable = { assertNull((sessions.value.value as AppResult.Success).value) }
        assertIs<AppResult.Success<Unit>>(ResetLockedSession(sessions, locks)())
        assertNull(locks.value.value.authorization)
    }
}

private fun lockSession(id: String) =
    Session(
        id,
        User(id, "Demo", "demo@example.com"),
        SessionTokens("access", "refresh", Long.MAX_VALUE),
    )

private class LockSessions : SessionRepository {
    override val persistent = false
    val value = MutableStateFlow<AppResult<Session?>>(AppResult.Success(lockSession("a")))

    override fun observe() = value

    override suspend fun current() = value.value

    override suspend fun compareAndSet(expected: Session?, updated: Session?) =
        AppResult.Success(
            value.compareAndSet(AppResult.Success(expected), AppResult.Success(updated))
        )
}

private class LockStore : AppLockRepository {
    val value = MutableStateFlow(AppLockSnapshot(true, null))
    var renewals = 0
    var onDisable: () -> Unit = {}

    override fun observe() = value.map { AppResult.Success(it) }

    override suspend fun current() = AppResult.Success(value.value)

    override suspend fun setEnabled(enabled: Boolean): AppResult<Unit> {
        if (!enabled) onDisable()
        value.update { it.copy(enabled = enabled) }
        return AppResult.Success(Unit)
    }

    override suspend fun authorize(grant: DeviceAuthorization): AppResult<Unit> {
        value.update { it.copy(authorization = grant) }
        return AppResult.Success(Unit)
    }

    override suspend fun renew(
        expected: DeviceAuthorization,
        expiresAtMillis: Long,
    ): AppResult<Boolean> {
        renewals++
        val old = value.value
        return AppResult.Success(
            old.authorization == expected &&
                value.compareAndSet(
                    old,
                    old.copy(authorization = expected.copy(expiresAtMillis = expiresAtMillis)),
                )
        )
    }

    override suspend fun revoke(): AppResult<Unit> {
        value.update { it.copy(authorization = null) }
        return AppResult.Success(Unit)
    }
}
