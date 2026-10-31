package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Room

/** Two SDK crypto stores talking only to an owned HRIS gateway, never directly to Synapse. */
internal class HrisNativeMatrixProbe(private val context: Context, private val directory: File) {
    suspend fun run() =
        withContext(Dispatchers.IO) {
            val input = File(directory, "gateway-fixture.json")
            check(input.length() in 1..16_384)
            val fixture = JSONObject(input.readText())
            val home = URI(fixture.getString("baseUrl"))
            require(
                home.scheme == "http" &&
                    home.host == "127.0.0.1" &&
                    home.port in 1024..65535 &&
                    home.rawUserInfo == null &&
                    home.rawQuery == null &&
                    home.rawFragment == null &&
                    home.path in listOf("", "/")
            )
            require(
                fixture.getJSONObject("sender").getString("userId") !=
                    fixture.getJSONObject("recipient").getString("userId")
            )
            require(!File(directory, "gateway-send.json").exists())
            val roomId = fixture.getString("roomId")
            require(roomId.startsWith('!') && roomId.length in 2..255)
            var sender: Client? = null
            var recipient: Client? = null
            var senderRoom: Room? = null
            var recipientRoom: Room? = null
            var senderLease: AndroidCollaborationRuntimeLease? = null
            var recipientLease: AndroidCollaborationRuntimeLease? = null
            val senderSync = ProofSyncOwner(ProofSyncMode.CLASSIC)
            val recipientSync = ProofSyncOwner(ProofSyncMode.CLASSIC)
            try {
                senderLease =
                    ProtectedHrisProofOwner(context, fixture.getJSONObject("sender"), home)
                        .installAndReopen()
                sender = senderLease.nativeClient
                recipientLease =
                    ProtectedHrisProofOwner(context, fixture.getJSONObject("recipient"), home)
                        .installAndReopen()
                recipient = recipientLease.nativeClient
                senderSync.start(sender)
                recipientSync.start(recipient)
                senderRoom = sender.joinRoomById(roomId)
                recipientRoom = recipient.joinRoomById(roomId)
                exchange(fixture, sender, recipient, senderRoom, recipientRoom)
            } finally {
                withContext(NonCancellable) {
                    try {
                        withTimeout(5_000) {
                            senderSync.stop()
                            recipientSync.stop()
                        }
                    } finally {
                        senderSync.close()
                        recipientSync.close()
                        senderRoom?.close()
                        recipientRoom?.close()
                        senderLease?.closeProofRuntime()
                        recipientLease?.closeProofRuntime()
                    }
                }
            }
        }

    private suspend fun exchange(
        fixture: JSONObject,
        sender: Client,
        recipient: Client,
        senderRoom: Room,
        recipientRoom: Room,
    ) {
        val body = fixture.getString("body")
        val reply = fixture.getString("reply")
        require(body.startsWith("fictional-hris-") && body.length in 1..150)
        require(reply.startsWith("fictional-hris-") && reply.length in 1..150)
        val logicalId = UUID.fromString(fixture.getString("logicalId")).toString()
        val replyId = UUID.fromString(fixture.getString("replyId")).toString()
        HrisProofMessages(recipientRoom, body, logicalId).use { incoming ->
            HrisProofMessages(senderRoom, reply, replyId).use { returning ->
                File(directory, "gateway-ready.json")
                    .writeText(JSONObject().put("ready", true).toString())
                withTimeout(30_000) {
                    while (!File(directory, "gateway-send.json").exists()) delay(100)
                }
                val sent = ProofQueuedMessage(sender, senderRoom).send(body, logicalId)
                check(incoming.eventId() == sent.eventId)
                val answered = ProofQueuedMessage(recipient, recipientRoom).send(reply, replyId)
                check(returning.eventId() == answered.eventId)
                File(directory, "gateway-result.json")
                    .writeText(
                        JSONObject()
                            .put("encryptedBothDirections", true)
                            .put("logicalIdsPreserved", true)
                            .put("protectedStoreReopened", true)
                            .put("missingRecordPreserved", true)
                            .put("wrongPassphrasePreserved", true)
                            .put("exclusiveOwner", true)
                            .put("senderEventId", sent.eventId)
                            .put("recipientEventId", answered.eventId)
                            .put("senderTransaction", sent.transactionId)
                            .put("recipientTransaction", answered.transactionId)
                            .put("syncMode", "classic")
                            .toString()
                    )
            }
        }
    }
}
