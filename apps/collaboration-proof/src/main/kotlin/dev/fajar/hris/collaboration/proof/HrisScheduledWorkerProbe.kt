package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.os.Process
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageRecord
import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageState
import dev.fajar.starter.collaboration.domain.repositories.CollaborationTimelineLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.worker.AndroidWorkScheduler
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Real platform scheduling with fictional session inputs; observers never execute the task. */
internal class HrisScheduledWorkerProbe(private val context: Context, directory: File) {
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
    private val scheduler by lazy { AndroidWorkScheduler(WorkManager.getInstance(context)) }
    private val app by lazy {
        HrisWorkerProofContainer(
            context,
            sender.currentOwner,
            files.conversationId,
            files.fixture.getJSONObject("sender").getString("hrisAccessToken"),
            sender.repository,
            http,
            platformScheduler =
                object : WorkScheduler {
                    override suspend fun enqueue(key: String) = scheduler.enqueue(key)
                },
        )
    }
    private val state = HrisScheduledWorkState(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private var phase = "Preparation"
    private var restored: CollaborationMessageRecord? = null

    fun tasks(): List<SyncTask> = app.syncTasks()

    suspend fun initialize() {
        check(files.read("workmanager-enabled", 128).get("enabled") == true)
        if (!files.exists("scheduled-queued")) return
        app.activate()
        val queued = files.read("scheduled-queued", 4096)
        check(queued.getInt("processId") != Process.myPid())
        check(
            queued.getString("messageId") == files.logicalId &&
                queued.getString("bodySha256") == files.bodyHash
        )
        check(queued.getString("state") == "QUEUED")
        check(!files.exists("scheduled-started") && !files.exists("scheduled-result"))
        val pending = app.pending().single()
        check(pending.intent.id == files.logicalId && pending.intent.text == files.body)
        check(pending.state == CollaborationMessageState.Queued)
        check(!expect(sender.repository.isOpen(sender.currentOwner, sender.registeredDeviceId)))
        restored = pending
        files.write(
            "scheduled-started",
            files
                .checkpoint()
                .put("workId", queued.getString("workId"))
                .put("senderRuntimeOpen", false),
        )
    }

    fun startStage() {
        check(restored == null && started.compareAndSet(false, true))
        launchOwned { stage() }
    }

    fun observeBackgroundWork() {
        if (restored == null) return
        check(started.compareAndSet(false, true))
        launchOwned {
            val id = UUID.fromString(files.read("scheduled-queued", 4096).getString("workId"))
            phase = "AwaitCompletion"
            val info =
                requireNotNull(
                    WorkManager.getInstance(context).getWorkInfoByIdFlow(id).first {
                        it != null && it.state.isFinished
                    }
                )
            check(info.state == WorkInfo.State.SUCCEEDED && info.runAttemptCount in 0..3)
            val result = verifyCompleted(info)
            close()
            files.write("scheduled-result", result)
        }
    }

    private suspend fun stage() {
        var own: AndroidCollaborationRuntimeLease? = null
        var peer: AndroidCollaborationRuntimeLease? = null
        var ownTimeline: CollaborationTimelineLease? = null
        var peerTimeline: CollaborationTimelineLease? = null
        try {
            check(state.connected())
            own = sender.installAndReopen()
            peer = recipient.installAndReopen()
            app.activate()
            expect(own.startSync())
            expect(peer.startSync())
            ownTimeline = sender.enterTimeline(files.roomId)
            peerTimeline = recipient.enterTimeline(files.roomId)
            app.cacheConversation()
            check(app.pending().isEmpty())
            files.write("scheduled-prepared", files.checkpoint().put("state", "PREPARED"))
            phase = "AwaitOffline"
            withTimeout(45_000) {
                while (!files.exists("scheduled-offline")) delay(100)
                check(files.read("scheduled-offline", 128).get("ready") == true)
                while (state.connected()) delay(100)
            }
            phase = "Queue"
            val queued = app.queue(files.logicalId, files.body)
            check(
                queued.state == CollaborationMessageState.Queued && queued.intent.text == files.body
            )
            check(app.pending() == listOf(queued))
            phase = "AwaitWorkInfo"
            val work = state.pending()
            files.write(
                "scheduled-queued",
                files.checkpoint().also { checkpoint ->
                    work.keys().forEach { key -> checkpoint.put(key, work.get(key)) }
                },
            )
            phase = "AwaitDeath"
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                try {
                    ownTimeline?.close()?.let { expect(it) }
                } finally {
                    try {
                        peerTimeline?.close()?.let { expect(it) }
                    } finally {
                        try {
                            own?.closeProofRuntime()
                        } finally {
                            peer?.closeProofRuntime()
                        }
                    }
                }
            }
        }
    }

    private suspend fun verifyCompleted(info: WorkInfo): JSONObject {
        check(expect(sender.repository.isOpen(sender.currentOwner, sender.registeredDeviceId)))
        val original = app.history().messages.single { it.logicalId == files.logicalId }
        check(original.text == files.body && !original.redacted)
        val completion = requireNotNull(app.completion(files.logicalId))
        check(completion.record.intent == requireNotNull(restored).intent)
        val event = original.eventIds.intersect(completion.eventIds.toSet()).single()
        check(app.pending().isEmpty())
        return files
            .checkpoint()
            .put("state", "COMPLETED")
            .put("workId", info.id.toString())
            .put("workState", "SUCCEEDED")
            .put("runAttemptCount", info.runAttemptCount)
            .put("eventId", event)
            .put("senderRuntimeRestoredByWorker", true)
            .put("originalHistoryAcquired", true)
            .put("completionRetired", true)
    }

    private fun launchOwned(operation: suspend () -> Unit) {
        scope.launch {
            try {
                withTimeout(210_000) { operation() }
            } catch (failure: Exception) {
                files.write(
                    "scheduled-failure",
                    JSONObject()
                        .put(
                            "failureCategory",
                            if (failure is TimeoutCancellationException) "Scheduled${phase}Timeout"
                            else
                                (failure as? HrisProofFailure)?.category
                                    ?: failure.javaClass.simpleName,
                        )
                        .put(
                            "failureFrames",
                            JSONArray(
                                failure.stackTrace
                                    .filter {
                                        it.className.startsWith("dev.fajar.") ||
                                            it.className.startsWith("org.matrix.")
                                    }
                                    .take(12)
                                    .map { "${it.className}.${it.methodName}:${it.lineNumber}" }
                            ),
                        ),
                )
            } finally {
                try {
                    close()
                } finally {
                    scope.cancel()
                }
            }
        }
    }

    private suspend fun close() =
        withContext(NonCancellable) {
            if (closed.compareAndSet(false, true)) {
                try {
                    app.close()
                } finally {
                    http.cancelAndClose()
                }
            }
        }

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
