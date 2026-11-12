package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Explicit fictional login input; product authentication and server revocation are not simulated.
 */
internal class HrisProofSessionRepository(initial: Session) : SessionRepository {
    private val state = MutableStateFlow<AppResult<Session?>>(AppResult.Success(initial))
    override val persistent = true

    override fun observe() = state.asStateFlow()

    override suspend fun current() = state.value

    fun replace(session: Session) {
        state.value = AppResult.Success(session)
    }

    override suspend fun compareAndSet(expected: Session?, updated: Session?): AppResult<Boolean> =
        error("No login mutation in sender proof")

    override suspend fun clear(sessionId: String): AppResult<Boolean> =
        error("No logout in sender proof")
}
