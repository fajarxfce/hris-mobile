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
) : CollaborationRuntimeRepository {
    private val registry =
        SdkRuntimeRegistry<AndroidCollaborationRuntimeLease>(
            { it.availableForEntry && it.credentialFailures.value == null },
            { it.requestClose() },
        )
    private val leases
        get() = registry.resources()

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
                val acquired = handover {
                    acquisitionStarted = true
                    acquire(borrow.reservation, scope, null)
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
        return runtime.readProviderHistory(matrixRoomId, position)
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

    private suspend fun <T : AndroidCollaborationRuntimeLease?> handover(
        block: suspend () -> T
    ): AppResult<T> {
        var opened: T? = null
        try {
            return safeStorageCall { block().also { opened = it } }
        } catch (cancelled: CancellationException) {
            opened?.close()
            throw cancelled
        }
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
        val reserved = registry.reserveForeground(scope.storeId, owner, deviceId)
        if (reserved is AppResult.Failed) return reserved
        val reservation = (reserved as AppResult.Success).value
        return handover { result(acquire(reservation, scope, installation)) }
    }

    private suspend fun acquire(
        reservation: SdkRuntimeReservation<AndroidCollaborationRuntimeLease>,
        scope: CollaborationSdkScope,
        installation: Pair<CollaborationSetupCommand, CollaborationGateway>?,
    ): AndroidCollaborationRuntimeLease? {
        val owner = reservation.owner
        var directory: CollaborationSdkStoreLease? = null
        var lease: AndroidCollaborationRuntimeLease? = null
        var credentials: AtomicCredentialStore? = null
        var client: Client? = null
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
            val persistence = SdkSessionPersistence(credentials, codec, retained)
            client =
                clients.create(
                    directory,
                    retained.passphrase,
                    retained.toNativeSession(),
                    AndroidSdkSessionDelegate(persistence),
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
                if (lease != null) lease.close()
                else {
                    try {
                        try {
                            client?.close()
                        } finally {
                            credentials?.close()
                        }
                    } finally {
                        try {
                            directory?.close()
                        } finally {
                            registry.released(reservation)
                        }
                    }
                }
            }
        }
    }

    private suspend fun acquireRecord(
        directory: CollaborationSdkStoreLease,
        credentials: AtomicCredentialStore,
        codec: CollaborationSdkRecordCodec,
        installation: Pair<CollaborationSetupCommand, CollaborationGateway>?,
    ): CollaborationSdkRecord? {
        val stored = credentials.read()?.let(codec::decode)
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
        val replacement =
            codec
                .create(installation.first, gateway, stored.passphrase)
                .copy(credentialRevision = stored.credentialRevision + 1)
        credentials.write(codec.encode(replacement))
        return replacement
    }
}
