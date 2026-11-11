package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** The incoming worker resolves owned bindings locally before any remote metadata acquisition. */
class ResolveCollaborationSpaceIncomingBinding(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val sync: CollaborationSpaceSyncRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val physicalRooms: CollaborationSpacePhysicalRoomRepository,
    private val conversations: CollaborationSpaceConversationRepository,
    private val metadata: CollaborationSpaceConversationMetadataRepository,
    private val originals: CollaborationSpaceOriginalConversationRepository,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** The one incoming worker serializes conversions and logical progression/completion. */
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingPhysicalRoomDemand,
    ): AppResult<CollaborationSpaceIncomingBindingResolution> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceIncomingPhysicalRoom(captured) ||
                !validCollaborationMessageId(runtime.deviceId) ||
                !validCollaborationMessageId(runtime.operationId)
        )
            return changed(FailureKind.Validation)
        val local =
            withTimeoutOrNull(5_000) { resolveLocal(runtime, captured) }
                ?: changed(FailureKind.Timeout)
        return when (local) {
            is AppResult.Failed -> local
            is AppResult.Success ->
                when (val result = local.value) {
                    is LocalResolution.Completed -> AppResult.Success(result.value)
                    is LocalResolution.Missing ->
                        MetadataAcquisition(runtime, captured, result).run()
                }
        }
    }

    /**
     * A missing local binding carries only the exact source and registered provider into live work.
     */
    private sealed interface LocalResolution {
        data class Completed(val value: CollaborationSpaceIncomingBindingResolution) :
            LocalResolution

        data class Missing(
            val source: CollaborationSpaceIncomingSource,
            val provider: CollaborationSpaceIdentity,
        ) : LocalResolution
    }

    private suspend fun resolveLocal(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingPhysicalRoomDemand,
    ): AppResult<LocalResolution> {
        val access = runtime.owner.localDirectoryAccess()
        admission(runtime).let { if (it is AppResult.Failed) return it }
        val source = sync.incomingSource(runtime)
        if (
            source == null ||
                !validCollaborationSpaceIncomingSource(source) ||
                source.storeId != captured.sourceId
        )
            return changed(FailureKind.Unavailable)
        val loaded = demands.current(access)
        val snapshot =
            (loaded as? AppResult.Success)
                ?.value
                ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        if ((loaded as AppResult.Success).value != null && snapshot == null) return changed()
        val selected =
            selectCollaborationSpaceIncomingPhysicalSnapshot(access, source, captured, snapshot)
        if (selected is AppResult.Failed) return selected
        val current =
            (selected as AppResult.Success).value
                ?: return AppResult.Success(
                    LocalResolution.Completed(CollaborationSpaceIncomingBindingResolution.Obsolete)
                )
        val registered = readRegistration(runtime, source)
        if (registered is AppResult.Failed) return registered
        val provider = (registered as AppResult.Success).value.command.provider
        val found = bindings.findByRoom(access, provider.serverName, captured.roomId)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (found is AppResult.Failed) return found
        val binding =
            (found as AppResult.Success).value
                ?: return AppResult.Success(LocalResolution.Missing(source, provider))
        if (
            !validCollaborationSpaceRoomBinding(access, binding) ||
                binding.room.serverName != provider.serverName ||
                binding.room.roomId != captured.roomId
        )
            return changed()
        return when (val result = convert(runtime, source, captured, current, binding)) {
            is AppResult.Failed -> result
            is AppResult.Success -> AppResult.Success(LocalResolution.Completed(result.value))
        }
    }

    private suspend fun readRegistration(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
    ): AppResult<CollaborationSpaceRegistrationRecord> {
        val access = runtime.owner.localDirectoryAccess()
        val loaded = registrations.current(access, runtime.owner.parent.nativeSessionId)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        val registration = (loaded as AppResult.Success).value
        if (
            registration == null ||
                !validCollaborationSpaceRegistrationRecord(access, registration) ||
                registration.command.binding.nativeSessionId !=
                    runtime.owner.parent.nativeSessionId ||
                registration.command.platform.name != build.platform.name.uppercase() ||
                (registration.outcome as? CollaborationSpaceRegistrationOutcome.Registered)
                    ?.receipt
                    ?.id != runtime.deviceId ||
                registration.command.provider.matrixUserId != source.matrixUserId
        )
            return changed()
        return AppResult.Success(registration)
    }

    private suspend fun convert(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
        captured: CollaborationSpaceIncomingPhysicalRoomDemand,
        snapshot: CollaborationSpaceIncomingDemandSnapshot,
        binding: CollaborationSpaceRoomBinding,
    ): AppResult<CollaborationSpaceIncomingBindingResolution> {
        val planned =
            resolveCollaborationSpaceIncomingPhysicalRoom(
                snapshot.demand,
                captured,
                binding.conversation.id,
            )
        if (planned is AppResult.Failed) return planned
        val next = (planned as AppResult.Success).value
        return if (next.demand == snapshot.demand) AppResult.Success(next.outcome)
        else retain(runtime, source, captured, snapshot, next)
    }

    /** A live metadata operation owns its deadline and remembered denial for one invocation. */
    private inner class MetadataAcquisition(
        val runtime: CollaborationSpaceRuntimeReady,
        val captured: CollaborationSpaceIncomingPhysicalRoomDemand,
        val missing: LocalResolution.Missing,
    ) {
        val owner = runtime.owner
        val access = owner.localDirectoryAccess()
        val source = missing.source
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var deadline: Instant? = null
        var denial: Failure? = null

        fun failed(failure: Failure) =
            AppResult.Failed(retainCollaborationSpaceConversationFailure(denial, failure))

        suspend fun run(): AppResult<CollaborationSpaceIncomingBindingResolution> =
            withTimeoutOrNull(15_000) { acquire() } ?: failed(changed(FailureKind.Timeout).failure)

        private suspend fun gate(): AppResult<Unit> {
            val owned = admission(runtime, source)
            if (owned is AppResult.Failed) return failed(owned.failure)
            val parent =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            if (parent is AppResult.Failed) return failed(parent.failure)
            val currentDeadline =
                Instant.fromEpochMilliseconds(
                    (parent as AppResult.Success).value.binding.expiresAtEpochMillis
                )
            deadline = deadline?.let { minOf(it, currentDeadline) } ?: currentDeadline
            val registration = readRegistration(runtime, source)
            if (registration is AppResult.Failed) return failed(registration.failure)
            if ((registration as AppResult.Success).value.command.provider != missing.provider)
                return failed(changed().failure)
            val now = clock.now()
            if (
                now < requestedAt ||
                    now >= requireNotNull(deadline) ||
                    now - requestedAt >= 15.seconds ||
                    started.elapsedNow() >=
                        minOf(15.seconds, requireNotNull(deadline) - requestedAt)
            )
                return failed(
                    collaborationRecoveryFailure(
                            "client.collaboration_revalidation_required",
                            FailureKind.Unavailable,
                        )
                        .failure
                )
            return denial?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
        }

        private suspend fun rejected(failure: Failure): AppResult.Failed {
            denial = retainCollaborationSpaceConversationFailure(denial, failure)
            if (collaborationSpaceConversationResponseClosesDirectory(failure)) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) { directory.restrict(access, failure) }
                }
            }
            currentCoroutineContext().ensureActive()
            val current = gate()
            return if (current is AppResult.Failed) current else failed(failure)
        }

        private suspend fun acquire(): AppResult<CollaborationSpaceIncomingBindingResolution> {
            gate().let { if (it is AppResult.Failed) return it }
            val found = physicalRooms.published(owner, missing.provider.serverName, captured.roomId)
            if (found is AppResult.Failed) {
                val checked = rejected(found.failure)
                return if (
                    checked.failure.kind == FailureKind.Unexpected &&
                        checked.failure.code == "collaboration_conversation_not_found"
                )
                    AppResult.Success(CollaborationSpaceIncomingBindingResolution.BindingMissing)
                else checked
            }
            gate().let { if (it is AppResult.Failed) return it }
            val physical = (found as AppResult.Success).value
            if (
                !validCollaborationSpacePhysicalRoomView(
                    physical,
                    owner,
                    missing.provider.serverName,
                    captured.roomId,
                )
            )
                return changed()
            val received = conversations.get(owner, physical.conversationId)
            if (received is AppResult.Failed) return rejected(received.failure)
            gate().let { if (it is AppResult.Failed) return it }
            val conversation = (received as AppResult.Success).value
            val view =
                CollaborationSpaceRoomView(conversation, physical.provisioning, physical.room)
            if (
                conversation.owner != owner ||
                    conversation.id != physical.conversationId ||
                    !validCollaborationSpaceRoomView(view)
            )
                return changed()
            val original =
                if (physical.room.origin == CollaborationSpaceRoomOrigin.LEGACY) {
                    val position = requireNotNull(physical.room.original).position
                    val read = originals.get(owner, position)
                    if (read is AppResult.Failed) return rejected(read.failure)
                    gate().let { if (it is AppResult.Failed) return it }
                    val value = (read as AppResult.Success).value
                    if (
                        !validCollaborationSpaceOriginalConversation(value, owner, position) ||
                            value.room == null
                    )
                        return changed()
                    CollaborationSpaceOriginalRoomMetadata(value.conversation, value.room)
                } else null
            val binding =
                CollaborationSpaceRoomBinding(conversation.localMetadata(), physical.room, original)
            if (!validCollaborationSpaceRoomBinding(access, binding)) return changed()
            val existing = metadata.getById(access, conversation.id)
            gate().let { if (it is AppResult.Failed) return it }
            if (existing is AppResult.Failed) return failed(existing.failure)
            val previous = (existing as AppResult.Success).value
            if (previous != null && previous != binding.conversation) return changed()
            if (previous == null) {
                val saved = metadata.retain(access, conversation)
                gate().let { if (it is AppResult.Failed) return it }
                val confirmed = metadata.getById(access, conversation.id)
                gate().let { if (it is AppResult.Failed) return it }
                if (confirmed is AppResult.Failed) return failed(confirmed.failure)
                if ((confirmed as AppResult.Success).value != binding.conversation)
                    return if (saved is AppResult.Failed) failed(saved.failure) else changed()
            }
            val saved = bindings.retain(access, binding)
            gate().let { if (it is AppResult.Failed) return it }
            val confirmed = bindings.getByConversation(access, conversation.id)
            gate().let { if (it is AppResult.Failed) return it }
            if (confirmed is AppResult.Failed) return failed(confirmed.failure)
            if ((confirmed as AppResult.Success).value != binding)
                return if (saved is AppResult.Failed) failed(saved.failure) else changed()
            // Network reads may span many producer commits. Convert from a fresh owned snapshot.
            val loaded = demands.current(access)
            val snapshot =
                (loaded as? AppResult.Success)
                    ?.value
                    ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            gate().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return failed(loaded.failure)
            if ((loaded as AppResult.Success).value != null && snapshot == null) return changed()
            val selected =
                selectCollaborationSpaceIncomingPhysicalSnapshot(access, source, captured, snapshot)
            if (selected is AppResult.Failed) return selected
            val current =
                (selected as AppResult.Success).value
                    ?: return AppResult.Success(
                        CollaborationSpaceIncomingBindingResolution.Obsolete
                    )
            val converted = convert(runtime, source, captured, current, binding)
            gate().let { if (it is AppResult.Failed) return it }
            return converted
        }
    }

    private suspend fun retain(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
        captured: CollaborationSpaceIncomingPhysicalRoomDemand,
        previous: CollaborationSpaceIncomingDemandSnapshot,
        next: CollaborationSpaceIncomingPhysicalResolution,
    ): AppResult<CollaborationSpaceIncomingBindingResolution> {
        val access = runtime.owner.localDirectoryAccess()
        val converted = next.demand.rooms.single { it.generation == captured.generation }
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        // Keep the independent incoming wake. Conversion does not create another scheduling turn.
        val written = demands.replace(access, previous, next.demand)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        val loaded = demands.current(access)
        val retained =
            (loaded as? AppResult.Success)
                ?.value
                ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        if (
            retained == null ||
                !validCollaborationMessageId(retained.generation) ||
                !validCollaborationSpaceIncomingDemand(access, retained.demand)
        )
            return if (written is AppResult.Failed) written else changed()
        val confirmed =
            confirmCollaborationSpaceIncomingPhysicalResolution(
                source,
                captured,
                converted,
                retained.demand,
                casRejected = written is AppResult.Success && written.value == null,
            )
        return if (confirmed is AppResult.Failed && written is AppResult.Failed) written
        else confirmed
    }

    private suspend fun admission(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource? = null,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val gate = directory.admission(runtime.owner.localDirectoryAccess())
        val parent = sessions.guardPushParent(server, build, runtime.owner.parent)
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success) {
            if (!validCollaborationSpaceDirectoryAdmission(gate.value)) return changed()
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        }
        if (parent is AppResult.Failed) return parent
        if (gate is AppResult.Failed) return gate
        if (source != null && sync.incomingSource(runtime) != source)
            return changed(FailureKind.Unavailable)
        return AppResult.Success(Unit)
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_incoming_binding_changed", kind)
}
