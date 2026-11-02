package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.os.Process
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

/** Two protected SDK stores and one durable queue across an actual owned process death. */
internal class HrisNativeMatrixProbe(private val context: Context, private val directory: File) {
    suspend fun run(resuming: Boolean) =
        withContext(Dispatchers.IO) {
            val (fixture, home) = readFixture(resuming)
            val roomId = fixture.getString("roomId")
            val http =
                createHttpClient(
                    createPlatformHttpEngine(),
                    HttpClientSettings(home.toString(), allowCleartext = true),
                )
            var sender: AndroidCollaborationRuntimeLease? = null
            var recipient: AndroidCollaborationRuntimeLease? = null
            var senderRoom: Room? = null
            var recipientRoom: Room? = null
            var pending: HrisProofPendingQueue? = null
            var productQueue: HrisProductQueueProof? = null
            var senderTimeline: CollaborationTimelineLease? = null
            var recipientTimeline: CollaborationTimelineLease? = null
            try {
                val senderOwner =
                    ProtectedHrisProofOwner(context, fixture.getJSONObject("sender"), home, http)
                val recipientOwner =
                    ProtectedHrisProofOwner(context, fixture.getJSONObject("recipient"), home, http)
                sender =
                    if (resuming) senderOwner.restoreStored() else senderOwner.installAndReopen()
                recipient =
                    if (resuming) recipientOwner.restoreStored()
                    else recipientOwner.installAndReopen()
                pending = HrisProofPendingQueue(sender.nativeClient, roomId)
                pending.attach()
                check(sender.startSync() is AppResult.Success)
                check(sender.startSync() is AppResult.Success)
                check(recipient.startSync() is AppResult.Success)
                // Exercise a new product queue before any timeline has initialized this room.
                val held =
                    HrisProofHeldQueue(
                        sender,
                        fixture.getString("heldRoomId"),
                        logicalId(fixture, "heldLogicalId"),
                        body(fixture, "heldBody"),
                    )
                held.prepare(
                    if (resuming) read("gateway-staged", 4096).getString("heldTransactionId")
                    else null
                )
                senderTimeline = senderOwner.enterTimeline(roomId)
                recipientTimeline = recipientOwner.enterTimeline(roomId)
                senderRoom = requireNotNull(sender.nativeClient.getRoom(roomId))
                recipientRoom = requireNotNull(recipient.nativeClient.getRoom(roomId))
                productQueue =
                    HrisProductQueueProof(
                        context,
                        sender,
                        roomId,
                        fixture.getString("conversationId"),
                        fixture.getJSONObject("sender").getString("hrisAccessToken"),
                    )
                if (resuming) {
                    val result =
                        resume(
                            fixture,
                            sender,
                            recipient,
                            senderRoom,
                            recipientRoom,
                            pending,
                            senderTimeline,
                            recipientTimeline,
                            productQueue,
                            held,
                        )
                    pending.close()
                    pending = null
                    senderRoom.close()
                    senderRoom = null
                    check(senderTimeline.close() is AppResult.Success)
                    productQueue.reopenHistory(
                        senderOwner.repository,
                        result.getString("senderEventId"),
                        body(fixture, "body"),
                        fixture.getString("heldRoomId"),
                        held.transaction,
                    )
                    write("gateway-preack-result", result.put("historyRuntimeReopened", true))
                    productQueue.close()
                    productQueue = null
                    crashAfterAck(fixture, senderOwner)
                } else stage(fixture, sender, recipient, pending, productQueue, held)
            } finally {
                closeProofOwners(
                    listOf(
                        { pending?.close() },
                        { productQueue?.close() },
                        { senderRoom?.close() },
                        { recipientRoom?.close() },
                        { sender?.closeProofRuntime() },
                        { recipient?.closeProofRuntime() },
                        { http.cancelAndClose() },
                    )
                )
            }
            verifyClosedTimelines(listOfNotNull(senderTimeline, recipientTimeline))
        }

    private suspend fun crashAfterAck(fixture: JSONObject, owner: ProtectedHrisProofOwner) {
        val runtime = owner.restoreStored()
        var proof: HrisProductQueueProof? = null
        try {
            check(runtime.startSync() is AppResult.Success)
            proof =
                HrisProductQueueProof(
                    context,
                    runtime,
                    fixture.getString("roomId"),
                    fixture.getString("conversationId"),
                    fixture.getJSONObject("sender").getString("hrisAccessToken"),
                )
            proof.crashAfterAcknowledgement(
                logicalId(fixture, "ackLogicalId"),
                body(fixture, "ackBody"),
            ) { transaction ->
                write(
                    "gateway-acknowledged",
                    JSONObject()
                        .put("processId", Process.myPid())
                        .put("transactionId", transaction)
                        .put("ackObserved", true)
                        .put("receiptAbsent", true),
                )
            }
        } finally {
            closeProofOwners(listOf({ proof?.close() }, { runtime.closeProofRuntime() }))
        }
    }

