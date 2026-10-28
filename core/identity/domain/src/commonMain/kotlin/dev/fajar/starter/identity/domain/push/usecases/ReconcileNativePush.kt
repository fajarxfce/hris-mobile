@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.identity.domain.push.usecases

import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.push.entities.*
import dev.fajar.starter.identity.domain.push.repositories.*
import dev.fajar.starter.identity.domain.repositories.*
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.domain.repositories.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One finite reconciliation owns SDK rotation, frozen transport and receipt recovery. */
class ReconcileNativePush(
    private val sessions: SessionRepository,
    private val registrations: NativePushRegistrationRepository,
    private val storage: NativePushStateRepository,
    private val tokens: PushTokenRepository,
    private val permissions: NotificationAccessRepository,
    private val devices: DeviceSessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val mutations: Mutex,
) {
    suspend operator fun invoke(
        sessionId: String?,
        action: NativePushAction = NativePushAction.Refresh,
    ): AppResult<NativePushStatus> {
        val platform =
            when (build.platform) {
                AppPlatform.Android -> NativePushPlatform.ANDROID
                AppPlatform.Ios -> NativePushPlatform.IOS
                else ->
                    return AppResult.Success(
                        NativePushStatus(
                            false,
                            false,
                            false,
                            false,
                            NotificationAccess.Unavailable,
                            null,
                        )
                    )
            }
        return try {
            withTimeoutOrNull(30.seconds) {
                mutations.withLock { Execution(sessionId, action, platform).run() }
            } ?: pushFailure("client.push_timeout", FailureKind.Timeout)
        } catch (stopped: Stop) {
            AppResult.Failed(stopped.failure)
        }
    }

    private inner class Execution(
        private val sessionId: String?,
        private val action: NativePushAction,
        private val platform: NativePushPlatform,
    ) {
        private var owner: NativePushOwner? = null
        private var state = NativePushState()
        private var access = NotificationAccess.Unavailable

        suspend fun run(): AppResult<NativePushStatus> {
            if (!initialize()) return AppResult.Success(status())
            transitionOwner()
            if (owner == null || state.owner == null) return complete()
            val currentOwner = requireNotNull(owner)
            if (currentOwner.expiresAt <= clock.now())
                stop("client.session_expired", FailureKind.Unauthorized)

            // A pending operation precedes any new work, including an explicit disable.
            if (state.pending != null) {
                recoverPending()
                if (!state.desired) disableCurrent()
                return complete()
            }
            if (!state.desired || access != NotificationAccess.Granted) {
                disableCurrent()
                return complete()
            }

            val observed = registrations.read(currentOwner).required()
            guard()
            if (observed != null && !observed.enabled && !state.enableRequested) {
                save(
                    state.copy(
                        desired = false,
                        enableRequested = false,
                        registration = observed,
                        retiringToken = true,
                    )
                )
                retireToken()
                return complete()
            }
            val token = tokens.token().required()
            guard()
            if (token.length !in 16..2048 || token.any { it.code !in 33..126 })
                stop("client.push_token_invalid", FailureKind.Unexpected)
            val needsRenewal =
                observed == null ||
                    !observed.enabled ||
                    observed.platform != platform ||
                    observed.expiresAt <= clock.now() + 1.days ||
                    token != state.token
            if (!needsRenewal) {
                save(state.copy(registration = observed, enableRequested = false))
                return complete()
            }
            if (observed != null && observed.version >= 9999)
                stop("push_registration_version_limit", FailureKind.Conflict)
            val command =
                NativePushCommand.Save(Uuid.random().toString(), observed?.version, platform, token)
            save(state.copy(token = token, registration = observed, pending = command))
            recoverPending()
            return complete()
        }

        private suspend fun complete(): AppResult<NativePushStatus> {
            save(state.copy(automaticAttempts = 0, automaticRetryAfter = null))
            return AppResult.Success(status())
        }

        private suspend fun initialize(): Boolean {
            val session = sessions.current().required()
            if (session?.id != sessionId) stop("client.session_changed", FailureKind.Unauthorized)
            owner = session?.pushOwner()
            if (owner != null && owner?.audience != server.audience)
                stop("client.session_changed", FailureKind.Unauthorized)
            state = storage.read().required()
            guard()
            if (action == NativePushAction.Background) {
                if (
                    state.owner == null &&
                        !state.desired &&
                        !state.retiringToken &&
                        state.revocations.isEmpty()
                )
                    return false
                if (state.automaticAttempts >= 8)
                    stop("client.push_retry_limit", FailureKind.Conflict)
                if (state.automaticRetryAfter?.let { it > clock.now() } == true)
                    stop("client.push_retry_later", FailureKind.Unavailable)
                val delay = (30.seconds * (1 shl state.automaticAttempts)).coerceAtMost(15.minutes)
                save(
                    state.copy(
                        automaticAttempts = state.automaticAttempts + 1,
                        automaticRetryAfter = clock.now() + delay,
                    )
                )
            } else {
                save(state.copy(automaticAttempts = 0, automaticRetryAfter = null))
            }
            access = permissions.check().required()
            guard()
            when (action) {
                NativePushAction.Enable -> {
                    if (owner == null) stop("client.session_changed", FailureKind.Unauthorized)
                    if (access != NotificationAccess.Granted)
                        stop("client.push_permission", FailureKind.Permission)
                    save(state.copy(desired = true, enableRequested = true))
                }
                NativePushAction.Disable ->
                    save(state.copy(desired = false, enableRequested = false))
                NativePushAction.Refresh,
                NativePushAction.Background -> Unit
            }
            if (access != NotificationAccess.Granted && state.desired)
                save(state.copy(desired = false, enableRequested = false))
            return true
        }

        private suspend fun transitionOwner() {
            // A new account/family can never replay its predecessor's command or reuse its token.
            if (state.owner != null && state.owner != owner) {
                val previous = requireNotNull(state.owner)
                val retained = state.revocations.filter { it.expiresAt > clock.now() }
                val queued =
                    if (
                        previous.familyId == owner?.familyId &&
                            previous.accountId == owner?.accountId &&
                            previous.audience == owner?.audience
                    )
                        retained
                    else
                        (retained + previous).distinctBy {
                            Triple(it.audience, it.accountId, it.familyId)
                        }
                if (queued.size > 32) stop("client.push_cleanup_capacity", FailureKind.Conflict)
                save(
                    state.copy(
                        owner = null,
                        token = null,
                        registration = null,
                        pending = null,
                        retiringToken = true,
                        revocations = queued,
                    )
                )
            }
            val cleanupFailure = revokePreviousSessions()
            if (
                state.owner == null &&
                    (state.desired || action == NativePushAction.Disable) &&
                    owner != null &&
                    !state.retiringToken
            )
                save(state.copy(retiringToken = true))
            if (state.retiringToken) retireToken()
            if (
                state.owner == null &&
                    owner != null &&
                    (state.desired || action == NativePushAction.Disable)
            )
                save(state.copy(owner = owner, enableRequested = state.desired))
            cleanupFailure?.let { throw Stop(it) }
        }

        private suspend fun recoverPending() {
            val command = state.pending ?: return
            val currentOwner = requireNotNull(owner)
            val result = registrations.send(currentOwner, command)
            guard()
            if (result is AppResult.Failed && result.failure.code == "stale_version") {
                // A later observed revision fences any delayed copy of this old command.
                val latest = registrations.read(currentOwner).required()
                guard()
                if (latest != null && latest.version > (command.expectedVersion ?: -1))
                    save(
                        state.copy(
                            pending = null,
                            registration = latest,
                            desired = state.desired && latest.enabled,
                            enableRequested = state.enableRequested && latest.enabled,
                        )
                    )
                throw Stop(result.failure)
            }
            val receipt = result.required()
            val latest = registrations.read(currentOwner).required()
            guard()
            if (latest == null || latest.version < receipt.version)
                stop("client.push_receipt_unconfirmed", FailureKind.Unexpected)
            // Receipt replay cannot reactivate a later disabled registration.
            val wanted = state.desired && latest.enabled
            save(
                state.copy(
                    pending = null,
                    registration = latest,
                    desired = wanted,
                    enableRequested = false,
                    retiringToken = !latest.enabled || !wanted,
                )
            )
            if (state.retiringToken) retireToken()
        }

        private suspend fun disableCurrent() {
            val currentOwner = requireNotNull(owner)
            val latest = registrations.read(currentOwner).required()
            guard()
            if (latest?.enabled == true) {
                val command = NativePushCommand.Disable(Uuid.random().toString(), latest.version)
                save(
                    state.copy(
                        registration = latest,
                        pending = command,
                        desired = false,
                        enableRequested = false,
                    )
                )
                recoverPending()
            } else {
                save(
                    state.copy(
                        registration = latest,
                        desired = false,
                        enableRequested = false,
                        retiringToken = state.token != null || state.retiringToken,
                    )
                )
                if (state.retiringToken) retireToken()
            }
        }

        private suspend fun retireToken() {
            tokens.deleteToken().required()
            guard()
            save(state.copy(token = null, retiringToken = false))
        }

        private suspend fun revokePreviousSessions(): Failure? {
            val retained = state.revocations.filter { it.expiresAt > clock.now() }
            if (retained != state.revocations) save(state.copy(revocations = retained))
            val current = owner ?: return null
            for (previous in
                retained
                    .filter {
                        it.accountId == current.accountId &&
                            it.audience == current.audience &&
                            it.familyId != current.familyId
                    }
                    .take(4)) {
                val result = devices.revoke(current.localSessionId, previous.familyId)
                guard()
                if (result is AppResult.Failed) return result.failure
                save(state.copy(revocations = state.revocations.filterNot { it == previous }))
            }
            return null
        }

        private suspend fun save(updated: NativePushState) {
            guard()
            if (updated == state) return
            if (!storage.compareAndSet(state, updated).required())
                stop("client.push_state_changed", FailureKind.Conflict)
            state = updated
            guard()
        }

        private suspend fun guard() {
            currentCoroutineContext().ensureActive()
            val current = sessions.current().required()?.pushOwner()
            if (current != owner) stop("client.session_changed", FailureKind.Unauthorized)
        }

        private fun status(): NativePushStatus {
            val registration = state.registration?.takeIf { state.owner == owner }
            return NativePushStatus(
                true,
                state.desired,
                registration?.enabled == true &&
                    registration.expiresAt > clock.now() &&
                    !state.retiringToken &&
                    access == NotificationAccess.Granted,
                state.pending != null ||
                    state.retiringToken ||
                    state.revocations.isNotEmpty() ||
                    state.automaticAttempts > 0,
                access,
                registration?.expiresAt,
            )
        }
    }

    private class Stop(val failure: Failure) : RuntimeException()

    private fun stop(code: String, kind: FailureKind): Nothing =
        throw Stop(Failure(kind, "Push registration could not be completed.", code = code))

    private fun <T> AppResult<T>.required(): T =
        when (this) {
            is AppResult.Success -> value
            is AppResult.Failed -> throw Stop(failure)
        }

    private fun pushFailure(code: String, kind: FailureKind) =
        AppResult.Failed(Failure(kind, "Push registration could not be completed.", code = code))
}
