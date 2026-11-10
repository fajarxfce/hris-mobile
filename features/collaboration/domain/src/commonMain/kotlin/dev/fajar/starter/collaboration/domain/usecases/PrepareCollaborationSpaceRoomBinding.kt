package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/**
 * Recovers owned immutable facts before finite native acquisition. Never opens or authorizes an
 * SDK. A new binding retains recovery work atomically; scheduling is a bounded hint, independent of
 * the confirmed local binding returned here.
 */
class PrepareCollaborationSpaceRoomBinding(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val rooms: CollaborationSpaceRoomRepository,
    private val originals: CollaborationSpaceOriginalConversationRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val schedules: SyncScheduleRepository,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        conversation: CollaborationSpaceConversation
    ): AppResult<CollaborationSpaceRoomBinding> {
        val owner = conversation.owner
        val access = owner.localDirectoryAccess()
        var denial: Failure? = null
        fun failed(failure: Failure) =
            AppResult.Failed(retainCollaborationSpaceConversationFailure(denial, failure))
        return withTimeoutOrNull(15_000) {
            val retained = readOwnedBinding(conversation)
            if (retained is AppResult.Failed) return@withTimeoutOrNull retained
            (retained as AppResult.Success).value?.let {
                return@withTimeoutOrNull AppResult.Success(it)
            }

            // This deadline starts only when local facts are absent. It never ages local reads.
            val requestedAt = clock.now()
            val started = timeSource.markNow()
            var deadline: Instant? = null
            suspend fun admitted(): AppResult<Unit> {
                currentCoroutineContext().ensureActive()
                val captured =
                    sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
                currentCoroutineContext().ensureActive()
                if (captured is AppResult.Failed)
                    return AppResult.Failed(
                        denial?.takeIf(::collaborationSpaceConversationResponseClosesDirectory)
                            ?: captured.failure
                    )
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
                            "Room binding preparation expired",
                            code = "client.collaboration_revalidation_required",
                        )
                    )
                return AppResult.Success(Unit)
            }
            suspend fun gate(): AppResult<Unit> {
                val read = directory.admission(access)
                if (
                    read is AppResult.Success &&
                        validCollaborationSpaceDirectoryAdmission(read.value)
                ) {
                    read.value.denial?.let {
                        denial = retainCollaborationSpaceConversationFailure(denial, it)
                    }
                }
                val current = admitted()
                if (current is AppResult.Failed) return current
                if (read is AppResult.Failed) return failed(read.failure)
                val admission = (read as AppResult.Success).value
                if (!validCollaborationSpaceDirectoryAdmission(admission))
                    return failed(invalid().failure)
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
                if (current is AppResult.Failed) return current
                val latest = gate()
                return if (latest is AppResult.Failed) latest else failed(failure)
            }

            val initialGate = gate()
            if (initialGate is AppResult.Failed) return@withTimeoutOrNull initialGate
            val readRoom = rooms.get(conversation)
            if (readRoom is AppResult.Failed) return@withTimeoutOrNull rejected(readRoom.failure)
            val afterRoom = gate()
            if (afterRoom is AppResult.Failed) return@withTimeoutOrNull afterRoom
            val view = (readRoom as AppResult.Success).value
            if (view.conversation != conversation || !validCollaborationSpaceRoomView(view))
                return@withTimeoutOrNull failed(invalid().failure)
            val room =
                view.room
                    ?: return@withTimeoutOrNull collaborationRecoveryFailure(
                        "client.collaboration_room_not_ready",
                        FailureKind.Unavailable,
                    )
            val original =
                if (room.origin == CollaborationSpaceRoomOrigin.LEGACY) {
                    val position = requireNotNull(room.original).position
                    val read = originals.get(owner, position)
                    if (read is AppResult.Failed) return@withTimeoutOrNull rejected(read.failure)
                    val afterOriginal = gate()
                    if (afterOriginal is AppResult.Failed) return@withTimeoutOrNull afterOriginal
                    val value = (read as AppResult.Success).value
                    if (
                        !validCollaborationSpaceOriginalConversation(value, owner, position) ||
                            value.room == null
                    )
                        return@withTimeoutOrNull failed(invalid().failure)
                    CollaborationSpaceOriginalRoomMetadata(value.conversation, value.room)
                } else null
            val binding =
                CollaborationSpaceRoomBinding(conversation.localMetadata(), room, original)
            if (!validCollaborationSpaceRoomBinding(access, binding))
                return@withTimeoutOrNull failed(invalid().failure)
            val canonical = metadata.getById(access, conversation.id)
            val afterCanonical = gate()
            if (afterCanonical is AppResult.Failed) return@withTimeoutOrNull afterCanonical
            if (canonical is AppResult.Failed) return@withTimeoutOrNull failed(canonical.failure)
            val previous = (canonical as AppResult.Success).value
            if (previous != null && previous != binding.conversation)
                return@withTimeoutOrNull failed(conflict().failure)
            if (previous == null) {
                val written = metadata.retain(access, conversation)
                val afterWrite = gate()
                if (afterWrite is AppResult.Failed) return@withTimeoutOrNull afterWrite
                val confirmed = metadata.getById(access, conversation.id)
                val afterConfirmation = gate()
                if (afterConfirmation is AppResult.Failed)
                    return@withTimeoutOrNull afterConfirmation
                if (confirmed is AppResult.Failed)
                    return@withTimeoutOrNull failed(confirmed.failure)
                if ((confirmed as AppResult.Success).value != binding.conversation) {
                    return@withTimeoutOrNull if (written is AppResult.Failed)
                        failed(written.failure)
                    else failed(conflict().failure)
                }
            }

            val written = bindings.retain(access, binding)
            val afterWrite = gate()
            if (afterWrite is AppResult.Failed) return@withTimeoutOrNull afterWrite
            // A lost response is reconciled by the same immutable conversation, never another
            // write.
            val confirmed = bindings.getByConversation(access, conversation.id)
            val afterConfirmation = gate()
            if (afterConfirmation is AppResult.Failed) return@withTimeoutOrNull afterConfirmation
            if (confirmed is AppResult.Failed) return@withTimeoutOrNull failed(confirmed.failure)
            if ((confirmed as AppResult.Success).value != binding) {
                return@withTimeoutOrNull if (written is AppResult.Failed) failed(written.failure)
                else failed(conflict().failure)
            }
            // The repository already retained recovery work with the binding. A failed or lost
            // scheduling response leaves that marker for lifecycle recovery, not another write.
            withTimeoutOrNull(1_500) { schedules.request(CollaborationSpaceRecoveryWake.WORK_KEY) }
            currentCoroutineContext().ensureActive()
            val afterSchedule = gate()
            if (afterSchedule is AppResult.Failed) return@withTimeoutOrNull afterSchedule
            AppResult.Success(binding)
        }
            ?: failed(
                Failure(
                    FailureKind.Timeout,
                    "Room binding preparation timed out",
                    code = "client.collaboration_recovery_timeout",
                )
            )
    }

    /** Local ownership is independent of native expiry and current content admission. */
    private suspend fun readOwnedBinding(
        conversation: CollaborationSpaceConversation
    ): AppResult<CollaborationSpaceRoomBinding?> {
        val access = conversation.owner.localDirectoryAccess()
        val initial = owned(access)
        if (initial is AppResult.Failed) return initial
        if (!validCollaborationSpaceConversation(conversation))
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        val retained = bindings.getByConversation(access, conversation.id)
        val afterRead = owned(access)
        if (afterRead is AppResult.Failed) return afterRead
        if (retained is AppResult.Failed) return retained
        val value = (retained as AppResult.Success).value
        return if (
            value == null ||
                (validCollaborationSpaceRoomBinding(access, value) &&
                    value.conversation == conversation.localMetadata())
        )
            AppResult.Success(value)
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
        collaborationRecoveryFailure("client.collaboration_room_changed", FailureKind.Conflict)
}
