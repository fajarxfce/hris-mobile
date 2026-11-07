package dev.fajar.hris.collaboration.proof

import android.content.Context
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationTimelineLease
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationText
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import dev.fajar.starter.worker.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/**
 * Two fictional rooms, actual app delivery, and a recipient whose SDK is closed after key upload.
 */
internal class HrisMixedRoomProbe(private val context: Context, directory: File) {
    private val files = HrisWorkerProofFiles(directory)
    private val unavailable = files.fixture.getJSONObject("unavailable")
    private val blockedConversation = unavailable.getString("conversationId")
    private val blockedId = unavailable.getString("logicalId")
    private val blockedBody = "fictional-hris-worker-unavailable"
    private val http =
        createHttpClient(
            createPlatformHttpEngine(),
            HttpClientSettings(files.home.toString(), allowCleartext = true),
        )
    private val sender =
        ProtectedHrisProofOwner(context, files.fixture.getJSONObject("sender"), files.home, http)
    private val recipient =
        ProtectedHrisProofOwner(context, files.fixture.getJSONObject("recipient"), files.home, http)

    init {
        require(UUID.fromString(blockedConversation).toString() == blockedConversation)
        require(UUID.fromString(blockedId).toString() == blockedId)
        require(blockedConversation != files.conversationId && blockedId != files.logicalId)
    }

    suspend fun run(): Unit =
        withContext(Dispatchers.IO) {
            coroutineScope {
                var senderLease: AndroidCollaborationRuntimeLease? = null
                var recipientLease: AndroidCollaborationRuntimeLease? = null
                var timeline: CollaborationTimelineLease? = null
                var application: HrisWorkerProofContainer? = null
                var foreground: Job? = null
                var observer: Job? = null
                var primary: Exception? = null
                val scheduler =
                    ForegroundWorkScheduler(
                        setOf(
                            QueueCollaborationText.DELIVERY_KEY,
                            CollaborationRecoveryWake.WORK_KEY,
                        )
                    )
                val observations = HrisMixedRoomObservations(files)
                try {
                    senderLease = sender.installInitial()
                    expect(senderLease.startSync())
                    recipientLease = recipient.installInitial()
                    expect(recipientLease.startSync())
                    timeline = recipient.enterTimeline(files.roomId)
                    files.write("mixed-initialized", JSONObject().put("ready", true))
                    awaitSignal("mixed-readiness")
                    expect(timeline.close())
                    timeline = null
                    recipientLease.closeProofRuntime()
                    recipientLease = null
                    check(
                        !expect(
                            recipient.repository.isOpen(
                                recipient.currentOwner,
                                recipient.registeredDeviceId,
                            )
                        )
                    )
                    observations.record("recipientRuntimeClosedMillis")
                    val app =
                        HrisWorkerProofContainer(
                                context,
                                sender.currentOwner,
                                files.conversationId,
                                files.fixture.getJSONObject("sender").getString("hrisAccessToken"),
                                sender.repository,
                                http,
                                platformScheduler = scheduler,
                            )
                            .also { application = it }
                    app.activate()
                    app.cacheConversation(blockedConversation)
                    app.cacheConversation()
                    files.write("mixed-prepared", JSONObject().put("recipientRuntimeClosed", true))
                    awaitSignal("mixed-start")
                    val blocked = app.queue(blockedId, blockedBody, blockedConversation)
                    check(blocked.state == CollaborationMessageState.Queued)
                    observations.record("unavailableQueuedMillis")
                    val task =
                        app.syncTasks().single { it.key == QueueCollaborationText.DELIVERY_KEY }
                    foreground = ForegroundSyncWorker(listOf(task), scheduler).start(this)
                    awaitSignal("mixed-enqueue")
                    observer =
                        launch(start = CoroutineStart.UNDISPATCHED) { observations.collect(app) }
                    val ready = app.queue(files.logicalId, files.body)
                    check(ready.state == CollaborationMessageState.Queued)
                    observations.record("readyQueuedMillis")
                    observations.awaitCompletion(app, ready)
                    foreground.cancelAndJoin()
                    foreground = null
                    val retained = requireNotNull(app.message(blockedId))
                    check(
                        retained.intent == blocked.intent &&
                            retained.state !is CollaborationMessageState.Sent
                    )
                    check(
                        app.completion(blockedId) == null &&
                            app.receipts(blockedConversation).isEmpty()
                    )
                    observations.complete()
                } catch (failure: Exception) {
                    primary = failure
                    throw failure
                } finally {
                    closeHrisProofResources(
                        primary,
                        listOf(
                            { foreground?.cancelAndJoin() },
                            { observer?.cancelAndJoin() },
                            { scheduler.close() },
                            { timeline?.close()?.let { expect(it) } },
                            { application?.close() },
                            { senderLease?.closeProofRuntime() },
                            { recipientLease?.closeProofRuntime() },
                            { http.cancelAndClose() },
                            { observations.write() },
                        ),
                    )
                }
            }
        }

    private suspend fun awaitSignal(name: String) =
        withTimeout(60_000) {
            while (!files.exists(name)) delay(50)
            check(files.read(name, 64).toString() == "{\"ready\":true}")
        }

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}

/** Monotonic observations, never inferred from a quiet log or a different process clock. */
private class HrisMixedRoomObservations(private val files: HrisWorkerProofFiles) {
    private val started = System.nanoTime()
    private val result = JSONObject().put("complete", false)

    suspend fun collect(app: HrisWorkerProofContainer) {
        app.observePending().collect { value ->
            val messages =
                when (value) {
                    is AppResult.Success -> value.value
                    is AppResult.Failed -> throw HrisProofFailure(value.failure)
                }
            if (
                messages.any {
                    it.intent.id == files.logicalId && it.state is CollaborationMessageState.Sent
                }
            ) {
                record("sentObservedMillis")
            }
        }
    }

    @Synchronized
    fun record(name: String) {
        if (!result.has(name)) result.put(name, (System.nanoTime() - started) / 1_000_000)
    }

    suspend fun awaitCompletion(
        app: HrisWorkerProofContainer,
        original: CollaborationMessageRecord,
    ) =
        withTimeout(150_000) {
            while (true) {
                val receipt = app.receipts().singleOrNull { it.intent.id == files.logicalId }
                if (receipt != null) {
                    check(receipt.intent == original.intent && receipt.eventIds.isNotEmpty())
                    record("journalObservedMillis")
                }
                val completed = app.completion(files.logicalId)
                if (completed != null) {
                    check(
                        completed.record.intent == original.intent && completed.eventIds.size == 1
                    )
                    record("completionObservedMillis")
                    synchronized(this@HrisMixedRoomObservations) {
                        result.put("eventId", completed.eventIds.single())
                        result.put("originalReadyIntentRetained", true)
                    }
                    if (
                        synchronized(this@HrisMixedRoomObservations) {
                            result.has("sentObservedMillis")
                        }
                    )
                        return@withTimeout
                }
                delay(40)
            }
        }

    @Synchronized
    fun complete() {
        check(result.has("sentObservedMillis"))
        result.put("unavailableOriginalRetained", true).put("unavailableReceiptAbsent", true)
        result.put("complete", true)
    }

    @Synchronized fun write() = files.write("mixed-result", result)
}
