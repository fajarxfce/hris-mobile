package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

/** Explicit live entry. Offline history observation has a separate owned-facts contract. */
class EnterCollaborationConversation(
    private val runtimes: CollaborationRuntimeRepository,
    private val conversations: CollaborationConversationRepository,
    private val setup: CollaborationSetupRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val lists: CollaborationListRepository,
) {
    suspend operator fun invoke(
        workspaceId: String,
        deviceId: String,
        conversationId: String,
    ): AppResult<CollaborationTimelineLease> =
        invoke(CollaborationOperationScope.Selected(workspaceId), deviceId, conversationId)

    suspend operator fun invoke(
        scope: CollaborationOperationScope,
        deviceId: String,
        conversationId: String,
    ): AppResult<CollaborationTimelineLease> {
        if (
            !canonicalId.matches(deviceId) ||
                !canonicalId.matches(conversationId) ||
                !collaborationOperationOwnsDevice(scope, deviceId)
        )
            return collaborationSetupFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        val owner =
            when (val captured = capture(scope)) {
                is AppResult.Failed -> return captured
                is AppResult.Success -> captured.value
            }
        continuation(scope, owner, AppResult.Success(Unit)).let {
            if (it is AppResult.Failed) return it
        }
        val registered = setup.read(owner)
        continuation(scope, owner, registered).let { if (it is AppResult.Failed) return it }
        val device =
            when (registered) {
                is AppResult.Failed -> return registered
                is AppResult.Success -> registered.value
            }
        if (
            device == null ||
                device.id != deviceId ||
                device.state != CollaborationDeviceState.ACTIVE ||
                device.platform.name != build.platform.name.uppercase()
        )
            return collaborationSetupFailure(
                "client.collaboration_device_unavailable",
                FailureKind.Unavailable,
            )
        val direct = conversations.get(owner, conversationId)
        continuation(scope, owner, direct).let { if (it is AppResult.Failed) return it }
        val conversation =
            when (direct) {
                is AppResult.Failed -> return direct
                is AppResult.Success -> direct.value
            }
        if (
            conversation.id != conversationId ||
                conversation.companyId != owner.companyId ||
                owner.accountId !in
                    listOf(conversation.firstAccountId, conversation.secondAccountId)
        )
            return collaborationSetupFailure(
                "client.collaboration_conversation_mismatch",
                FailureKind.AccessDenied,
            )
        val provisioned = conversations.room(owner, conversationId)
        continuation(scope, owner, provisioned).let { if (it is AppResult.Failed) return it }
        val room =
            when (provisioned) {
                is AppResult.Failed -> return provisioned
                is AppResult.Success -> provisioned.value
            }
        if (room.conversationId != conversationId)
            return collaborationSetupFailure(
                "client.collaboration_conversation_mismatch",
                FailureKind.AccessDenied,
            )
        val matrixRoomId = room.matrixRoomId
        if (
            room.state != CollaborationDirectRoomState.READY ||
                matrixRoomId == null ||
                !matrixRoomId.startsWith('!') ||
                matrixRoomId.length !in 2..255 ||
                matrixRoomId.any { it.isWhitespace() || it.isISOControl() }
        )
            return collaborationSetupFailure(
                "client.collaboration_room_not_ready",
                FailureKind.Unavailable,
            )
        var acquired: CollaborationTimelineLease? = null
        var handedOver = false
        try {
            val entered = runtimes.enterTimeline(owner, deviceId, matrixRoomId)
            if (entered is AppResult.Success) acquired = entered.value
            continuation(scope, owner, entered).let { if (it is AppResult.Failed) return it }
            if (entered is AppResult.Failed) return entered
            val timeline = requireNotNull(acquired)
            if (!matchesTimeline(timeline, owner, deviceId, matrixRoomId))
                return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
            currentCoroutineContext().ensureActive()
            handedOver = true
            return AppResult.Success(timeline)
        } finally {
            if (!handedOver) withContext(NonCancellable) { acquired?.close() }
        }
    }

    private fun matchesTimeline(
        timeline: CollaborationTimelineLease,
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ) =
        timeline.owner == owner &&
            timeline.deviceId == deviceId &&
            timeline.matrixRoomId == matrixRoomId &&
            validCollaborationMessageId(timeline.device.bootstrapOperationId) &&
            timeline.device.nativeSessionId == owner.nativeSessionId &&
            timeline.device.deviceId == deviceId &&
            timeline.device.roomId == matrixRoomId

    private suspend fun capture(scope: CollaborationOperationScope) =
        sessions.captureCollaborationOwner(scope, workspaces, server, build, clock)

    private suspend fun continuation(
        scope: CollaborationOperationScope,
        owner: CollaborationOwner,
        received: AppResult<*>,
    ): AppResult<Unit> {
        val rejected = (received as? AppResult.Failed)?.failure
        if (rejected != null && collaborationContentResponseClosesRead(rejected))
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { lists.restrict(owner.localListAccess(), rejected) }
            }
        currentCoroutineContext().ensureActive()
        val gate = lists.admission(owner.localListAccess())
        val current = capture(scope)
        currentCoroutineContext().ensureActive()
        if (rejected != null && collaborationContentResponseClosesRead(rejected))
            return AppResult.Failed(rejected)
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        val owned = requireCollaborationOwner(current, owner, received)
        if (owned is AppResult.Failed) return owned
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(Unit)
    }

    private companion object {
        val canonicalId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
