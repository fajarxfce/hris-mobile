package dev.fajar.starter.identity.domain.firebase.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.firebase.entities.*
import dev.fajar.starter.identity.domain.firebase.repositories.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** A configured provider never falls back to a different password authority after a failed read. */
class LoadFirebaseSignInOptions(
    private val firebase: FirebaseAuthenticationRepository,
    private val native: NativeFirebaseSessionRepository,
) {
    suspend operator fun invoke(): AppResult<FirebaseSignInOptions> =
        withTimeoutOrNull(15_000) { load() }
            ?: AppResult.Failed(
                Failure(
                    FailureKind.Timeout,
                    "Sign-in options could not be loaded.",
                    code = "identity.firebase_unavailable",
                )
            )

    private suspend fun load(): AppResult<FirebaseSignInOptions> {
        val local =
            when (val result = firebase.configuration()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        if (!local.enabled) return AppResult.Success(FirebaseSignInOptions())
        val remote =
            when (val result = native.configuration()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        if (!remote.enabled) return AppResult.Success(FirebaseSignInOptions())
        if (local.projectId == null || local.projectId != remote.projectId)
            return AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "This sign-in method is unavailable.",
                    code = "identity.firebase_unavailable",
                )
            )
        val available = local.providers.intersect(remote.providers)
        if (available.isEmpty())
            return AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "This sign-in method is unavailable.",
                    code = "identity.firebase_unavailable",
                )
            )
        val email = FirebaseSignInProvider.EmailPassword in available
        return AppResult.Success(
            FirebaseSignInOptions(email, FirebaseSignInProvider.Google in available, email)
        )
    }
}
