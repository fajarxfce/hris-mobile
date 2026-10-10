package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.SessionTokens
import dev.fajar.starter.identity.domain.repositories.*
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One instance per container coalesces refreshes and never reauthenticates a different session. */
class AcquireSessionTokens(
    private val identity: IdentityRepository,
    private val sessions: SessionRepository,
    private val clock: Clock = Clock.System,
) {
    private val refresh = Mutex()

    suspend operator fun invoke(
        sessionId: String,
        rejectedAccessToken: String? = null,
    ): AppResult<SessionTokens?> =
        refresh.withLock {
            val session =
                when (val result = sessions.current()) {
                    is AppResult.Failed -> return@withLock result
                    is AppResult.Success -> result.value
                }
            if (session == null || session.id != sessionId) return@withLock AppResult.Success(null)
            val expired =
                session.tokens.expiresAtEpochMillis <= clock.now().toEpochMilliseconds() + 30_000
            if (
                !expired &&
                    (rejectedAccessToken == null ||
                        rejectedAccessToken != session.tokens.accessToken)
            ) {
                return@withLock AppResult.Success(session.tokens)
            }
            val authenticated =
                when (val result = identity.refresh(session.tokens.refreshToken)) {
                    is AppResult.Success -> result.value
                    is AppResult.Failed -> {
                        if (result.failure.kind == FailureKind.Unauthorized) {
                            when (val cleared = sessions.compareAndSet(session, null)) {
                                is AppResult.Failed -> return@withLock cleared
                                is AppResult.Success -> Unit
                            }
                        }
                        return@withLock result
                    }
                }
            currentCoroutineContext().ensureActive()
            if (authenticated.user.id != session.user.id)
                return@withLock AppResult.Failed(
                    Failure(
                        FailureKind.Unexpected,
                        "The refreshed session belongs to a different account.",
                    )
                )
            val updated = session.copy(user = authenticated.user, tokens = authenticated.tokens)
            when (val committed = sessions.compareAndSet(session, updated)) {
                is AppResult.Failed -> committed
                is AppResult.Success ->
                    AppResult.Success(if (committed.value) updated.tokens else null)
            }
        }
}
