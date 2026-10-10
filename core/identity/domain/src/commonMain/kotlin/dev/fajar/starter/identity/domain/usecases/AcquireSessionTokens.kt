@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.SessionTokens
import dev.fajar.starter.identity.domain.repositories.*
import kotlin.time.Clock
import kotlin.uuid.Uuid
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
                session.pendingRefreshOperationId == null &&
                    !expired &&
                    (rejectedAccessToken == null ||
                        rejectedAccessToken != session.tokens.accessToken)
            ) {
                return@withLock AppResult.Success(session.tokens)
            }
            val prepared =
                if (session.pendingRefreshOperationId == null) {
                    val updated = session.copy(pendingRefreshOperationId = Uuid.random().toString())
                    when (val result = sessions.compareAndSet(session, updated)) {
                        is AppResult.Failed -> return@withLock result
                        is AppResult.Success ->
                            if (!result.value) return@withLock AppResult.Success(null)
                    }
                    updated
                } else session
            // Persist intent before I/O. Cancellation or a lost reply leaves the same operation
            // available after restart; the backend decides whether its replay window is valid.
            currentCoroutineContext().ensureActive()
            val authenticated =
                when (
                    val result =
                        identity.refresh(
                            prepared,
                            requireNotNull(prepared.pendingRefreshOperationId),
                        )
                ) {
                    is AppResult.Success -> result.value
                    is AppResult.Failed -> {
                        if (result.failure.kind == FailureKind.Unauthorized) {
                            when (val cleared = sessions.compareAndSet(prepared, null)) {
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
            val updated =
                prepared.copy(
                    user = authenticated.user,
                    tokens = authenticated.tokens,
                    pendingRefreshOperationId = null,
                )
            when (val committed = sessions.compareAndSet(prepared, updated)) {
                is AppResult.Failed -> committed
                is AppResult.Success ->
                    AppResult.Success(if (committed.value) updated.tokens else null)
            }
        }
}
