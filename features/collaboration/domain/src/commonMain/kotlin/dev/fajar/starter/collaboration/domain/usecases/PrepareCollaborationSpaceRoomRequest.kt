@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Recover owned work first, then prepare at most one request. Never dispatches or opens an SDK. */
class PrepareCollaborationSpaceRoomRequest(
    private val commands: CollaborationSpaceConversationCommandRepository,
    private val rooms: CollaborationSpaceRoomRepository,
    private val devices: CollaborationSpaceDeviceRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        conversation: CollaborationSpaceConversation,
        retryAttempt: CollaborationSpaceRoomAttempt? = null,
    ): AppResult<CollaborationSpaceConversationCommandRecord> {
        val owner = conversation.owner
        val access = owner.localDirectoryAccess()
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var deadline: Instant? = null
        var denial: Failure? = null
        fun failed(failure: Failure) =
            AppResult.Failed(retainCollaborationSpaceConversationFailure(denial, failure))
        suspend fun admitted(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val captured =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            currentCoroutineContext().ensureActive()
            if (captured is AppResult.Failed) return failed(captured.failure)
            val currentDeadline =
                Instant.fromEpochMilliseconds(
                    (captured as AppResult.Success).value.binding.expiresAtEpochMillis
                )
            deadline = deadline?.let { minOf(it, currentDeadline) } ?: currentDeadline
            val now = clock.now()
            if (
                now < requestedAt ||
                    now >= requireNotNull(deadline) ||
                    now - requestedAt >= 15.seconds ||
                    started.elapsedNow() >=
                        minOf(15.seconds, requireNotNull(deadline) - requestedAt)
            )
                return failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Room preparation expired",
                        code = "client.collaboration_revalidation_required",
                    )
                )
            return AppResult.Success(Unit)
        }
        suspend fun gate(): AppResult<Unit> {
            val read = directory.admission(access)
            val current = owned(access)
            if (current is AppResult.Failed) return current
            if (read is AppResult.Failed) return failed(read.failure)
            val admission = (read as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(admission))
                return failed(invalid().failure)
            admission.denial?.let {
                denial = retainCollaborationSpaceConversationFailure(denial, it)
            }
            return denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
        }
        suspend fun rejected(failure: Failure): AppResult.Failed {
            denial = retainCollaborationSpaceConversationFailure(denial, failure)
            if (collaborationSpaceConversationResponseClosesDirectory(failure)) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) { directory.restrict(access, failure) }
                }
            }
            currentCoroutineContext().ensureActive()
            val current = owned(access)
            return if (current is AppResult.Failed) current else failed(failure)
        }
        return withTimeoutOrNull(15_000) {
            val initial = owned(access)
            if (initial is AppResult.Failed) return@withTimeoutOrNull initial
            if (!validCollaborationSpaceConversation(conversation))
                return@withTimeoutOrNull invalid()
            val existing = active(conversation)
            if (existing is AppResult.Failed) return@withTimeoutOrNull existing
            val previous = (existing as AppResult.Success).value
            if (
                previous != null &&
                    previous.outcome !is CollaborationSpaceConversationOutcome.Fenced
            )
                return@withTimeoutOrNull AppResult.Success(previous)
            val initialGate = gate()
            if (initialGate is AppResult.Failed) return@withTimeoutOrNull initialGate
            val beforeRoom = admitted()
            if (beforeRoom is AppResult.Failed) return@withTimeoutOrNull beforeRoom
            val room = rooms.get(conversation)
            if (room is AppResult.Failed) return@withTimeoutOrNull rejected(room.failure)
            val afterRoom = admitted()
            if (afterRoom is AppResult.Failed) return@withTimeoutOrNull afterRoom
            val view = (room as AppResult.Success).value
            if (view.conversation != conversation || !validCollaborationSpaceRoomView(view))
                return@withTimeoutOrNull invalid()
            if (retryAttempt != null && !matchesCollaborationSpaceRoomRetry(view, retryAttempt))
                return@withTimeoutOrNull conflict()
            val observed = devices.status(owner)
            if (observed is AppResult.Failed) return@withTimeoutOrNull rejected(observed.failure)
            val afterDevice = admitted()
            if (afterDevice is AppResult.Failed) return@withTimeoutOrNull afterDevice
            val status = (observed as AppResult.Success).value
            if (
                status.owner != owner ||
                    status.device?.let(::validCollaborationSpaceDeviceStatus) == false
            )
                return@withTimeoutOrNull invalid()
            val device = status.device
            if (device == null || device.state != CollaborationDeviceState.ACTIVE)
                return@withTimeoutOrNull collaborationRecoveryFailure(
                    "client.collaboration_revalidation_required",
                    FailureKind.Unavailable,
                )
            val platform =
                if (build.platform == AppPlatform.Android) CollaborationNativePlatform.ANDROID
                else CollaborationNativePlatform.IOS
            if (device.platform != platform) return@withTimeoutOrNull invalid()
            val finalGate = gate()
            if (finalGate is AppResult.Failed) return@withTimeoutOrNull finalGate
            val beforeWrite = admitted()
            if (beforeWrite is AppResult.Failed) return@withTimeoutOrNull beforeWrite
            val command =
                CollaborationSpaceConversationCommand.Room(
                    CollaborationSpaceRoomRequestIntent(
                        Uuid.random().toString(),
                        owner.identityBinding(),
                        conversation.id,
                        CollaborationSpaceRoomRequest(device.id, device.version, retryAttempt),
                    ),
                    listOf(conversation.firstAccountId, conversation.secondAccountId).single {
                        it != access.accountId
                    },
                    clock.now(),
                    previous?.command?.id,
                )
            if (
                !validCollaborationSpaceConversationCommand(access, command) ||
                    (previous != null &&
                        !validCollaborationSpaceRoomHandoff(access, previous, command))
            )
                return@withTimeoutOrNull invalid()
            if (previous == null) insert(conversation, command)
            else replace(conversation, previous, command)
        }
            ?: failed(
                Failure(
                    FailureKind.Timeout,
                    "Room preparation timed out",
                    code = "client.collaboration_recovery_timeout",
                )
            )
    }

    private suspend fun active(
        conversation: CollaborationSpaceConversation
    ): AppResult<CollaborationSpaceConversationCommandRecord?> {
        val access = conversation.owner.localDirectoryAccess()
        val read = commands.observeAll(access).first()
        val snapshot = (read as? AppResult.Success)?.value?.takeIf { it.size <= 128 }?.toList()
        val current = owned(access)
        if (current is AppResult.Failed) return current
        if (read is AppResult.Failed) return read
        if (
            snapshot == null ||
                !validCollaborationSpaceConversationCommandInventory(access, snapshot) ||
                snapshot.any { it.successorId != null }
        )
            return invalid()
        val original =
            snapshot.singleOrNull {
                (it.command as? CollaborationSpaceConversationCommand.Room)
                    ?.intent
                    ?.conversationId == conversation.id
            } ?: return AppResult.Success(null)
        return if (validCollaborationSpaceRoomCommand(conversation, original))
            AppResult.Success(original)
        else invalid()
    }

    private suspend fun insert(
        conversation: CollaborationSpaceConversation,
        command: CollaborationSpaceConversationCommand.Room,
    ): AppResult<CollaborationSpaceConversationCommandRecord> {
        val access = conversation.owner.localDirectoryAccess()
        val inserted = commands.insert(access, command)
        val current = owned(access)
        if (current is AppResult.Failed) return current
        val read = commands.find(access, command.id)
        val afterRead = owned(access)
        if (afterRead is AppResult.Failed) return afterRead
        if (read is AppResult.Failed) return read
        (read as AppResult.Success).value?.let {
            return if (
                it.command == command &&
                    it.successorId == null &&
                    validCollaborationSpaceRoomCommand(conversation, it)
            )
                AppResult.Success(it)
            else invalid()
        }
        if (inserted is AppResult.Failed) return inserted
        return when ((inserted as AppResult.Success).value) {
            CollaborationSetupInsertion.OCCUPIED ->
                when (val winner = active(conversation)) {
                    is AppResult.Failed -> winner
                    is AppResult.Success ->
                        winner.value?.let { AppResult.Success(it) } ?: conflict()
                }
            CollaborationSetupInsertion.CAPACITY_EXCEEDED ->
                collaborationRecoveryFailure("client.command_queue_full", FailureKind.Storage)
            CollaborationSetupInsertion.SCOPE_CHANGED ->
                collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
            CollaborationSetupInsertion.STORED -> conflict()
        }
    }

    private suspend fun replace(
        conversation: CollaborationSpaceConversation,
        previous: CollaborationSpaceConversationCommandRecord,
        command: CollaborationSpaceConversationCommand.Room,
    ): AppResult<CollaborationSpaceConversationCommandRecord> {
        val access = conversation.owner.localDirectoryAccess()
        val inserted = commands.prepareReplacement(access, previous, command)
        val current = owned(access)
        if (current is AppResult.Failed) return current
        val original = commands.find(access, previous.command.id)
        val afterOriginal = owned(access)
        if (afterOriginal is AppResult.Failed) return afterOriginal
        if (original is AppResult.Failed) return original
        val archived = (original as AppResult.Success).value
        if (archived == null || archived.copy(successorId = null) != previous) return conflict()
        val nextId =
            archived.successorId
                ?: return if (inserted is AppResult.Failed) inserted else conflict()
        val next = commands.find(access, nextId)
        val afterNext = owned(access)
        if (afterNext is AppResult.Failed) return afterNext
        if (next is AppResult.Failed) return next
        val successor = (next as AppResult.Success).value ?: return conflict()
        val replacement =
            successor.command as? CollaborationSpaceConversationCommand.Room ?: return invalid()
        return if (
            successor.successorId == null &&
                replacement.id == nextId &&
                validCollaborationSpaceRoomCommand(conversation, successor) &&
                validCollaborationSpaceRoomHandoff(access, previous, replacement)
        )
            AppResult.Success(successor)
        else invalid()
    }

    private suspend fun owned(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationSpaceLocalAccess> {
        currentCoroutineContext().ensureActive()
        val session = sessions.current()
        currentCoroutineContext().ensureActive()
        return when (session) {
            is AppResult.Failed -> session
            is AppResult.Success ->
                localCollaborationSpaceAccess(access, session.value, server.audience)
        }
    }

    private fun invalid() =
        collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )

    private fun conflict() =
        collaborationRecoveryFailure("client.collaboration_command_changed", FailureKind.Conflict)
}
