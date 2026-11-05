package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.api.CollaborationHistoryApi
import dev.fajar.starter.collaboration.data.api.CollaborationPusherApi
import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationGatewayMatchesCommand
import dev.fajar.starter.collaboration.domain.policies.validCollaborationEventId
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
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
import kotlinx.coroutines.flow.first
import org.matrix.rustcomponents.sdk.Client

/**
 * One application instance owns its SDK resources, including resources still finishing shutdown.
 */
class AndroidCollaborationRuntimeRepository(
    private val stores: CollaborationSdkStoreSource,
    private val credentialFactory: (String) -> AtomicCredentialStore,
    private val clients: AndroidCollaborationSdkClientSource,
    private val cipher: ContentCipher,
    private val server: IdentityServer,
    private val endpoint: CollaborationSdkEndpoint,
    private val http: HttpClient,
) : CollaborationRuntimeRepository, CollaborationLocalDeviceIdentityRepository {
    private val registry =
        SdkRuntimeRegistry<AndroidCollaborationRuntimeLease>(
            { it.availableForEntry && it.credentialFailures.value == null },
            { it.requestClose() },
        )
    private val leases
        get() = registry.resources()

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
        return drain((stopped + leases.filter { it.closingRequested }).distinct())
    }

    override suspend fun acquireBackground(
        owner: CollaborationOwner,
        deviceId: String,
        lifetimeMillis: Long,
    ): AppResult<CollaborationBackgroundRuntimeLease?> {
        if (lifetimeMillis !in 1..60_000)
            return AppResult.Failed(Failure(FailureKind.Validation, "Invalid recovery lifetime."))
        val deadline = TimeSource.Monotonic.markNow() + lifetimeMillis.milliseconds
        drain(leases.filter { it.closingRequested })
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
                val acquired = handoverAndroidSdkRuntime { failed ->
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
            leases.singleOrNull {
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
            leases.singleOrNull {
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

    override suspend fun activatePusher(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<Unit> {
        val runtime =
            leases.singleOrNull {
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
            leases.singleOrNull {
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
            leases.singleOrNull {
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
            leases.singleOrNull {
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
            )
        )
    }

    override suspend fun acquireMessageQueue(
        owner: CollaborationOwner,
        deviceId: String,
        matrixRoomId: String,
    ): AppResult<CollaborationMessageQueueLease> {
        val runtime =
            leases.singleOrNull {
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
            leases.singleOrNull {
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
            leases.singleOrNull {
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
        return drain((obsolete + leases.filter { it.closingRequested }).distinct())
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
        drain(leases.filter { it.closingRequested })
        currentCoroutineContext().ensureActive()
        val prepared = safeStorageCall { collaborationSdkScope(owner, deviceId, server, endpoint) }
        if (prepared is AppResult.Failed) return prepared
        val scope = (prepared as AppResult.Success).value
        val reserved =
            if (installation == null) registry.reserveForeground(scope.storeId, owner, deviceId)
            else reserveInstallation(scope, owner, deviceId, installation)
        if (reserved is AppResult.Failed) return reserved
        val reservation = (reserved as AppResult.Success).value
        return handoverAndroidSdkRuntime { failed ->
            result(acquire(reservation, scope, installation, failed))
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

    private suspend fun acquire(
        reservation: SdkRuntimeReservation<AndroidCollaborationRuntimeLease>,
        scope: CollaborationSdkScope,
        installation: Pair<CollaborationSetupCommand, CollaborationGateway>?,
        failed: (Failure) -> Unit,
    ): AndroidCollaborationRuntimeLease? {
        val owner = reservation.owner
        var directory: CollaborationSdkStoreLease? = null
        var lease: AndroidCollaborationRuntimeLease? = null
        var credentials: AtomicCredentialStore? = null
        var client: Client? = null
        var persistence: SdkSessionPersistence? = null
        var authenticationDelegate: AndroidSdkAuthDelegate? = null
        var authentication: AndroidSdkAuthRegistration? = null
        var handedOver = false
        try {
            check(registry.accepts(reservation)) { "SDK acquisition is no longer retained." }
            directory = stores.acquire(scope.storeId)
            credentials = credentialFactory(scope.credentialRecordName)
            check(credentials.persistent) {
                "SDK credentials require protected persistent storage."
            }
            val codec = CollaborationSdkRecordCodec(scope)
            val retained = acquireRecord(directory, credentials, codec, installation) ?: return null
            persistence = SdkSessionPersistence(credentials, codec, retained)
            client =
                clients.create(
                    directory,
                    retained.passphrase,
                    retained.toNativeSession(),
                    AndroidSdkSessionDelegate(persistence),
                )
            authenticationDelegate = AndroidSdkAuthDelegate(persistence)
            authentication =
                AndroidSdkAuthRegistration(
                    authenticationDelegate,
                    checkNotNull(clients.observeAuthentication(client, authenticationDelegate)) {
                        "SDK authentication observer is already installed."
                    },
                )
            lease =
                AndroidCollaborationRuntimeLease(
                    owner,
                    client,
                    directory,
                    persistence,
                    retained,
                    CollaborationHistoryApi(http, endpoint),
                    CollaborationPusherApi(http, endpoint),
                    authentication = authentication,
                ) {
                    registry.released(reservation)
                }
            check(registry.attach(reservation, lease)) { "SDK acquisition is no longer retained." }
            clients.restorePaused(client, retained.toNativeSession())
            lease.startMonitoring()
            when (persistence.markInitialized()) {
                is AppResult.Failed -> error("SDK initialization could not be persisted.")
                is AppResult.Success -> Unit
            }
            currentCoroutineContext().ensureActive()
            lease.completeAcquisition()
            handedOver = true
            return lease
        } finally {
            lease?.finishAcquisition()
            if (!handedOver) {
                persistence?.failures?.value?.let(failed)
                try {
                    if (lease != null) lease.close()
                    else {
                        try {
                            closeUnattached(
                                authenticationDelegate,
                                authentication,
                                client,
                                credentials,
                                directory,
                            )
                        } finally {
                            registry.released(reservation)
                        }
                    }
                } finally {
                    persistence?.failures?.value?.let(failed)
                }
            }
        }
    }

    private suspend fun closeUnattached(
        authenticationDelegate: AndroidSdkAuthDelegate?,
        authentication: AndroidSdkAuthRegistration?,
        client: Client?,
        credentials: AtomicCredentialStore?,
        directory: CollaborationSdkStoreLease?,
    ) {
        authenticationDelegate?.close()
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

    private suspend fun acquireRecord(
        directory: CollaborationSdkStoreLease,
        credentials: AtomicCredentialStore,
        codec: CollaborationSdkRecordCodec,
        installation: Pair<CollaborationSetupCommand, CollaborationGateway>?,
    ): CollaborationSdkRecord? {
        val stored = credentials.read()?.let(codec::decode)
        check(stored?.handover == null) { "SDK ownership is being transferred." }
        if (stored == null) {
            check(!directory.initiallyOccupied) { "SDK store key is unavailable." }
            if (installation == null) return null
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
