package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.mappers.collaborationSdkAcquisitionFailure
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationEventId
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import org.matrix.rustcomponents.sdk.*

/** Finite operations on the installed SDK. The caller separately admits any live receipt write. */
internal class AndroidCollaborationReadReceipts(
    private val client: Client,
    private val ownUserId: String,
    private val children: SdkChildResources,
    private val lifetime: CoroutineScope,
    private val failure: () -> Failure?,
) {
    suspend fun readOwn(roomId: String): AppResult<CollaborationOwnReadReceipts> =
        operate(roomId, AndroidReadReceiptOperation.ReadOwn(ownUserId)) { snapshot ->
            val value = requireNotNull(snapshot)
            require(
                listOfNotNull(value.publicEventId, value.privateEventId)
                    .all(::validCollaborationEventId)
            )
            CollaborationOwnReadReceipts(value.publicEventId, value.privateEventId)
        }

    suspend fun send(
        roomId: String,
        eventId: String,
        visibility: CollaborationReadReceiptVisibility,
    ): AppResult<Unit> {
        if (!validCollaborationEventId(eventId)) return invalid()
        val type =
            when (visibility) {
                CollaborationReadReceiptVisibility.Public -> ReceiptType.READ
                CollaborationReadReceiptVisibility.Private -> ReceiptType.READ_PRIVATE
            }
        return operate(roomId, AndroidReadReceiptOperation.Send(type, eventId)) { Unit }
    }

    private suspend fun <T> operate(
        roomId: String,
        operation: AndroidReadReceiptOperation,
        value: (AndroidOwnReadReceiptSnapshot?) -> T,
    ): AppResult<T> {
        failure()?.let {
            return AppResult.Failed(it)
        }
        if (
            !roomId.startsWith('!') ||
                roomId.length !in 2..255 ||
                roomId.any { it.isWhitespace() || it.isISOControl() }
        )
            return invalid()
        return withTimeoutOrNull(10_000) { acquire(roomId, operation, value) }
            ?: AppResult.Failed(
                Failure(
                    FailureKind.Timeout,
                    "Chat receipt timed out.",
                    code = "client.collaboration_receipt_timeout",
                )
            )
    }

    private suspend fun <T> acquire(
        roomId: String,
        operation: AndroidReadReceiptOperation,
        value: (AndroidOwnReadReceiptSnapshot?) -> T,
    ): AppResult<T> {
        val source =
            AndroidCollaborationReadReceiptSource(roomId, operation, lifetime) {
                requireNotNull(client.getRoom(roomId))
            }
        val acquired =
            children.acquire(source, { safeAndroidCollaborationCall { it.open() } }, { it.stop() })
        if (acquired is AppResult.Failed) return acquired
        val child = (acquired as AppResult.Success).value
        val disposed: AppResult<Unit>
        val result =
            try {
                currentCoroutineContext().ensureActive()
                failure()?.let {
                    return AppResult.Failed(it)
                }
                safeAndroidCollaborationCall { value(source.execute()) }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                AppResult.Failed(
                    failure()
                        ?: Failure(
                            FailureKind.Unavailable,
                            "Chat receipt was interrupted.",
                            code = "client.collaboration_receipt_interrupted",
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

    private fun invalid() =
        AppResult.Failed(
            Failure(
                FailureKind.Validation,
                "Invalid chat receipt.",
                code = "client.collaboration_invalid_read_receipt",
            )
        )
}
