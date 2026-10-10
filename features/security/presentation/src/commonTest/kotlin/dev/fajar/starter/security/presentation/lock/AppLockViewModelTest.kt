@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.security.presentation.lock

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.lock.entities.*
import dev.fajar.starter.security.domain.lock.repositories.*
import dev.fajar.starter.security.domain.lock.usecases.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class AppLockViewModelTest {
    private val store = ViewModelStore()

    @BeforeTest
    fun before() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun after() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun leavingDuringAuthenticationCancelsThePromptAndDisposalStopsObservation() = runTest {
        val session =
            Session(
                "a",
                User("a", "Demo", "demo@example.com"),
                SessionTokens("access", "refresh", Long.MAX_VALUE),
            )
        val sessions =
            object : SessionRepository {
                override val persistent = false

                override fun observe() = flowOf(AppResult.Success(session))

                override suspend fun current() = AppResult.Success(session)

                override suspend fun compareAndSet(expected: Session?, updated: Session?) =
                    AppResult.Success(true)
            }
        var observing = 0
        var grants = 0
        var revoked = 0
        val locks =
            object : AppLockRepository {
                override fun observe() = flow {
                    observing++
                    try {
                        emit(AppResult.Success(AppLockSnapshot(true, null)))
                        awaitCancellation()
                    } finally {
                        observing--
                    }
                }

                override suspend fun current() = AppResult.Success(AppLockSnapshot(true, null))

                override suspend fun setEnabled(enabled: Boolean) = AppResult.Success(Unit)

                override suspend fun authorize(grant: DeviceAuthorization): AppResult<Unit> {
                    grants++
                    return AppResult.Success(Unit)
                }

                override suspend fun renew(expected: DeviceAuthorization, expiresAtMillis: Long) =
                    AppResult.Success(false)

                override suspend fun revoke(): AppResult<Unit> {
                    revoked++
                    return AppResult.Success(Unit)
                }
            }
        var promptCancelled = false
        val pending = CompletableDeferred<Unit>()
        val device =
            object : DeviceAuthenticationRepository {
                override suspend fun available() = AppResult.Success(true)

                override suspend fun authenticate(): AppResult<Unit> {
                    try {
                        pending.await()
                    } catch (error: CancellationException) {
                        promptCancelled = true
                        withContext(NonCancellable) { pending.await() }
                    }
                    return AppResult.Success(Unit)
                }
            }
        val viewModel =
            AppLockViewModel(
                ObserveAppLock(sessions, locks),
                UnlockApp(sessions, locks, device),
                LockApp(locks),
                RecordAppInteraction(sessions, locks),
                ResetLockedSession(sessions, locks),
            )
        store.put("lock", viewModel)
        viewModel.onEvent(AppLockEvent.Foregrounded)
        runCurrent()
        assertEquals(1, observing)
        viewModel.onEvent(AppLockEvent.UnlockRequested)
        runCurrent()
        viewModel.onEvent(AppLockEvent.Backgrounded)
        runCurrent()
        assertTrue(promptCancelled)
        assertEquals(0, observing)
        assertEquals(1, revoked)
        pending.complete(Unit)
        runCurrent()
        assertEquals(0, grants)
        assertTrue(viewModel.state.value.locked)
        viewModel.onEvent(AppLockEvent.Foregrounded)
        runCurrent()
        assertEquals(1, observing)
        store.clear()
        runCurrent()
        assertEquals(0, observing)
    }
}
