@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

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
import kotlin.uuid.Uuid
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
    private val originalMetadata: CollaborationSpaceOriginalRoomMetadataRepository,
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
            withTimeoutOrNull(5_000) {
                val access = runtime.owner.localDirectoryAccess()
                admission(runtime).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
                val source = sync.incomingSource(runtime)
                if (
                    source == null ||
                        !validCollaborationSpaceIncomingSource(source) ||
                        source.storeId != captured.sourceId
                )
                    return@withTimeoutOrNull changed(FailureKind.Unavailable)
                val loaded = demands.current(access)
                val snapshot =
                    (loaded as? AppResult.Success)
                        ?.value
                        ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
                admission(runtime, source).let {
                    if (it is AppResult.Failed) return@withTimeoutOrNull it
                }
                if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
                if ((loaded as AppResult.Success).value != null && snapshot == null)
                    return@withTimeoutOrNull changed()
                val selected =
                    selectCollaborationSpaceIncomingPhysicalSnapshot(
                        access,
                        source,
                        captured,
                        snapshot,
                    )
                if (selected is AppResult.Failed) return@withTimeoutOrNull selected
                val current =
                    (selected as AppResult.Success).value
                        ?: return@withTimeoutOrNull AppResult.Success(
                            LocalResolution.Completed(
                                CollaborationSpaceIncomingBindingResolution.Obsolete
                            )
                        )
                resolveRetainedBinding(runtime, captured, source, current)
            } ?: changed(FailureKind.Timeout)
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
     * Routes an exact logical alias before Published provider work. The worker owns serialization;
     * this local operation never acquires a provider page or restarts an old Space traversal.
     */
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingRoomDemand,
    ): AppResult<CollaborationSpaceIncomingBindingResolution> {
        currentCoroutineContext().ensureActive()
        val target =
            captured.target as? CollaborationSpaceIncomingTarget.Published
                ?: return changed(FailureKind.Validation)
        if (
            !validCollaborationSpaceIncomingRoomDemand(captured) ||
                !validCollaborationMessageId(runtime.deviceId) ||
                !validCollaborationMessageId(runtime.operationId)
        )
            return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) {
            val access = runtime.owner.localDirectoryAccess()
            admission(runtime).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            val loaded = demands.current(access)
            val snapshot =
                (loaded as? AppResult.Success)
                    ?.value
                    ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission(runtime).let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            if ((loaded as AppResult.Success).value == null)
                return@withTimeoutOrNull AppResult.Success(
                    CollaborationSpaceIncomingBindingResolution.Obsolete
                )
            if (
                snapshot == null ||
                    !validCollaborationMessageId(snapshot.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, snapshot.demand)
            )
                return@withTimeoutOrNull changed()
            val current = snapshot.demand.rooms.singleOrNull { it.target == target }
            if (current == null || current.generation != captured.generation)
                return@withTimeoutOrNull AppResult.Success(
                    CollaborationSpaceIncomingBindingResolution.Obsolete
                )
            if (current != captured) return@withTimeoutOrNull changed()
            val source = if (captured.sourceId != null) sync.incomingSource(runtime) else null
            if (
                captured.sourceId != null &&
                    (source == null ||
                        !validCollaborationSpaceIncomingSource(source) ||
                        source.storeId != captured.sourceId ||
                        snapshot.demand.sources.none { it.source == source })
            )
                return@withTimeoutOrNull changed(FailureKind.Unavailable)
            val found = bindings.getByConversation(access, target.conversationId)
            admission(runtime, source).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (found is AppResult.Failed) return@withTimeoutOrNull found
            val binding =
                (found as AppResult.Success).value
                    ?: return@withTimeoutOrNull AppResult.Success(
                        CollaborationSpaceIncomingBindingResolution.BindingMissing
                    )
            if (
                !validCollaborationSpaceRoomBinding(access, binding) ||
                    binding.conversation.id != target.conversationId
            )
                return@withTimeoutOrNull changed()
            if (binding.room.origin == CollaborationSpaceRoomOrigin.SPACE)
                return@withTimeoutOrNull AppResult.Success(
                    CollaborationSpaceIncomingBindingResolution.Published
                )
            resolveAlias(runtime, captured, snapshot, binding)
        } ?: changed(FailureKind.Timeout)
    }

    /** Retains canonical Company metadata before proposing an independently identified turn. */
    private suspend fun resolveAlias(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingRoomDemand,
        snapshot: CollaborationSpaceIncomingDemandSnapshot,
        binding: CollaborationSpaceRoomBinding,
    ): AppResult<CollaborationSpaceIncomingBindingResolution> {
        val source = sync.incomingSource(runtime)
        if (source == null || !validCollaborationSpaceIncomingSource(source))
            return AppResult.Success(
                CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource
            )
        if (captured.sourceId != null && captured.sourceId != source.storeId) return changed()
        val progress =
            snapshot.demand.sources.singleOrNull { it.source.storeId == source.storeId }
                ?: return AppResult.Success(
                    CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource
                )
        if (progress.source != source) return changed()
        val registered = readRegistration(runtime, source)
        if (registered is AppResult.Failed) return registered
        val registration = (registered as AppResult.Success).value
        val legacy =
            registeredCollaborationSpaceOriginalIncomingSdk(
                runtime,
                source,
                registration,
                registration.command.platform,
            )
                ?: return AppResult.Success(
                    CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource
                )
        val original = binding.original ?: return changed()
        if (original.conversation.position.companyId != legacy.binding.companyId)
            return AppResult.Success(
                CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource
            )
        if (binding.room.serverName != registration.command.provider.serverName) return changed()
        val access = runtime.owner.localDirectoryAccess()
        val gate: suspend () -> AppResult<Unit> = {
            val checked = readRegistration(runtime, source, registration)
            if (checked is AppResult.Failed) checked
            else {
                val alias = bindings.getByConversation(access, binding.conversation.id)
                val admitted = admission(runtime, source)
                when {
                    admitted is AppResult.Failed -> admitted
                    alias is AppResult.Failed -> alias
                    (alias as AppResult.Success).value != binding -> changed()
                    else -> AppResult.Success(Unit)
                }
            }
        }
        gate().let { if (it is AppResult.Failed) return it }
        val planned =
            routeCollaborationSpaceIncomingAlias(
                snapshot.demand,
                captured,
                source,
                CollaborationSpaceIncomingTarget.Original(original.conversation.position),
                Uuid.random().toString(),
            )
        if (planned is AppResult.Failed) return planned
        val next = (planned as AppResult.Success).value
        if (next.demand == snapshot.demand) return AppResult.Success(next.outcome)
        val retained = retainOriginalMetadata(access, original, gate)
        if (retained is AppResult.Failed) return retained
        return retainAlias(runtime, source, captured, snapshot, next, gate)
    }

    /** The one CAS removes only the alias capture and schedules a fresh Original turn. */
    private suspend fun retainAlias(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
        captured: CollaborationSpaceIncomingRoomDemand,
        previous: CollaborationSpaceIncomingDemandSnapshot,
        next: CollaborationSpaceIncomingAliasResolution,
        gate: suspend () -> AppResult<Unit>,
    ): AppResult<CollaborationSpaceIncomingBindingResolution> {
        val access = runtime.owner.localDirectoryAccess()
        val converted = next.demand.rooms.single { it.position == captured.position }
        gate().let { if (it is AppResult.Failed) return it }
        val written = demands.replace(access, previous, next.demand)
        gate().let { if (it is AppResult.Failed) return it }
        val loaded = demands.current(access)
        val retained =
            (loaded as? AppResult.Success)
                ?.value
                ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
        gate().let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        if (
            retained == null ||
                !validCollaborationMessageId(retained.generation) ||
                !validCollaborationSpaceIncomingDemand(access, retained.demand)
        )
            return if (written is AppResult.Failed) written else changed()
        val confirmed =
            confirmCollaborationSpaceIncomingAliasRouting(
                source,
                captured,
                converted,
                retained.demand,
                casRejected = written is AppResult.Success && written.value == null,
            )
        return if (confirmed is AppResult.Failed && written is AppResult.Failed) written
        else confirmed
    }

    /**
     * A missing local binding carries the exact source and complete registration into live work.
     */
    private sealed interface LocalResolution {
        data class Completed(val value: CollaborationSpaceIncomingBindingResolution) :
            LocalResolution

        data class Missing(
            val source: CollaborationSpaceIncomingSource,
            val registration: CollaborationSpaceRegistrationRecord,
        ) : LocalResolution
    }

    /**
     * Resolves immutable room metadata after the invocation has selected its exact physical task.
     */
    private suspend fun resolveRetainedBinding(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingPhysicalRoomDemand,
        source: CollaborationSpaceIncomingSource,
        current: CollaborationSpaceIncomingDemandSnapshot,
    ): AppResult<LocalResolution> {
        val access = runtime.owner.localDirectoryAccess()
        val registered = readRegistration(runtime, source)
        if (registered is AppResult.Failed) return registered
        val registration = (registered as AppResult.Success).value
        val provider = registration.command.provider
        val gate: suspend () -> AppResult<Unit> = {
            when (val checked = readRegistration(runtime, source, registration)) {
                is AppResult.Failed -> checked
                is AppResult.Success -> AppResult.Success(Unit)
            }
        }
        val found = bindings.findByRoom(access, provider.serverName, captured.roomId)
        gate().let { if (it is AppResult.Failed) return it }
        if (found is AppResult.Failed) return found
        val binding = (found as AppResult.Success).value
        if (
            binding != null &&
                (!validCollaborationSpaceRoomBinding(access, binding) ||
                    binding.room.serverName != provider.serverName ||
                    binding.room.roomId != captured.roomId)
        )
            return changed()
        val originalSource =
            registeredCollaborationSpaceOriginalIncomingSdk(
                runtime,
                source,
                registration,
                registration.command.platform,
            )
        val target =
            if (binding?.room?.origin == CollaborationSpaceRoomOrigin.SPACE) {
                CollaborationSpaceIncomingTarget.Published(binding.conversation.id)
            } else {
                val legacy =
                    originalSource
                        ?: return AppResult.Success(
                            if (binding == null) LocalResolution.Missing(source, registration)
                            else
                                LocalResolution.Completed(
                                    CollaborationSpaceIncomingBindingResolution
                                        .WaitingForOriginalSource
                                )
                        )
                val original =
                    binding?.original
                        ?: run {
                            val read =
                                originalMetadata.findByRoom(
                                    access,
                                    legacy.binding.companyId,
                                    provider.serverName,
                                    captured.roomId,
                                )
                            gate().let { if (it is AppResult.Failed) return it }
                            if (read is AppResult.Failed) return read
                            (read as AppResult.Success).value
                                ?: return AppResult.Success(
                                    LocalResolution.Missing(source, registration)
                                )
                        }
                if (original.conversation.position.companyId != legacy.binding.companyId)
                    return AppResult.Success(
                        LocalResolution.Completed(
                            CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource
                        )
                    )
                if (
                    !validCollaborationSpaceOriginalRoomMetadata(access, original) ||
                        original.room.serverName != provider.serverName ||
                        original.room.roomId != captured.roomId
                )
                    return changed()
                val retained = retainOriginalMetadata(access, original, gate)
                if (retained is AppResult.Failed) return retained
                CollaborationSpaceIncomingTarget.Original(original.conversation.position)
            }
        return when (val result = convert(runtime, source, captured, current, target, gate)) {
            is AppResult.Failed -> result
            is AppResult.Success -> AppResult.Success(LocalResolution.Completed(result.value))
        }
    }

    private suspend fun readRegistration(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
        expected: CollaborationSpaceRegistrationRecord? = null,
    ): AppResult<CollaborationSpaceRegistrationRecord> {
        val access = runtime.owner.localDirectoryAccess()
        val loaded = registrations.current(access, runtime.owner.parent.nativeSessionId)
        admission(runtime, source).let { if (it is AppResult.Failed) return it }
        if (loaded is AppResult.Failed) return loaded
        val registration = (loaded as AppResult.Success).value
        if (
            registration == null ||
                (expected != null && registration != expected) ||
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
        target: CollaborationSpaceIncomingTarget,
        gate: suspend () -> AppResult<Unit>,
    ): AppResult<CollaborationSpaceIncomingBindingResolution> {
        gate().let { if (it is AppResult.Failed) return it }
        val planned =
            resolveCollaborationSpaceIncomingPhysicalRoom(snapshot.demand, captured, target)
        if (planned is AppResult.Failed) return planned
        val next = (planned as AppResult.Success).value
        return if (next.demand == snapshot.demand) AppResult.Success(next.outcome)
        else retain(runtime, source, captured, snapshot, next, gate)
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
        val registration = missing.registration
        val provider = registration.command.provider
        val originalSource =
            registeredCollaborationSpaceOriginalIncomingSdk(
                runtime,
                source,
                registration,
                registration.command.platform,
            )
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
            val registered = readRegistration(runtime, source, registration)
            if (registered is AppResult.Failed) return failed(registered.failure)
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
            val found = physicalRooms.published(owner, provider.serverName, captured.roomId)
            if (found is AppResult.Failed) {
                if (
                    found.failure.kind == FailureKind.Unexpected &&
                        found.failure.code == "collaboration_conversation_not_found"
                ) {
                    gate().let { if (it is AppResult.Failed) return it }
                    return acquireOriginal()
                }
                return rejected(found.failure)
            }
            gate().let { if (it is AppResult.Failed) return it }
            val physical = (found as AppResult.Success).value
            if (
                !validCollaborationSpacePhysicalRoomView(
                    physical,
                    owner,
                    provider.serverName,
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
                    if (
                        originalSource == null ||
                            position.companyId != originalSource.binding.companyId
                    )
                        return AppResult.Success(
                            CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource
                        )
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
            if (original != null) {
                val retained = retainOriginalMetadata(access, original, ::gate)
                if (retained is AppResult.Failed) return failed(retained.failure)
            }
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
            val target =
                original?.let {
                    CollaborationSpaceIncomingTarget.Original(it.conversation.position)
                } ?: CollaborationSpaceIncomingTarget.Published(conversation.id)
            return convertCurrent(target)
        }

        /** Only authoritative Published absence permits one Company-qualified fallback. */
        private suspend fun acquireOriginal():
            AppResult<CollaborationSpaceIncomingBindingResolution> {
            val legacy =
                originalSource
                    ?: return AppResult.Success(
                        CollaborationSpaceIncomingBindingResolution.WaitingForOriginalSource
                    )
            gate().let { if (it is AppResult.Failed) return it }
            val found =
                physicalRooms.original(
                    owner,
                    legacy.binding.companyId,
                    provider.serverName,
                    captured.roomId,
                )
            if (found is AppResult.Failed) {
                if (
                    found.failure.kind == FailureKind.Unexpected &&
                        found.failure.code == "collaboration_conversation_not_found"
                ) {
                    gate().let { if (it is AppResult.Failed) return it }
                    return AppResult.Success(
                        CollaborationSpaceIncomingBindingResolution.BindingMissing
                    )
                }
                return rejected(found.failure)
            }
            gate().let { if (it is AppResult.Failed) return it }
            val value = (found as AppResult.Success).value
            if (
                !validCollaborationSpaceOriginalConversation(value, owner) ||
                    value.conversation.position.companyId != legacy.binding.companyId ||
                    value.room == null ||
                    value.room.serverName != provider.serverName ||
                    value.room.roomId != captured.roomId
            )
                return changed()
            val retained =
                retainOriginalMetadata(
                    access,
                    CollaborationSpaceOriginalRoomMetadata(value.conversation, value.room),
                    ::gate,
                )
            if (retained is AppResult.Failed) return failed(retained.failure)
            return convertCurrent(
                CollaborationSpaceIncomingTarget.Original(value.conversation.position)
            )
        }

        private suspend fun convertCurrent(
            target: CollaborationSpaceIncomingTarget
        ): AppResult<CollaborationSpaceIncomingBindingResolution> {
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
            val converted = convert(runtime, source, captured, current, target, ::gate)
            gate().let { if (it is AppResult.Failed) return it }
            return converted
        }
    }

    /** Confirms both immutable Original indexes within the caller's local or admitted deadline. */
    private suspend fun retainOriginalMetadata(
        access: CollaborationSpaceLocalAccess,
        value: CollaborationSpaceOriginalRoomMetadata,
        gate: suspend () -> AppResult<Unit>,
    ): AppResult<Unit> {
        gate().let { if (it is AppResult.Failed) return it }
        val existing = originalMetadata.getByConversation(access, value.conversation.position)
        gate().let { if (it is AppResult.Failed) return it }
        if (existing is AppResult.Failed) return existing
        val indexed =
            originalMetadata.findByRoom(
                access,
                value.conversation.position.companyId,
                value.room.serverName,
                value.room.roomId,
            )
        gate().let { if (it is AppResult.Failed) return it }
        if (indexed is AppResult.Failed) return indexed
        val previous = (existing as AppResult.Success).value
        val previousIndex = (indexed as AppResult.Success).value
        if (previous == value && previousIndex == value) return AppResult.Success(Unit)
        // The repository writes both indexes atomically. A partial or conflicting index is not
        // absence.
        if (previous != null || previousIndex != null) return changed()
        val written = originalMetadata.retain(access, value)
        gate().let { if (it is AppResult.Failed) return it }
        val confirmed = originalMetadata.getByConversation(access, value.conversation.position)
        gate().let { if (it is AppResult.Failed) return it }
        if (confirmed is AppResult.Failed) return confirmed
        val confirmedIndex =
            originalMetadata.findByRoom(
                access,
                value.conversation.position.companyId,
                value.room.serverName,
                value.room.roomId,
            )
        gate().let { if (it is AppResult.Failed) return it }
        if (confirmedIndex is AppResult.Failed) return confirmedIndex
        return if (
            (confirmed as AppResult.Success).value == value &&
                (confirmedIndex as AppResult.Success).value == value
        )
            AppResult.Success(Unit)
        else if (written is AppResult.Failed) written else changed()
    }

    private suspend fun retain(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
        captured: CollaborationSpaceIncomingPhysicalRoomDemand,
        previous: CollaborationSpaceIncomingDemandSnapshot,
        next: CollaborationSpaceIncomingPhysicalResolution,
        gate: suspend () -> AppResult<Unit>,
    ): AppResult<CollaborationSpaceIncomingBindingResolution> {
        val access = runtime.owner.localDirectoryAccess()
        val converted = next.demand.rooms.single { it.generation == captured.generation }
        gate().let { if (it is AppResult.Failed) return it }
        // Keep the independent incoming wake. Conversion does not create another scheduling turn.
        val written = demands.replace(access, previous, next.demand)
        gate().let { if (it is AppResult.Failed) return it }
        val loaded = demands.current(access)
        val retained =
            (loaded as? AppResult.Success)
                ?.value
                ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
        gate().let { if (it is AppResult.Failed) return it }
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
