@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.identity.domain.push.usecases

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.identity.domain.push.repositories.NativePushStateRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.domain.repositories.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*

/** Emits invalidations only; raw SDK tokens never leave the identity policy boundary. */
class ObserveNativePushTokenChanges(
    private val sessions: SessionRepository,
    private val storage: NativePushStateRepository,
    private val tokens: PushTokenRepository,
    private val permissions: NotificationAccessRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    operator fun invoke(sessionId: String): Flow<AppResult<Unit>> {
        if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios)) return emptyFlow()
        return combine(sessions.observe(), storage.observe()) { session, state -> session to state }
            .flatMapLatest { (sessionResult, stored) ->
                flow {
                    if (sessionResult is AppResult.Failed) {
                        emit(sessionResult)
                        return@flow
                    }
                    if (stored is AppResult.Failed) {
                        emit(stored)
                        return@flow
                    }
                    val owner =
                        (sessionResult as AppResult.Success).value?.pushOwner() ?: return@flow
                    val state = (stored as AppResult.Success).value
                    if (
                        owner.localSessionId != sessionId ||
                            owner.audience != server.audience ||
                            state.owner != owner ||
                            !state.desired ||
                            state.token == null ||
                            state.pending != null ||
                            state.retiringToken ||
                            state.registration?.enabled != true
                    )
                        return@flow
                    when (val access = permissions.check()) {
                        is AppResult.Failed -> {
                            emit(access)
                            return@flow
                        }
                        is AppResult.Success ->
                            if (access.value != NotificationAccess.Granted) return@flow
                    }
                    tokens.observeTokens().collect { token ->
                        currentCoroutineContext().ensureActive()
                        val currentSession = sessions.current()
                        val currentState = storage.read()
                        if (
                            currentSession !is AppResult.Success ||
                                currentSession.value?.pushOwner() != owner ||
                                currentState !is AppResult.Success ||
                                currentState.value != state
                        )
                            return@collect
                        when (token) {
                            is AppResult.Failed -> emit(token)
                            is AppResult.Success ->
                                if (token.value != state.token) emit(AppResult.Success(Unit))
                        }
                    }
                }
            }
    }
}
