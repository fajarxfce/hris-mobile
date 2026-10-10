@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.domain.sso.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.identity.domain.sso.repositories.SsoRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Provider availability, interactive timeout, and session publication are application policy. */
class SignInWithSso(
    private val sso: SsoRepository,
    private val identity: IdentityRepository,
    private val sessions: SessionRepository,
    private val timeoutMillis: Long = 120_000,
) {
    private val operation = Mutex()

    suspend operator fun invoke(providerId: String): AppResult<User> =
        operation.withLock {
            val previous =
                when (val result = sessions.current()) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            if (previous != null)
                return@withLock AppResult.Failed(
                    Failure(FailureKind.Validation, "Sign out before changing accounts.")
                )
            val providers =
                when (val result = sso.providers()) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            val provider =
                providers.firstOrNull { it.id == providerId }
                    ?: return@withLock AppResult.Failed(
                        Failure(FailureKind.Validation, "This sign-in method is unavailable.")
                    )
            val proof =
                when (val result = withTimeoutOrNull(timeoutMillis) { sso.authorize(provider) }) {
                    null ->
                        return@withLock AppResult.Failed(
                            Failure(FailureKind.Timeout, "The sign-in window expired. Try again.")
                        )
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            val current =
                when (val result = sessions.current()) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            if (current != null)
                return@withLock AppResult.Failed(
                    Failure(FailureKind.Unavailable, "The session changed. Sign in again.")
                )
            val authenticated =
                when (val result = identity.completeSso(proof)) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            val session =
                Session(Uuid.random().toString(), authenticated.user, authenticated.tokens)
            when (val result = sessions.compareAndSet(previous, session)) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    if (result.value) AppResult.Success(authenticated.user)
                    else
                        AppResult.Failed(
                            Failure(FailureKind.Unavailable, "The session changed. Sign in again.")
                        )
            }
        }
}
