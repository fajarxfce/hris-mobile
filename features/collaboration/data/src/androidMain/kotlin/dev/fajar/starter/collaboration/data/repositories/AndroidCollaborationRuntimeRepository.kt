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
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
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
    private val capacity = Semaphore(5)
    private val leases = ConcurrentHashMap.newKeySet<AndroidCollaborationRuntimeLease>()

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
        val matching = leases.filter { it.owner == owner && it.deviceId == deviceId }
        if (matching.size > 1)
            return AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Chat runtime is busy.",
                    code = "client.collaboration_runtime_opening",
                )
            )
        return matching.singleOrNull()?.prepareHistoryReopen(matrixRoomId, anchorEventId)
            ?: AppResult.Success(true)
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
        return AppResult.Success(true)
    }

    override suspend fun install(
        owner: CollaborationOwner,
        command: CollaborationSetupCommandRecord,
    ): AppResult<CollaborationRuntimeLease> = handover {
        val outcome = command.outcome as CollaborationSetupOutcome.Bootstrapped
        require(
            command.attempts > 0 &&
                collaborationGatewayMatchesCommand(owner, command.command, outcome.gateway)
        )
        requireNotNull(open(owner, outcome.gateway.deviceId, command.command to outcome.gateway))
    }

    override suspend fun restore(
        owner: CollaborationOwner,
        deviceId: String,
    ): AppResult<CollaborationRuntimeLease?> = handover { open(owner, deviceId, null) }

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
        val obsolete = leases.filter { it.owner != retainedOwner || it.closingRequested }
        val results = coroutineScope { obsolete.map { async { it.close() } }.awaitAll() }
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

    private suspend fun open(
        owner: CollaborationOwner,
        deviceId: String,
        installation: Pair<CollaborationSetupCommand, CollaborationGateway>?,
    ): AndroidCollaborationRuntimeLease? {
        // An earlier cancelled installation may have no caller left to retry its
        // failed drain. An explicit open retries each already-closing owner once;
        // at most five concurrent five-second waits, never a live lease or a loop.
        coroutineScope {
            leases.filter { it.closingRequested }.map { async { it.close() } }.awaitAll()
        }
        currentCoroutineContext().ensureActive()
        check(capacity.tryAcquire()) { "SDK runtime capacity is full." }
        var directory: CollaborationSdkStoreLease? = null
        var lease: AndroidCollaborationRuntimeLease? = null
        var credentials: AtomicCredentialStore? = null
        var client: Client? = null
        var handedOver = false
        try {
            val scope = collaborationSdkScope(owner, deviceId, server, endpoint)
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
                    leases.remove(it)
                    capacity.release()
                }
            leases.add(lease)
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
                            capacity.release()
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
