package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.*
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VerifyMfa(
    private val sessions: SessionRepository,
    private val mfa: MfaRepository,
    private val server: IdentityServer,
    private val mutations: Mutex,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(
        sessionId: String,
        operationId: String,
        code: String,
        recovery: Boolean,
    ): AppResult<Unit> =
        mutations.withLock {
            val normalized = code.trim()
            if (
                normalized.length !in 1..64 || (!recovery && !normalized.matches(Regex("[0-9]{6}")))
            )
                return@withLock AppResult.Failed(
                    Failure(
                        FailureKind.Validation,
                        "Enter a valid verification code.",
                        code = "invalid_mfa_code",
                    )
                )
            val session =
                when (val result = sessions.current()) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            if (
                session?.id != sessionId ||
                    session.binding.audience != server.audience ||
                    session.pendingRefreshOperationId != null ||
                    session.binding.expiresAtEpochMillis <= clock.now().toEpochMilliseconds() ||
                    session.tokens.expiresAtEpochMillis <= clock.now().toEpochMilliseconds()
            )
                return@withLock AppResult.Failed(
                    Failure(
                        FailureKind.Unauthorized,
                        "Sign in again.",
                        code = "client.session_changed",
                    )
                )
            val verified =
                when (val result = mfa.verify(session, operationId, normalized, recovery)) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            if (verified.user.id != session.user.id || verified.binding != session.binding)
                return@withLock AppResult.Failed(
                    Failure(
                        FailureKind.Unexpected,
                        "Verification could not be confirmed.",
                        code = "client.invalid_session_response",
                    )
                )
            when (
                val committed =
                    sessions.compareAndSet(
                        session,
                        session.copy(tokens = verified.tokens, pendingRefreshOperationId = null),
                    )
            ) {
                is AppResult.Failed -> committed
                is AppResult.Success ->
                    if (committed.value) AppResult.Success(Unit)
                    else
                        AppResult.Failed(
                            Failure(
                                FailureKind.Unauthorized,
                                "Sign in again.",
                                code = "client.session_changed",
                            )
                        )
            }
        }
}
