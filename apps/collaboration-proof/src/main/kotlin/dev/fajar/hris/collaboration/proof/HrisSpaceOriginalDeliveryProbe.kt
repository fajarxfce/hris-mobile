package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.os.Process
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRuntimeLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Instant
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** New fictional originals only; real app storage/transport and existing-only SDK adoption. */
internal class HrisSpaceOriginalDeliveryProbe(private val context: Context, directory: File) {
    private val files = HrisWorkerProofFiles(directory)
    private val secondId =
        files.fixture.getString("secondLogicalId").also {
            require(UUID.fromString(it).toString() == it && it != files.logicalId)
        }
    private val ids = listOf(files.logicalId, secondId)
    private val http =
        createHttpClient(
            createPlatformHttpEngine(),
            HttpClientSettings(files.home.toString(), allowCleartext = true),
        )
    private val sender =
        ProtectedHrisProofOwner(context, files.fixture.getJSONObject("sender"), files.home, http)
    private val receiver =
        ProtectedHrisProofOwner(context, files.fixture.getJSONObject("recipient"), files.home, http)

    suspend fun run(scenario: String) =
        withContext(Dispatchers.IO) {
            try {
                when (scenario) {
                    "space-original-stage" -> stage()
                    "space-original-interrupt" -> deliver(interrupt = true)
                    "space-original-recover" -> deliver(interrupt = false)
                    "space-original-offline" -> offline()
                    else -> error("Unknown owned original-delivery phase")
                }
            } finally {
                http.cancelAndClose()
            }
        }

    private fun application(before: (suspend (CollaborationMessageRecord) -> Unit)? = null) =
        HrisWorkerProofContainer(
            context,
            sender.currentOwner,
            files.conversationId,
            files.fixture.getJSONObject("sender").getString("hrisAccessToken"),
            sender.repository,
            http,
            beforeOriginalProjection = before,
        )

    private suspend fun stage() {
        var source: AndroidCollaborationRuntimeLease? = null
        var peer: AndroidCollaborationRuntimeLease? = null
        var app: HrisWorkerProofContainer? = null
        var primary: Exception? = null
        try {
            peer = receiver.installInitial()
            expect(peer.startSync())
            expect(receiver.enterTimeline(files.roomId).close())
            source = sender.installInitial()
            expect(source.startSync())
            expect(sender.enterTimeline(files.roomId).close())
            val senderIdentity = sender.originalIdentity()
            val receiverIdentity = receiver.originalIdentity()
            files.write(
                "original-ready",
                checkpoint()
                    .put("sender", identity(senderIdentity))
                    .put("recipient", identity(receiverIdentity)),
            )
            waitFor("original-stage-allowed")
            peer.closeProofRuntime()
            peer = null
            val host = application().also { app = it }
            host.activate()
            host.cacheConversation()
            val records =
                ids.mapIndexed { index, id ->
                    host.queue(id, files.body + "-" + index)
                    host.stageOriginal(sender.registeredDeviceId, id)
                }
            val queue =
                expect(
                    sender.repository.acquireMessageQueue(
                        sender.currentOwner,
                        sender.registeredDeviceId,
                        files.roomId,
                    )
                )
            val originals =
                try {
                    val values = expect(queue.originals())
                    check(values.size == 2 && values.map { it.logicalId }.toSet() == ids.toSet())
                    check(expect(queue.snapshot()) == values.map { it.transactionId }.toSet())
                    for (record in records) {
                        val staged = record.state as CollaborationMessageState.Staged
                        val original = values.single { it.logicalId == record.intent.id }
                        check(
                            original.transactionId == staged.transactionId &&
                                original.origin == staged.origin
                        )
                    }
                    JSONArray(
                        records.map { record ->
                            val staged = record.state as CollaborationMessageState.Staged
                            JSONObject()
                                .put("logicalId", record.intent.id)
                                .put("transactionId", staged.transactionId)
                                .put("originHash", requireNotNull(staged.origin).contentHash)
                                .put("sender", requireNotNull(staged.origin).sender)
                                .put("textHash", hash(record.intent.text))
                                .put("nativeSessionId", staged.device.nativeSessionId)
                                .put("deviceId", staged.device.deviceId)
                                .put("bootstrapOperationId", staged.device.bootstrapOperationId)
                        }
                    )
                } finally {
                    expect(queue.close())
                }
            for (id in ids) check(
                host.receipts().none { it.intent.id == id } && host.completion(id) == null
            )
            host.close()
            app = null
            source.closeProofRuntime()
            source = null
            files.write(
                "original-staged",
                checkpoint()
                    .put("sender", identity(senderIdentity))
                    .put("recipient", identity(receiverIdentity))
                    .put("originals", originals)
                    .put("bothSdkClosed", true)
                    .put("recipientClosedBeforeDelivery", true),
            )
        } catch (failure: Exception) {
            primary = failure
            throw failure
        } finally {
            closeHrisProofResources(
                primary,
                listOf(
                    { app?.close() },
                    { source?.closeProofRuntime() },
                    { peer?.closeProofRuntime() },
                ),
            )
        }
    }

