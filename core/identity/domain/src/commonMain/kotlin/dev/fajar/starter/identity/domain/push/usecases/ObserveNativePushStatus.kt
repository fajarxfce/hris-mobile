package dev.fajar.starter.identity.domain.push.usecases

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.identity.domain.push.repositories.NativePushStateRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.domain.repositories.NotificationAccessRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*

/** Local registration facts remain readable while credentials or the network are unavailable. */
class ObserveNativePushStatus(
    private val sessions: SessionRepository,
    private val storage: NativePushStateRepository,
    private val permissions: NotificationAccessRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    operator fun invoke(sessionId: String?): Flow<AppResult<NativePushStatus>> {
        if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios))
            return flowOf(
                AppResult.Success(
                    NativePushStatus(
                        false,
                        false,
                        false,
                        false,
                        NotificationAccess.Unavailable,
                        null,
                    )
                )
            )
        return combine(sessions.observe(), storage.observe()) { sessionResult, stored ->
                if (sessionResult is AppResult.Failed) return@combine sessionResult
                if (stored is AppResult.Failed) return@combine stored
                val owner = (sessionResult as AppResult.Success).value?.pushOwner()
                if (
                    owner?.localSessionId != sessionId ||
                        (owner != null && owner.audience != server.audience)
                )
                    return@combine changed()
                val access =
                    when (val result = permissions.check()) {
                        is AppResult.Failed -> return@combine result
                        is AppResult.Success -> result.value
                    }
                currentCoroutineContext().ensureActive()
                when (val current = sessions.current()) {
                    is AppResult.Failed -> return@combine current
                    is AppResult.Success ->
                        if (current.value?.pushOwner() != owner) return@combine changed()
                }
                val state = (stored as AppResult.Success).value
                val owned = state.owner == owner && owner != null
                val registration = state.registration?.takeIf { owned }
                AppResult.Success(
                    NativePushStatus(
                        supported = true,
                        enabled = owned && state.desired,
                        registered =
                            registration?.enabled == true &&
                                registration.expiresAt > clock.now() &&
                                !state.retiringToken &&
                                access == NotificationAccess.Granted,
                        pending =
                            (owned && state.pending != null) ||
                                state.retiringToken ||
                                state.revocations.isNotEmpty(),
                        access = access,
                        expiresAt = registration?.expiresAt,
                    )
                )
            }
            .distinctUntilChanged()
    }

    private fun changed() =
        AppResult.Failed(
            Failure(FailureKind.Unauthorized, "Sign in again.", code = "client.session_changed")
        )
}
