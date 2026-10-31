package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkStoreLease
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecord
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val closing = Mutex()
    private var closeTask: Deferred<AppResult<Unit>>? = null
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

    override suspend fun close(): AppResult<Unit> =
        withContext(NonCancellable) {
            val task =
                closing.withLock {
                    closeTask ?: lifetime.async { releaseResources() }.also { closeTask = it }
                }
            // The repository retains this lease while SDK initialization finishes. A UI owner
            // can stop waiting without releasing a store still held by native background work.
            withTimeoutOrNull(5_000) { task.await() }
                ?: AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Chat storage is still closing.",
                        code = "client.collaboration_close_pending",
                    )
                )
        }

    private suspend fun releaseResources(): AppResult<Unit> {
        val credentialResult = persistence.close()
        credentialWatcher.cancelAndJoin()
        safeStorageCall { nativeClient.enableAllSendQueues(false) }
        initialization.start()
        initialization.join()
        val result = safeStorageCall {
            try {
                nativeClient.close()
            } finally {
                directory.close()
            }
        }
        released(this)
        lifetimeJob.complete()
        return if (result is AppResult.Failed) result else credentialResult
    }

    override fun toString() = "AndroidCollaborationRuntimeLease([redacted])"
}