    suspend fun runAckRecovery() =
        withContext(Dispatchers.IO) {
            val (fixture, home) = readFixture(resuming = true, afterAck = true)
            val checkpoint = read("gateway-acknowledged", 4096)
            val result = read("gateway-preack-result", 8192)
            check(checkpoint.get("ackObserved") == true && checkpoint.get("receiptAbsent") == true)
            check(
                checkpoint.getInt("processId") == result.getInt("processId") &&
                    checkpoint.getInt("processId") != Process.myPid()
            )
            val transaction = checkpoint.getString("transactionId")
            check(
                transaction.matches(Regex("[A-Za-z0-9._~-]{1,128}")) &&
                    transaction != result.getString("senderTransaction")
            )
            val http =
                createHttpClient(
                    createPlatformHttpEngine(),
                    HttpClientSettings(home.toString(), allowCleartext = true),
                )
            var runtime: AndroidCollaborationRuntimeLease? = null
            var proof: HrisProductQueueProof? = null
            try {
                val owner =
                    ProtectedHrisProofOwner(context, fixture.getJSONObject("sender"), home, http)
                runtime = owner.restoreStored()
                check(
                    runtime.proofKeyFingerprint() ==
                        read("gateway-staged", 4096).getString("senderKeyFingerprint")
                )
                HrisReadReceiptProof.restored(runtime, fixture.getString("roomId"), result)
                check(runtime.startSync() is AppResult.Success)
                proof =
                    HrisProductQueueProof(
                        context,
                        runtime,
                        fixture.getString("roomId"),
                        fixture.getString("conversationId"),
                        fixture.getJSONObject("sender").getString("hrisAccessToken"),
                    )
                write(
                    "gateway-ack-restored",
                    JSONObject().put("processId", Process.myPid()).put("transactionId", transaction),
                )
                awaitFile("gateway-ack-resume")
                val sent =
                    proof.recoverAcknowledgement(
                        logicalId(fixture, "ackLogicalId"),
                        body(fixture, "ackBody"),
                        transaction,
                    )
                proof.recoverReadState(result)
                write(
                    "gateway-precompletion-result",
                    result
                        .put("processId", Process.myPid())
                        .put("ackBeforeJournalRecovered", true)
                        .put("ackSenderEventId", sent.eventId)
                        .put("ackTransaction", sent.transactionId),
                )
                proof.crashAfterCompletion(logicalId(fixture, "ackLogicalId"), transaction) {
                    retained ->
                    write(
                        "gateway-completed",
                        JSONObject()
                            .put("processId", Process.myPid())
                            .put("transactionId", retained)
                            .put("archiveRetained", true)
                            .put("receiptRetained", true),
                    )
                }
            } finally {
                closeProofOwners(
                    listOf(
                        { proof?.close() },
                        { runtime?.closeProofRuntime() },
                        { http.cancelAndClose() },
                    )
                )
            }
        }

    suspend fun runCompletionRecovery() =
        withContext(Dispatchers.IO) {
            val (fixture, home) = readFixture(resuming = true, afterAck = true)
            val checkpoint = read("gateway-completed", 4096)
            val result = read("gateway-precompletion-result", 8192)
            check(
                checkpoint.get("archiveRetained") == true &&
                    checkpoint.get("receiptRetained") == true
            )
            check(
                checkpoint.getInt("processId") == result.getInt("processId") &&
                    checkpoint.getInt("processId") != Process.myPid()
            )
            val transaction = checkpoint.getString("transactionId")
            check(
                transaction.matches(Regex("[A-Za-z0-9._~-]{1,128}")) &&
                    transaction == result.getString("ackTransaction")
            )
            val http =
                createHttpClient(
                    createPlatformHttpEngine(),
                    HttpClientSettings(home.toString(), allowCleartext = true),
                )
            var runtime: AndroidCollaborationRuntimeLease? = null
            var proof: HrisProductQueueProof? = null
            try {
                runtime =
                    ProtectedHrisProofOwner(context, fixture.getJSONObject("sender"), home, http)
                        .restoreStored()
                check(
                    runtime.proofKeyFingerprint() ==
                        read("gateway-staged", 4096).getString("senderKeyFingerprint")
                )
                // Cleanup reads owned local evidence. No sync or delivery is started in this
                // process.
                proof =
                    HrisProductQueueProof(
                        context,
                        runtime,
                        fixture.getString("roomId"),
                        fixture.getString("conversationId"),
                        fixture.getJSONObject("sender").getString("hrisAccessToken"),
                    )
                write(
                    "gateway-completion-restored",
                    JSONObject().put("processId", Process.myPid()).put("transactionId", transaction),
                )
                awaitFile("gateway-completion-resume")
                val sent = proof.recoverCompletion(logicalId(fixture, "ackLogicalId"), transaction)
                check(
                    sent.eventId == result.getString("ackSenderEventId") &&
                        sent.transactionId == transaction
                )
                write(
                    "gateway-result",
                    result.put("processId", Process.myPid()).put("completionCleanupRecovered", true),
                )
            } finally {
                closeProofOwners(
                    listOf(
                        { proof?.close() },
                        { runtime?.closeProofRuntime() },
                        { http.cancelAndClose() },
                    )
                )
            }
        }

