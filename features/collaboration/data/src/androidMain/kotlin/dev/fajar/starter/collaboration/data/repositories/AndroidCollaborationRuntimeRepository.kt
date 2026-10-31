package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationGatewayMatchesCommand
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
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
) : CollaborationRuntimeRepository {
    private val capacity = Semaphore(5)
    private val leases = ConcurrentHashMap.newKeySet<AndroidCollaborationRuntimeLease>()

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
                AndroidCollaborationRuntimeLease(owner, client, directory, persistence, retained) {
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
