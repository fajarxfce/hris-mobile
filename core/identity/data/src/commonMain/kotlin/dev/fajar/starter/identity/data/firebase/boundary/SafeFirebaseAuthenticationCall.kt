package dev.fajar.starter.identity.data.firebase.boundary

import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.mapHttpFailure
import kotlinx.coroutines.*

class FirebaseProviderException(val status: Int, val providerCode: String?) :
    Exception("Firebase authentication was rejected")

class GoogleCredentialCancelled(cause: Exception? = null) :
    Exception("Google sign-in was cancelled", cause)

class GoogleCredentialUnavailable(cause: Exception? = null) :
    Exception("Google sign-in is unavailable", cause)

/** Maps sanitized protocol/SDK categories; never retains provider messages or credential bodies. */
suspend fun <T> safeFirebaseAuthenticationCall(operation: suspend () -> T): AppResult<T> =
    try {
        currentCoroutineContext().ensureActive()
        val result = operation()
        currentCoroutineContext().ensureActive()
        AppResult.Success(result)
    } catch (error: Exception) {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
        causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
        currentCoroutineContext().ensureActive()
        val failure =
            when (error) {
                is GoogleCredentialCancelled ->
                    Failure(FailureKind.Cancelled, "Sign-in was cancelled.")
                is GoogleCredentialUnavailable,
                is UnsupportedOperationException -> unavailable()
                is FirebaseProviderException ->
                    when {
                        error.status == 429 ||
                            error.providerCode == "TOO_MANY_ATTEMPTS_TRY_LATER" ->
                            Failure(
                                FailureKind.Service,
                                "Too many attempts. Try again later.",
                                code = "sign_in_rate_limited",
                            )
                        error.status >= 500 ||
                            error.providerCode in
                                setOf(
                                    "CONFIGURATION_NOT_FOUND",
                                    "OPERATION_NOT_ALLOWED",
                                    "API_KEY_INVALID",
                                    "PROJECT_NOT_FOUND",
                                    "INVALID_APP_CREDENTIAL",
                                    "MISSING_APP_CREDENTIAL",
                                ) -> unavailable()
                        else ->
                            Failure(
                                FailureKind.Unauthorized,
                                "Sign-in was not accepted. Check your account and try again.",
                                code = "firebase_sign_in_failed",
                            )
                    }
                else -> mapHttpFailure(error)
            }
        AppResult.Failed(failure)
    }

private fun unavailable() =
    Failure(
        FailureKind.Unavailable,
        "This sign-in method is unavailable.",
        code = "identity.firebase_unavailable",
    )