    private suspend fun deliver(interrupt: Boolean) {
        val previous = files.read("original-staged", 16_384)
        val group = files.read("original-group", 16_384)
        lateinit var host: HrisWorkerProofContainer
        var lease: CollaborationSpaceRuntimeLease? = null
        var primary: Exception? = null
        host =
            application(
                if (interrupt)
                    { record ->
                        val journal = host.receipts().single { it.intent.id == record.intent.id }
                        check(journal.intent == record.intent && journal.eventIds.isNotEmpty())
                        check(
                            host.message(record.intent.id)?.state
                                is CollaborationMessageState.Staged
                        )
                        files.write(
                            "original-ack",
                            checkpoint()
                                .put("logicalId", record.intent.id)
                                .put("journalDurable", true)
                                .put("projectionNotWritten", true)
                                .put("events", JSONArray(journal.eventIds)),
                        )
                        awaitCancellation()
                    }
                else null
            )
        try {
            host.activate()
            verifyOriginals(host, previous)
            val restored = restoreOriginal(host, previous, group)
            lease = restored
            val ready =
                CollaborationSpaceRuntimeReady(
                    restored.owner,
                    restored.deviceId,
                    restored.installedOperationId,
                )
            val conversation =
                host.prepareOriginalRoom(restored.owner, canonical(group, "conversationId"))
            val result = expect(host.deliverOriginals(ready, conversation))
            check(!interrupt) { "The owned process was not interrupted before projection" }
            check(
                result.confirmed.containsAll(ids) &&
                    result.unresolved.intersect(ids.toSet()).isEmpty()
            )
            verifyOriginals(host, previous)
            val journal = host.receipts()
            val sent =
                ids.map { id ->
                    val record = requireNotNull(host.message(id))
                    val state = record.state as CollaborationMessageState.Sent
                    val receipt = journal.single { it.intent.id == id }
                    check(receipt.intent == record.intent && state.eventId in receipt.eventIds)
                    JSONObject()
                        .put("logicalId", id)
                        .put("transactionId", state.transactionId)
                        .put("events", JSONArray(receipt.eventIds))
                }
            files.write(
                "original-recovered",
                checkpoint()
                    .put("confirmed", JSONArray(sent))
                    .put("originalsRetained", true)
                    .put("recipientStayedOffline", true),
            )
        } catch (failure: Exception) {
            primary = failure
            throw failure
        } finally {
            closeHrisProofResources(
                primary,
                listOf({ lease?.close()?.let { expect(it) } }, { host.close() }),
            )
        }
    }

    /** One bounded fixture bootstrap replay and existing-only acquisition; no replacement keys. */
    private suspend fun restoreOriginal(
        host: HrisWorkerProofContainer,
        previous: JSONObject,
        group: JSONObject,
    ): CollaborationSpaceRuntimeLease {
        val parent =
            sender.currentOwner.let {
                CollaborationNativeParent(
                    it.sessionId,
                    it.audience,
                    it.accountId,
                    it.nativeSessionId,
                )
            }
        val owner = CollaborationSpaceOwner(parent, canonical(group, "spaceId"))
        val selected = host.originalIdentity(owner)
        val originalIdentity = readIdentity(previous.getJSONObject("sender"))
        check(selected.matrixUserId == originalIdentity.matrixUserId)
        val request =
            CollaborationSpaceBootstrap(
                canonical(group, "bootstrapOperationId"),
                CollaborationSpaceDeviceBinding(
                    parent.audience,
                    parent.accountId,
                    owner.spaceId,
                    parent.nativeSessionId,
                ),
                CollaborationNativePlatform.ANDROID,
                1,
                canonical(group, "deviceId"),
                1,
                null,
                selected,
                originalIdentity.matrixDeviceId,
            )
        val gateway = host.originalBootstrap(owner, request)
        val setup =
            CollaborationSpaceSetupRecord(
                CollaborationSpaceSetupCommand(
                    canonical(group, "registrationId"),
                    CollaborationNativePlatform.ANDROID,
                    1,
                    Instant.parse(group.getString("createdAt")),
                    request,
                ),
                2,
                1,
                CollaborationSpaceSetupOutcome.Bootstrapped(gateway),
            )
        val target =
            CollaborationExistingRuntimeHandover(
                CollaborationSpaceLegacySdk(
                    sender.currentOwner.setupBinding(),
                    sender.registeredDeviceId,
                    originalIdentity,
                ),
                CollaborationRuntimeHandoverIntent(
                    canonical(group, "handoverOperationId"),
                    owner.spaceId,
                    request.deviceId,
                    0,
                    originalIdentity.fingerprint,
                ),
            )
        return sender.installOriginal(target, setup)
    }

    private suspend fun offline() {
        val host = application()
        var primary: Exception? = null
        try {
            host.activate()
            verifyOriginals(host, files.read("original-staged", 16_384))
            for (id in ids) check(
                requireNotNull(host.message(id)).state is CollaborationMessageState.Sent
            )
            files.write(
                "original-offline",
                checkpoint().put("localBeforeTransport", true).put("confirmedCount", ids.size),
            )
        } catch (failure: Exception) {
            primary = failure
            throw failure
        } finally {
            closeHrisProofResources(primary, listOf({ host.close() }))
        }
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

    private suspend fun waitFor(name: String) =
        withTimeout(60_000) {
            while (!files.exists(name)) delay(50)
            check(files.read(name, 64).toString() == "{\"ready\":true}")
        }

    private fun checkpoint() = JSONObject().put("processId", Process.myPid())

    private fun identity(value: CollaborationLocalDeviceIdentity) =
        JSONObject()
            .put("matrixUserId", value.matrixUserId)
            .put("matrixDeviceId", value.matrixDeviceId)
            .put("fingerprint", value.fingerprint)

    private fun readIdentity(value: JSONObject) =
        CollaborationLocalDeviceIdentity(
            value.getString("matrixUserId"),
            value.getString("matrixDeviceId"),
            value.getString("fingerprint"),
        )

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

    private fun <T> expect(value: AppResult<T>): T =
        when (value) {
            is AppResult.Success -> value.value
            is AppResult.Failed -> throw HrisProofFailure(value.failure)
        }
}
