package dev.fajar.starter.collaboration.data.datasources

import kotlinx.coroutines.*
import org.matrix.rustcomponents.sdk.*

internal sealed interface AndroidReadReceiptOperation {
    data class ReadOwn(val userId: String) : AndroidReadReceiptOperation

    data class Send(val type: ReceiptType, val eventId: String) : AndroidReadReceiptOperation
}

internal data class AndroidOwnReadReceiptSnapshot(
    val publicEventId: String?,
    val privateEventId: String?,
) {
    override fun toString() = "AndroidOwnReadReceiptSnapshot([redacted])"
}

/** The parent owns room acquisition and the separate, cancellable receipt request. */
internal class AndroidCollaborationReadReceiptSource(
    private val roomId: String,
    private val operation: AndroidReadReceiptOperation,
    private val lifetime: CoroutineScope,
    private val acquireRoom: () -> Room,
) {
    private var room: Room? = null
    private var request: Deferred<AndroidOwnReadReceiptSnapshot?>? = null

    suspend fun open() {
        val acquired = acquireRoom().also { room = it }
        require(acquired.id() == roomId && acquired.isEncrypted())
        // Starting the request belongs to the still-active caller after child acquisition.
        request =
            lifetime.async(start = CoroutineStart.LAZY) {
                when (val value = operation) {
                    is AndroidReadReceiptOperation.ReadOwn -> {
                        val publicReceipt =
                            acquired.loadUserReceipt(
                                ReceiptType.READ,
                                ReceiptThread.Unthreaded,
                                value.userId,
                            )
                        val privateReceipt =
                            acquired.loadUserReceipt(
                                ReceiptType.READ_PRIVATE,
                                ReceiptThread.Unthreaded,
                                value.userId,
                            )
                        AndroidOwnReadReceiptSnapshot(
                            publicReceipt?.eventId,
                            privateReceipt?.eventId,
                        )
                    }
                    is AndroidReadReceiptOperation.Send -> {
                        acquired.sendSingleReceipt(
                            value.type,
                            ReceiptThread.Unthreaded,
                            value.eventId,
                        )
                        null
                    }
                }
            }
    }

    suspend fun execute(): AndroidOwnReadReceiptSnapshot? = requireNotNull(request).await()

    suspend fun stop() {
        val pending = request
        if (pending != null) {
            pending.cancel()
            checkNotNull(
                withTimeoutOrNull(5_000) {
                    pending.join()
                    Unit
                }
            ) {
                "Native receipt request is still stopping."
            }
            request = null
        }
        room?.close()
        room = null
    }
}
