package dev.fajar.hris.collaboration.proof

import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomMessageLikeEvent
import org.matrix.rustcomponents.sdk.RoomMessageLikeEventsListener
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.ruma_events.MessageLikeEventType

/** Owns every native client, observer and sync service for one bounded experiment. */
internal class NativeMatrixProbe(private val directory: File) {
    suspend fun run() =
        withContext(Dispatchers.IO) {
            val fixtureFile = File(directory, "fixture.json")
            check(fixtureFile.length() in 1..16_384)
            val fixture = JSONObject(fixtureFile.readText())
            val home = URI(fixture.getString("baseUrl"))
            require(home.scheme == "http" && home.host == "127.0.0.1" && home.port in 1024..65535)
            require(fixture.getString("userId") == "@native:proof.invalid")
            require(fixture.getString("deviceId") == "PROOF_NATIVE")
            val expectedBody = fixture.getString("expectedBody")
            val replyBody = fixture.getString("replyBody")
            require(expectedBody.startsWith("fictional-native-") && expectedBody.length < 150)
            require(replyBody.startsWith("fictional-native-") && replyBody.length < 150)
            val store = File(directory, "store").apply { mkdirs() }
            val cache = File(directory, "cache").apply { mkdirs() }
            val keyFile = File(directory, "store-key")
            if (!keyFile.exists()) {
                val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
                keyFile.writeText(Base64.getEncoder().encodeToString(key))
                key.fill(0)
            }

            var client: Client? = null
            var sync: SyncService? = null
            var room: Room? = null
            var listener: TaskHandle? = null
            try {
                SqliteStoreBuilder(store.absolutePath, cache.absolutePath).use { storeBuilder ->
                    storeBuilder.passphrase(keyFile.readText()).use { encryptedStore ->
                        ClientBuilder().use { initial ->
                            initial.homeserverUrl(home.toString()).use { located ->
                                located.sqliteStore(encryptedStore).use { configured ->
                                    client = configured.build()
                                }
                            }
                        }
                    }
                }
                val active = checkNotNull(client)
                active.restoreSession(
                    Session(
                        accessToken = fixture.getString("accessToken"),
                        refreshToken = null,
                        userId = fixture.getString("userId"),
                        deviceId = fixture.getString("deviceId"),
                        homeserverUrl = home.toString(),
                        oauthData = null,
                        slidingSyncVersion = SlidingSyncVersion.NATIVE,
                    )
                )
                active.syncService().use { builder -> sync = builder.finish() }
                checkNotNull(sync).start()
                room = active.joinRoomById(fixture.getString("roomId"))
                val joined = checkNotNull(room)
                val received = CompletableDeferred<RoomMessageLikeEvent>()
                listener =
                    joined.subscribeToMessageLikeEvents(
                        MessageLikeEventType.RoomMessage,
                        object : RoomMessageLikeEventsListener {
                            override fun onEvent(event: RoomMessageLikeEvent) {
                                val content =
                                    runCatching { JSONObject(event.contentJson) }.getOrNull()
                                if (
                                    content?.optString("body") == expectedBody &&
                                        event.encryptionInfo != null
                                ) {
                                    received.complete(event)
                                }
                            }
                        },
                    )
                active.encryption().use { encryption ->
                    val ready =
                        JSONObject()
                            .put("ready", true)
                            .put("userId", active.userId())
                            .put("deviceId", active.deviceId())
                            .put("ed25519", checkNotNull(encryption.ed25519Key()))
                    File(directory, "ready.json").writeText(ready.toString())
                }
                val incoming = withTimeout(45_000) { received.await() }
                val outgoing =
                    joined.sendRaw(
                        "m.room.message",
                        JSONObject().put("msgtype", "m.text").put("body", replyBody).toString(),
                    )
                File(directory, "result.json")
                    .writeText(
                        JSONObject()
                            .put("receivedEncrypted", true)
                            .put("receivedEventId", incoming.eventId)
                            .put("sentEventId", outgoing)
                            .put("sdk", "26.10.23")
                            .toString()
                    )
            } finally {
                listener?.cancel()
                listener?.close()
                withContext(NonCancellable) {
                    try {
                        withTimeout(5_000) { sync?.stop() }
                    } finally {
                        sync?.close()
                        room?.close()
                        client?.close()
                    }
                }
            }
        }
}