    private suspend fun closeProofOwners(operations: List<suspend () -> Unit>) =
        withContext(NonCancellable) {
            // Every owner gets a cleanup attempt even after another owner fails or times out.
            var first: Exception? = null
            for (operation in operations) try {
                operation()
            } catch (failure: Exception) {
                if (first == null) first = failure else first.addSuppressed(failure)
            }
            first?.let { throw it }
        }

    private suspend fun verifyClosedTimelines(timelines: List<CollaborationTimelineLease>) {
        for (timeline in timelines) {
            val ended = timeline.updates.first()
            check(
                ended is AppResult.Failed &&
                    ended.failure.code == "client.collaboration_timeline_closed"
            )
        }
    }

    private fun readFixture(resuming: Boolean, afterAck: Boolean = false): Pair<JSONObject, URI> {
        val fixture = read("gateway-fixture", 16_384)
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
        require(File(directory, "gateway-send.json").exists() == resuming)
        require(File(directory, "gateway-resume.json").exists() == afterAck)
        val roomId = fixture.getString("roomId")
        require(roomId.startsWith('!') && roomId.length in 2..255)
        for (name in listOf("body", "reply", "heldBody", "ackBody")) body(fixture, name)
        for (name in listOf("logicalId", "replyId", "heldLogicalId", "ackLogicalId")) logicalId(
            fixture,
            name,
        )
        return fixture to home
    }

    private suspend fun stage(
        fixture: JSONObject,
        sender: AndroidCollaborationRuntimeLease,
        recipient: AndroidCollaborationRuntimeLease,
        pending: HrisProofPendingQueue,
        productQueue: HrisProductQueueProof,
        held: HrisProofHeldQueue,
    ): Nothing {
        write("gateway-ready", JSONObject().put("ready", true))
        awaitFile("gateway-send")
        val transaction = productQueue.stage(logicalId(fixture, "logicalId"), body(fixture, "body"))
        check(pending.retainedTransaction() == transaction)
        pending.checkPaused()
        write(
            "gateway-staged",
            protectedFlags(JSONObject())
                .put("transactionId", transaction)
                .put("heldTransactionId", held.transaction)
                .put("processId", Process.myPid())
                .put("senderKeyFingerprint", sender.proofKeyFingerprint())
                .put("recipientKeyFingerprint", recipient.proofKeyFingerprint()),
        )
        // The driver force-stops this exact package. Normal disposal must not stand in
        // for process death or make an extra flush that the real crash would not make.
        awaitCancellation()
    }

