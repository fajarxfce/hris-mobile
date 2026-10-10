@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.auth.presentation.login

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.common.result.ValidationIssue
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.entities.AuthenticatedUser
import dev.fajar.starter.identity.domain.repositories.IdentityRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.identity.domain.sso.entities.SsoProof
import dev.fajar.starter.identity.domain.sso.entities.SsoProvider
import dev.fajar.starter.identity.domain.sso.repositories.SsoRepository
import dev.fajar.starter.identity.domain.sso.usecases.ListSsoProviders
import dev.fajar.starter.identity.domain.sso.usecases.SignInWithSso
import dev.fajar.starter.identity.domain.usecases.SignIn
import dev.fajar.starter.identity.domain.usecases.ValidateSignIn
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

class LoginViewModelTest {
    private val sso =
        object : SsoRepository {
            override suspend fun providers() = AppResult.Success(emptyList<SsoProvider>())

            override suspend fun authorize(provider: SsoProvider): AppResult<SsoProof> =
                error("Unused browser authorization")
        }
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
        val response = CompletableDeferred<AppResult<AuthenticatedUser>>()
        var calls = 0
        val repository =
            object : IdentityRepository {
                override suspend fun completeSso(proof: SsoProof): AppResult<AuthenticatedUser> =
                    error("Unused SSO exchange")

                override suspend fun refresh(refreshToken: String): AppResult<AuthenticatedUser> =
                    error("Unused")

                override suspend fun signIn(
                    email: String,
                    password: String,
                ): AppResult<AuthenticatedUser> {
                    calls++
                    return response.await()
                }
            }
        val sessions = TestSessions()
        val viewModel =
            LoginViewModel(
                SignIn(repository, sessions),
                ValidateSignIn(),
                ListSsoProviders(sso),
                SignInWithSso(sso, repository, sessions),
            )
        store.put("login", viewModel)
        viewModel.onEvent(LoginEvent.DemoAccountSelected)
        viewModel.onEvent(LoginEvent.SignInRequested)
        viewModel.onEvent(LoginEvent.SignInRequested)
        viewModel.onEvent(LoginEvent.EmailChanged("changed@example.com"))
        runCurrent()
        assertEquals(1, calls)
        assertEquals("demo@example.com", viewModel.state.value.email)
        response.complete(
            AppResult.Success(AuthenticatedUser(testSession().user, testSession().tokens))
        )
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
                override suspend fun completeSso(proof: SsoProof): AppResult<AuthenticatedUser> =
                    error("Unused SSO exchange")

                override suspend fun refresh(refreshToken: String): AppResult<AuthenticatedUser> =
                    error("Unused")

                override suspend fun signIn(
                    email: String,
                    password: String,
                ): AppResult<AuthenticatedUser> {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            }
        val sessions = TestSessions()
        val viewModel =
            LoginViewModel(
                SignIn(repository, sessions),
                ValidateSignIn(),
                ListSsoProviders(sso),
                SignInWithSso(sso, repository, sessions),
            )
        store.put("login", viewModel)
        viewModel.onEvent(LoginEvent.DemoAccountSelected)
        viewModel.onEvent(LoginEvent.SignInRequested)
        runCurrent()
        started.await()
        store.clear()
        runCurrent()
        assertTrue(cancelled)
    }

    @Test
    fun validationDoesNotSubmitAndEditingOneFieldPreservesTheOtherError() = runTest {
        var calls = 0
        val repository =
            object : IdentityRepository {
                override suspend fun completeSso(proof: SsoProof): AppResult<AuthenticatedUser> =
                    error("Unused SSO exchange")

                override suspend fun refresh(refreshToken: String): AppResult<AuthenticatedUser> =
                    error("unused")

                override suspend fun signIn(
                    email: String,
                    password: String,
                ): AppResult<AuthenticatedUser> {
                    calls++
                    return AppResult.Failed(
                        Failure(
                            FailureKind.Validation,
                            "Rejected",
                            violations = mapOf("email" to ValidationIssue.Rejected),
                        )
                    )
                }
            }
        val sessions = TestSessions()
        val vm =
            LoginViewModel(
                SignIn(repository, sessions),
                ValidateSignIn(),
                ListSsoProviders(sso),
                SignInWithSso(sso, repository, sessions),
            )
        store.put("login", vm)
        vm.onEvent(LoginEvent.SignInRequested)
        runCurrent()
        assertEquals(setOf("email", "password"), vm.state.value.fieldErrors.keys)
        assertEquals(0, calls)
        vm.onEvent(LoginEvent.EmailChanged("demo@example.com"))
        runCurrent()
        assertEquals(setOf("password"), vm.state.value.fieldErrors.keys)
        vm.onEvent(LoginEvent.PasswordChanged("password"))
        vm.onEvent(LoginEvent.SignInRequested)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(setOf("email"), vm.state.value.fieldErrors.keys)
        vm.onEvent(LoginEvent.PasswordChanged("edited"))
        runCurrent()
        assertEquals(setOf("email"), vm.state.value.fieldErrors.keys)
    }
}

private class TestSessions(initial: Session? = null) : SessionRepository {
    override val persistent = false
    val value = MutableStateFlow<AppResult<Session?>>(AppResult.Success(initial))

    override fun observe() = value

    override suspend fun current() = value.value

    override suspend fun compareAndSet(expected: Session?, updated: Session?): AppResult<Boolean> {
        val current = value.value
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != expected) return AppResult.Success(false)
        value.value = AppResult.Success(updated)
        return AppResult.Success(true)
    }
}

private fun testSession() =
    Session(
        "session-a",
        User("1", "Alex", "demo@example.com"),
        SessionTokens("access", "refresh", Long.MAX_VALUE),
    )
