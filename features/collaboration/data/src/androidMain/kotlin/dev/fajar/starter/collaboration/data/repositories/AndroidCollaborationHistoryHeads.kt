package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.api.CollaborationHistoryHeadApi
import dev.fajar.starter.collaboration.data.datasources.CollaborationHistoryHeadSource
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import kotlinx.coroutines.*
import org.matrix.rustcomponents.sdk.Client

/**
 * A single gateway page selects an anchor; it neither decrypts content nor advances SDK cursors.
 */
internal class AndroidCollaborationHistoryHeads(
    private val client: Client,
    private val expectedUserId: String,
    private val expectedDeviceId: String,
    private val expectedHomeserverUrl: String,
    private val children: SdkChildResources,
    private val lifetime: CoroutineScope,
    private val api: CollaborationHistoryHeadApi,
    private val failure: () -> Failure?,
) {
    suspend fun read(roomId: String): AppResult<String?> {
        failure()?.let {
            return AppResult.Failed(it)
        }
        if (
            !roomId.startsWith('!') ||
                roomId.length !in 2..255 ||
                roomId.any { it.isWhitespace() || it.isISOControl() }
        )
            return AppResult.Failed(Failure(FailureKind.Validation, "Invalid history room."))
        val source = CollaborationHistoryHeadSource(lifetime, api, roomId)
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
                    val json =
                        try {
                            source.read()
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
                    withContext(Dispatchers.Default) { collaborationHistoryHead(json, roomId) }
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                AppResult.Failed(
                    failure()
                        ?: Failure(
                            FailureKind.Unavailable,
                            "Chat request was interrupted.",
                            code = "client.collaboration_history_interrupted",
                        )
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
}
