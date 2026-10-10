@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.identity.domain.validation.signInViolations
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SignIn(
    private val identity: IdentityRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(
        email: String,
        password: String,
        operationId: String,
    ): AppResult<User> {
        val normalizedEmail = email.trim().lowercase()
        val violations = signInViolations(normalizedEmail, password)
        if (violations.isNotEmpty())
            return AppResult.Failed(
                Failure(
                    FailureKind.Validation,
                    "Check the information and try again.",
                    violations.keys.first(),
                    violations,
                )
            )
        val previous =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (previous != null)
            return AppResult.Failed(
                Failure(FailureKind.Validation, "Sign out before changing accounts.")
            )
        val authenticated =
            when (
                val result =
                    identity.signIn(normalizedEmail, password, server.deviceName, operationId)
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        if (
            authenticated.binding.audience != server.audience ||
                authenticated.binding.expiresAtEpochMillis <= clock.now().toEpochMilliseconds()
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
        return when (val committed = sessions.compareAndSet(previous, session)) {
            is AppResult.Failed -> committed
            is AppResult.Success ->
                if (committed.value) AppResult.Success(authenticated.user)
                else
                    AppResult.Failed(
                        Failure(FailureKind.Unavailable, "The session changed. Sign in again.")
                    )
        }
    }
}
