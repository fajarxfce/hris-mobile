@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.domain.firebase.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.firebase.entities.*
import dev.fajar.starter.identity.domain.firebase.policies.normalizedFirebaseSignIn
import dev.fajar.starter.identity.domain.firebase.repositories.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Provider availability and publication belong to the application, not SDK callbacks. */
class SignInWithFirebase(
    private val firebase: FirebaseAuthenticationRepository,
    private val native: NativeFirebaseSessionRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val clock: Clock = Clock.System,
    private val timeoutMillis: Long = 120_000,
) {
    private val operation = Mutex()

    suspend operator fun invoke(input: FirebaseSignInInput, operationId: String): AppResult<User> =
        withTimeoutOrNull(timeoutMillis) { operation.withLock { signIn(input, operationId) } }
            ?: AppResult.Failed(
                Failure(
                    FailureKind.Timeout,
                    "Sign-in timed out. Try again.",
                    code = "identity.firebase_timeout",
                )
            )

    private suspend fun signIn(input: FirebaseSignInInput, operationId: String): AppResult<User> {
        currentCoroutineContext().ensureActive()
        if (
            Uuid.parseOrNull(operationId) == null ||
                server.deviceName.isBlank() ||
                server.deviceName.length > 100
        )
            return unavailable()
        val normalized =
            when (val result = normalizedFirebaseSignIn(input)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        when (val current = sessions.current()) {
            is AppResult.Failed -> return current
            is AppResult.Success -> if (current.value != null) return changed()
        }
        currentCoroutineContext().ensureActive()
        val local =
            when (val result = firebase.configuration()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        if (
            !local.enabled ||
                local.projectId?.matches(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")) != true ||
                normalized.provider !in local.providers
        )
            return unavailable()
        val remote =
            when (val result = native.configuration()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        if (
            !remote.enabled ||
                remote.projectId != local.projectId ||
                normalized.provider !in remote.providers
        )
            return unavailable()
        when (val current = sessions.current()) {
            is AppResult.Failed -> return current
            is AppResult.Success -> if (current.value != null) return changed()
        }
        currentCoroutineContext().ensureActive()
        val proof =
            when (val result = firebase.authenticate(normalized)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        if (
            proof.projectId != local.projectId ||
                proof.provider != normalized.provider ||
                proof.idToken.length !in 32..8192 ||
                proof.idToken.any(Char::isWhitespace)
        )
            return unavailable()
        when (val current = sessions.current()) {
            is AppResult.Failed -> return current
            is AppResult.Success -> if (current.value != null) return changed()
        }
        currentCoroutineContext().ensureActive()
        val authenticated =
            when (val result = native.exchange(proof.idToken, server.deviceName, operationId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val now = clock.now().toEpochMilliseconds()
        if (
            authenticated.binding.audience != server.audience ||
                authenticated.binding.expiresAtEpochMillis <= now ||
                authenticated.tokens.expiresAtEpochMillis <= now
        )
            return AppResult.Failed(
                Failure(
                    FailureKind.Unauthorized,
                    "Sign in again.",
                    code = "identity.session_expired",
                )
            )
        val session =
            Session(
                Uuid.random().toString(),
                authenticated.user,
                authenticated.tokens,
                authenticated.binding,
            )
        return when (val result = sessions.compareAndSet(null, session)) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                if (result.value) AppResult.Success(authenticated.user) else changed()
        }
    }

    private fun unavailable() =
        AppResult.Failed(
            Failure(
                FailureKind.Unavailable,
                "This sign-in method is unavailable.",
                code = "identity.firebase_unavailable",
            )
        )

    private fun changed() =
        AppResult.Failed(
            Failure(
                FailureKind.Unavailable,
                "The session changed. Sign in again.",
                code = "identity.session_changed",
            )
        )
}
