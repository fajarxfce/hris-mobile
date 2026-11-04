package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.os.Process
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.*

/** Actual queued original keys through a new gateway; deliberately not product owner adoption. */
internal class HrisSpaceHistoryProbe(private val context: Context, private val directory: File) {
    private val fixture by lazy { read("space-fixture") }
    private val home by lazy {
        URI(fixture.getString("baseUrl")).also {
            require(it.scheme == "http" && it.host == "127.0.0.1" && it.port in 1024..65535)
            require(it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null)
        }
    }
    private val root = File(context.noBackupFilesDir, "space-sdk-history")

    private fun owner(name: String) =
        SpaceHistoryProofClient(context, root, home, fixture.getJSONObject(name))

    private val roomId
        get() = fixture.getString("roomId")

    suspend fun run(scenario: String) {
        when (scenario) {
            "space-history-stage" -> stage()
            "space-history-lose" -> loseResponse()
            "space-history-recover" -> recover()
            "space-history-final" -> history()
            else -> error("Unknown owned space scenario")
        }
    }

    private suspend fun stage() =
        withContext(Dispatchers.IO) {
            val receiver = owner("receiver")
            val sender = owner("sender")
            try {
                receiver.open(initial = true)
                receiver.sync()
                receiver.client.joinRoomById(roomId).close()
                val known = fixture.getBoolean("knownPeer")
                receiver.sync()
                val identity = receiver.identity()
                write("space-ready", JSONObject().put("fingerprint", identity.fingerprint))
                waitFor("space-continue")
                // Unknown variant: the peer SDK has never existed while receiver was online.
                if (!known) receiver.close()
                sender.open(initial = true)
                sender.sync()
                sender.client.joinRoomById(roomId).close()
                sender.sync()
                if (known) {
                    receiver.sync()
                    // Sending first learns the peer device, without creating its Megolm session.
                    checkNotNull(receiver.client.getRoom(roomId)).use { room ->
                        val receipt =
                            ProofQueuedMessage(receiver.client, room)
                                .send(body("primerBody"), logical("primerLogicalId"))
                        receive(
                            sender,
                            roomId,
                            body("primerBody"),
                            logical("primerLogicalId"),
                            receipt.eventId,
                        )
                    }
                    receiver.close()
                }
                val sent =
                    sender.client.joinRoomById(roomId).use {
                        ProofQueuedMessage(sender.client, it)
                            .send(body("body"), logical("logicalId"))
                    }
                sender.close()
                write(
                    "space-queued",
                    JSONObject()
                        .put("fingerprint", identity.fingerprint)
                        .put("eventId", sent.eventId)
                        .put("knownPeer", known)
                        .put("receiverClosedBeforeSend", true),
                )
            } finally {
                sender.dispose()
                receiver.dispose()
            }
        }

    private suspend fun loseResponse(): Nothing =
        withContext(Dispatchers.IO) {
            val receiver = owner("receiver")
            try {
                receiver.open(replacement = read("space-group"))
                assertIdentity(receiver)
                withTimeout(45_000) {
                    repeat(4) {
                        try {
                            receiver.sync()
                        } catch (_: ClientException) {
                            write("space-lost", JSONObject().put("responseFailed", true))
                            return@withTimeout
                        }
                    }
                    error("The owned encrypted group response was not interrupted")
                }
                awaitCancellation()
            } finally {
                receiver.dispose()
            }
        }

    private suspend fun recover(): Nothing =
        withContext(Dispatchers.IO) {
            val receiver = owner("receiver")
            try {
                receiver.open()
                assertIdentity(receiver)
                val incoming =
                    receive(
                        receiver,
                        roomId,
                        body("body"),
                        logical("logicalId"),
                        read("space-queued").getString("eventId"),
                        oneSync = true,
                    )
                check(incoming.sender == fixture.getJSONObject("sender").getString("userId"))
                // Do not equate decryption under SDK defaults with verified/cross-signed trust.
                write(
                    "space-decrypted",
                    JSONObject()
                        .put("eventId", incoming.eventId)
                        .put("fingerprint", receiver.identity().fingerprint)
                        .put("encrypted", true)
                        .put("strictShield", incoming.strictShield),
                )
                // No second sync: the provider has not received the new cursor acknowledgment.
                awaitCancellation()
            } finally {
                receiver.dispose()
            }
        }

