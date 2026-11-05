package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationTimelineLease
import dev.fajar.starter.common.result.*
import io.ktor.client.HttpClient
import java.net.URI
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Room

/** Preserves one rejected original transaction through real SDK credential replacement. */
internal class HrisAuthQueueSession(
    private val context: Context,
    private val fixture: JSONObject,
    home: URI,
    http: HttpClient,
) {
    private val peerOwner =
        ProtectedHrisProofOwner(context, fixture.getJSONObject("recipient"), home, http)
    private var peer: AndroidCollaborationRuntimeLease? = null
    private var room: Room? = null
    private var senderTimeline: CollaborationTimelineLease? = null
    private var oldMessages: HrisProofMessages? = null
    private var newMessages: HrisProofMessages? = null
    private var original: CollaborationMessageRecord? = null
    private val roomId = fixture.getString("roomId")
    private val oldId = fixture.getString("oldLogicalId")
    private val newId = fixture.getString("newLogicalId")

    suspend fun prepare(owner: ProtectedHrisProofOwner, sender: AndroidCollaborationRuntimeLease) {
        val recipient = peerOwner.installInitial()
        peer = recipient
        success(recipient.startSync())
        peerOwner.enterTimeline(roomId)
        senderTimeline = owner.enterTimeline(roomId)
        val recipientRoom = requireNotNull(recipient.nativeClient.getRoom(roomId))
        room = recipientRoom
        oldMessages = HrisProofMessages(recipientRoom, fixture.getString("oldBody"), oldId)
        newMessages = HrisProofMessages(recipientRoom, fixture.getString("newBody"), newId)
        val store = store(sender)
        try {
            store.activate()
            val staged = store.stage(oldId, fixture.getString("oldBody"))
            check(staged.state is CollaborationMessageState.Staged)
            original = staged
            val rejected = store.flush()
            check(rejected.nativeReason == CollaborationQueueDeliveryReason.FAILED)
            check(rejected.confirmed.isEmpty() && rejected.unresolved == setOf(oldId))
            check(store.read(oldId) == staged)
        } finally {
            store.close()
        }
        success(requireNotNull(senderTimeline).close())
        senderTimeline = null
    }

    suspend fun checkStableQueue(sender: AndroidCollaborationRuntimeLease) {
        val queue = success(sender.acquireMessageQueue(roomId))
        try {
            val state = requireNotNull(original).state as CollaborationMessageState.Staged
            check(queue.device == state.device)
            check(queue.device.bootstrapOperationId != sender.installedOperationId)
            check(success(queue.snapshot()) == setOf(state.transactionId))
        } finally {
            success(queue.close())
        }
    }

    suspend fun recoverLegacy(sender: AndroidCollaborationRuntimeLease) {
        val store = store(sender)
        try {
            store.activate()
            check(store.read(oldId) == original)
            val old = requireNotNull(original).state as CollaborationMessageState.Staged
            val queue = success(sender.acquireMessageQueue(roomId))
            try {
                check(queue.device.bootstrapOperationId != old.device.bootstrapOperationId)
                check(success(queue.snapshot()) == setOf(old.transactionId))
            } finally {
                success(queue.close())
            }
            val fresh = store.stage(newId, fixture.getString("newBody"))
            val next = fresh.state as CollaborationMessageState.Staged
            check(next.transactionId != old.transactionId)
            check(store.read(oldId) == original)
            val sent = store.flush()
            check(sent.unresolved.isEmpty() && sent.confirmed == setOf(oldId, newId))
            val oldSent = store.read(oldId).state as CollaborationMessageState.Sent
            val newSent = store.read(newId).state as CollaborationMessageState.Sent
            check(oldSent.transactionId == old.transactionId && oldSent.device == old.device)
            check(newSent.transactionId == next.transactionId)
            check(requireNotNull(oldMessages).eventId() == oldSent.eventId)
            check(requireNotNull(newMessages).eventId() == newSent.eventId)
        } finally {
            store.close()
        }
    }

    suspend fun close() =
        closeHrisProofResources(
            null,
            listOf(
                { oldMessages?.close() },
                { newMessages?.close() },
                { room?.close() },
                { senderTimeline?.let { success(it.close()) } },
                { peer?.closeProofRuntime() },
            ),
        )

    private fun store(sender: AndroidCollaborationRuntimeLease) =
        HrisAuthMessageStore(
            context,
            sender,
            fixture.getString("conversationId"),
            fixture.getJSONObject("owner").getString("hrisAccessToken"),
        )

    private fun <T> success(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
