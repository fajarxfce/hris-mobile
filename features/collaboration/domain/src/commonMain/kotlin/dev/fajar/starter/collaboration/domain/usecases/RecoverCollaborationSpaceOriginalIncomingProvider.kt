package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

/** Recovers only missing Company progress or a new head after retained pending work is drained. */
class RecoverCollaborationSpaceOriginalIncomingProvider(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val metadata: CollaborationSpaceOriginalRoomMetadataRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val history: CollaborationHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val sync: CollaborationSpaceSyncRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        room: CollaborationSpaceOriginalIncomingRoom,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceOriginalIncomingRoom(runtime, room) ||
                room.captured.phase != CollaborationSpaceIncomingPhase.Pending
        )
            return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) {
            val source = sync.incomingSource(runtime)
            if (
                source == null ||
                    !validCollaborationSpaceIncomingSource(source) ||
                    source.storeId != room.captured.sourceId
            )
                return@withTimeoutOrNull changed(FailureKind.Unavailable)
            Recovery(runtime, room, source).recover()
        } ?: changed(FailureKind.Timeout)
    }

    private enum class Mode {
        None,
        MissingProgress,
        UnseenHead,
    }

    private inner class Recovery(
        val runtime: CollaborationSpaceRuntimeReady,
        val room: CollaborationSpaceOriginalIncomingRoom,
        val source: CollaborationSpaceIncomingSource,
    ) {
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

        suspend fun recover(): AppResult<Boolean> {
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
            if (retained != captured) {
                val replay =
                    retained.phase == CollaborationSpaceIncomingPhase.Latest &&
                        retained.position > captured.position &&
                        retained.copy(
                            phase = captured.phase,
                            retry = captured.retry,
                            position = captured.position,
                        ) == captured
                return if (replay) AppResult.Success(true) else changed()
            }
            val recovery = providerRecovery()
            if (recovery is AppResult.Failed) return recovery
            val mode = (recovery as AppResult.Success).value
            if (mode == Mode.None) return AppResult.Success(false)
            return retain(snapshot, mode)
        }

        suspend fun providerRecovery(): AppResult<Mode> {
            val loaded =
                history.observeReferences(companyAccess, room.traversal, null, 1).firstOrNull()
            val page =
                (loaded as? AppResult.Success)
                    ?.value
                    ?.takeIf { it.events.size <= 1 }
                    ?.let { it.copy(events = it.events.toList()) }
            current().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if (page == null || !validCollaborationPendingHistoryReferences(page, null, 1))
                return changed()
            val progress = page.progress
            val mode =
                if (progress == null) Mode.MissingProgress
                else {
                    if (progress.provider != null || progress.incoming == null) return changed()
                    if (
                        !progress.exhausted ||
                            page.events.isNotEmpty() ||
                            captured.headEventId == null
                    )
                        return AppResult.Success(Mode.None)
                    val loadedSource =
                        history.source(companyAccess, room.traversal, captured.headEventId)
                    current().let { if (it is AppResult.Failed) return it }
                    if (loadedSource is AppResult.Failed) return loadedSource
                    val retained = (loadedSource as AppResult.Success).value
                    if (retained.progress != progress) return changed()
                    val reference = retained.reference
                    if (reference != null) {
                        if (
                            !reference.resolved ||
                                reference.event.eventId != captured.headEventId ||
                                !validCollaborationProviderEvent(reference.event) ||
                                reference.sequence !in 0 until progress.references
                        )
                            return changed()
                        return AppResult.Success(Mode.None)
                    }
                    Mode.UnseenHead
                }
            val latest = history.progress(companyAccess, room.traversal)
            current().let { if (it is AppResult.Failed) return it }
            if (latest is AppResult.Failed) return latest
            return AppResult.Success(
                if ((latest as AppResult.Success).value == progress) mode else Mode.None
            )
        }

        suspend fun retain(
            snapshot: CollaborationSpaceIncomingDemandSnapshot,
            mode: Mode,
        ): AppResult<Boolean> {
            val retry =
                if (mode == Mode.UnseenHead)
                    nextCollaborationSpaceIncomingRetry(
                        captured.retry,
                        clock.now().toEpochMilliseconds(),
                    ) ?: return changed(FailureKind.Validation)
                else null
            val planned =
                nextCollaborationSpaceIncomingDemand(
                    snapshot.demand,
                    CollaborationSpaceIncomingDemandChange.RestartProvider(captured, retry),
                )
            if (planned is AppResult.Failed) return planned
            val next = (planned as AppResult.Success).value
            current().let { if (it is AppResult.Failed) return it }
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
            "client.collaboration_original_incoming_recovery_changed",
            kind,
        )
}
