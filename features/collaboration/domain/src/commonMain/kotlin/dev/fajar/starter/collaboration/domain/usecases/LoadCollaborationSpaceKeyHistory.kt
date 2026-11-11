package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Resolves one retained Space locator without changing its history lane or acquiring content. */
class LoadCollaborationSpaceKeyHistory(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val build: AppBuild,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val sync: CollaborationSpaceSyncRepository,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
        locator: CollaborationIncomingHistoryLocator,
    ): AppResult<CollaborationSpaceKeyHistoryResolution> {
        currentCoroutineContext().ensureActive()
        val access = runtime.owner.localDirectoryAccess()
        if (
            !validCollaborationOriginalConversationOwner(runtime.owner) ||
                !validCollaborationSpaceAccountAccess(
                    CollaborationSpaceAccountAccess(
                        access.sessionId,
                        access.audience,
                        access.accountId,
                    )
                ) ||
                !validCollaborationSpaceConversationId(runtime.deviceId) ||
                !validCollaborationSpaceConversationId(runtime.operationId) ||
                !validCollaborationSpaceIncomingSource(source) ||
                !validCollaborationSpaceConversationId(locator.conversationId) ||
                !validCollaborationSpaceConversationId(locator.deviceId)
        )
            return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) {
            admission(runtime, source).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            val loaded = registrations.current(access, runtime.owner.parent.nativeSessionId)
            admission(runtime, source).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            val registration =
                (loaded as AppResult.Success).value ?: return@withTimeoutOrNull changed()
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return@withTimeoutOrNull changed(FailureKind.Unavailable)
            if (
                registeredCollaborationSpaceOriginalIncomingSdk(
                    runtime,
                    source,
                    registration,
                    platform,
                ) == null
            )
                return@withTimeoutOrNull changed()
            resolveMetadata(runtime, source, locator, registration)
        } ?: changed(FailureKind.Timeout)
    }

    private suspend fun resolveMetadata(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
        locator: CollaborationIncomingHistoryLocator,
        registration: CollaborationSpaceRegistrationRecord,
    ): AppResult<CollaborationSpaceKeyHistoryResolution> {
        val access = runtime.owner.localDirectoryAccess()
        val found = bindings.getByConversation(access, locator.conversationId)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (found is AppResult.Failed) return found
        val value = (found as AppResult.Success).value
        if (
            value != null &&
                (!validCollaborationSpaceRoomBinding(access, value) ||
                    value.conversation.id != locator.conversationId ||
                    value.room.serverName != registration.command.provider.serverName)
        )
            return changed()
        val current = registrations.current(access, runtime.owner.parent.nativeSessionId)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != registration) return changed()
        if (value == null)
            return AppResult.Success(CollaborationSpaceKeyHistoryResolution.MetadataUnavailable)
        return AppResult.Success(
            CollaborationSpaceKeyHistoryResolution.Ready(
                CollaborationSpaceKeyHistory(
                    source,
                    value,
                    CollaborationHistoryTraversal(
                        locator.conversationId,
                        value.room.roomId,
                        locator.deviceId,
                        CollaborationHistoryPurpose.Incoming,
                    ),
                )
            )
        )
    }

    private suspend fun admission(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
    ): AppResult<Unit> {
        val access = runtime.owner.localDirectoryAccess()
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

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_key_history_changed", kind)
}
