package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkStoreLease
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecord
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import org.matrix.rustcomponents.sdk.Client

/** Platform data access only; feature pages/ViewModels never receive the raw SDK client. */
class AndroidCollaborationRuntimeLease
internal constructor(
    override val owner: CollaborationOwner,
    val nativeClient: Client,
    private val directory: CollaborationSdkStoreLease,
    private val persistence: SdkSessionPersistence,
    record: CollaborationSdkRecord,
    private val released: (AndroidCollaborationRuntimeLease) -> Unit,
) : CollaborationRuntimeLease {
    override val deviceId = record.deviceId
    override val installedOperationId = record.bootstrapOperationId
    override val credentialFailures = persistence.failures
    private val lifetimeJob = SupervisorJob()
    private val lifetime = CoroutineScope(lifetimeJob + Dispatchers.Default)
    private val shutdown = SdkStoreShutdown(lifetime) { releaseResources() }
    private val stopRequested = AtomicBoolean()
    internal val closingRequested
        get() = stopRequested.get()

    private val initialization =
        lifetime.async(start = CoroutineStart.LAZY) {
            nativeClient.encryption().use { it.waitForE2eeInitializationTasks() }
        }
    private val credentialWatcher =
        lifetime.launch(start = CoroutineStart.LAZY) {
            credentialFailures.filterNotNull().collect {
                safeStorageCall { nativeClient.enableAllSendQueues(false) }
            }
        }

    internal fun startMonitoring() {
        initialization.start()
        credentialWatcher.start()
    }

    override suspend fun retryCredentialWrite() = persistence.retryPending()

    override suspend fun close(): AppResult<Unit> {
        stopRequested.set(true)
        return shutdown.close()
    }

    private suspend fun releaseResources(): SdkShutdownAttempt {
        val credentialResult = persistence.close()
        credentialWatcher.cancelAndJoin()
        safeStorageCall { nativeClient.enableAllSendQueues(false) }
        initialization.start()
        initialization.join()
        // Disabling queues does not abort an in-flight send. Pause drains and closes
        // the SDK store pools so delayed native work cannot write after our unlock.
        val drained = safeStorageCall { nativeClient.pause() }
        if (drained is AppResult.Failed) return SdkShutdownAttempt(drained, released = false)
        val result = safeStorageCall {
            try {
                nativeClient.close()
            } finally {
                directory.close()
            }
        }
        released(this)
        lifetimeJob.complete()
        return SdkShutdownAttempt(
            if (result is AppResult.Failed) result else credentialResult,
            released = true,
        )
    }

    override fun toString() = "AndroidCollaborationRuntimeLease([redacted])"
}
