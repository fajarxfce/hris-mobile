package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.*
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The caller acquires tokens first. This operation never refreshes or retries a proof itself. */
class BeginMfaEnrollment(
    private val sessions: SessionRepository,
    private val mfa: MfaRepository,
    private val server: IdentityServer,
    private val mutations: Mutex,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(sessionId: String, operationId: String): AppResult<MfaEnrollment> =
        mutations.withLock {
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
            val enrollment =
                when (val result = mfa.begin(session.tokens.accessToken, operationId)) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            val current =
                when (val result = sessions.current()) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            if (current != session)
                return@withLock AppResult.Failed(
                    Failure(
                        FailureKind.Unauthorized,
                        "Sign in again.",
                        code = "client.session_changed",
                    )
                )
            if (
                enrollment.operationId != operationId ||
                    enrollment.expiresAtEpochMillis <= clock.now().toEpochMilliseconds()
            )
                return@withLock AppResult.Failed(
                    Failure(
                        FailureKind.Validation,
                        "Set up the authenticator again.",
                        code = "mfa_enrollment_expired",
                    )
                )
            AppResult.Success(enrollment)
        }
}
