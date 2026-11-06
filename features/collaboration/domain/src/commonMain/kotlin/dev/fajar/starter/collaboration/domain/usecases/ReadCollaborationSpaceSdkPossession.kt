package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Read original private-account identity without acquiring a runtime or granting live access. */
class ReadCollaborationSpaceSdkPossession(
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val possession: CollaborationSdkPossessionRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        owner: CollaborationSpaceOwner,
        registrationId: String,
    ): AppResult<CollaborationSdkPossession> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationMessageId(owner.spaceId) ||
                !validCollaborationMessageId(owner.parent.nativeSessionId) ||
                !validCollaborationMessageId(registrationId)
        )
            return changedSpaceSetup()
        val access = owner.localDirectoryAccess()
        return withTimeoutOrNull(5_000) {
            val before = owned(access)
            if (before is AppResult.Failed) return@withTimeoutOrNull before
            val loaded = registrations.current(access, owner.parent.nativeSessionId)
            val afterRead = owned(access)
            if (afterRead is AppResult.Failed) return@withTimeoutOrNull afterRead
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            val record =
                (loaded as AppResult.Success).value ?: return@withTimeoutOrNull changedSpaceSetup()
            if (
                !validCollaborationSpaceRegistrationRecord(access, record) ||
                    record.command.id != registrationId ||
                    record.command.binding.nativeSessionId != owner.parent.nativeSessionId
            )
                return@withTimeoutOrNull invalidSpaceSetup()
            val original = record.command.legacy
            if (
                record.command.origin != CollaborationSpaceDeviceOrigin.LEGACY || original == null
            ) {
                return@withTimeoutOrNull collaborationRecoveryFailure(
                    "client.collaboration_original_device_required",
                    FailureKind.Conflict,
                )
            }
            val binding = original.binding
            val physical =
                CollaborationOwner(
                    access.sessionId,
                    binding.audience,
                    binding.accountId,
                    binding.companyId,
                    binding.nativeSessionId,
                )
            val received = possession.readPrivateIdentity(physical, original.deviceId)
            val afterPrivateRead = owned(access)
            if (afterPrivateRead is AppResult.Failed) return@withTimeoutOrNull afterPrivateRead
            if (received is AppResult.Failed) return@withTimeoutOrNull received
            val observed =
                (received as AppResult.Success).value
                    ?: return@withTimeoutOrNull collaborationRecoveryFailure(
                        "client.collaboration_original_device_unavailable",
                        FailureKind.Conflict,
                    )
            if (!validCollaborationSdkPossession(original, observed))
                return@withTimeoutOrNull invalidSpaceSetup()
            AppResult.Success(observed)
        }
            ?: collaborationRecoveryFailure(
                "client.collaboration_private_identity_timeout",
                FailureKind.Timeout,
            )
    }

    private suspend fun owned(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationSpaceLocalAccess> {
        val session = sessions.current()
        currentCoroutineContext().ensureActive()
        if (session is AppResult.Failed) return session
        return localCollaborationSpaceAccess(
            access,
            (session as AppResult.Success).value,
            server.audience,
        )
    }
}
