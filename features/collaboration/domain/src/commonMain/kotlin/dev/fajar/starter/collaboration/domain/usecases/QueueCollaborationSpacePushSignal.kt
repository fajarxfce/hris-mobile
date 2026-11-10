package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.*

/** Persists recovery before OS scheduling. No SDK, room acquisition or notification display. */
class QueueCollaborationSpacePushSignal(
    private val sessions: SessionRepository,
    private val roots: CollaborationSpaceRootRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val wakes: CollaborationSpaceRecoveryWakeRepository,
    private val schedules: SyncScheduleRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(fields: Map<String, String>): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        val signal = parseCollaborationSpacePushSignal(fields) ?: return AppResult.Success(false)
        if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios))
            return AppResult.Success(false)
        return withTimeoutOrNull(3_000) {
            val captured = sessions.capturePushParent(server, build)
            if (captured is AppResult.Failed) return@withTimeoutOrNull captured
            val parent = (captured as AppResult.Success).value
            if (
                parent.accountId != signal.accountId ||
                    parent.nativeSessionId != signal.nativeSessionId
            )
                return@withTimeoutOrNull AppResult.Success(false)
            val root = roots.current(parent.localSpaceAccountAccess())
            sessions.guardPushParent(server, build, parent).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (root is AppResult.Failed) return@withTimeoutOrNull root
            if ((root as AppResult.Success).value?.spaceId != signal.spaceId)
                return@withTimeoutOrNull AppResult.Success(false)
            val owner = CollaborationSpaceOwner(parent, signal.spaceId)
            val local = owner.localDirectoryAccess()
            val loaded = registrations.current(local, parent.nativeSessionId)
            sessions.guardPushParent(server, build, parent).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            val record =
                (loaded as AppResult.Success).value
                    ?: return@withTimeoutOrNull AppResult.Success(false)
            if (!validCollaborationSpaceRegistrationRecord(local, record))
                return@withTimeoutOrNull collaborationRecoveryFailure(
                    "client.collaboration_invalid_space_registration",
                    FailureKind.Unexpected,
                )
            val registered =
                record.outcome as? CollaborationSpaceRegistrationOutcome.Registered
                    ?: return@withTimeoutOrNull AppResult.Success(false)
            // Server logical device comes from the receipt, never the operation or physical SDK ID.
            if (
                record.command.binding.nativeSessionId != parent.nativeSessionId ||
                    record.command.platform.name != build.platform.name.uppercase() ||
                    registered.receipt.id != signal.deviceId
            )
                return@withTimeoutOrNull AppResult.Success(false)
            val retained = wakes.request()
            currentCoroutineContext().ensureActive()
            sessions.guardPushParent(server, build, parent).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (retained is AppResult.Failed) return@withTimeoutOrNull retained
            val scheduled =
                withTimeoutOrNull(1_500) {
                    schedules.request(CollaborationSpaceRecoveryWake.WORK_KEY)
                }
            currentCoroutineContext().ensureActive()
            sessions.guardPushParent(server, build, parent).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            when (scheduled) {
                null ->
                    collaborationRecoveryFailure(
                        "client.collaboration_space_recovery_schedule_timeout",
                        FailureKind.Timeout,
                    )
                is AppResult.Failed -> scheduled
                is AppResult.Success -> AppResult.Success(true)
            }
        }
            ?: collaborationRecoveryFailure(
                "client.collaboration_space_push_timeout",
                FailureKind.Timeout,
            )
    }
}
