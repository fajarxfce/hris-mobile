package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.util.AtomicFile
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.repositories.CollaborationTimelineLease
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.network.*
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomMessageLikeEvent
import org.matrix.rustcomponents.sdk.RoomMessageLikeEventsListener
import uniffi.ruma_events.MessageLikeEventType

/** One fictional encrypted peer; the receiving employee application is a separate package. */
internal class HrisProductNotificationPeer(
    private val context: Context,
    private val directory: File,
) {
    suspend fun run() =
        withContext(Dispatchers.IO) {
            val file = File(directory, "product-peer.json")
            require(file.length() in 1..16_384)
            val fixture = JSONObject(file.readText())
            val home = URI(fixture.getString("baseUrl"))
            require(home.scheme == "http" && home.host == "127.0.0.1" && home.port in 1024..65535)
            require(
                home.rawPath in listOf("", "/") &&
                    home.rawQuery == null &&
                    home.rawUserInfo == null &&
                    home.rawFragment == null
            )
            val body = fixture.getString("body")
            val reply = fixture.getString("reply")
            require(body.startsWith("Fictional FCM ") && body.length in 1..256)
            require(reply.startsWith("Fictional reply ") && reply.length in 1..256)
            val logicalId = fixture.getString("logicalId")
            require(UUID.fromString(logicalId).toString() == logicalId)
            val http =
                createHttpClient(
                    createPlatformHttpEngine(),
                    HttpClientSettings(home.toString(), allowCleartext = true),
                )
            var runtime: AndroidCollaborationRuntimeLease? = null
            var timeline: CollaborationTimelineLease? = null
            var room: Room? = null
            var cancelListener: (() -> Unit)? = null
            var closeListener: (() -> Unit)? = null
            var primary: Exception? = null
            try {
                val owner =
                    ProtectedHrisProofOwner(context, fixture.getJSONObject("peer"), home, http)
                runtime = owner.installAndReopen()
                check(runtime.startSync() is AppResult.Success)
                val roomId = fixture.getString("roomId")
                timeline = owner.enterTimeline(roomId)
                room = requireNotNull(runtime.nativeClient.getRoom(roomId))
                val received = CompletableDeferred<JSONObject>()
                val listener =
                    room.subscribeToMessageLikeEvents(
                        MessageLikeEventType.RoomMessage,
                        object : RoomMessageLikeEventsListener {
                            override fun onEvent(event: RoomMessageLikeEvent) {
                                encryptedReply(event, reply)?.let { received.complete(it) }
                            }
                        },
                    )
                cancelListener = { listener.cancel() }
                closeListener = { listener.close() }
                write("product-peer-ready", JSONObject().put("ready", true))
                withTimeout(120_000) {
                    while (!File(directory, "product-peer-send.json").exists()) delay(100)
                }
                val sent = ProofQueuedMessage(runtime.nativeClient, room).send(body, logicalId)
                write(
                    "product-peer-sent",
                    JSONObject()
                        .put("eventId", sent.eventId)
                        .put("transactionId", sent.transactionId)
                        .put("logicalId", logicalId),
                )
                val result = withTimeout(120_000) { received.await() }
                write("product-peer-result", result.put("receivedReply", true))
            } catch (failure: Exception) {
                primary = failure
                throw failure
            } finally {
                closeHrisProofResources(
                    primary,
                    listOf(
                        { cancelListener?.invoke() },
                        { closeListener?.invoke() },
                        { room?.close() },
                        { runtime?.closeProofRuntime() },
                        { http.cancelAndClose() },
                    ),
                )
            }
            timeline?.let {
                val ended = withTimeout(5_000) { it.updates.first() }
                check(
                    ended is AppResult.Failed &&
                        ended.failure.code == "client.collaboration_timeline_closed"
                )
            }
        }

    private fun encryptedReply(event: RoomMessageLikeEvent, expected: String): JSONObject? {
        val content = runCatching { JSONObject(event.contentJson) }.getOrNull() ?: return null
        if (content.optString("body") != expected || event.encryptionInfo == null) return null
        val id = content.optString("org.hris.message_id")
        if (!runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) return null
        return JSONObject()
            .put("encrypted", true)
            .put("eventId", event.eventId)
            .put("logicalId", id)
    }

    private fun write(name: String, value: JSONObject) {
        val file = AtomicFile(File(directory, "$name.json"))
        val output = file.startWrite()
        try {
            output.write(value.toString().toByteArray())
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }
}
