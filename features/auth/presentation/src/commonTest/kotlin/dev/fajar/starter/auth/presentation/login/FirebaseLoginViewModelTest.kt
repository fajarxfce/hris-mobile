@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.auth.presentation.login

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.firebase.entities.FirebaseSignInInput
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class FirebaseLoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val failure = AppResult.Failed(Failure(FailureKind.Network, "Offline"))

    @BeforeTest
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun after() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun model(fixture: FirebaseLoginFixture) =
        fixture.viewModel().also { store.put("login", it) }

    private fun fill(vm: LoginViewModel) {
        vm.onEvent(LoginEvent.EmailChanged("fixture@example.test"))
        vm.onEvent(LoginEvent.PasswordChanged("fixture-password"))
    }

    @Test
    fun configuredEmailAndGoogleUseFirebaseAndClearPasswordsAfterHrisPublication() = runTest {
        for (google in listOf(false, true)) {
            val f = FirebaseLoginFixture()
            val vm = model(f)
            runCurrent()
            assertTrue(vm.state.value.options!!.firebaseEmail)
            assertTrue(vm.state.value.options!!.google)
            fill(vm)
            vm.onEvent(if (google) LoginEvent.GoogleRequested else LoginEvent.SignInRequested)
            runCurrent()
            assertEquals(0, f.legacyCalls)
            assertEquals(1, f.operations.size)
            if (google) assertEquals(FirebaseSignInInput.Google, f.providerInputs.single())
            else assertIs<FirebaseSignInInput.EmailPassword>(f.providerInputs.single())
            assertEquals("", vm.state.value.password)
            assertFalse(vm.state.value.submitting)
            assertNotNull(assertIs<AppResult.Success<Session?>>(f.sessions.current()).value)
        }
    }

    @Test
    fun pendingAndFailedDiscoveryNeverSendPasswordsToAnotherAuthority() = runTest {
        val f = FirebaseLoginFixture()
        val release = CompletableDeferred<Unit>()
        f.configurationHook = { release.await() }
        f.configurationFailure = failure
        val vm = model(f)
        fill(vm)
        vm.onEvent(LoginEvent.SignInRequested)
        runCurrent()
        assertTrue(vm.state.value.optionsLoading)
        assertEquals(0, f.legacyCalls)
        assertTrue(f.providerInputs.isEmpty())
        release.complete(Unit)
        runCurrent()
        assertNull(vm.state.value.options)
        vm.onEvent(LoginEvent.SignInRequested)
        runCurrent()
        assertEquals(0, f.legacyCalls)
        f.configurationFailure = null
        vm.onEvent(LoginEvent.ProvidersRequested)
        runCurrent()
        vm.onEvent(LoginEvent.SignInRequested)
        runCurrent()
        assertEquals(1, f.providerInputs.size)
    }

    @Test
    fun unchangedInputRetriesTheSameNativeIntentAndEditsReplaceIt() = runTest {
        val f = FirebaseLoginFixture().apply { exchangeFailure = failure }
        val vm = model(f)
        runCurrent()
        fill(vm)
        repeat(2) {
            vm.onEvent(LoginEvent.SignInRequested)
            runCurrent()
        }
        assertEquals(2, f.operations.size)
        assertEquals(f.operations[0], f.operations[1])
        assertEquals("fixture-password", vm.state.value.password)
        vm.onEvent(LoginEvent.PasswordChanged("different-password"))
        vm.onEvent(LoginEvent.SignInRequested)
        runCurrent()
        assertNotEquals(f.operations[1], f.operations[2])
    }

    @Test
    fun cancelledGoogleWorkCannotBeReplacedUntilItsLateResultIsDiscarded() = runTest {
        val f = FirebaseLoginFixture()
        val release = CompletableDeferred<Unit>()
        f.providerHook = { withContext(NonCancellable) { release.await() } }
        val vm = model(f)
        runCurrent()
        vm.onEvent(LoginEvent.GoogleRequested)
        runCurrent()
        vm.onEvent(LoginEvent.SsoCancellationRequested)
        vm.onEvent(LoginEvent.GoogleRequested)
        assertEquals(1, f.providerInputs.size)
        release.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.submitting)
        assertTrue(f.operations.isEmpty())
        assertEquals(AppResult.Success(null), f.sessions.current())
        f.providerHook = {}
        vm.onEvent(LoginEvent.GoogleRequested)
        runCurrent()
        assertEquals(2, f.providerInputs.size)
        assertEquals(1, f.operations.size)
    }

    @Test
    fun disposalCancelsTheInteractionAndCannotPublishALateSession() = runTest {
        val f = FirebaseLoginFixture()
        var cancelled = false
        f.providerHook = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val vm = model(f)
        runCurrent()
        vm.onEvent(LoginEvent.GoogleRequested)
        runCurrent()
        store.clear()
        runCurrent()
        assertTrue(cancelled)
        assertTrue(f.operations.isEmpty())
        assertEquals(AppResult.Success(null), f.sessions.current())
    }

    @Test
    fun lateDiscoveryCannotReplaceTheNewerAuthoritySelection() = runTest {
        val f = FirebaseLoginFixture()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        f.configurationHook = { if (++reads == 1) withContext(NonCancellable) { release.await() } }
        val vm = model(f)
        runCurrent()
        f.remoteConfiguration = f.remoteConfiguration.copy(enabled = false)
        vm.onEvent(LoginEvent.ProvidersRequested)
        runCurrent()
        assertFalse(vm.state.value.options!!.firebaseEmail)
        release.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.options!!.firebaseEmail)
        fill(vm)
        vm.onEvent(LoginEvent.SignInRequested)
        runCurrent()
        assertEquals(1, f.legacyCalls)
        assertTrue(f.providerInputs.isEmpty())
    }
}