    private suspend fun resume(
        fixture: JSONObject,
        sender: AndroidCollaborationRuntimeLease,
        recipient: AndroidCollaborationRuntimeLease,
        senderRoom: Room,
        recipientRoom: Room,
        pending: HrisProofPendingQueue,
        senderTimeline: CollaborationTimelineLease,
        recipientTimeline: CollaborationTimelineLease,
        productQueue: HrisProductQueueProof,
        held: HrisProofHeldQueue,
    ): JSONObject {
        val staged = read("gateway-staged", 4096)
        for (name in protectedFlagNames) check(staged.get(name) == true)
        check(staged.getInt("processId") != Process.myPid())
        check(staged.getString("senderKeyFingerprint") == sender.proofKeyFingerprint())
        check(staged.getString("recipientKeyFingerprint") == recipient.proofKeyFingerprint())
        val expected = staged.getString("transactionId")
        productQueue.recover(logicalId(fixture, "logicalId"), body(fixture, "body"), expected)
        check(pending.retainedTransaction() == expected)
        pending.checkPaused()
        return HrisProofMessages(
                recipientRoom,
                body(fixture, "body"),
                logicalId(fixture, "logicalId"),
            )
            .use { incoming ->
                HrisProofMessages(senderRoom, body(fixture, "reply"), logicalId(fixture, "replyId"))
                    .use { returning ->
                        write(
                            "gateway-restored",
                            JSONObject()
                                .put("processId", Process.myPid())
                                .put("transactionId", expected),
                        )
                        awaitFile("gateway-resume")
                        check(
                            sender.credentialFailures.value == null &&
                                recipient.credentialFailures.value == null
                        )
                        val sent = productQueue.deliver(logicalId(fixture, "logicalId"), expected)
                        check(pending.accepted(expected) == sent)
                        productQueue.accepted(
                            logicalId(fixture, "logicalId"),
                            sent.transactionId,
                            sent.eventId,
                        )
                        check(incoming.eventId() == sent.eventId)
                        val answered =
                            ProofQueuedMessage(recipient.nativeClient, recipientRoom)
                                .send(body(fixture, "reply"), logicalId(fixture, "replyId"))
                        check(returning.eventId() == answered.eventId)
                        held.checkRetained()
                        recipientTimeline.awaitMessage(
                            sent.eventId,
                            body(fixture, "body"),
                            logicalId(fixture, "logicalId"),
                        )
                        senderTimeline.awaitMessage(
                            answered.eventId,
                            body(fixture, "reply"),
                            logicalId(fixture, "replyId"),
                        )
                        val history =
                            productQueue.recoverHistory(
                                sent.eventId,
                                body(fixture, "body"),
                                answered.eventId,
                                recipient.nativeClient.userId(),
                                body(fixture, "reply"),
                            )
                        HrisReadReceiptProof.exchange(
                            sender,
                            recipient,
                            senderRoom,
                            sent.eventId,
                            answered.eventId,
                        )
                        check(recipientTimeline.close() is AppResult.Success)
                        // Screen disposal leaves the sibling runtime/observer usable.
                        senderTimeline.awaitMessage(
                            answered.eventId,
                            body(fixture, "reply"),
                            logicalId(fixture, "replyId"),
                        )
                        exchangeFlags()
                            .withHistoryEvidence(history)
                            .put("senderEventId", sent.eventId)
                            .put("recipientEventId", answered.eventId)
                            .put("senderTransaction", sent.transactionId)
                            .put("recipientTransaction", answered.transactionId)
                            .put("processId", Process.myPid())
                            .put("syncMode", "classic")
                    }
            }
    }

    private fun exchangeFlags() =
        protectedFlags(JSONObject())
            .put("encryptedBothDirections", true)
            .put("nativeReadReceiptsExchanged", true)
            .put("nativePrivateReceiptIsolated", true)
            .put("logicalIdsPreserved", true)
            .put("queueRestored", true)
            .put("ownedTimelineObserved", true)
            .put("transactionReused", true)
            .put("identityAfterRestart", true)
            .put("messageIntentRestored", true)
            .put("queueMappingRecovered", true)
            .put("queueWriterExclusive", true)
            .put("perRoomDeliveryIsolated", true)
            .put("heldQueueRestored", true)
            .put("nativeDeliveryOwned", true)
            .put("commonSenderOwned", true)
            .put("receiptJournalRecovered", true)
            .put("projectionInterrupted", true)

    private fun JSONObject.withHistoryEvidence(history: JSONObject) = apply {
        for (flag in
            listOf(
                "latestHistoryHeadOwned",
                "commonHistoryAcquisitionOwned",
                "providerHistoryPageOwned",
                "commonProviderHistoryAcquisitionOwned",
                "providerHistoryReferencesRestored",
                "providerHistoryReferencesReconciled",
                "providerHistoryRestartPreserved",
                "nativeLocalReadIntentPrepared",
            )) {
            put(flag, history.remove(flag) == true)
        }
        put("originalHistoryWindowOwned", true)
        put("historyBodiesRestored", true)
        put("historyWindow", history)
    }

    private suspend fun CollaborationTimelineLease.awaitMessage(
        eventId: String,
        body: String,
        logicalId: String,
    ) {
        withTimeout(30_000) {
            updates.first { result ->
                check(result !is AppResult.Failed)
                result is AppResult.Success &&
                    result.value.any {
                        it.eventId == eventId && it.body == body && it.logicalId == logicalId
                    }
            }
        }
    }

    private fun body(value: JSONObject, name: String) =
        value.getString(name).also {
            require(it.startsWith("fictional-hris-") && it.length in 1..150)
        }

    private fun logicalId(value: JSONObject, name: String) =
        UUID.fromString(value.getString(name)).toString()

    private fun protectedFlags(value: JSONObject): JSONObject =
        value.apply { for (name in protectedFlagNames) put(name, true) }

    private fun read(name: String, maximum: Int): JSONObject {
        val file = File(directory, "$name.json")
        check(file.length() in 1..maximum.toLong())
        return JSONObject(file.readText())
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

    private suspend fun awaitFile(name: String) =
        withTimeout(30_000) {
            while (!File(directory, "$name.json").exists()) delay(100)
            check(read(name, 128).get("ready") == true)
        }

    private companion object {
        val protectedFlagNames =
            listOf(
                "protectedStoreReopened",
                "missingRecordPreserved",
                "wrongPassphrasePreserved",
                "exclusiveOwner",
            )
    }
}
