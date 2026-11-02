package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.api.CollaborationPusherApi
import dev.fajar.starter.collaboration.data.datasources.CollaborationPusherRequestSource
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.matrix.rustcomponents.sdk.Client

/** One finite request borrows the current SDK identity. No enrollment, refresh or send retry. */
internal class AndroidCollaborationPusher(
    private val client: Client,
    private val expectedUserId: String,
    private val expectedDeviceId: String,
    private val expectedHomeserverUrl: String,
    private val children: SdkChildResources,
    private val lifetime: CoroutineScope,
    private val api: CollaborationPusherApi,
    private val failure: () -> Failure?,
) {
    private val execution = Mutex()

    suspend fun activate(): AppResult<Unit> {
        failure()?.let {
            return AppResult.Failed(it)
        }
        if (!execution.tryLock())
            return failed("client.collaboration_pusher_busy", FailureKind.Unavailable)
        try {
            val result =
                withTimeoutOrNull(10_000) { request() }
                    ?: failed("client.collaboration_pusher_timeout", FailureKind.Timeout)
            currentCoroutineContext().ensureActive()
            failure()?.let {
                return AppResult.Failed(collaborationSdkAcquisitionFailure(result, it))
            }
            return result
        } finally {
            execution.unlock()
        }
    }

    private suspend fun request(): AppResult<Unit> {
        val source = CollaborationPusherRequestSource(lifetime, api)
        val acquired =
            children.acquire(
                source,
                { resource ->
                    safeAndroidCollaborationCall {
                        val session = client.session()
                        check(
                            session.userId == expectedUserId &&
                                session.deviceId == expectedDeviceId &&
                                session.homeserverUrl.trimEnd('/') ==
                                    expectedHomeserverUrl.trimEnd('/')
                        )
                        val token = session.accessToken
                        check(token.length in 1..8192 && token.all { it.code in 33..126 })
                        resource.open(token)
                    }
                },
                { it.stop() },
            )
        if (acquired is AppResult.Failed) return acquired
        val child = (acquired as AppResult.Success).value
        val disposed: AppResult<Unit>
        val result =
            try {
                safeApiCall {
                    try {
                        source.await()
                    } catch (rejected: ApiResponseException) {
                        when (rejected.statusCode) {
                            401 ->
                                throw RequestFailureException(
                                    collaborationSdkFailure("M_UNKNOWN_TOKEN", null)
                                )
                            403 ->
                                throw RequestFailureException(
                                    collaborationSdkFailure("M_FORBIDDEN", null)
                                )
                            else -> throw rejected
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                AppResult.Failed(
                    failure()
                        ?: failed(
                                "client.collaboration_pusher_interrupted",
                                FailureKind.Unavailable,
                            )
                            .failure
                )
            } finally {
                disposed = child.close()
            }
        currentCoroutineContext().ensureActive()
        failure()?.let {
            return AppResult.Failed(collaborationSdkAcquisitionFailure(result, it))
        }
        if (disposed is AppResult.Failed)
            return AppResult.Failed(collaborationSdkAcquisitionFailure(result, disposed.failure))
        return result
    }

    private fun failed(code: String, kind: FailureKind) =
        AppResult.Failed(
            Failure(kind, "Chat notification activation did not complete.", code = code)
        )
}
