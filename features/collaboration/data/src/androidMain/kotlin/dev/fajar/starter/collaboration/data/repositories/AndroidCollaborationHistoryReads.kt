package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.api.CollaborationHistoryApi
import dev.fajar.starter.collaboration.data.datasources.CollaborationHistoryRequestSource
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationProviderPosition
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import kotlinx.coroutines.*
import org.matrix.rustcomponents.sdk.Client

/** Finite gateway metadata reads; the SDK remains the crypto, credential and sync-cursor owner. */
internal class AndroidCollaborationHistoryReads(
    private val client: Client,
    private val expectedUserId: String,
    private val expectedDeviceId: String,
    private val expectedHomeserverUrl: String,
    private val children: SdkChildResources,
    private val lifetime: CoroutineScope,
    private val api: CollaborationHistoryApi,
    private val failure: () -> Failure?,
) {
    suspend fun read(roomId: String): AppResult<String?> =
        request(CollaborationHistoryRequest.Head(roomId)) { collaborationHistoryHead(it, roomId) }

    suspend fun page(
        roomId: String,
        position: String?,
        untilPosition: String? = null,
    ): AppResult<CollaborationProviderPageDto> {
        return request(CollaborationHistoryRequest.Page(roomId, position, untilPosition)) {
            collaborationProviderPage(it, roomId, position)
        }
    }

    private suspend fun <T> request(
        query: CollaborationHistoryRequest,
        decode: (String) -> T,
    ): AppResult<T> {
        failure()?.let {
            return AppResult.Failed(it)
        }
        val position = (query as? CollaborationHistoryRequest.Page)?.position
        val untilPosition = (query as? CollaborationHistoryRequest.Page)?.untilPosition
        if (listOfNotNull(position, untilPosition).any { !validCollaborationProviderPosition(it) })
            return AppResult.Failed(Failure(FailureKind.Validation, "Invalid history position."))
        val roomId = query.roomId
        if (
            !roomId.startsWith('!') ||
                roomId.length !in 2..255 ||
                roomId.any { it.isWhitespace() || it.isISOControl() }
        )
            return AppResult.Failed(Failure(FailureKind.Validation, "Invalid history room."))
        val source = CollaborationHistoryRequestSource(lifetime, api, query)
        val acquired =
            children.acquire(
                source,
                { resource ->
                    safeAndroidCollaborationCall(AndroidCollaborationOperation.HistorySession) {
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
                    withContext(Dispatchers.Default) { decode(json) }
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
