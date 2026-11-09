package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Discovers retained originals only. Missing or unfinished setup cannot enroll or replace keys. */
class LoadCollaborationSpaceRecoveryDevice(
    private val roots: CollaborationSpaceRootRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val commands: CollaborationSpaceSetupCommandRepository,
    private val originals: CollaborationRuntimeRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        parent: CollaborationNativeParent
    ): AppResult<CollaborationSpaceRecoveryDevice?> =
        withTimeoutOrNull(5_000) { load(parent) }
            ?: collaborationRecoveryFailure(
                "client.collaboration_space_recovery_local_timeout",
                FailureKind.Timeout,
            )

    private suspend fun load(
        parent: CollaborationNativeParent
    ): AppResult<CollaborationSpaceRecoveryDevice?> {
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        val found = roots.current(parent.localSpaceAccountAccess())
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        if (found is AppResult.Failed) return found
        val root = (found as AppResult.Success).value ?: return AppResult.Success(null)
        val owner = CollaborationSpaceOwner(parent, root.spaceId)
        sessions.guardSpaceRecoveryOwner(server, owner).let {
            if (it is AppResult.Failed) return it
        }
        val local = owner.localDirectoryAccess()
        val registration = registrations.current(local, parent.nativeSessionId)
        sessions.guardSpaceRecoveryOwner(server, owner).let {
            if (it is AppResult.Failed) return it
        }
        if (registration is AppResult.Failed) return registration
        val record = (registration as AppResult.Success).value ?: return missingOriginal()
        val original = spaceSetupOriginal(owner, record.command.id, record)
        if (original is AppResult.Failed) return original
        val legacy = requireNotNull(record.command.legacy)
        val loaded = commands.current(local, parent.nativeSessionId)
        sessions.guardSpaceRecoveryOwner(server, owner).let {
            if (it is AppResult.Failed) return it
        }
        if (loaded is AppResult.Failed) return loaded
        val setup = (loaded as AppResult.Success).value ?: return missingOriginal()
        if (setup.command.platform.name != build.platform.name.uppercase()) return missingOriginal()
        val physical =
            CollaborationOwner(
                parent.sessionId,
                legacy.binding.audience,
                legacy.binding.accountId,
                legacy.binding.companyId,
                legacy.binding.nativeSessionId,
            )
        val retained = originals.handoverIntent(physical, legacy.deviceId)
        sessions.guardSpaceRecoveryOwner(server, owner).let {
            if (it is AppResult.Failed) return it
        }
        if (retained is AppResult.Failed) return retained
        val intent = (retained as AppResult.Success).value ?: return missingOriginal()
        val source =
            CollaborationSpaceRuntimeAdoption(
                record,
                setup,
                CollaborationExistingRuntimeHandover(legacy, intent),
            )
        if (!validCollaborationSpaceRuntimeAdoption(owner, source)) return missingOriginal()
        return AppResult.Success(
            CollaborationSpaceRecoveryDevice(owner, record.command.id, setup.command.id)
        )
    }

    private fun missingOriginal() =
        collaborationRecoveryFailure(
            "client.collaboration_space_recovery_original_unavailable",
            FailureKind.Conflict,
        )
}
