package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.mappers.collaborationSdkFailure
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecord
import dev.fajar.starter.collaboration.data.sdk.CollaborationSdkRecordCodec
import dev.fajar.starter.common.result.*
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Per-lease part of the runtime repository, shared by its SDK callback and explicit recovery. */
internal class SdkSessionPersistence(
    private val credentials: CredentialStore,
    private val codec: CollaborationSdkRecordCodec,
    initial: CollaborationSdkRecord,
    private val namespace: SdkCredentialNamespace = SdkCredentialNamespace.Original,
) {
    private val writes = Mutex()
    private var current = initial
    private var pending: CollaborationSdkRecord? = null
    private var closed = false
    private val failure = MutableStateFlow<Failure?>(null)
    private val terminalFailure = MutableStateFlow<Failure?>(null)
    val failures = failure.asStateFlow()

    suspend fun snapshot(): AppResult<CollaborationSdkRecord> =
        writes.withLock {
            val denied = currentFailure()
            if (denied != null) AppResult.Failed(denied)
            else
                safeStorageCall {
                    check(!closed && pending == null) { "SDK credentials are unavailable." }
                    codec.encode(current)
                    current
                }
        }

    /** Exposes only the namespace fixed by the owner of this opened store. */
    suspend fun session(): AppResult<SdkSessionCredentials> =
        writes.withLock {
            val denied = currentFailure()
            if (denied != null) AppResult.Failed(denied)
            else
                safeStorageCall {
                    check(!closed && pending == null) { "SDK credentials are unavailable." }
                    codec.encode(current)
                    val tokens =
                        when (val owned = namespace) {
                            SdkCredentialNamespace.Original -> {
                                require(current.schema == 1 && current.handover == null)
                                current.accessToken to current.refreshToken
                            }
                            is SdkCredentialNamespace.Adopted -> {
                                val adopted = requireNotNull(current.adoption)
                                require(adopted.bootstrapOperationId == owned.operationId)
                                adopted.accessToken to adopted.refreshToken
                            }
                        }
                    SdkSessionCredentials(
                        current.matrixUserId,
                        current.matrixDeviceId,
                        current.homeserverUrl,
                        tokens.first,
                        tokens.second,
                    )
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
                when (val owned = namespace) {
                    SdkCredentialNamespace.Original ->
                        codec.withTokens(pending ?: current, access, refresh)
                    is SdkCredentialNamespace.Adopted ->
                        codec.withAdoptedTokens(
                            pending ?: current,
                            owned.operationId,
                            access,
                            refresh,
                        )
                }
            }
            when (prepared) {
                is AppResult.Failed -> retainFailure(prepared)
                is AppResult.Success -> {
                    if (prepared.value == current && pending == null && currentFailure() == null)
                        AppResult.Success(Unit)
                    else {
                        pending = prepared.value
                        currentFailure()?.let { AppResult.Failed(it) } ?: persistPending()
                    }
                }
            }
        }

    suspend fun markInitialized(): AppResult<Unit> =
        writes.withLock {
            currentFailure()?.let {
                return@withLock AppResult.Failed(it)
            }
            val prepared = safeStorageCall {
                check(!closed && pending == null) { "SDK credentials are unavailable." }
                when (val owned = namespace) {
                    SdkCredentialNamespace.Original -> codec.initialized(current)
                    is SdkCredentialNamespace.Adopted ->
                        codec.initializedAdoption(current, owned.operationId)
                }
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
            terminalFailure.value?.let {
                return@withLock AppResult.Failed(it)
            }
            if (closed) return@withLock pendingFailure()
            if (pending == null)
                return@withLock failure.value?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(Unit)
            persistPending()
        }

    suspend fun close(): AppResult<Unit> =
        writes.withLock {
            if (closed) AppResult.Success(Unit)
            else {
                closed = true
                safeStorageCall { credentials.close() }
            }
        }

    /** A failed synchronous bridge cannot safely acknowledge any further SDK refresh. */
    fun reportCallbackFailure() {
        terminalFailure.compareAndSet(
            null,
            Failure(
                FailureKind.Storage,
                "SDK credential callback failed.",
                code = "client.collaboration_callback_failed",
            ),
        )
        failure.value = terminalFailure.value
    }

    /** A rejected SDK refresh closes live credentials, not ownership of local chat history. */
    fun reportAuthenticationFailure() {
        val rejected = collaborationSdkFailure("M_UNKNOWN_TOKEN", null)
        terminalFailure.update { previous ->
            previous?.takeIf {
                it.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)
            } ?: rejected
        }
        failure.value = terminalFailure.value
    }

    suspend fun rejectCallback(): AppResult<Unit> =
        writes.withLock {
            retainFailure(
                AppResult.Failed(
                    Failure(
                        FailureKind.Storage,
                        "SDK session did not match its owner.",
                        code = "client.collaboration_session_changed",
                    )
                )
            )
        }

    /** The exclusive directory lease and this mutex remain held by the repository owner. */
    private suspend fun persistPending(): AppResult<Unit> {
        val previousFailure = failure.value
        val result =
            try {
                safeStorageCall {
                    check(!closed) { "SDK credential owner is closed." }
                    val value = requireNotNull(pending)
                    credentials.write(codec.encode(value))
                    value
                }
            } catch (cancelled: CancellationException) {
                failure.update { terminalFailure.value ?: it ?: pendingFailure().failure }
                throw cancelled
            }
        return when (result) {
            is AppResult.Failed -> retainFailure(result)
            is AppResult.Success -> {
                current = result.value
                pending = null
                failure.compareAndSet(previousFailure, null)
                currentFailure()?.let { AppResult.Failed(it) } ?: AppResult.Success(Unit)
            }
        }
    }

    private fun retainFailure(result: AppResult.Failed): AppResult.Failed {
        failure.update { terminalFailure.value ?: it ?: result.failure }
        return AppResult.Failed(requireNotNull(currentFailure()))
    }

    private fun currentFailure() = terminalFailure.value ?: failure.value

    private fun pendingFailure() =
        AppResult.Failed(
            Failure(
                FailureKind.Storage,
                "SDK credentials could not be saved.",
                code = "client.collaboration_credentials_pending",
            )
        )
}
