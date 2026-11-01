package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.repositories.CollaborationTimelineLease
import dev.fajar.starter.common.result.AppResult
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
                    ProtectedHrisProofOwner(context, fixture.getJSONObject("sender"), home)
                val recipientOwner =
                    ProtectedHrisProofOwner(context, fixture.getJSONObject("recipient"), home)
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
                if (resuming)
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
                else stage(fixture, sender, recipient, pending, productQueue, held)
            } finally {
                withContext(NonCancellable) {
                    // Attempt every owned cleanup even if another owner fails or exceeds its wait.
                    var first: Exception? = null
                    val operations: List<suspend () -> Unit> =
                        listOf(
                            { pending?.close() },
                            { productQueue?.close() },
                            { senderRoom?.close() },
                            { recipientRoom?.close() },
                            { sender?.closeProofRuntime() },
                            { recipient?.closeProofRuntime() },
                        )
                    for (operation in operations) try {
                        operation()
                    } catch (failure: Exception) {
                        if (first == null) first = failure else first.addSuppressed(failure)
                    }
                    first?.let { throw it }
                }
            }
            for (timeline in listOfNotNull(senderTimeline, recipientTimeline)) {
                val ended = timeline.updates.first()
                check(
                    ended is AppResult.Failed &&
                        ended.failure.code == "client.collaboration_timeline_closed"
                )
            }
        }

    private fun readFixture(resuming: Boolean): Pair<JSONObject, URI> {
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
        require(!File(directory, "gateway-resume.json").exists())
        val roomId = fixture.getString("roomId")
        require(roomId.startsWith('!') && roomId.length in 2..255)
        for (name in listOf("body", "reply", "heldBody")) body(fixture, name)
        for (name in listOf("logicalId", "replyId", "heldLogicalId")) logicalId(fixture, name)
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
    ) {
        val staged = read("gateway-staged", 4096)
        for (name in protectedFlagNames) check(staged.get(name) == true)
        check(staged.getInt("processId") != Process.myPid())
        check(staged.getString("senderKeyFingerprint") == sender.proofKeyFingerprint())
        check(staged.getString("recipientKeyFingerprint") == recipient.proofKeyFingerprint())
        val expected = staged.getString("transactionId")
        productQueue.recover(logicalId(fixture, "logicalId"), body(fixture, "body"), expected)
        check(pending.retainedTransaction() == expected)
        pending.checkPaused()
        HrisProofMessages(recipientRoom, body(fixture, "body"), logicalId(fixture, "logicalId"))
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
                        check(recipientTimeline.close() is AppResult.Success)
                        // Screen disposal leaves the sibling runtime/observer usable.
                        senderTimeline.awaitMessage(
                            answered.eventId,
                            body(fixture, "reply"),
                            logicalId(fixture, "replyId"),
                        )
                        write(
                            "gateway-result",
                            protectedFlags(JSONObject())
                                .put("encryptedBothDirections", true)
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
                                .put("originalHistoryWindowOwned", true)
                                .put("historyBodiesRestored", true)
                                .put(
                                    "commonHistoryAcquisitionOwned",
                                    history.remove("commonHistoryAcquisitionOwned") == true,
                                )
                                .put("historyWindow", history)
                                .put("senderEventId", sent.eventId)
                                .put("recipientEventId", answered.eventId)
                                .put("senderTransaction", sent.transactionId)
                                .put("recipientTransaction", answered.transactionId)
                                .put("processId", Process.myPid())
                                .put("syncMode", "classic"),
                        )
                    }
            }
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
