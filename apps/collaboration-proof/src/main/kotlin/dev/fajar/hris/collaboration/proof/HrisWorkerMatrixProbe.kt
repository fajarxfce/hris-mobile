package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.os.Process
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageState
import dev.fajar.starter.collaboration.domain.repositories.CollaborationTimelineLease
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationText
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import dev.fajar.starter.sync.domain.SyncResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.matrix.rustcomponents.sdk.Room

/** Actual app worker/SDK across process death. Platform scheduling is a separate proof. */
internal class HrisWorkerMatrixProbe(private val context: Context, directory: File) {
    private val files = HrisWorkerProofFiles(directory)
    private val http =
        createHttpClient(
            createPlatformHttpEngine(),
            HttpClientSettings(files.home.toString(), allowCleartext = true),
        )
    private val sender =
        ProtectedHrisProofOwner(context, files.fixture.getJSONObject("sender"), files.home, http)
    private val recipient =
        ProtectedHrisProofOwner(context, files.fixture.getJSONObject("recipient"), files.home, http)

    suspend fun stage(): Unit =
        withContext(Dispatchers.IO) {
            check(!files.exists("worker-queued") && !files.exists("worker-send"))
            var senderLease: AndroidCollaborationRuntimeLease? = null
            var recipientLease: AndroidCollaborationRuntimeLease? = null
            var application: HrisWorkerProofContainer? = null
            var primary: Exception? = null
            try {
                senderLease = sender.installAndReopen()
                recipientLease = recipient.installAndReopen()
                val app = openApplication().also { application = it }
                app.activate()
                app.cacheConversation()
                check(app.pending().isEmpty())
                val queued = app.queue(files.logicalId, files.body)
                check(queued.state == CollaborationMessageState.Queued)
                check(queued.intent.id == files.logicalId && queued.intent.text == files.body)
                check(app.pending() == listOf(queued))
                check(app.scheduledKeys == listOf(QueueCollaborationText.DELIVERY_KEY))
                files.write("worker-queued", files.checkpoint())
                // Driver confirms this PID and kills the process. No graceful substitute for death.
                awaitCancellation()
            } catch (failure: Exception) {
                primary = failure
                throw failure
            } finally {
                closeHrisProofResources(
                    primary,
                    listOf(
                        { application?.close() },
                        { senderLease?.closeProofRuntime() },
                        { recipientLease?.closeProofRuntime() },
                        { http.cancelAndClose() },
                    ),
                )
            }
        }

