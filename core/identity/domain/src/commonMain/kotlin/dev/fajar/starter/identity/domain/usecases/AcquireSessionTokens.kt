@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.entities.SessionBinding
import dev.fajar.starter.identity.domain.entities.SessionTokens
import dev.fajar.starter.identity.domain.repositories.*
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One instance per container coalesces refreshes and never reauthenticates a different session. */
class AcquireSessionTokens(
    private val identity: IdentityRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val clock: Clock = Clock.System,
    private val refresh: Mutex = Mutex(),
) {

    /**
     * A live transport owner keeps its parent access current without making probe requests. Each
     * advancing credential deadline schedules one attempt. Failed attempts retain the durable
     * refresh operation; observation alone never retries the same revision. Refresh stays in the
     * collector so persisting its own operation cannot cancel the in-flight exchange.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun maintain(sessionId: String): Flow<AppResult<SessionTokens?>> = flow {
        var attemptedThrough = Long.MIN_VALUE
        sessions
            .observe()
            .map { observed ->
                when (observed) {
                    is AppResult.Failed -> observed
                    is AppResult.Success ->
                        AppResult.Success(
                            observed.value
                                ?.takeIf { it.id == sessionId }
                                ?.let { RenewalObservation(it.tokens, it.binding) }
                        )
                }
            }
            .distinctUntilChanged()
            .transformLatest { observed ->
                when (observed) {
                    is AppResult.Failed -> emit(observed)
                    is AppResult.Success -> {
                        val current = observed.value ?: return@transformLatest
                        if (
                            current.binding.audience != server.audience ||
                                current.binding.expiresAtEpochMillis <=
                                    clock.now().toEpochMilliseconds()
                        )
                            return@transformLatest
                        val due = current.tokens.expiresAtEpochMillis - REFRESH_MARGIN_MILLIS
                        delay((due - clock.now().toEpochMilliseconds()).coerceAtLeast(0))
                        emit(AppResult.Success(current.tokens.expiresAtEpochMillis))
                    }
                }
            }
            .collect { due ->
                when (due) {
                    is AppResult.Failed -> emit(due)
                    is AppResult.Success -> {
                        if (due.value <= attemptedThrough) return@collect
                        val result = invoke(sessionId)
                        // Keep returned credentials, including a short-lived or clock-skewed
                        // response, without turning its publication into another immediate retry.
                        attemptedThrough =
                            maxOf(
                                attemptedThrough,
                                due.value,
                                clock.now().toEpochMilliseconds() + REFRESH_MARGIN_MILLIS,
                            )
                        emit(result)
                    }
                }
            }
    }

    private data class RenewalObservation(val tokens: SessionTokens, val binding: SessionBinding)

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
            if (session.binding.audience != server.audience) {
                return@withLock when (val cleared = sessions.compareAndSet(session, null)) {
                    is AppResult.Failed -> cleared
                    is AppResult.Success -> AppResult.Success(null)
                }
            }
            if (session.binding.expiresAtEpochMillis <= clock.now().toEpochMilliseconds())
                return@withLock AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Sign in to synchronize changes.",
                        code = "client.authentication_required",
                    )
                )
            val expired =
                session.tokens.expiresAtEpochMillis <=
                    clock.now().toEpochMilliseconds() + REFRESH_MARGIN_MILLIS
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
            if (
                authenticated.user.id != session.user.id || authenticated.binding != session.binding
            )
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

    private companion object {
        const val REFRESH_MARGIN_MILLIS = 30_000L
    }
}
