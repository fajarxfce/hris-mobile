package dev.fajar.starter.identity.data.firebase.repositories

import dev.fajar.starter.identity.data.firebase.boundary.*
import dev.fajar.starter.identity.data.firebase.datasources.*
import dev.fajar.starter.identity.data.firebase.dto.FirebasePasswordRequest
import dev.fajar.starter.identity.data.firebase.dto.GoogleCredentialResult
import dev.fajar.starter.identity.data.firebase.mappers.requireGoogleCredentialNonce
import dev.fajar.starter.identity.data.firebase.mappers.validated
import dev.fajar.starter.identity.domain.firebase.entities.*
import dev.fajar.starter.identity.domain.firebase.repositories.FirebaseAuthenticationRepository
import kotlinx.coroutines.*
import org.koin.core.annotation.Single

@Single
class InstalledFirebaseAuthenticationRepository(
    private val options: FirebaseClientOptionsSource,
    private val google: GoogleCredentialSource,
    private val remote: Lazy<FirebaseProviderRemoteSource>,
) : FirebaseAuthenticationRepository {
    override suspend fun configuration() = safeFirebaseAuthenticationCall {
        val config = options.load()?.validated()
        if (config == null) FirebaseSignInConfiguration(false, null, emptySet())
        else
            FirebaseSignInConfiguration(
                true,
                config.projectId,
                buildSet {
                    add(FirebaseSignInProvider.EmailPassword)
                    if (google.supported && config.googleClientId != null)
                        add(FirebaseSignInProvider.Google)
                },
            )
    }

    override suspend fun authenticate(input: FirebaseSignInInput) = safeFirebaseAuthenticationCall {
        val config =
            options.load()?.validated()
                ?: throw UnsupportedOperationException("Firebase is unavailable")
        currentCoroutineContext().ensureActive()
        val result =
            when (input) {
                is FirebaseSignInInput.EmailPassword ->
                    remote.value.password(
                        config,
                        FirebasePasswordRequest(input.email, input.password),
                    )
                FirebaseSignInInput.Google -> {
                    val clientId = config.googleClientId ?: throw GoogleCredentialUnavailable()
                    val credential =
                        withContext(Dispatchers.Main.immediate) {
                            if (!google.supported) throw GoogleCredentialUnavailable()
                            google.open(clientId).use { it.credential() }
                        }
                    currentCoroutineContext().ensureActive()
                    val token =
                        when (credential) {
                            is GoogleCredentialResult.IdToken -> credential.value
                            is GoogleCredentialResult.AuthorizationCode ->
                                remote.value.googleCode(clientId, credential).also {
                                    currentCoroutineContext().ensureActive()
                                    requireGoogleCredentialNonce(it, clientId, credential.nonce)
                                }
                        }
                    currentCoroutineContext().ensureActive()
                    remote.value.google(config, token)
                }
            }
        currentCoroutineContext().ensureActive()
        if (result.needConfirmation)
            throw FirebaseProviderException(400, "ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL")
        val token = requireNotNull(result.idToken)
        require(token.length in 32..8192 && token.none(Char::isWhitespace))
        FirebaseSignInProof(config.projectId, input.provider, token)
    }
}
