package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.CollaborationReadReceiptVisibility
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.ReceiptThread
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room

/**
 * Fictional native owners only. Provider scope and encryption are exercised by the parent proof.
 */
internal object HrisReadReceiptProof {
    suspend fun exchange(
        sender: AndroidCollaborationRuntimeLease,
        recipient: AndroidCollaborationRuntimeLease,
        senderRoom: Room,
        sentEventId: String,
        replyEventId: String,
    ) {
        val roomId = senderRoom.id()
        repeat(2) {
            expect(
                recipient.sendReadReceipt(
                    roomId,
                    sentEventId,
                    CollaborationReadReceiptVisibility.Private,
                )
            )
        }
        await { expect(recipient.readOwnReadReceipts(roomId)).privateEventId == sentEventId }
        repeat(2) {
            expect(
                recipient.sendReadReceipt(
                    roomId,
                    sentEventId,
                    CollaborationReadReceiptVisibility.Public,
                )
            )
        }
        await {
            senderRoom
                .loadUserReceipt(
                    ReceiptType.READ,
                    ReceiptThread.Unthreaded,
                    recipient.nativeClient.userId(),
                )
                ?.eventId == sentEventId
        }
        // The public receipt is a sync barrier; the private receipt must still be invisible here.
        check(
            senderRoom.loadUserReceipt(
                ReceiptType.READ_PRIVATE,
                ReceiptThread.Unthreaded,
                recipient.nativeClient.userId(),
            ) == null
        )
        val own = expect(recipient.readOwnReadReceipts(roomId))
        check(own.privateEventId == sentEventId && own.publicEventId == sentEventId)
        expect(
            sender.sendReadReceipt(roomId, replyEventId, CollaborationReadReceiptVisibility.Private)
        )
        await { expect(sender.readOwnReadReceipts(roomId)).privateEventId == replyEventId }
    }

    /**
     * Called in the next OS process before starting sync, so this requires the retained SDK store.
     */
    suspend fun restored(
        sender: AndroidCollaborationRuntimeLease,
        roomId: String,
        result: JSONObject,
    ) {
        check(
            expect(sender.readOwnReadReceipts(roomId)).privateEventId ==
                result.getString("recipientEventId")
        )
        result.put("nativeReadReceiptStoreRestored", true)
    }

    private suspend fun await(ready: suspend () -> Boolean) {
        withTimeout(20_000) { while (!ready()) delay(100) }
    }

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
