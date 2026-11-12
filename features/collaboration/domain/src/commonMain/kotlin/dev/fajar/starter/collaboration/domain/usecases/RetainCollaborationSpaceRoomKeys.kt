package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application-owned collector serializes durable intent before releasing each raw receipt. */
class RetainCollaborationSpaceRoomKeys(
    private val keys: CollaborationSpaceRoomKeyRepository,
    private val sync: CollaborationSpaceSyncRepository,
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val roots: CollaborationSpaceRootRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val originals: CollaborationSpaceOriginalRoomMetadataRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val schedules: SyncScheduleRepository,
) {
    private val collection = Mutex()

    /** The app must cancel/join on runtime change. No failed emission restarts this observation. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(runtime: CollaborationSpaceRuntimeReady): Flow<AppResult<Boolean>> = flow {
        collection.withLock {
            if (
                !validCollaborationOriginalConversationOwner(runtime.owner) ||
                    !validCollaborationSpaceConversationId(runtime.deviceId) ||
                    !validCollaborationSpaceConversationId(runtime.operationId)
            ) {
                emit(changed(FailureKind.Validation))
                return@withLock
            }
            val source = sync.incomingSource(runtime)
            if (source == null || !validCollaborationSpaceIncomingSource(source)) {
                emit(changed(FailureKind.Unavailable))
                return@withLock
            }
            val owner = Collection(runtime, source)
            val started = withTimeoutOrNull(5_000) { owner.start() } ?: changed(FailureKind.Timeout)
            if (started is AppResult.Failed) {
                emit(started)
                return@withLock
            }
            emitAll(
                keys.observeKeys(runtime, source).transformWhile { received ->
                    val result =
                        when (received) {
                            is AppResult.Failed -> received
                            is AppResult.Success -> {
                                val batch = captureCollaborationSpaceRoomKeyBatch(received.value)
                                if (
                                    batch == null ||
                                        batch.runtime != runtime ||
                                        batch.source != source
                                )
                                    changed(FailureKind.Validation)
                                else owner.retain(batch)
                            }
                        }
                    emit(result)
                    result is AppResult.Success
                }
            )
        }
    }

    private inner class Collection(
        val runtime: CollaborationSpaceRuntimeReady,
        val source: CollaborationSpaceIncomingSource,
    ) {
        val access = runtime.owner.localDirectoryAccess()
        val account = runtime.owner.parent.localSpaceAccountAccess()
        var registration: CollaborationSpaceRegistrationRecord? = null
        var legacyCompanyId: String? = null

        suspend fun start(): AppResult<Unit> {
            current().let { if (it is AppResult.Failed) return it }
            val started = keys.startKeyObservation(runtime, source)
            current().let { if (it is AppResult.Failed) return it }
            return started
        }

        suspend fun retain(batch: CollaborationSpaceRoomKeyBatch): AppResult<Boolean> {
            // Receipt UUID comes from the frozen raw occurrence and never changes between attempts.
            repeat(3) { attempt ->
                val result =
                    withTimeoutOrNull(5_000) { retainOnce(batch) } ?: changed(FailureKind.Timeout)
                if (result is AppResult.Success) {
                    if (result.value)
                        withTimeoutOrNull(1_500) {
                            schedules.request(CollaborationSpaceIncomingWake.WORK_KEY)
                        }
                    return result
                }
                val failure = (result as AppResult.Failed).failure
                val retryable =
                    failure.kind in setOf(FailureKind.Storage, FailureKind.Timeout) ||
                        (failure.kind == FailureKind.Conflict &&
                            failure.code == "client.collaboration_room_key_write_contended")
                if (!retryable || attempt == 2) return result
                delay((attempt + 1) * 250L)
            }
            error("The bounded receipt attempts always return")
        }

        suspend fun retainOnce(batch: CollaborationSpaceRoomKeyBatch): AppResult<Boolean> {
            val receipt =
                captureCollaborationSpaceRoomKeyReceipt(batch)
                    ?: return changed(FailureKind.Validation)
            current().let { if (it is AppResult.Failed) return it }
            val loaded = demands.current(access)
            val raw = (loaded as? AppResult.Success)?.value
            val previous = raw?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if (
                raw != null &&
                    (previous == null ||
                        !validCollaborationMessageId(previous.generation) ||
                        !validCollaborationSpaceIncomingDemand(access, previous.demand))
            )
                return changed()
            if (previous?.demand?.roomKeyReceipt == receipt) return acknowledge(batch, false)
            val classified = classify(batch)
            if (classified is AppResult.Failed) return classified
            val rooms = (classified as AppResult.Success).value
            val planned = planCollaborationSpaceRoomKeyRetention(previous, receipt, rooms)
            if (planned is AppResult.Failed) return planned
            val next = (planned as AppResult.Success).value
            if (!validCollaborationSpaceIncomingDemand(access, next)) return changed()
            current().let { if (it is AppResult.Failed) return it }
            val saved =
                demands.replace(
                    access,
                    previous,
                    next,
                    CollaborationSpaceIncomingWake(receipt.receiptId),
                )
            val written =
                when (saved) {
                    is AppResult.Failed -> saved
                    is AppResult.Success ->
                        AppResult.Success(
                            saved.value?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
                        )
                }
            current().let { if (it is AppResult.Failed) return it }
            if (saved is AppResult.Success && saved.value == null)
                return collaborationRecoveryFailure(
                    "client.collaboration_room_key_write_contended",
                    FailureKind.Conflict,
                )
            if (saved is AppResult.Success && (written as AppResult.Success).value == null)
                return changed()
            if (written is AppResult.Failed && written.failure.kind != FailureKind.Storage)
                return written
            val found = demands.current(access)
            val retained =
                (found as? AppResult.Success)
                    ?.value
                    ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (found is AppResult.Failed) return found
            val confirmed =
                confirmCollaborationSpaceRoomKeyRetention(
                    access,
                    previous,
                    receipt,
                    rooms,
                    next,
                    written,
                    retained,
                )
            if (confirmed is AppResult.Failed) return confirmed
            return acknowledge(batch, true)
        }

        suspend fun acknowledge(
            batch: CollaborationSpaceRoomKeyBatch,
            changedIntent: Boolean,
        ): AppResult<Boolean> {
            current().let { if (it is AppResult.Failed) return it }
            val released = keys.acknowledgeKeys(batch)
            if (released is AppResult.Failed) return released
            // False consumes nothing. The exact intent is already durable, so do not mint a new
            // request or ACK a successor when this occurrence was previously released.
            return AppResult.Success(changedIntent)
        }

        suspend fun classify(batch: CollaborationSpaceRoomKeyBatch): AppResult<List<String>?> {
            if (batch.reconcileRequired) return AppResult.Success(null)
            val companyId = requireNotNull(legacyCompanyId)
            val serverName = requireNotNull(registration).command.provider.serverName
            for (roomId in batch.roomIds) {
                val original = originals.findByRoom(access, companyId, serverName, roomId)
                admission().let { if (it is AppResult.Failed) return it }
                if (original is AppResult.Failed) return original
                val metadata = (original as AppResult.Success).value
                if (metadata != null) {
                    if (
                        !validCollaborationSpaceOriginalRoomMetadata(access, metadata) ||
                            metadata.conversation.position.companyId != companyId ||
                            metadata.room.roomId != roomId ||
                            metadata.room.serverName != serverName
                    )
                        return changed()
                    continue
                }
                val published = bindings.findByRoom(access, serverName, roomId)
                admission().let { if (it is AppResult.Failed) return it }
                if (published is AppResult.Failed) return published
                val binding =
                    (published as AppResult.Success).value ?: return AppResult.Success(null)
                if (
                    !validCollaborationSpaceRoomBinding(access, binding) ||
                        binding.room.roomId != roomId ||
                        binding.room.serverName != serverName
                )
                    return changed()
            }
            return AppResult.Success(batch.roomIds.toList())
        }

        suspend fun current(): AppResult<Unit> {
            admission().let { if (it is AppResult.Failed) return it }
            val loaded = registrations.current(access, runtime.owner.parent.nativeSessionId)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            val value = (loaded as AppResult.Success).value ?: return changed()
            if (registration != null && registration != value) return changed()
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return changed(FailureKind.Unavailable)
            val original =
                registeredCollaborationSpaceOriginalIncomingSdk(runtime, source, value, platform)
                    ?: return changed()
            registration = value
            legacyCompanyId = original.binding.companyId
            return AppResult.Success(Unit)
        }

        suspend fun admission(): AppResult<Unit> {
            localAdmission().let { if (it is AppResult.Failed) return it }
            val root = roots.current(account)
            localAdmission().let { if (it is AppResult.Failed) return it }
            if (root is AppResult.Failed) return root
            if ((root as AppResult.Success).value?.spaceId != access.spaceId) return changed()
            return AppResult.Success(Unit)
        }

        suspend fun localAdmission(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val gate = directory.admission(access)
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
            if (sync.incomingSource(runtime) != source) return changed(FailureKind.Unavailable)
            return AppResult.Success(Unit)
        }
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_room_key_retention_changed", kind)
}