    private suspend fun history() =
        withContext(Dispatchers.IO) {
            val receiver = owner("receiver")
            try {
                receiver.open()
                assertIdentity(receiver)
                val expected = read("space-queued").getString("eventId")
                // Read the persisted SDK timeline before any sync/network acquisition.
                assertLocalEvent(receiver.client, expected)
                receiver.sync()
                assertLocalEvent(receiver.client, expected)
                write(
                    "space-result",
                    JSONObject()
                        .put("sameOriginalKeys", true)
                        .put("decryptedAfterLostResponse", true)
                        .put("localHistoryBeforeSync", true)
                        .put("singleLogicalEvent", true)
                        .put("eventId", expected)
                        .put("knownPeer", fixture.getBoolean("knownPeer")),
                )
            } finally {
                receiver.dispose()
            }
        }

    private suspend fun assertIdentity(receiver: SpaceHistoryProofClient) {
        check(receiver.identity().fingerprint == read("space-queued").getString("fingerprint"))
    }

    private suspend fun receive(
        receiver: SpaceHistoryProofClient,
        roomId: String,
        body: String,
        logical: String,
        eventId: String,
        oneSync: Boolean = false,
    ): SpaceHistoryEventListener.Received {
        checkNotNull(receiver.client.getRoom(roomId)).use { room ->
            check(room.isEncrypted())
            room.timeline().use { timeline ->
                val observer = SpaceHistoryEventListener(eventId, body, logical)
                val result = observer.result
                val listener = timeline.addListener(observer)
                try {
                    withTimeout(45_000) {
                        repeat(if (oneSync) 1 else 8) {
                            receiver.sync()
                            if (result.isCompleted) return@withTimeout
                            delay(50)
                        }
                        result.await()
                    }
                    return result.await()
                } finally {
                    stop(listener)
                }
            }
        }
    }

    private suspend fun assertLocalEvent(client: Client, expected: String) {
        checkNotNull(client.getRoom(roomId)).use { room ->
            check(room.isEncrypted())
            room.timeline().use { timeline ->
                val captured = CompletableDeferred<Unit>()
                val listener =
                    timeline.addListener(
                        object : TimelineListener {
                            override fun onUpdate(diff: List<TimelineDiff>) {
                                try {
                                    if (captured.isCompleted) return
                                    val items = (diff.single() as TimelineDiff.Reset).values
                                    check(items.size <= 128)
                                    var matches = 0
                                    for (item in items) {
                                        val event = item.asEvent() ?: continue
                                        try {
                                            val id =
                                                (event.eventOrTransactionId
                                                        as? EventOrTransactionId.EventId)
                                                    ?.eventId
                                            if (id != expected) continue
                                            matches++
                                            val message =
                                                ((event.content as TimelineItemContent.MsgLike)
                                                        .content
                                                        .kind as MsgLikeKind.Message)
                                                    .content
                                            check(message.body == body("body"))
                                            val raw = checkNotNull(event.lazyProvider.latestJson())
                                            check(raw.length <= 96_000)
                                            check(
                                                JSONObject(raw)
                                                    .getJSONObject("content")
                                                    .getString("org.hris.message_id") ==
                                                    logical("logicalId")
                                            )
                                        } finally {
                                            event.destroy()
                                        }
                                    }
                                    check(matches == 1)
                                    captured.complete(Unit)
                                } catch (failure: Exception) {
                                    captured.completeExceptionally(failure)
                                } finally {
                                    diff.forEach { it.destroy() }
                                }
                            }
                        }
                    )
                try {
                    withTimeout(5_000) { captured.await() }
                } finally {
                    stop(listener)
                }
            }
        }
    }

    private suspend fun stop(listener: TaskHandle) {
        try {
            listener.cancel()
            withContext(NonCancellable) {
                withTimeout(5_000) { while (!listener.isFinished()) delay(10) }
            }
        } finally {
            listener.close()
        }
    }

    private fun body(key: String) =
        fixture.getString(key).also {
            require(it.startsWith("fictional-space-") && it.length in 16..200)
        }

    private fun logical(key: String) = UUID.fromString(fixture.getString(key)).toString()

    private fun read(name: String): JSONObject {
        val file = File(directory, "$name.json")
        require(file.length() in 1..16_384)
        return JSONObject(file.readText())
    }

    private suspend fun waitFor(name: String) =
        withTimeout(60_000) {
            while (!File(directory, "$name.json").exists()) delay(100)
            check(read(name).getBoolean("ready"))
        }

    private fun write(name: String, value: JSONObject) {
        value.put("processId", Process.myPid())
        val target = File(directory, "$name.json")
        check(!target.exists())
        val temporary = File(directory, "$name.next")
        temporary.writeText(value.toString())
        check(temporary.renameTo(target))
    }
}
