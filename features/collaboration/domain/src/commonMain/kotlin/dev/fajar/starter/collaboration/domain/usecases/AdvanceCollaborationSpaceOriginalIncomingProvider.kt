package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Confirms a retained Company checkpoint before moving its exact original incoming task. */
class AdvanceCollaborationSpaceOriginalIncomingProvider(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val metadata: CollaborationSpaceOriginalRoomMetadataRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val history: CollaborationHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val sync: CollaborationSpaceSyncRepository,
) {
    suspend operator fun invoke(
        acquired: CollaborationSpaceOriginalProviderHistoryAcquisition
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationSpaceOriginalProviderAcquisition(acquired))
            return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) {
            val source = sync.incomingSource(acquired.runtime)
            if (
                source == null ||
                    !validCollaborationSpaceIncomingSource(source) ||
                    source.storeId != acquired.room.captured.sourceId
            )
                return@withTimeoutOrNull changed(FailureKind.Unavailable)
            Transition(acquired, source).advance()
        } ?: changed(FailureKind.Timeout)
    }

    private inner class Transition(
        val acquired: CollaborationSpaceOriginalProviderHistoryAcquisition,
        val source: CollaborationSpaceIncomingSource,
    ) {
        val runtime = acquired.runtime
        val room = acquired.room
        val captured = room.captured
        val access = runtime.owner.localDirectoryAccess()
        val position = room.conversation.conversation.position
        val companyAccess =
            CollaborationLocalAccess(
                access.sessionId,
                access.audience,
                access.accountId,
                position.companyId,
            )
        val expectedMetadata =
            CollaborationSpaceOriginalRoomMetadata(
                room.conversation.conversation,
                requireNotNull(room.conversation.room),
            )
        var registration: CollaborationSpaceRegistrationRecord? = null

        suspend fun advance(): AppResult<Boolean> {
            current().let { if (it is AppResult.Failed) return it }
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if (observed == null) return AppResult.Success(false)
            if (
                snapshot == null ||
                    !validCollaborationMessageId(snapshot.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, snapshot.demand) ||
                    snapshot.demand.sources.none { it.source == source }
            )
                return changed()
            val retained = snapshot.demand.rooms.singleOrNull { it.target == captured.target }
            if (retained == null || retained.generation != captured.generation)
                return AppResult.Success(false)
            val applied =
                captured.copy(
                    phase =
                        if (acquired.progress.exhausted) CollaborationSpaceIncomingPhase.Pending
                        else CollaborationSpaceIncomingPhase.Provider,
                    retry = null,
                )
            if (retained != captured && retained != applied) return changed()
            val progress = history.progress(companyAccess, room.traversal)
            current().let { if (it is AppResult.Failed) return it }
            if (progress is AppResult.Failed) return progress
            if ((progress as AppResult.Success).value != acquired.progress) return changed()
            if (retained == applied) return AppResult.Success(true)
            return retain(snapshot)
        }

        suspend fun retain(snapshot: CollaborationSpaceIncomingDemandSnapshot): AppResult<Boolean> {
            val planned =
                nextCollaborationSpaceIncomingDemand(
                    snapshot.demand,
                    CollaborationSpaceIncomingDemandChange.ProviderAcquired(
                        captured,
                        acquired.progress.exhausted,
                    ),
                )
            if (planned is AppResult.Failed) return planned
            val next = (planned as AppResult.Success).value
            if (next == snapshot.demand) return AppResult.Success(true)
            val written = demands.replace(access, snapshot, next)
            current().let { if (it is AppResult.Failed) return it }
            val loaded = demands.current(access)
            val retained =
                (loaded as? AppResult.Success)
                    ?.value
                    ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            return when (
                val confirmed =
                    confirmCollaborationSpaceIncomingRoomWrite(
                        access,
                        snapshot.demand,
                        next,
                        captured,
                        retained,
                        written,
                    )
            ) {
                is AppResult.Failed -> confirmed
                is AppResult.Success ->
                    AppResult.Success(
                        confirmed.value == CollaborationSpaceIncomingRoomWrite.Applied
                    )
            }
        }

        suspend fun current(): AppResult<Unit> {
            admission().let { if (it is AppResult.Failed) return it }
            val found = metadata.getByConversation(access, position)
            admission().let { if (it is AppResult.Failed) return it }
            if (found is AppResult.Failed) return found
            if ((found as AppResult.Success).value != expectedMetadata) return changed()
            val loaded = registrations.current(access, runtime.owner.parent.nativeSessionId)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            val value = (loaded as AppResult.Success).value
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return changed(FailureKind.Unavailable)
            if (
                value == null ||
                    !validCollaborationSpaceOriginalIncomingRegistration(
                        runtime,
                        captured,
                        source,
                        value,
                        platform,
                    ) ||
                    value.command.legacy?.deviceId != room.traversal.deviceId ||
                    value.command.provider.serverName != expectedMetadata.room.serverName ||
                    (registration != null && value != registration)
            )
                return changed()
            registration = value
            return AppResult.Success(Unit)
        }

        suspend fun admission(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val gate = directory.admission(access)
            val current = sessions.current()
            currentCoroutineContext().ensureActive()
            if (gate is AppResult.Success) {
                if (!validCollaborationSpaceDirectoryAdmission(gate.value)) return changed()
                gate.value.denial?.let {
                    return AppResult.Failed(it)
                }
            }
            if (current is AppResult.Failed) return current
            val session = (current as AppResult.Success).value
            val owned = localCollaborationSpaceAccess(access, session, server.audience)
            if (owned is AppResult.Failed) return owned
            if (gate is AppResult.Failed) return gate
            if (session?.binding?.serverId != runtime.owner.parent.nativeSessionId)
                return changed(FailureKind.Unauthorized)
            if (sync.incomingSource(runtime) != source) return changed(FailureKind.Unavailable)
            return AppResult.Success(Unit)
        }
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure(
            "client.collaboration_original_incoming_provider_changed",
            kind,
        )
}
