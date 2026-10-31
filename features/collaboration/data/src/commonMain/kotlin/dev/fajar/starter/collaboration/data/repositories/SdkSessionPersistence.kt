package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecord
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecordCodec
import dev.fajar.starter.common.result.*
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-lease part of the runtime repository, shared by its SDK callback and explicit recovery. */
internal class SdkSessionPersistence(
    private val credentials: CredentialStore,
    private val codec: CollaborationSdkRecordCodec,
    initial: CollaborationSdkRecord,
) {
    private val writes = Mutex()
    private var current = initial
    private var pending: CollaborationSdkRecord? = null
    private var closed = false
    private val failure = MutableStateFlow<Failure?>(null)
    val failures = failure.asStateFlow()

    suspend fun snapshot(): AppResult<CollaborationSdkRecord> =
        writes.withLock {
            val denied = failure.value
            if (denied != null) AppResult.Failed(denied)
            else
                safeStorageCall {
                    check(!closed && pending == null) { "SDK credentials are unavailable." }
                    codec.encode(current)
                    current
                }
        }

    suspend fun saveTokens(
        userId: String,
        deviceId: String,
        homeserver: String,
        access: String,
        refresh: String,
    ): AppResult<Unit> =
        writes.withLock {
            val prepared = safeStorageCall {
                check(!closed) { "SDK credential owner is closed." }
                require(
                    userId == current.matrixUserId &&
                        deviceId == current.matrixDeviceId &&
                        homeserver.trimEnd('/') == current.homeserverUrl.trimEnd('/')
                )
                codec.withTokens(pending ?: current, access, refresh)
            }
            when (prepared) {
                is AppResult.Failed -> retainFailure(prepared)
                is AppResult.Success -> {
                    if (prepared.value == current && pending == null && failure.value == null)
                        AppResult.Success(Unit)
                    else {
                        pending = prepared.value
                        failure.value?.let { AppResult.Failed(it) } ?: persistPending()
                    }
                }
            }
        }

    suspend fun markInitialized(): AppResult<Unit> =
        writes.withLock {
            failure.value?.let {
                return@withLock AppResult.Failed(it)
            }
            val prepared = safeStorageCall {
                check(!closed && pending == null) { "SDK credentials are unavailable." }
                codec.initialized(current)
            }
            when (prepared) {
                is AppResult.Failed -> retainFailure(prepared)
                is AppResult.Success -> {
                    if (prepared.value == current) AppResult.Success(Unit)
                    else {
                        pending = prepared.value
                        persistPending()
                    }
                }
            }
        }

    suspend fun retryPending(): AppResult<Unit> =
        writes.withLock {
            if (closed) return@withLock pendingFailure()
            if (pending == null)
                return@withLock failure.value?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(Unit)
            persistPending()
        }

    suspend fun close() = writes.withLock { closed = true }

    /** The exclusive directory lease and this mutex remain held by the repository owner. */
    private suspend fun persistPending(): AppResult<Unit> {
        val result =
            try {
                safeStorageCall {
                    check(!closed) { "SDK credential owner is closed." }
                    val value = requireNotNull(pending)
                    credentials.write(codec.encode(value))
                    value
                }
            } catch (cancelled: CancellationException) {
                failure.value = pendingFailure().failure
                throw cancelled
            }
        return when (result) {
            is AppResult.Failed -> retainFailure(result)
            is AppResult.Success -> {
                current = result.value
                pending = null
                failure.value = null
                AppResult.Success(Unit)
            }
        }
    }

    private fun retainFailure(result: AppResult.Failed): AppResult.Failed {
        failure.value = failure.value ?: result.failure
        return AppResult.Failed(requireNotNull(failure.value))
    }

    private fun pendingFailure() =
        AppResult.Failed(
            Failure(
                FailureKind.Storage,
                "SDK credentials could not be saved.",
                code = "client.collaboration_credentials_pending",
            )
        )
}
