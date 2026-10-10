package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.*
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConfirmMfaEnrollment(
    private val sessions: SessionRepository,
    private val mfa: MfaRepository,
    private val server: IdentityServer,
    private val mutations: Mutex,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(
        sessionId: String,
        operationId: String,
        enrollmentId: String,
        code: String,
    ): AppResult<List<String>> =
        mutations.withLock {
            if (!code.matches(Regex("[0-9]{6}")))
                return@withLock AppResult.Failed(
                    Failure(
                        FailureKind.Validation,
                        "Enter the authenticator code.",
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
            val confirmed =
                when (val result = mfa.confirm(session, operationId, enrollmentId, code)) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            currentCoroutineContext().ensureActive()
            if (
                confirmed.authenticated.user.id != session.user.id ||
                    confirmed.authenticated.binding != session.binding
            )
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
                        session.copy(
                            tokens = confirmed.authenticated.tokens,
                            pendingRefreshOperationId = null,
                        ),
                    )
            ) {
                is AppResult.Failed -> committed
                is AppResult.Success ->
                    if (committed.value) AppResult.Success(confirmed.recoveryCodes)
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
