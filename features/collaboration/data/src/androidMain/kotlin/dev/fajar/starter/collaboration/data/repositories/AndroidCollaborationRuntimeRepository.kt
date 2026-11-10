package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.api.CollaborationHistoryApi
import dev.fajar.starter.collaboration.data.api.CollaborationPusherApi
import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.mappers.collaborationSdkAcquisitionFailure
import dev.fajar.starter.collaboration.data.mappers.toLocalDeviceIdentity
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationGatewayMatchesCommand
import dev.fajar.starter.collaboration.domain.policies.validCollaborationEventId
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceSetupRecord
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import io.ktor.client.HttpClient
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import org.matrix.rustcomponents.sdk.Client

/**
 * One application instance owns its SDK resources, including resources still finishing shutdown.
 */
class AndroidCollaborationRuntimeRepository(
    private val stores: CollaborationSdkStoreSource,
    private val credentialFactory: (String) -> AtomicCredentialStore,
    private val clients: AndroidCollaborationClientSource,
    private val cipher: ContentCipher,
    private val server: IdentityServer,
    private val endpoint: CollaborationSdkEndpoint,
    private val http: HttpClient,
    private val inventories: CollaborationSdkInventorySource? = null,
    private val privateIdentities: CollaborationSdkPrivateIdentitySource? = null,
    private val httpObservation: AndroidCollaborationHttpObservation? = null,
) :
    CollaborationRuntimeRepository,
    CollaborationLocalDeviceIdentityRepository,
    CollaborationSpaceRuntimeRepository,
    CollaborationSpaceSyncRepository,
    CollaborationSpacePusherRepository {
    override val supportsDeviceActivation = true

    private val readiness =
        MutableSharedFlow<CollaborationReadinessSignal>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun readinessSignals() = readiness.asSharedFlow()

    private val spaceReadiness =
        MutableSharedFlow<CollaborationSpaceReadinessSignal>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun spaceReadinessSignals() = spaceReadiness.asSharedFlow()

    private val credentialStates =
        MutableSharedFlow<CollaborationCredentialSignal>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun credentialSignals() = credentialStates.asSharedFlow()

    private val registry =
        SdkRuntimeRegistry<AndroidCollaborationRuntimeLease>(
            { it.availableForEntry && it.credentialFailures.value == null },
            { it.requestClose() },
        )
    private val legacyLeases
        get() = registry.legacyResources()

    override suspend fun reserveRejectedInspection(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationRuntimeInspection> {
        currentCoroutineContext().ensureActive()
        val closing = drain(registry.resources().filter { it.closingRequested })
        if (closing is AppResult.Failed) return closing
        val scoped = safeStorageCall { collaborationSdkScope(owner, deviceId, server, endpoint) }
        if (scoped is AppResult.Failed) return scoped
        val scope = (scoped as AppResult.Success).value
        val existing = registry.find(owner, deviceId) ?: return reserveInspection(owner, deviceId)
        if (existing.credentialFailures.value?.code != "client.collaboration_sdk_unauthorized")
            return AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Chat recovery owner changed.",
                    code = "client.collaboration_runtime_not_retained",
                )
            )
        val reserved = registry.beginCredentialReplacement(scope.storeId, owner, deviceId, existing)
        if (reserved is AppResult.Failed) return reserved
        val gate = (reserved as AppResult.Success).value
        try {
            val drained = drainHandover(gate)
            if (drained is AppResult.Failed) return drained
            currentCoroutineContext().ensureActive()
            val acquired = registry.reserveCredentialReplacement(gate)
            if (acquired is AppResult.Failed) return acquired
            val reservation = (acquired as AppResult.Success).value
            return AppResult.Success(
                object : CollaborationRuntimeInspection.Reserved {
                    override val owner = reservation.owner
                    override val deviceId = reservation.deviceId

                    override fun isCurrent() = registry.accepts(reservation)

                    override fun close() = registry.released(reservation)
                }
            )
        } finally {
            registry.endHandover(gate)
        }
    }

    override suspend fun reserveInspection(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationRuntimeInspection> {
        currentCoroutineContext().ensureActive()
        val scoped = safeStorageCall { collaborationSdkScope(owner, deviceId, server, endpoint) }
        if (scoped is AppResult.Failed) return scoped
        val scope = (scoped as AppResult.Success).value
        val acquired = registry.reserveInspection(scope.storeId, owner, deviceId)
        if (acquired is AppResult.Failed) return acquired
        val reservation =
            (acquired as AppResult.Success).value
                ?: return AppResult.Success(CollaborationRuntimeInspection.Open)
        return AppResult.Success(
            object : CollaborationRuntimeInspection.Reserved {
                override val owner = reservation.owner
                override val deviceId = reservation.deviceId

                override fun isCurrent() = registry.accepts(reservation)

                override fun close() = registry.released(reservation)
            }
        )
    }

    override suspend fun acquireOriginalQueue(
        runtime: CollaborationSpaceRuntimeReady,
        matrixRoomId: String,
    ): AppResult<CollaborationSpaceOriginalQueueLease> {
        currentCoroutineContext().ensureActive()
        val lease =
            registry.findSpace(runtime.owner, runtime.deviceId, runtime.operationId)
                ?: return handoverPending()
        var child: CollaborationMessageQueueLease? = null
        var handedOver = false
        var primary: Throwable? = null
        try {
            val acquired = lease.acquireMessageQueue(matrixRoomId)
            if (acquired is AppResult.Success) child = acquired.value
            currentCoroutineContext().ensureActive()
            if (acquired is AppResult.Failed) return acquired
            val original = requireNotNull(child)
            val restriction: () -> Failure? = {
                lease.credentialFailures.value
                    ?: if (
                        registry.findSpace(runtime.owner, runtime.deviceId, runtime.operationId) !==
                            lease
                    )
                        handoverPending().failure
                    else null
            }
            restriction()?.let {
                return AppResult.Failed(it)
            }
            if (
                original.owner.audience != runtime.owner.parent.audience ||
                    original.owner.accountId != runtime.owner.parent.accountId ||
                    original.device.roomId != matrixRoomId
            )
                return handoverPending()
            return AppResult.Success(
                    AndroidCollaborationSpaceOriginalQueueLease(
                        runtime,
                        original,
                        { lease.readHeldOriginalQueue(original) },
                        restriction,
                    )
                )
                .also { handedOver = true }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            if (!handedOver)
                withContext(NonCancellable) {
                    try {
                        child?.close()
                    } catch (failure: Throwable) {
                        // Acquisition failure/cancellation remains primary; the parent keeps the
                        // unfinished writer reserved until its own drain can complete.
                        primary?.addSuppressed(failure)
                    }
                }
        }
    }

    override suspend fun inspectOriginalQueue(
        runtime: CollaborationSpaceRuntimeReady,
        matrixRoomId: String,
    ): AppResult<CollaborationSpaceOriginalQueueSnapshot> {
        currentCoroutineContext().ensureActive()
        val lease =
            registry.findSpace(runtime.owner, runtime.deviceId, runtime.operationId)
                ?: return handoverPending()
        val result = lease.inspectOriginalQueue(matrixRoomId)
        currentCoroutineContext().ensureActive()
        lease.credentialFailures.value?.let {
            return AppResult.Failed(it)
        }
        if (registry.findSpace(runtime.owner, runtime.deviceId, runtime.operationId) !== lease)
            return handoverPending()
        return when (result) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                AppResult.Success(CollaborationSpaceOriginalQueueSnapshot(runtime, result.value))
        }
    }

    override suspend fun synchronize(runtime: CollaborationSpaceRuntimeReady): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val lease =
            registry.findSpace(runtime.owner, runtime.deviceId, runtime.operationId)
                ?: return handoverPending()
        val result = lease.synchronizeDevice()
        currentCoroutineContext().ensureActive()
        lease.credentialFailures.value?.let {
            return AppResult.Failed(it)
        }
        if (registry.findSpace(runtime.owner, runtime.deviceId, runtime.operationId) !== lease) {
            return AppResult.Failed(
                collaborationSdkAcquisitionFailure(result, handoverPending().failure)
            )
        }
        return result
    }

    override suspend fun activate(runtime: CollaborationSpaceRuntimeReady): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val lease =
            registry.findSpace(runtime.owner, runtime.deviceId, runtime.operationId)
                ?: return handoverPending()
        val result = lease.activatePusher()
        currentCoroutineContext().ensureActive()
        lease.credentialFailures.value?.let {
            return AppResult.Failed(it)
        }
        if (registry.findSpace(runtime.owner, runtime.deviceId, runtime.operationId) !== lease) {
            return AppResult.Failed(
                collaborationSdkAcquisitionFailure(result, handoverPending().failure)
            )
        }
        return result
    }

    override suspend fun isSpaceOpen(
        owner: CollaborationSpaceOwner,
        deviceId: String,
        operationId: String,
    ): AppResult<Boolean> = safeStorageCall {
        registry.findSpace(owner, deviceId, operationId) != null
    }

    override suspend fun closeSpace(owner: CollaborationSpaceOwner): AppResult<Unit> =
        drain(registry.closeSpace(owner))

    override suspend fun installOriginal(
        owner: CollaborationSpaceOwner,
        original: CollaborationExistingRuntimeHandover,
        setup: CollaborationSpaceSetupRecord,
    ): AppResult<CollaborationSpaceRuntimeLease> {
        val prepared = safeStorageCall {
            val command = setup.command
            val local =
                CollaborationSpaceLocalAccess(
                    owner.parent.sessionId,
                    owner.parent.audience,
                    owner.parent.accountId,
                    owner.spaceId,
                )
            require(validCollaborationSpaceSetupRecord(local, setup) && setup.successorId == null)
            require(command.platform == CollaborationNativePlatform.ANDROID)
            require(command.binding.nativeSessionId == owner.parent.nativeSessionId)
            require(command.binding.spaceId == original.intent.spaceId)
            require(command.request.deviceId == original.intent.deviceId)
            require(command.request.deviceVersion == original.intent.expectedDeviceVersion + 1)
            val gateway =
                when (val outcome = setup.outcome) {
                    is CollaborationSpaceSetupOutcome.Bootstrapped -> outcome.gateway
                    is CollaborationSpaceSetupOutcome.BootstrapReplaced -> outcome.receipt.gateway
                    else -> error("Original runtime requires confirmed credentials.")
                }
            require(original.intent.fingerprint == original.original.identity.fingerprint)
            val binding = original.original.binding
            val physical =
                CollaborationOwner(
                    owner.parent.sessionId,
                    binding.audience,
                    binding.accountId,
                    binding.companyId,
                    binding.nativeSessionId,
                )
            val scope =
                collaborationSdkScope(physical, original.original.deviceId, server, endpoint)
            CollaborationSdkRecordCodec(scope).validateHandoverIntent(original.intent)
            scope to SdkSpaceRuntimeInstallation(original, command, gateway)
        }
        if (prepared is AppResult.Failed) return prepared
        val (scope, installation) = (prepared as AppResult.Success).value
        val identity =
            SdkSpaceRuntimeIdentity(
                owner,
                installation.command.request.deviceId,
                installation.command.id,
                installation.gateway.sessionId,
                installation.gateway.version,
            )
        val reserved =
            registry.beginSpaceAdoption(scope.storeId, scope.owner, scope.deviceId, identity)
        if (reserved is AppResult.Failed) return reserved
        val gate = (reserved as AppResult.Success).value
        return try {
            val drained = drainHandover(gate)
            if (drained is AppResult.Failed) return drained
            currentCoroutineContext().ensureActive()
            if (!registry.spaceHandoverCurrent(gate)) return handoverPending()
            val selected = registry.reserveSpaceAdoption(gate)
            if (selected is AppResult.Failed) return selected
            val reservation = (selected as AppResult.Success).value
            handoverAndroidSdkRuntime(
                map = { AndroidCollaborationSpaceRuntimeLease(identity, requireNotNull(it)) }
            ) { failed ->
                acquire(reservation, scope, null, failed, installation)
            }
        } finally {
            registry.endHandover(gate)
        }
    }

    override suspend fun readLocalIdentity(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationLocalDeviceIdentity> {
        currentCoroutineContext().ensureActive()
        val runtime =
            registry.find(owner, deviceId)
                ?: return AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Chat runtime is not open.",
                        code = "client.collaboration_runtime_not_open",
                    )
                )
        val result = runtime.localDeviceIdentity(clients)
        currentCoroutineContext().ensureActive()
        runtime.credentialFailures.value?.let {
            return AppResult.Failed(it)
        }
        if (result is AppResult.Failed) return result
        if (registry.find(owner, deviceId) !== runtime || !runtime.availableForEntry)
            return AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Chat runtime has stopped.",
                    code = "client.collaboration_sync_stopped",
                )
            )
        return result
    }

    override suspend fun handoverIntent(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationRuntimeHandoverIntent?> {
        val scoped = safeStorageCall { collaborationSdkScope(owner, deviceId, server, endpoint) }
        if (scoped is AppResult.Failed) return scoped
        return handoverIntent((scoped as AppResult.Success).value)
    }

    private suspend fun handoverIntent(
        scope: CollaborationSdkScope
    ): AppResult<CollaborationRuntimeHandoverIntent?> = safeStorageCall {
        val credentials = credentialFactory(scope.credentialRecordName)
        try {
            check(credentials.persistent) { "SDK handover requires protected persistent storage." }
            val codec = CollaborationSdkRecordCodec(scope)
            credentials.read()?.let(codec::decode)?.let(codec::handoverIntent)
        } finally {
            withContext(NonCancellable) { credentials.close() }
        }
    }

    override suspend fun stageHandover(
        owner: CollaborationOwner,
        deviceId: String,
        intent: CollaborationRuntimeHandoverIntent,
    ): AppResult<CollaborationRuntimeHandoverIntent> {
        val prepared = safeStorageCall {
            collaborationSdkScope(owner, deviceId, server, endpoint).also {
                CollaborationSdkRecordCodec(it).validateHandoverIntent(intent)
            }
        }
        if (prepared is AppResult.Failed) return prepared
        val scope = (prepared as AppResult.Success).value
        val recorded = handoverIntent(scope)
        if (recorded is AppResult.Failed) return recorded
        (recorded as AppResult.Success).value?.let {
            return if (it == intent) AppResult.Success(it) else handoverChanged()
        }
        val runtime = registry.find(owner, deviceId) ?: return handoverPending()
        val observed = runtime.localDeviceIdentity(clients)
        if (observed is AppResult.Failed) return observed
        val identity = (observed as AppResult.Success).value
        if (identity.fingerprint != intent.fingerprint) return handoverChanged()
        val reserved = registry.beginHandover(scope.storeId, owner, deviceId, runtime)
        if (reserved is AppResult.Failed) return reserved
        val gate = (reserved as AppResult.Success).value
        return try {
            val drained = drainHandover(gate)
            if (drained is AppResult.Failed) return drained
            safeStorageCall {
                var directory: CollaborationSdkStoreLease? = null
                var credentials: AtomicCredentialStore? = null
                try {
                    directory = stores.acquire(scope.storeId)
                    credentials = credentialFactory(scope.credentialRecordName)
                    check(credentials.persistent) {
                        "SDK handover requires protected persistent storage."
                    }
                    val codec = CollaborationSdkRecordCodec(scope)
                    val current = codec.decode(requireNotNull(credentials.read()))
                    require(
                        current.matrixUserId == identity.matrixUserId &&
                            current.matrixDeviceId == identity.matrixDeviceId
                    )
                    val staged = codec.stageHandover(current, intent)
                    if (staged != current) credentials.write(codec.encode(staged))
                    requireNotNull(codec.handoverIntent(staged))
                } finally {
                    withContext(NonCancellable) {
                        try {
                            credentials?.close()
                        } finally {
                            directory?.close()
                        }
                    }
                }
            }
        } finally {
            registry.endHandover(gate)
        }
    }

    override suspend fun stageExistingHandover(
        currentParent: CollaborationNativeParent,
        target: CollaborationExistingRuntimeHandover,
    ): AppResult<CollaborationRuntimeHandoverIntent> {
        if (inventories == null || privateIdentities == null) return handoverPending()
        val original = target.original
        val prepared = safeStorageCall {
            val owner =
                CollaborationOwner(
                    currentParent.sessionId,
                    original.binding.audience,
                    original.binding.accountId,
                    original.binding.companyId,
                    original.binding.nativeSessionId,
                )
            collaborationSdkScope(owner, original.deviceId, server, endpoint).also {
                CollaborationSdkRecordCodec(it).validateHandoverIntent(target.intent)
                require(target.intent.fingerprint == original.identity.fingerprint)
            }
        }
        if (prepared is AppResult.Failed) return prepared
        val scope = (prepared as AppResult.Success).value
        val reserved =
            registry.beginExistingHandover(
                scope.storeId,
                scope.owner,
                scope.deviceId,
                currentParent,
            )
        if (reserved is AppResult.Failed) return reserved
        val gate = (reserved as AppResult.Success).value
        return try {
            val drained = drainHandover(gate)
            if (drained is AppResult.Failed) return drained
            currentCoroutineContext().ensureActive()
            if (!registry.existingHandoverCurrent(gate, currentParent)) return handoverPending()
            val stored = persistExistingHandover(scope, target, gate, currentParent)
            if (stored is AppResult.Failed) return stored
            currentCoroutineContext().ensureActive()
            if (!registry.existingHandoverCurrent(gate, currentParent)) return handoverPending()
            val intent = (stored as AppResult.Success).value ?: return handoverPending()
            AppResult.Success(intent)
        } finally {
            registry.endHandover(gate)
        }
    }

    private suspend fun persistExistingHandover(
        scope: CollaborationSdkScope,
        target: CollaborationExistingRuntimeHandover,
        gate: SdkRuntimeHandoverGate,
        currentParent: CollaborationNativeParent,
    ): AppResult<CollaborationRuntimeHandoverIntent?> = safeStorageCall {
        val directory =
            requireNotNull(inventories).inspect(scope.storeId) ?: return@safeStorageCall null
        var credentials: AtomicCredentialStore? = null
        var primary: Throwable? = null
        try {
            require(directory.storeId == scope.storeId)
            if (!directory.stateDatabasePresent || !directory.cryptoDatabasePresent)
                return@safeStorageCall null
            val store = credentialFactory(scope.credentialRecordName)
            credentials = store
            check(store.persistent) { "SDK handover requires protected persistent storage." }
            val codec = CollaborationSdkRecordCodec(scope)
            val current = store.read()?.let(codec::decode) ?: return@safeStorageCall null
            currentCoroutineContext().ensureActive()
            val identity =
                requireNotNull(privateIdentities)
                    .read(directory.databasePath, current.passphrase)
                    .toLocalDeviceIdentity()
            currentCoroutineContext().ensureActive()
            require(identity == target.original.identity)
            require(
                identity.matrixUserId == current.matrixUserId &&
                    identity.matrixDeviceId == current.matrixDeviceId
            )
            if (!registry.existingHandoverCurrent(gate, currentParent)) return@safeStorageCall null
            val staged = codec.stageExistingHandover(current, target.intent)
            if (staged != current) store.write(codec.encode(staged))
            currentCoroutineContext().ensureActive()
            requireNotNull(codec.handoverIntent(staged))
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                val credentialClose = runCatching { credentials?.close() }.exceptionOrNull()
                val directoryClose = runCatching { directory.close() }.exceptionOrNull()
                val closing = credentialClose ?: directoryClose
                if (credentialClose != null && directoryClose != null)
                    credentialClose.addSuppressed(directoryClose)
                if (closing != null) {
                    val original = primary
                    if (original != null) original.addSuppressed(closing) else throw closing
                }
            }
        }
    }

    private suspend fun drainHandover(gate: SdkRuntimeHandoverGate): AppResult<Unit> {
        val reached =
            withTimeoutOrNull(5_000) {
                registry.handoverChanges.first {
                    registry.handoverDrained(gate) || registry.handoverResource(gate) != null
                }
                true
            } ?: false
        if (!reached) return handoverPending()
        registry.handoverResource(gate)?.let {
            val closed = it.close()
            if (closed is AppResult.Failed) return closed
        }
        return if (registry.handoverDrained(gate)) AppResult.Success(Unit) else handoverPending()
    }

    private fun handoverPending() =
        AppResult.Failed(
            Failure(
                FailureKind.Unavailable,
                "Chat ownership is being transferred.",
                code = "client.collaboration_handover_pending",
            )
        )

    private fun handoverChanged() =
        AppResult.Failed(
            Failure(
                FailureKind.Conflict,
                "Chat ownership changed.",
                code = "client.collaboration_handover_changed",
            )
        )

    override suspend fun updateRetention(
        retention: CollaborationRuntimeRetention
    ): AppResult<Unit> {
        val stopped = registry.update(retention)
        return drain((stopped + registry.resources().filter { it.closingRequested }).distinct())
    }

    override suspend fun acquireBackground(
        owner: CollaborationOwner,
        deviceId: String,
        lifetimeMillis: Long,
    ): AppResult<CollaborationBackgroundRuntimeLease?> {
        if (lifetimeMillis !in 1..60_000)
            return AppResult.Failed(Failure(FailureKind.Validation, "Invalid recovery lifetime."))
        val deadline = TimeSource.Monotonic.markNow() + lifetimeMillis.milliseconds
        drain(registry.resources().filter { it.closingRequested })
        currentCoroutineContext().ensureActive()
        if (deadline.hasPassedNow()) return AppResult.Failed(backgroundRuntimeExpired())
        val prepared = safeStorageCall { collaborationSdkScope(owner, deviceId, server, endpoint) }
        if (prepared is AppResult.Failed) return prepared
        val scope = (prepared as AppResult.Success).value
        val reserved = registry.reserveBackground(scope.storeId, owner, deviceId)
        if (reserved is AppResult.Failed) return reserved
        val borrow = (reserved as AppResult.Success).value
        val owned = AndroidCollaborationBackgroundRuntimeLease(registry, borrow, deadline)
        var transferred = false
        var acquisitionStarted = false
        try {
            currentCoroutineContext().ensureActive()
            owned.interruptions.value?.let {
                return AppResult.Failed(it)
            }
            if (borrow.creating) {
                val acquired =
                    handoverAndroidSdkRuntime(map = { it }) { failed ->
                        acquisitionStarted = true
                        acquire(borrow.reservation, scope, null, failed)
                    }
                if (acquired is AppResult.Failed) return acquired
                if ((acquired as AppResult.Success).value == null) return AppResult.Success(null)
            }
            currentCoroutineContext().ensureActive()
            owned.interruptions.value?.let {
                return AppResult.Failed(it)
            }
            owned.credentialFailures.value?.let {
                return AppResult.Failed(it)
            }
            if (deadline.hasPassedNow()) return AppResult.Failed(backgroundRuntimeExpired())
            transferred = true
            return AppResult.Success(owned)
        } finally {
            if (!transferred)
                withContext(NonCancellable) {
                    try {
                        owned.close()
                    } finally {
                        if (borrow.creating && !acquisitionStarted)
                            registry.released(borrow.reservation)
                    }
                }
        }
    }

    override suspend fun readOwnReadReceipts(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<dev.fajar.starter.collaboration.domain.entities.CollaborationOwnReadReceipts> {
        currentCoroutineContext().ensureActive()
        val runtime =
            legacyLeases.singleOrNull {
                it.owner == owner && it.deviceId == deviceId && it.availableForEntry
            }
                ?: return AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Chat runtime is not open.",
                        code = "client.collaboration_runtime_not_open",
                    )
                )
        return runtime.readOwnReadReceipts(matrixRoomId)
    }

    override suspend fun sendReadReceipt(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        eventId: String,
        visibility:
            dev.fajar.starter.collaboration.domain.entities.CollaborationReadReceiptVisibility,
    ): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val runtime =
            legacyLeases.singleOrNull {
                it.owner == owner && it.deviceId == deviceId && it.availableForEntry
            }
                ?: return AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Chat runtime is not open.",
                        code = "client.collaboration_runtime_not_open",
                    )
                )
        return runtime.sendReadReceipt(matrixRoomId, eventId, visibility)
    }

    override suspend fun synchronizeDevice(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<Unit> {
        val runtime =
            legacyLeases.singleOrNull {
                it.owner == owner && it.deviceId == deviceId && it.availableForEntry
            }
                ?: return AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Chat runtime is not open.",
                        code = "client.collaboration_runtime_not_open",
                    )
                )
        return runtime.synchronizeDevice()
    }

    override suspend fun activatePusher(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<Unit> {
        val runtime =
            legacyLeases.singleOrNull {
                it.owner == owner && it.deviceId == deviceId && it.availableForEntry
            }
                ?: return AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Chat runtime is not open.",
                        code = "client.collaboration_runtime_not_open",
                    )
                )
        return runtime.activatePusher()
    }

    override suspend fun selfIdentity(owner: CollaborationOwner, deviceId: String) =
        safeStorageCall {
            val scope = collaborationSdkScope(owner, deviceId, server, endpoint)
            val credentials = credentialFactory(scope.credentialRecordName)
            try {
                check(credentials.persistent) {
                    "SDK identity requires protected persistent storage."
                }
                credentials
                    .read()
                    ?.let { CollaborationSdkRecordCodec(scope).decode(it) }
                    ?.takeIf { it.initialized }
                    ?.let { CollaborationSelfIdentity(it.matrixUserId) }
            } finally {
                credentials.close()
            }
        }

    override suspend fun prepareHistoryReopen(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        anchorEventId: String,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationMessageId(deviceId) ||
                !validCollaborationEventId(anchorEventId) ||
                !matrixRoomId.startsWith('!') ||
                matrixRoomId.length !in 2..255 ||
                matrixRoomId.any { it.isWhitespace() || it.isISOControl() }
        )
            return AppResult.Failed(Failure(FailureKind.Validation, "Invalid history focus."))
        val reserved = registry.beginHistoryReopen(owner, deviceId)
        if (reserved is AppResult.Failed) return reserved
        val entry = (reserved as AppResult.Success).value ?: return AppResult.Success(true)
        return try {
            checkNotNull(entry.resource).prepareHistoryReopen(matrixRoomId, anchorEventId)
        } finally {
            registry.endHistoryReopen(entry)
        }
    }

    override suspend fun readProviderHistory(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        position: String?,
        untilPosition: String?,
    ): AppResult<CollaborationProviderHistoryPage> {
        val runtime =
            legacyLeases.singleOrNull {
                it.owner == owner && it.deviceId == deviceId && it.availableForEntry
            }
                ?: return AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Chat runtime is not open.",
                        code = "client.collaboration_runtime_not_open",
                    )
                )
        return runtime.readProviderHistory(matrixRoomId, position, untilPosition)
    }

    override suspend fun findHistoryHead(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationHistoryHead> {
        val runtime =
            legacyLeases.singleOrNull {
                it.owner == owner && it.deviceId == deviceId && it.availableForEntry
            }
                ?: return AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Chat runtime is not open.",
                        code = "client.collaboration_runtime_not_open",
                    )
                )
        return when (val result = runtime.findHistoryHead(matrixRoomId)) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                AppResult.Success(
                    CollaborationHistoryHead(
                        runtime.owner,
                        runtime.deviceId,
                        matrixRoomId,
                        result.value,
                    )
                )
        }
    }

    override suspend fun readOriginalHistory(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
        anchorEventId: String,
    ): AppResult<CollaborationOriginalWindow> {
        val runtime =
            legacyLeases.singleOrNull {
                it.owner == owner && it.deviceId == deviceId && it.availableForEntry
            }
                ?: return AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Chat runtime is not open.",
                        code = "client.collaboration_runtime_not_open",
                    )
                )
        val result = runtime.readOriginalHistory(matrixRoomId, anchorEventId)
        if (result is AppResult.Failed) return result
        val window = (result as AppResult.Success).value
        return AppResult.Success(
            CollaborationOriginalWindow(
                runtime.owner,
                runtime.deviceId,
                matrixRoomId,
                anchorEventId,
                window.originals.toList(),
                window.unavailableEventIds.toList(),
                window.edits.toList(),
            )
        )
    }

    override suspend fun acquireMessageQueue(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationMessageQueueLease> {
        val runtime =
            legacyLeases.singleOrNull {
                it.owner == owner && it.deviceId == deviceId && it.availableForEntry
            }
                ?: return AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Chat runtime is not open.",
                        code = "client.collaboration_runtime_not_open",
                    )
                )
        return runtime.acquireMessageQueue(matrixRoomId)
    }

    override suspend fun isOpen(owner: CollaborationOwner, deviceId: String): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        val existing =
            legacyLeases.singleOrNull {
                it.owner == owner && it.deviceId == deviceId && !it.closingRequested
            } ?: return AppResult.Success(false)
        existing.credentialFailures.value?.let {
            return AppResult.Failed(it)
        }
        if (!existing.availableForEntry)
            return AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Chat is still opening.",
                    code = "client.collaboration_runtime_opening",
                )
            )
        if (registry.retainForeground(owner, deviceId) == null)
            return AppResult.Failed(
                existing.credentialFailures.value
                    ?: Failure(
                        FailureKind.Unavailable,
                        "Chat runtime is no longer retained.",
                        code = "client.collaboration_runtime_not_retained",
                    )
            )
        return AppResult.Success(true)
    }

    override suspend fun install(
        owner: CollaborationOwner,
        command: CollaborationSetupCommandRecord,
    ): AppResult<CollaborationRuntimeLease> {
        val prepared = safeStorageCall {
            val outcome = command.outcome as CollaborationSetupOutcome.Bootstrapped
            require(
                command.attempts > 0 &&
                    collaborationGatewayMatchesCommand(owner, command.command, outcome.gateway)
            )
            command.command to outcome.gateway
        }
        if (prepared is AppResult.Failed) return prepared
        val installation = (prepared as AppResult.Success).value
        return open(owner, installation.second.deviceId, installation) { requireNotNull(it) }
    }

    override suspend fun restore(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationRuntimeLease?> = open(owner, deviceId, null) { it }

    override suspend fun enterTimeline(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationTimelineLease> {
        val runtime =
            legacyLeases.singleOrNull {
                it.owner == owner && it.deviceId == deviceId && it.availableForEntry
            }
                ?: return AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Chat runtime is not open.",
                        code = "client.collaboration_runtime_not_open",
                    )
                )
        when (val syncing = runtime.startSync()) {
            is AppResult.Failed -> return syncing
            is AppResult.Success -> Unit
        }
        return runtime.enterTimeline(matrixRoomId)
    }

    override suspend fun closeOtherOwners(retainedOwner: CollaborationOwner?): AppResult<Unit> {
        val obsolete = registry.closeOtherOwners(retainedOwner)
        return drain((obsolete + registry.resources().filter { it.closingRequested }).distinct())
    }

    private suspend fun drain(resources: List<AndroidCollaborationRuntimeLease>): AppResult<Unit> {
        val results = coroutineScope { resources.map { async { it.close() } }.awaitAll() }
        return results.filterIsInstance<AppResult.Failed>().firstOrNull() ?: AppResult.Success(Unit)
    }

    private suspend fun <T : AndroidCollaborationRuntimeLease?> open(
        owner: CollaborationOwner,
        deviceId: String,
        installation: Pair<CollaborationSetupCommand, CollaborationGateway>?,
        result: (AndroidCollaborationRuntimeLease?) -> T,
    ): AppResult<T> {
        drain(registry.resources().filter { it.closingRequested })
        currentCoroutineContext().ensureActive()
        val prepared = safeStorageCall { collaborationSdkScope(owner, deviceId, server, endpoint) }
        if (prepared is AppResult.Failed) return prepared
        val scope = (prepared as AppResult.Success).value
        val reserved =
            if (installation == null) registry.reserveForeground(scope.storeId, owner, deviceId)
            else reserveInstallation(scope, owner, deviceId, installation)
        if (reserved is AppResult.Failed) return reserved
        val reservation = (reserved as AppResult.Success).value
        return handoverAndroidSdkRuntime(map = result) { failed ->
            acquire(reservation, scope, installation, failed)
        }
    }

    private suspend fun reserveInstallation(
        scope: CollaborationSdkScope,
        owner: CollaborationOwner,
        deviceId: String,
        installation: Pair<CollaborationSetupCommand, CollaborationGateway>,
    ): AppResult<SdkRuntimeReservation<AndroidCollaborationRuntimeLease>> {
        val existing =
            registry.find(owner, deviceId)
                ?: return registry.reserveForeground(scope.storeId, owner, deviceId)
        val checked = safeStorageCall {
            require(existing.acceptsReplacement(installation.first, installation.second)) {
                "SDK replacement does not match its original gateway."
            }
        }
        if (checked is AppResult.Failed) return checked
        val started = registry.beginCredentialReplacement(scope.storeId, owner, deviceId, existing)
        if (started is AppResult.Failed) return started
        val gate = (started as AppResult.Success).value
        return try {
            val drained = drainHandover(gate)
            if (drained is AppResult.Failed) return drained
            currentCoroutineContext().ensureActive()
            registry.reserveCredentialReplacement(gate)
        } finally {
            registry.endHandover(gate)
        }
    }

    /** Cold and live credential reports share one original-owner publication fence. */
    private fun publishCredentialState(
        reservation: SdkRuntimeReservation<AndroidCollaborationRuntimeLease>,
        record: CollaborationSdkRecord,
        state: CollaborationCredentialState,
    ) {
        if (reservation.space != null) return
        registry.publishSignal(reservation) {
            credentialStates.tryEmit(
                CollaborationCredentialSignal(
                    reservation.owner,
                    reservation.deviceId,
                    record.bootstrapOperationId,
                    state,
                )
            )
        }
    }

    private suspend fun acquire(
        reservation: SdkRuntimeReservation<AndroidCollaborationRuntimeLease>,
        scope: CollaborationSdkScope,
        installation: Pair<CollaborationSetupCommand, CollaborationGateway>?,
        failed: (Failure) -> Unit,
        spaceInstallation: SdkSpaceRuntimeInstallation? = null,
    ): AndroidCollaborationRuntimeLease? {
        var directory: CollaborationSdkStoreLease? = null
        var lease: AndroidCollaborationRuntimeLease? = null
        var credentials: AtomicCredentialStore? = null
        var client: Client? = null
        var persistence: SdkSessionPersistence? = null
        var authentication: AndroidSdkAuthRegistration? = null
        var handedOver = false
        var primary: Throwable? = null
        try {
            if (!registry.accepts(reservation))
                throw CancellationException("SDK acquisition was retired.")
            check((reservation.space == null) == (spaceInstallation == null))
            directory =
                if (spaceInstallation == null) stores.acquire(scope.storeId)
                else
                    requireNotNull(stores.acquireExisting(scope.storeId)) {
                        "Original SDK store is unavailable."
                    }
            credentials = credentialFactory(scope.credentialRecordName)
            check(credentials.persistent) { "Persistent SDK credentials are required." }
            val prepared =
                readRuntimeSession(
                    directory,
                    credentials,
                    CollaborationSdkRecordCodec(scope),
                    installation,
                    spaceInstallation,
                    reservation,
                    failed,
                ) ?: return null
            persistence = prepared.persistence
            if (!registry.accepts(reservation))
                throw CancellationException("SDK acquisition was retired.")
            client =
                clients.create(
                    directory,
                    prepared.record.passphrase,
                    prepared.session,
                    AndroidSdkSessionDelegate(persistence),
                )
            authentication = AndroidSdkAuthRegistration.attach(clients, client, persistence)
            lease =
                AndroidCollaborationRuntimeLease(
                    reservation.owner,
                    client,
                    directory,
                    persistence,
                    prepared.record,
                    CollaborationHistoryApi(http, endpoint),
                    CollaborationPusherApi(http, endpoint),
                    credentialState = { publishCredentialState(reservation, prepared.record, it) },
                    authentication = authentication,
                ) {
                    registry.released(reservation)
                }
            if (!registry.attach(reservation, lease))
                throw CancellationException("SDK acquisition was retired.")
            val initialized =
                initializeRuntime(
                    lease,
                    client,
                    prepared,
                    reservation,
                    spaceInstallation?.original?.original?.identity,
                )
            if (initialized is AppResult.Failed) {
                failed(initialized.failure)
                return null
            }
            handedOver = true
            return lease
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            lease?.finishAcquisition()
            if (!handedOver) {
                persistence?.failures?.value?.let(failed)
                try {
                    if (lease != null) lease.close()
                    else {
                        try {
                            closeUnattached(authentication, client, credentials, directory)
                        } finally {
                            registry.released(reservation)
                        }
                    }
                } catch (closing: Throwable) {
                    val original = primary
                    if (original != null) original.addSuppressed(closing) else throw closing
                } finally {
                    persistence?.failures?.value?.let(failed)
                }
            }
        }
    }

    /** Restore/verify the paused native owner and persist readiness before any publication. */
    private suspend fun initializeRuntime(
        lease: AndroidCollaborationRuntimeLease,
        client: Client,
        prepared: AndroidSdkPreparedSession,
        reservation: SdkRuntimeReservation<AndroidCollaborationRuntimeLease>,
        expectedIdentity: CollaborationLocalDeviceIdentity?,
    ): AppResult<Unit> {
        lease.startHttpObservation(httpObservation)
        lease.startReadinessObservation(
            clients,
            reservation.space?.let {
                CollaborationSpaceRuntimeReady(it.owner, it.deviceId, it.operationId)
            },
            { readiness.tryEmit(it) },
            { spaceReadiness.tryEmit(it) },
        )
        clients.restorePaused(client, prepared.session)
        if (!registry.accepts(reservation))
            throw CancellationException("SDK acquisition was retired.")
        if (expectedIdentity != null) {
            require(clients.identity(client).toLocalDeviceIdentity() == expectedIdentity)
            if (!registry.accepts(reservation))
                throw CancellationException("SDK acquisition was retired.")
        }
        lease.startMonitoring()
        when (val initialized = prepared.persistence.markInitialized()) {
            is AppResult.Failed -> return initialized
            is AppResult.Success -> Unit
        }
        currentCoroutineContext().ensureActive()
        lease.completeAcquisition()
        return AppResult.Success(Unit)
    }

    private suspend fun closeUnattached(
        authentication: AndroidSdkAuthRegistration?,
        client: Client?,
        credentials: AtomicCredentialStore?,
        directory: CollaborationSdkStoreLease?,
    ) {
        try {
            try {
                try {
                    authentication?.close()
                } finally {
                    client?.close()
                }
            } finally {
                credentials?.close()
            }
        } finally {
            directory?.close()
        }
    }

    /** One protected-record selection binds the writer and native session to the same namespace. */
    private suspend fun readRuntimeSession(
        directory: CollaborationSdkStoreLease,
        credentials: AtomicCredentialStore,
        codec: CollaborationSdkRecordCodec,
        installation: Pair<CollaborationSetupCommand, CollaborationGateway>?,
        spaceInstallation: SdkSpaceRuntimeInstallation?,
        reservation: SdkRuntimeReservation<AndroidCollaborationRuntimeLease>,
        failed: (Failure) -> Unit,
    ): AndroidSdkPreparedSession? {
        val record =
            if (spaceInstallation == null)
                acquireRecord(directory, credentials, codec, installation) ?: return null
            else acquireAdoptedRecord(directory, credentials, codec, spaceInstallation, reservation)
        val namespace =
            spaceInstallation?.let { SdkCredentialNamespace.Adopted(it.command.id) }
                ?: SdkCredentialNamespace.Original
        val persistence = SdkSessionPersistence(credentials, codec, record, namespace)
        val session =
            when (val selected = persistence.session()) {
                is AppResult.Failed -> {
                    failed(selected.failure)
                    if (
                        namespace == SdkCredentialNamespace.Original &&
                            selected.failure.code == "client.collaboration_sdk_unauthorized"
                    )
                        publishCredentialState(
                            reservation,
                            record,
                            CollaborationCredentialState.Rejected,
                        )
                    return null
                }
                is AppResult.Success -> selected.value.toNativeSession()
            }
        return AndroidSdkPreparedSession(record, persistence, session)
    }

    private suspend fun acquireAdoptedRecord(
        directory: CollaborationSdkStoreLease,
        credentials: AtomicCredentialStore,
        codec: CollaborationSdkRecordCodec,
        installation: SdkSpaceRuntimeInstallation,
        reservation: SdkRuntimeReservation<AndroidCollaborationRuntimeLease>,
    ): CollaborationSdkRecord {
        val stored =
            requireNotNull(credentials.read()?.let(codec::decode)) {
                "Original SDK credentials are unavailable."
            }
        require(codec.handoverIntent(stored) == installation.original.intent)
        val identity =
            requireNotNull(privateIdentities)
                .read(directory.databasePath, stored.passphrase)
                .toLocalDeviceIdentity()
        currentCoroutineContext().ensureActive()
        require(identity == installation.original.original.identity)
        require(
            identity.matrixUserId == stored.matrixUserId &&
                identity.matrixDeviceId == stored.matrixDeviceId
        )
        val selected =
            if (
                stored.adoption == null ||
                    stored.adoption.bootstrapOperationId == installation.command.id
            )
                codec.stageAdoption(stored, installation.command, installation.gateway)
            else codec.replaceAdoptedCredentials(stored, installation.command, installation.gateway)
        if (!registry.accepts(reservation))
            throw CancellationException("SDK acquisition was retired.")
        if (selected != stored) credentials.write(codec.encode(selected))
        currentCoroutineContext().ensureActive()
        if (!registry.accepts(reservation))
            throw CancellationException("SDK acquisition was retired.")
        return selected
    }

    private suspend fun acquireRecord(
        directory: CollaborationSdkStoreLease,
        credentials: AtomicCredentialStore,
        codec: CollaborationSdkRecordCodec,
        installation: Pair<CollaborationSetupCommand, CollaborationGateway>?,
    ): CollaborationSdkRecord? {
        val stored = credentials.read()?.let(codec::decode)
        check(stored?.handover == null) { "SDK ownership is being transferred." }
        if (
            stored != null &&
                (stored.initialized ||
                    stored.bootstrapGatewayVersion > 0 ||
                    stored.messageOperationId != stored.bootstrapOperationId)
        ) {
            // Initialization or credential-replacement lineage requires the original account,
            // including an interrupted replacement whose initialized flag is false. Read it
            // under this lease before client construction or credential replacement writes.
            val identity =
                requireNotNull(privateIdentities)
                    .read(directory.databasePath, stored.passphrase)
                    .toLocalDeviceIdentity()
            currentCoroutineContext().ensureActive()
            require(
                identity.matrixUserId == stored.matrixUserId &&
                    identity.matrixDeviceId == stored.matrixDeviceId
            ) {
                "Stored SDK credentials do not match the private account."
            }
        }
        if (stored == null) {
            check(!directory.initiallyOccupied) { "SDK store key is unavailable." }
            if (installation == null) return null
            require(installation.second.version == 0L) {
                "A replacement gateway requires the original private SDK account."
            }
            val key = cipher.generateKey()
            val candidate =
                try {
                    codec.create(installation.first, installation.second, Base64.encode(key))
                } finally {
                    key.fill(0)
                }
            return codec.decode(credentials.writeIfAbsent(codec.encode(candidate)))
        }
        if (installation == null) return stored
        val gateway = installation.second
        require(
            gateway.matrixUserId == stored.matrixUserId &&
                gateway.matrixDeviceId == stored.matrixDeviceId
        )
        if (stored.bootstrapOperationId == installation.first.id) {
            require(
                gateway.sessionId == stored.bootstrapGatewayId &&
                    gateway.version == stored.bootstrapGatewayVersion &&
                    installation.first.clientBuild == stored.bootstrapClientBuild
            )
            return stored
        }
        require(
            gateway.sessionId == stored.bootstrapGatewayId &&
                gateway.version > stored.bootstrapGatewayVersion
        )
        val replacement = codec.replaceCredentials(stored, installation.first, gateway)
        credentials.write(codec.encode(replacement))
        return replacement
    }
}
