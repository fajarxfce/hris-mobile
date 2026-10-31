package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.common.result.AppResult
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Room

/** Two protected SDK stores and one durable queue across an actual owned process death. */
internal class HrisNativeMatrixProbe(private val context: Context, private val directory: File) {
    suspend fun run(resuming: Boolean) =
        withContext(Dispatchers.IO) {
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
            for (name in listOf("body", "reply")) body(fixture, name)
            for (name in listOf("logicalId", "replyId")) logicalId(fixture, name)
            var sender: AndroidCollaborationRuntimeLease? = null
            var recipient: AndroidCollaborationRuntimeLease? = null
            var senderRoom: Room? = null
            var recipientRoom: Room? = null
            var pending: HrisProofPendingQueue? = null
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
                senderRoom = sender.nativeClient.joinRoomById(roomId)
                recipientRoom = recipient.nativeClient.joinRoomById(roomId)
                if (resuming) resume(fixture, sender, recipient, senderRoom, recipientRoom, pending)
                else stage(fixture, sender, recipient, senderRoom, pending)
            } finally {
                withContext(NonCancellable) {
                    // Attempt every owned cleanup even if another owner fails or exceeds its wait.
                    var first: Exception? = null
                    val operations: List<suspend () -> Unit> =
                        listOf(
                            { pending?.close() },
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
        }

    private suspend fun stage(
        fixture: JSONObject,
        sender: AndroidCollaborationRuntimeLease,
        recipient: AndroidCollaborationRuntimeLease,
        senderRoom: Room,
        pending: HrisProofPendingQueue,
    ): Nothing {
        write("gateway-ready", JSONObject().put("ready", true))
        awaitFile("gateway-send")
        val transaction =
            pending.stage(senderRoom, body(fixture, "body"), logicalId(fixture, "logicalId"))
        pending.checkPaused()
        write(
            "gateway-staged",
            protectedFlags(JSONObject())
                .put("transactionId", transaction)
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
    ) {
        val staged = read("gateway-staged", 4096)
        for (name in protectedFlagNames) check(staged.get(name) == true)
        check(staged.getInt("processId") != Process.myPid())
        check(staged.getString("senderKeyFingerprint") == sender.proofKeyFingerprint())
        check(staged.getString("recipientKeyFingerprint") == recipient.proofKeyFingerprint())
        val expected = staged.getString("transactionId")
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
                        val sent = pending.resume(expected)
                        check(incoming.eventId() == sent.eventId)
                        val answered =
                            ProofQueuedMessage(recipient.nativeClient, recipientRoom)
                                .send(body(fixture, "reply"), logicalId(fixture, "replyId"))
                        check(returning.eventId() == answered.eventId)
                        write(
                            "gateway-result",
                            protectedFlags(JSONObject())
                                .put("encryptedBothDirections", true)
                                .put("logicalIdsPreserved", true)
                                .put("queueRestored", true)
                                .put("transactionReused", true)
                                .put("identityAfterRestart", true)
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