    suspend fun resume(): Unit =
        withContext(Dispatchers.IO) {
            val queued = files.read("worker-queued", 2048)
            check(queued.getInt("processId") != Process.myPid())
            check(
                queued.getString("messageId") == files.logicalId &&
                    queued.getString("bodySha256") == files.bodyHash
            )
            check(queued.getString("state") == "QUEUED")
            check(!files.exists("worker-send") && !files.exists("worker-result"))
            var application: HrisWorkerProofContainer? = null
            var peer: AndroidCollaborationRuntimeLease? = null
            var timeline: CollaborationTimelineLease? = null
            var room: Room? = null
            var received: HrisProofMessages? = null
            var primary: Exception? = null
            try {
                val app = openApplication().also { application = it }
                app.activate()
                val restored = app.pending().single()
                check(restored.intent.id == files.logicalId && restored.intent.text == files.body)
                check(restored.state == CollaborationMessageState.Queued)
                check(app.scheduledKeys.isEmpty())
                check(
                    !expect(
                        sender.repository.isOpen(sender.currentOwner, sender.registeredDeviceId)
                    )
                )
                peer = recipient.restoreStored()
                expect(peer.startSync())
                timeline = recipient.enterTimeline(files.roomId)
                room = requireNotNull(peer.nativeClient.getRoom(files.roomId))
                received = HrisProofMessages(room, files.body, files.logicalId)
                files.write("worker-restored", files.checkpoint().put("senderRuntimeOpen", false))
                withTimeout(30_000) {
                    while (!files.exists("worker-send")) delay(100)
                    check(files.read("worker-send", 128).get("ready") == true)
                }
                val passes = deliver(app)
                val eventId = received.eventId()
                check(
                    expect(sender.repository.isOpen(sender.currentOwner, sender.registeredDeviceId))
                )
                val original = app.history().messages.single { it.logicalId == files.logicalId }
                check(
                    original.text == files.body &&
                        !original.redacted &&
                        eventId in original.eventIds
                )
                val completed = requireNotNull(app.completion(files.logicalId))
                check(completed.record.intent == restored.intent && eventId in completed.eventIds)
                check(app.pending().isEmpty())
                check(app.runWorker() == SyncResult.Complete)
                val historical = seedHistory(requireNotNull(peer), requireNotNull(room))
                val foreground =
                    app.foregroundHistory(
                        sender.registeredDeviceId,
                        files.roomId,
                        eventId,
                        files.body,
                        historical,
                    )
                files.write(
                    "worker-result",
                    files
                        .checkpoint()
                        .put("state", "COMPLETED")
                        .put("eventId", eventId)
                        .put("passes", passes)
                        .put("encryptedReceived", true)
                        .put("senderRuntimeRestoredByWorker", true)
                        .put("originalHistoryAcquired", true)
                        .put("completionRetired", true)
                        .put("repeatedWorkerComplete", true)
                        .put("foregroundHistoryPaged", true)
                        .put("foregroundStoppedHistoryRetained", true)
                        .put("historyPages", foreground.pages)
                        .put("providerReferences", foreground.references)
                        .put("pendingStateReferences", foreground.pendingStateReferences)
                        .put("historyPeerEvents", JSONArray(historical.map { it.eventId })),
                )
            } catch (failure: Exception) {
                primary = failure
                throw failure
            } finally {
                closeHrisProofResources(
                    primary,
                    listOf(
                        { received?.close() },
                        { room?.close() },
                        { timeline?.close()?.let { expect(it) } },
                        { application?.close() },
                        { peer?.closeProofRuntime() },
                        { http.cancelAndClose() },
                    ),
                )
            }
        }

    private fun openApplication() =
        HrisWorkerProofContainer(
            context,
            sender.currentOwner,
            files.conversationId,
            files.fixture.getJSONObject("sender").getString("hrisAccessToken"),
            sender.repository,
            http,
        )

    private suspend fun deliver(app: HrisWorkerProofContainer): Int {
        repeat(4) { attempt ->
            when (val result = app.runWorker()) {
                SyncResult.Complete -> return attempt + 1
                is SyncResult.Blocked -> throw HrisProofFailure(result.failure)
                is SyncResult.Retry -> {
                    val failure = result.failure
                    if (attempt == 3) {
                        // Keep the final controlled retry reason; a generic check loses the
                        // distinction between delivery, history and runtime recovery failures.
                        throw HrisProofFailure(
                            failure
                                ?: Failure(
                                    FailureKind.Unavailable,
                                    "Bounded worker proof attempts exhausted",
                                    code = "client.collaboration_proof_retry_exhausted",
                                )
                        )
                    }
                    val wait =
                        if (failure == null) 100L
                        else maxOf(30_000L, (failure.retryAfterSeconds ?: 0) * 1_000)
                    check(wait <= 60_000)
                    delay(wait)
                }
            }
        }
        error("Worker did not complete")
    }

    private suspend fun seedHistory(
        peer: AndroidCollaborationRuntimeLease,
        room: Room,
    ): List<HrisHistoricalMessage> {
        val seed = ProofQueuedMessage(peer.nativeClient, room)
        return List(32) { index ->
            val body = "history-$index-${files.logicalId}"
            val sent = seed.send(body, UUID.randomUUID().toString())
            HrisHistoricalMessage(sent.eventId, body)
        }
    }

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
