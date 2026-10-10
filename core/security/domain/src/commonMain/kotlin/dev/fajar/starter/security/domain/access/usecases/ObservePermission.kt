package dev.fajar.starter.security.domain.access.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.security.domain.access.entities.Permission
import dev.fajar.starter.security.domain.access.policy.allows
import dev.fajar.starter.security.domain.access.repositories.AccessRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

class ObservePermission(
    private val sessions: SessionRepository,
    private val access: AccessRepository,
    private val clock: Clock = Clock.System,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(permission: Permission): Flow<AppResult<Boolean>> =
        sessions
            .observe()
            .flatMapLatest { sessionResult ->
                when (sessionResult) {
                    is AppResult.Failed -> flowOf<AppResult<Boolean>>(sessionResult)
                    is AppResult.Success -> {
                        val session = sessionResult.value
                        if (session == null) flowOf(AppResult.Success(false))
                        else
                            access.observe(session.id).flatMapLatest { result ->
                                flow<AppResult<Boolean>> {
                                    when (result) {
                                        is AppResult.Failed -> emit(result)
                                        is AppResult.Success -> {
                                            val now = clock.now().toEpochMilliseconds()
                                            val granted =
                                                allows(result.value, permission, session.id, now)
                                            emit(AppResult.Success(granted))
                                            if (granted) {
                                                delay(
                                                    requireNotNull(result.value)
                                                        .expiresAtEpochMillis - now
                                                )
                                                emit(AppResult.Success(false))
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
}
