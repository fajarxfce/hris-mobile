package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.os.Process
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.network.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Actual native control and registered recovery task; only newly staged fictional originals. */
internal class HrisSpaceRecoveryProbe(private val context: Context, directory: File) {
    private val files = HrisWorkerProofFiles(directory)
    private val ids = listOf(files.logicalId, canonical(files.fixture, "secondLogicalId"))
    private val http =
        createHttpClient(
            createPlatformHttpEngine(),
            HttpClientSettings(files.home.toString(), allowCleartext = true),
        )
    private val sender =
        ProtectedHrisProofOwner(context, files.fixture.getJSONObject("sender"), files.home, http)

    suspend fun run(interrupt: Boolean) =
        withContext(Dispatchers.IO) {
            try {
                recover(interrupt)
            } finally {
                http.cancelAndClose()
            }
        }

    private suspend fun recover(interrupt: Boolean) {
        val previous = files.read("original-staged", 16_384)
        val group = files.read("original-group", 16_384)
        lateinit var app: HrisWorkerProofContainer
        lateinit var recovery: HrisSpaceRecoveryProofHost
        var recoveryCreated = false
        var primary: Exception? = null
        app =
            HrisWorkerProofContainer(
                context,
                sender.currentOwner,
                files.conversationId,
                files.fixture.getJSONObject("sender").getString("hrisAccessToken"),
                sender.repository,
                http,
                beforeOriginalProjection =
                    if (interrupt)
                        { record -> retainInterruptedAcknowledgment(app, recovery, record, group) }
                    else null,
            )
        try {
            app.activate()
            verifyOriginals(app, previous)
            val seed = app.spaceRecoverySeed(sender, previous, group)
            if (interrupt) {
                seed.prepare()
                app.prepareOriginalRoom(seed.owner, canonical(group, "conversationId"))
            }
            recovery = app.spaceRecovery(seed)
            recoveryCreated = true
            if (!interrupt) {
                val interrupted = files.read("original-ack", 16_384)
                val saved = requireNotNull(recovery.checkpoint().progress)
                check(saved.generation == interrupted.getString("recoveryGeneration"))
                check(saved.sweep?.pending?.conversationId == canonical(group, "conversationId"))
                check(recovery.pendingWake()?.generation == interrupted.getString("wakeGeneration"))
            }
            recovery.start()
            if (interrupt) {
                recovery.awaitReadyForControl()
                files.write(
                    "original-listening",
                    checkpoint()
                        .put("nativeSyncActive", true)
                        .put("startupWakeCleared", true)
                        .put("noMessagesDelivered", true),
                )
                val revision = recovery.awaitControl()
                files.write(
                    "original-control",
                    checkpoint()
                        .put("publicationRevision", revision)
                        .put("durableWake", true)
                        .put("capturedReplayDeduplicated", true),
                )
            }
            recovery.recover()
            check(!interrupt) { "The registered worker did not reach its durable ACK boundary" }
            verifyOriginals(app, previous)
            val journal = app.receipts()
            val sent =
                ids.map { id ->
                    val record = requireNotNull(app.message(id))
                    val state = record.state as CollaborationMessageState.Sent
                    val receipt = journal.single { it.intent.id == id }
                    check(receipt.intent == record.intent && state.eventId in receipt.eventIds)
                    JSONObject()
                        .put("logicalId", id)
                        .put("transactionId", state.transactionId)
                        .put("events", JSONArray(receipt.eventIds))
                }
            check(recovery.pendingWake() == null && recovery.checkpoint().progress?.sweep == null)
            files.write(
                "original-recovered",
                checkpoint()
                    .put("confirmed", JSONArray(sent))
                    .put("originalsRetained", true)
                    .put("recipientStayedOffline", true)
                    .put("groupTaskCompleted", true)
                    .put("progressAndWakeCompleted", true)
                    .put("recoveredWithoutNewControl", true),
            )
        } catch (failure: Exception) {
            primary = failure
            throw failure
        } finally {
            closeHrisProofResources(
                primary,
                listOf({ if (recoveryCreated) recovery.close() }, { app.close() }),
            )
        }
    }

    /** Interruption is permitted only after both the real receipt and group claim are durable. */
    private suspend fun retainInterruptedAcknowledgment(
        app: HrisWorkerProofContainer,
        recovery: HrisSpaceRecoveryProofHost,
        record: CollaborationMessageRecord,
        group: JSONObject,
    ): Nothing {
        val receipt = app.receipts().single { it.intent.id == record.intent.id }
        check(receipt.intent == record.intent && receipt.eventIds.isNotEmpty())
        check(app.message(record.intent.id)?.state is CollaborationMessageState.Staged)
        val snapshot = requireNotNull(recovery.checkpoint().progress)
        check(snapshot.sweep?.pending?.conversationId == canonical(group, "conversationId"))
        val wake = requireNotNull(recovery.pendingWake())
        files.write(
            "original-ack",
            checkpoint()
                .put("logicalId", record.intent.id)
                .put("journalDurable", true)
                .put("projectionNotWritten", true)
                .put("events", JSONArray(receipt.eventIds))
                .put("recoveryGeneration", snapshot.generation)
                .put("wakeGeneration", wake.generation)
                .put("claimedBeforeDelivery", true),
        )
        awaitCancellation()
    }

    private suspend fun verifyOriginals(host: HrisWorkerProofContainer, previous: JSONObject) {
        val originals = previous.getJSONArray("originals")
        check(originals.length() == ids.size)
        for (index in 0 until originals.length()) {
            val saved = originals.getJSONObject(index)
            val id = canonical(saved, "logicalId")
            check(id in ids)
            val record = requireNotNull(host.message(id))
            check(record.intent.id == id && record.intent.conversationId == files.conversationId)
            check(hash(record.intent.text) == saved.getString("textHash"))
            val coordinates =
                when (val state = record.state) {
                    is CollaborationMessageState.Staged ->
                        Triple(state.device, state.transactionId, state.origin)
                    is CollaborationMessageState.Sent ->
                        Triple(state.device, state.transactionId, state.origin)
                    else -> error("An original was recreated or lost")
                }
            check(coordinates.first.nativeSessionId == canonical(saved, "nativeSessionId"))
            check(coordinates.first.deviceId == canonical(saved, "deviceId"))
            check(
                coordinates.first.bootstrapOperationId == canonical(saved, "bootstrapOperationId")
            )
            check(
                coordinates.first.roomId == files.roomId &&
                    coordinates.second == saved.getString("transactionId")
            )
            check(requireNotNull(coordinates.third).sender == saved.getString("sender"))
            check(requireNotNull(coordinates.third).contentHash == saved.getString("originHash"))
        }
    }

    private fun checkpoint() = JSONObject().put("processId", Process.myPid())

    private fun canonical(value: JSONObject, name: String) =
        value.getString(name).also {
            check(
                UUID.fromString(it).toString() == it && it != "00000000-0000-0000-0000-000000000000"
            )
        }

    private fun hash(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
