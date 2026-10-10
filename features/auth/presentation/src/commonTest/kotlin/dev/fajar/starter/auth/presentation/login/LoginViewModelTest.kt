@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.auth.presentation.login

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.User
import dev.fajar.starter.identity.domain.repositories.IdentityRepository
import dev.fajar.starter.identity.domain.usecases.SignIn
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*

class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @BeforeTest
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun after() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun duplicateSubmissionIsDroppedAndEditsAreIgnoredWhilePending() = runTest {
        val response = CompletableDeferred<AppResult<User>>()
        var calls = 0
        val repository =
            object : IdentityRepository {
                override fun observeUser() = flowOf<User?>(null)

                override suspend fun signOut() = AppResult.Success(Unit)

                override suspend fun signIn(email: String, password: String): AppResult<User> {
                    calls++
                    return response.await()
                }
            }
        val viewModel = LoginViewModel(SignIn(repository))
        store.put("login", viewModel)
        viewModel.onEvent(LoginEvent.DemoAccountSelected)
        viewModel.onEvent(LoginEvent.SignInRequested)
        viewModel.onEvent(LoginEvent.SignInRequested)
        viewModel.onEvent(LoginEvent.EmailChanged("changed@example.com"))
        runCurrent()
        assertEquals(1, calls)
        assertEquals("demo@example.com", viewModel.state.value.email)
        response.complete(AppResult.Success(User("1", "Alex", "demo@example.com")))
        runCurrent()
        assertFalse(viewModel.state.value.submitting)
        assertEquals("", viewModel.state.value.password)
    }

    @Test
    fun clearingViewModelCancelsPendingRequest() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val repository =
            object : IdentityRepository {
                override fun observeUser() = flowOf<User?>(null)

                override suspend fun signOut() = AppResult.Success(Unit)

                override suspend fun signIn(email: String, password: String): AppResult<User> {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
        val viewModel = LoginViewModel(SignIn(repository))
        store.put("login", viewModel)
        viewModel.onEvent(LoginEvent.DemoAccountSelected)
        viewModel.onEvent(LoginEvent.SignInRequested)
        runCurrent()
        started.await()
        store.clear()
        runCurrent()
        assertTrue(cancelled)
    }
}
