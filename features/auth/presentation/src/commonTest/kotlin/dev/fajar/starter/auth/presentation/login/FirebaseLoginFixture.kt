package dev.fajar.starter.auth.presentation.login

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.firebase.entities.*
import dev.fajar.starter.identity.domain.firebase.repositories.*
import dev.fajar.starter.identity.domain.firebase.usecases.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.identity.domain.sso.entities.*
import dev.fajar.starter.identity.domain.sso.repositories.SsoRepository
import dev.fajar.starter.identity.domain.sso.usecases.*
import dev.fajar.starter.identity.domain.usecases.*

internal class FirebaseLoginFixture(
    val sessions: TestSessions = TestSessions(),
    enabled: Boolean = true,
) {
    private val server = IdentityServer("https://example.invalid/", "Test device")
    private val config =
        FirebaseSignInConfiguration(enabled, "cmp-fajar", FirebaseSignInProvider.entries.toSet())
    var remoteConfiguration = config
    var configurationFailure: AppResult.Failed? = null
    var exchangeFailure: AppResult.Failed? = null
    var configurationHook: suspend () -> Unit = {}
    var providerHook: suspend () -> Unit = {}
    var legacyCalls = 0
    val providerInputs = mutableListOf<FirebaseSignInInput>()
    val operations = mutableListOf<String>()
    private val authenticated =
        testSession().let { AuthenticatedUser(it.user, it.tokens, it.binding) }
    private val firebase =
        object : FirebaseAuthenticationRepository {
            override suspend fun configuration() = AppResult.Success(config)

            override suspend fun authenticate(
                input: FirebaseSignInInput
            ): AppResult<FirebaseSignInProof> {
                providerInputs += input
                providerHook()
                return AppResult.Success(
                    FirebaseSignInProof("cmp-fajar", input.provider, "fixture-proof".repeat(5))
                )
            }
        }
    private val native =
        object : NativeFirebaseSessionRepository {
            override suspend fun configuration(): AppResult<FirebaseSignInConfiguration> {
                val result = configurationFailure ?: AppResult.Success(remoteConfiguration)
                configurationHook()
                return result
            }

            override suspend fun exchange(
                idToken: String,
                deviceName: String,
                operationId: String,
            ): AppResult<AuthenticatedUser> {
                operations += operationId
                return exchangeFailure ?: AppResult.Success(authenticated)
            }
        }
    val options = LoadFirebaseSignInOptions(firebase, native)
    val signIn = SignInWithFirebase(firebase, native, sessions, server)

    fun viewModel(): LoginViewModel {
        val legacy =
            object : IdentityRepository {
                override suspend fun completeSso(proof: SsoProof): AppResult<AuthenticatedUser> =
                    error("Unused")

                override suspend fun refresh(
                    session: Session,
                    operationId: String,
                ): AppResult<AuthenticatedUser> = error("Unused")

                override suspend fun signIn(
                    email: String,
                    password: String,
                    deviceName: String,
                    operationId: String,
                ): AppResult<AuthenticatedUser> {
                    legacyCalls++
                    return AppResult.Success(authenticated)
                }
            }
        val sso =
            object : SsoRepository {
                override suspend fun providers() = AppResult.Success(emptyList<SsoProvider>())

                override suspend fun authorize(provider: SsoProvider): AppResult<SsoProof> =
                    error("Unused")
            }
        return LoginViewModel(
            SignIn(legacy, sessions, server),
            ValidateSignIn(),
            ListSsoProviders(sso),
            SignInWithSso(sso, legacy, sessions),
            options,
            signIn,
        )
    }
}
