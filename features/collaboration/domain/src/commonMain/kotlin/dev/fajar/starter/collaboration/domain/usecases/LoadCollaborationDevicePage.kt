package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** One bounded page for the current native parent; never selects or admits a company. */
class LoadCollaborationDevicePage(
    private val recovery: CollaborationRecoveryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        expectedParent: CollaborationNativeParent? = null,
        afterCompanyId: String? = null,
        limit: Int = 8,
    ): AppResult<CollaborationDevicePage> {
        if (
            limit !in 1..32 ||
                (afterCompanyId != null &&
                    (expectedParent == null || !validCollaborationMessageId(afterCompanyId)))
        )
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        if (!execution.tryLock())
            return collaborationRecoveryFailure(
                "client.collaboration_recovery_busy",
                FailureKind.Unavailable,
            )
        try {
            return withTimeoutOrNull(15_000) {
                val started = clock.now()
                val captured = capture()
                if (captured is AppResult.Failed) return@withTimeoutOrNull captured
                val parent = (captured as AppResult.Success).value
                if (expectedParent != null && parent != expectedParent)
                    return@withTimeoutOrNull collaborationRecoveryFailure(
                        "client.session_changed",
                        FailureKind.Unauthorized,
                    )
                val found = recovery.devices(parent, afterCompanyId, limit)
                currentCoroutineContext().ensureActive()
                val current = capture()
                currentCoroutineContext().ensureActive()
                // A remote denial remains the result even if a subsequent local read fails.
                if (found is AppResult.Failed) return@withTimeoutOrNull found
                if (current is AppResult.Failed) return@withTimeoutOrNull current
                if ((current as AppResult.Success).value != parent)
                    return@withTimeoutOrNull collaborationRecoveryFailure(
                        "client.session_changed",
                        FailureKind.Unauthorized,
                    )
                val now = clock.now()
                if (now < started || now - started >= 15.seconds)
                    return@withTimeoutOrNull collaborationRecoveryFailure(
                        "client.collaboration_revalidation_required",
                        FailureKind.Unavailable,
                    )
                val page = (found as AppResult.Success).value
                if (!validCollaborationDevicePage(page, parent, afterCompanyId, limit))
                    return@withTimeoutOrNull collaborationRecoveryFailure(
                        "client.collaboration_invalid_directory",
                        FailureKind.Unexpected,
                    )
                found
            }
                ?: collaborationRecoveryFailure(
                    "client.collaboration_recovery_timeout",
                    FailureKind.Timeout,
                )
        } finally {
            execution.unlock()
        }
    }

    private suspend fun capture(): AppResult<CollaborationNativeParent> =
        when (val current = sessions.current()) {
            is AppResult.Failed -> current
            is AppResult.Success ->
                collaborationNativeParent(current.value, server, build, clock.now())
        }
}
