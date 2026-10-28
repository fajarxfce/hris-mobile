package dev.fajar.starter

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.app.di.createAppContainer
import dev.fajar.starter.auth.presentation.login.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.datastore.createUserPreferences
import dev.fajar.starter.identity.data.firebase.datasources.*
import dev.fajar.starter.identity.data.firebase.dto.FirebaseClientOptions
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.firebase.usecases.SignInWithFirebase
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.network.*
import dev.fajar.starter.notifications.data.datasources.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.nio.file.Files
import java.util.Collections
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

class FirebaseLoginBindingsTest {
    @Test
    fun realContainerPublishesNativeSessionsFromBothLoginActions() = runBlocking {
        for (google in listOf(false, true)) {
            val f = Fixture()
            try {
                val vm = f.login()
                assertTrue(vm.state.value.options!!.firebaseEmail)
                assertTrue(vm.state.value.options!!.google)
                withContext(Dispatchers.Main.immediate) {
                    vm.onEvent(LoginEvent.EmailChanged("fixture@example.test"))
                    vm.onEvent(LoginEvent.PasswordChanged("fixture-password"))
                    vm.onEvent(
                        if (google) LoginEvent.GoogleRequested else LoginEvent.SignInRequested
                    )
                }
                val session =
                    withTimeout(10_000) {
                        f.container.koin.get<SessionRepository>().observe().first {
                            it is AppResult.Success && it.value != null
                        }
                    }
                assertEquals(
                    "hris-access",
                    assertIs<AppResult.Success<Session?>>(session).value!!.tokens.accessToken,
                )
                assertEquals(1, f.paths.count { it.endsWith("/firebase/login") })
                assertEquals(
                    1,
                    f.paths.count {
                        it.endsWith(
                            if (google) "accounts:signInWithIdp" else "accounts:signInWithPassword"
                        )
                    },
                )
                assertEquals(google, f.googleDisposed)
                assertSame(
                    f.container.koin.get<SignInWithFirebase>(),
                    f.container.koin.get<SignInWithFirebase>(),
                )
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun differentBackendProjectNeverOpensAProviderOrFallsBackToPassword() = runBlocking {
        val f = Fixture(project = "another-project")
        try {
            val vm = f.login()
            assertNull(vm.state.value.options)
            assertNotNull(vm.state.value.failure)
            withContext(Dispatchers.Main.immediate) {
                vm.onEvent(LoginEvent.GoogleRequested)
                vm.onEvent(LoginEvent.SignInRequested)
            }
            assertTrue(f.paths.all { it.endsWith("/auth/native/firebase") })
            assertEquals(
                AppResult.Success(null),
                f.container.koin.get<SessionRepository>().current(),
            )
        } finally {
            f.close()
        }
    }

    private class Fixture(project: String = "cmp-fajar") {
        private val directory = Files.createTempDirectory("firebase-login-bindings").toFile()
        private val views = ViewModelStore()
        private val id = "11111111-1111-4111-8111-111111111111"
        val paths = Collections.synchronizedList(mutableListOf<String>())
        var googleDisposed = false
        private var googleClosed = false
        private val google =
            object : GoogleCredentialSource {
                override val supported = true

                override suspend fun open(webClientId: String) =
                    object : GoogleCredentialSession {
                        override suspend fun idToken() = "google-proof".repeat(5)

                        override fun close() {
                            googleDisposed = true
                        }
                    }

                override fun close() {
                    googleClosed = true
                }
            }
        private val network = module {
            for (qualifier in
                listOf(HttpClients.Public, HttpClients.Authenticated, HttpClients.Oidc)) {
                single<HttpClient>(named(qualifier)) { client() }.onClose { it?.cancelAndClose() }
            }
        }
        private val configuration =
            """{"enabled":true,"projectId":"$project","providers":["password","google.com"]}"""
        val container =
            createAppContainer(
                    createUserPreferences(directory),
                    createAppDatabase(directory),
                    module { single<PushTokenSource> { UnavailablePushTokenSource() } },
                    firebaseOptions =
                        FirebaseClientOptionsSource {
                            FirebaseClientOptions(
                                "cmp-fajar",
                                "A".repeat(39),
                                "123456-abcdef.apps.googleusercontent.com",
                            )
                        },
                    googleCredentials = google,
                    http = network,
                )
                .also {
                    it.koin.loadModules(
                        listOf(
                            module {
                                single<HttpClient>(named(HttpClients.FirebaseAuth)) { client() }
                                    .onClose { client -> client?.cancelAndClose() }
                            }
                        )
                    )
                }

        private fun client() =
            createHttpClient(
                MockEngine { request ->
                    val path = request.url.encodedPath
                    paths += path
                    val body =
                        when {
                            path.endsWith("/auth/native/firebase") -> configuration
                            path.endsWith("/auth/native/firebase/login") ->
                                """{"account":{"id":"$id","email":"fixture@example.test","displayName":"Fixture","mfaConfigured":false},"credentials":{"sessionId":"$id","accessToken":"hris-access","refreshToken":"hris-refresh","accessExpiresAt":"2099-10-28T01:00:00Z","sessionExpiresAt":"2099-11-28T00:00:00Z","tokenType":"Bearer"}}"""
                            path.endsWith("accounts:signInWithPassword") ->
                                """{"idToken":"${"firebase-proof".repeat(5)}"}"""
                            path.endsWith("accounts:signInWithIdp") -> {
                                assertTrue(googleDisposed)
                                """{"idToken":"${"firebase-proof".repeat(5)}"}"""
                            }
                            else -> error("Unexpected Firebase fixture path")
                        }
                    respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
                "https://hris.invalid/api/v1/",
            )

        suspend fun login(): LoginViewModel {
            val vm =
                withContext(Dispatchers.Main.immediate) {
                    container.koin.get<LoginViewModel>().also { views.put("login", it) }
                }
            withTimeout(10_000) {
                vm.state.first { !it.optionsLoading && (it.options != null || it.failure != null) }
            }
            return vm
        }

        suspend fun close() {
            try {
                withContext(Dispatchers.Main.immediate) {
                    views.clear()
                    container.close()
                }
            } finally {
                directory.deleteRecursively()
            }
            assertTrue(googleClosed)
        }
    }
}
