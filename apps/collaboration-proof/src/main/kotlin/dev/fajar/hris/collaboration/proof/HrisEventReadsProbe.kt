package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.os.Process
import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import java.io.File
import java.util.UUID
import kotlin.time.Instant
import kotlinx.coroutines.*
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.SyncSettingsV2

/** Exact event capability over actual SDK6 stores, before and after original runtime adoption. */
internal class HrisEventReadsProbe(private val context: Context, directory: File) {
    private val files = HrisWorkerProofFiles(directory)
    private val evidence = HrisEventReadEvidence(files)
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
                withTimeout(180_000) {
                    when (scenario) {
                        "event-reads-stage" -> stage()
                        "event-reads-space" -> adopted()
                        "event-reads-local" -> reopened()
                        else -> error("Unknown event-read scenario")
                    }
                }
            } finally {
                http.cancelAndClose()
            }
        }

    private suspend fun stage() {
        var source: AndroidCollaborationRuntimeLease? = null
        var peer: AndroidCollaborationRuntimeLease? = null
        var primary: Exception? = null
        try {
            peer = receiver.installInitial()
            sync(peer)
            peer.nativeClient.joinRoomById(files.roomId).close()
            sync(peer)
            source = sender.installInitial()
            sync(source)
            source.nativeClient.joinRoomById(files.roomId).close()
            sync(source)
            val ready =
                checkpoint()
                    .put("sender", identity(sender.originalIdentity()))
                    .put("recipient", identity(receiver.originalIdentity()))
            files.write("original-ready", ready)
            check(waitFor("original-stage-allowed").toString() == "{\"ready\":true}")
            val staged = capturePhysical(source, peer)
            peer.closeProofRuntime()
            peer = null
            source.closeProofRuntime()
            source = null
            files.write("original-staged", staged.put("bothSdkClosed", true))
        } catch (failure: Exception) {
            primary = failure
            throw failure
        } finally {
            closeHrisProofResources(
                primary,
                listOf({ source?.closeProofRuntime() }, { peer?.closeProofRuntime() }),
            )
        }
    }

    private suspend fun capturePhysical(
        source: AndroidCollaborationRuntimeLease,
        peer: AndroidCollaborationRuntimeLease,
    ): JSONObject {
        val original = send(source, evidence.originalContent())
        val missing = physical(receiver, original)
        evidence.physical(missing, receiver)
        check(
            (missing.event.content as CollaborationAcquiredEventContent.Unresolved).reason ==
                CollaborationEventDecryptionFailure.UnableToDecrypt
        )
        sync(peer)
        evidence.original(physical(receiver, original), receiver, original)
        val peerOriginal = send(peer, evidence.peerContent())
        sync(source)
        val firstEdit = send(source, evidence.editContent(original, latest = false))
        val originalHash = evidence.original(physical(sender, original), sender, original)
        val firstHash =
            evidence.edit(physical(sender, firstEdit), sender, firstEdit, original, latest = false)
        check(
            expect(
                    sender.repository.findHistoryHead(
                        sender.currentOwner,
                        sender.registeredDeviceId,
                        files.roomId,
                    )
                )
                .eventId == firstEdit
        )
        val window =
            evidence.window(
                sender.repository.readOriginalHistory(
                    sender.currentOwner,
                    sender.registeredDeviceId,
                    files.roomId,
                    firstEdit,
                ),
                sender,
                original,
                firstEdit,
                latest = false,
            )
        files.write("original-redaction-request", checkpoint().put("targetEventId", peerOriginal))
        val redacted = waitFor("original-redaction-completed")
        check(redacted.getString("targetEventId") == peerOriginal)
        val redaction = redacted.getString("eventId")
        sync(source)
        evidence.redaction(physical(sender, redaction), sender, redaction, peerOriginal)
        evidence.redacted(physical(sender, peerOriginal), sender, peerOriginal, redaction)
        val latest = send(source, evidence.editContent(original, latest = true))
        val latestHash =
            evidence.edit(physical(sender, latest), sender, latest, original, latest = true)
        check(
            expect(
                    sender.repository.findHistoryHead(
                        sender.currentOwner,
                        sender.registeredDeviceId,
                        files.roomId,
                    )
                )
                .eventId == latest
        )
        return checkpoint()
            .put(
                "events",
                JSONObject()
                    .put("original", original)
                    .put("peer", peerOriginal)
                    .put("firstEdit", firstEdit)
                    .put("latestEdit", latest)
                    .put("redaction", redaction),
            )
            .put("originalHash", originalHash)
            .put("firstEditHash", firstHash)
            .put("latestEditHash", latestHash)
            .put("companyWindow", window)
            .put("missingKeyObserved", true)
            .put("decryptedAfterSync", true)
            .put("redactedTargetSenderPreserved", true)
    }

    private suspend fun adopted() {
        val staged = files.read("original-staged", 16_384)
        val ready = files.read("original-ready", 16_384)
        val group = files.read("original-group", 16_384)
        val app =
            HrisWorkerProofContainer(
                context,
                sender.currentOwner,
                files.conversationId,
                files.fixture.getJSONObject("sender").getString("hrisAccessToken"),
                sender.repository,
                http,
            )
        var lease: CollaborationSpaceRuntimeLease? = null
        var primary: Exception? = null
        val result =
            try {
                app.activate()
                val installed = installOriginal(app, ready, group).also { lease = it }
                val runtime =
                    CollaborationSpaceRuntimeReady(
                        installed.owner,
                        installed.deviceId,
                        installed.installedOperationId,
                    )
                captureAdopted(runtime, staged)
                    .put("incoming", retainIncoming(app, runtime, group, staged))
            } catch (failure: Exception) {
                primary = failure
                throw failure
            } finally {
                closeHrisProofResources(
                    primary,
                    listOf({ lease?.close()?.let { expect(it) } }, { app.close() }),
                )
            }
        files.write("original-events", result.put("runtimeReleased", true))
    }

    private suspend fun captureAdopted(
        runtime: CollaborationSpaceRuntimeReady,
        staged: JSONObject,
    ): JSONObject {
        val repository = sender.repository as CollaborationSpaceEventRepository
        val events = staged.getJSONObject("events")
        val original = events.getString("original")
        val latest = events.getString("latestEdit")
        val peer = events.getString("peer")
        val redaction = events.getString("redaction")
        val head = expect(repository.findHead(runtime, files.roomId))
        check(head.runtime == runtime && head.head.owner == sender.currentOwner)
        check(head.head.deviceId == sender.registeredDeviceId && head.head.eventId == latest)
        suspend fun read(id: String): CollaborationEventSnapshot {
            val value = expect(repository.read(runtime, files.roomId, id))
            check(value.runtime == runtime)
            evidence.physical(value.physical, sender)
            return value.physical
        }
        val originalHash = evidence.original(read(original), sender, original)
        val editHash = evidence.edit(read(latest), sender, latest, original, latest = true)
        check(
            originalHash == staged.getString("originalHash") &&
                editHash == staged.getString("latestEditHash")
        )
        evidence.redacted(read(peer), sender, peer, redaction)
        evidence.redaction(read(redaction), sender, redaction, peer)
        check(
            (sender.repository as CollaborationEventRepository).read(
                sender.currentOwner,
                sender.registeredDeviceId,
                files.roomId,
                original,
            ) is AppResult.Failed
        )
        check(
            repository.read(
                runtime.copy(operationId = UUID.randomUUID().toString()),
                files.roomId,
                original,
            ) is AppResult.Failed
        )
        check(
            repository.findHead(runtime.copy(deviceId = UUID.randomUUID().toString()), files.roomId)
                is AppResult.Failed
        )
        val history =
            expect(
                (sender.repository as CollaborationSpaceOriginalHistoryRepository).read(
                    runtime,
                    files.roomId,
                )
            )
        check(history.runtime == runtime && history.head.eventId == latest)
        val window =
            evidence.window(
                AppResult.Success(requireNotNull(history.window)),
                sender,
                original,
                latest,
                latest = true,
            )
        return checkpoint()
            .put("headEventId", latest)
            .put("originalHash", originalHash)
            .put("latestEditHash", editHash)
            .put("redactedEventId", peer)
            .put("redactionEventId", redaction)
            .put("spaceWindow", window)
            .put("logicalRuntimePreserved", true)
            .put("physicalOwnerPreserved", true)
            .put("oldCompanyReadRejected", true)
            .put("foreignRuntimeReadRejected", true)
            .put("redactedTargetSenderPreserved", true)
    }

    private suspend fun retainIncoming(
        app: HrisWorkerProofContainer,
        runtime: CollaborationSpaceRuntimeReady,
        group: JSONObject,
        staged: JSONObject,
    ): JSONObject {
        val conversation = app.prepareOriginalRoom(runtime.owner, group.getString("conversationId"))
        val host = app.incomingHistory()
        val ids = staged.getJSONObject("events")
        val selected = listOf(ids.getString("latestEdit"), ids.getString("redaction"))
        val journal = app.receipts()
        check(journal.isEmpty())
        val providerPages =
            host.stageProviderPages(
                runtime,
                conversation,
                files.roomId,
                sender.currentOwner,
                sender.registeredDeviceId,
            )
        val first = host.acquire(runtime, conversation, selected)
        check(
            first.runtime == runtime &&
                first.roomId == files.roomId &&
                first.conversationId == conversation.id
        )
        check(first.items.map { it.sourceEventId } == selected)
        check(first.items.all { it.outcome is CollaborationIncomingHistoryOutcome.Retained })
        host.resolveRetainedSources(runtime, conversation.id, files.roomId, selected)
        val access = runtime.owner.localDirectoryAccess()
        val original =
            host.read(
                access,
                conversation.id,
                files.roomId,
                runtime.deviceId,
                ids.getString("original"),
                ids.getString("latestEdit"),
                ids.getString("peer"),
            )
        val retained = evidence.retained(original, staged, sender)
        val replay = host.acquire(runtime, conversation, selected)
        check(replay.items.map { it.sourceEventId } == selected)
        check(replay.items.all { it.outcome is CollaborationIncomingHistoryOutcome.Retained })
        val again =
            host.read(
                access,
                conversation.id,
                files.roomId,
                runtime.deviceId,
                ids.getString("original"),
                ids.getString("latestEdit"),
                ids.getString("peer"),
            )
        check(original == again)
        check(app.receipts() == journal)
        val result =
            JSONObject()
                .put("retained", retained)
                .put("providerPages", providerPages)
                .put("replayUnchanged", true)
                .put("originalJournalUnchanged", true)
        if (files.fixture.optBoolean("completePendingIncoming", false)) {
            val completed = host.completePending(runtime, conversation, files.roomId)
            val after =
                host.read(
                    access,
                    conversation.id,
                    files.roomId,
                    runtime.deviceId,
                    ids.getString("original"),
                    ids.getString("latestEdit"),
                    ids.getString("peer"),
                )
            check(app.receipts() == journal)
            result.put(
                "completed",
                JSONObject()
                    .put("resolved", completed.resolved)
                    .put("unresolved", completed.unresolved)
                    .put("more", completed.more)
                    .put("retryRequired", completed.retryRequired)
                    .put("emptyRepeatUnchanged", true)
                    .put(
                        "retained",
                        evidence.retained(after, staged, sender, pendingCompleted = true),
                    ),
            )
        }
        return result
    }

    private suspend fun reopened() {
        val staged = files.read("original-staged", 16_384)
        val adopted = files.read("original-events", 16_384)
        val group = files.read("original-group", 16_384)
        check(adopted.getInt("processId") != Process.myPid())
        val parent =
            sender.currentOwner.let {
                CollaborationNativeParent(
                    it.sessionId,
                    it.audience,
                    it.accountId,
                    it.nativeSessionId,
                )
            }
        val owner = CollaborationSpaceOwner(parent, group.getString("spaceId"))
        val app =
            HrisWorkerProofContainer(
                context,
                sender.currentOwner,
                files.conversationId,
                files.fixture.getJSONObject("sender").getString("hrisAccessToken"),
                sender.repository,
                http,
            )
        var primary: Exception? = null
        val result =
            try {
                app.activate()
                val runtime = sender.repository as CollaborationSpaceRuntimeRepository
                check(
                    !expect(
                        runtime.isSpaceOpen(
                            owner,
                            group.getString("deviceId"),
                            group.getString("registrationId"),
                        )
                    )
                )
                val ids = staged.getJSONObject("events")
                val snapshot =
                    app.incomingHistory()
                        .read(
                            owner.localDirectoryAccess(),
                            group.getString("conversationId"),
                            files.roomId,
                            group.getString("deviceId"),
                            ids.getString("original"),
                            ids.getString("latestEdit"),
                            ids.getString("peer"),
                        )
                val incoming = adopted.getJSONObject("incoming")
                val completed = files.fixture.optBoolean("completePendingIncoming", false)
                val stored =
                    evidence.retained(snapshot, staged, sender, pendingCompleted = completed)
                val expected = if (completed) incoming.getJSONObject("completed") else incoming
                check(stored.toString() == expected.getJSONObject("retained").toString())
                check(
                    !expect(
                        runtime.isSpaceOpen(
                            owner,
                            group.getString("deviceId"),
                            group.getString("registrationId"),
                        )
                    )
                )
                checkpoint()
                    .put("incoming", stored)
                    .put("sameRevision", true)
                    .put("runtimeNeverOpened", true)
            } catch (failure: Exception) {
                primary = failure
                throw failure
            } finally {
                closeHrisProofResources(primary, listOf({ app.close() }))
            }
        files.write("original-local", result.put("databaseReleased", true))
    }

    private suspend fun installOriginal(
        app: HrisWorkerProofContainer,
        ready: JSONObject,
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
        val owner = CollaborationSpaceOwner(parent, group.getString("spaceId"))
        val selected = app.originalIdentity(owner)
        val previous = ready.getJSONObject("sender")
        val identity =
            CollaborationLocalDeviceIdentity(
                previous.getString("matrixUserId"),
                previous.getString("matrixDeviceId"),
                previous.getString("fingerprint"),
            )
        check(selected.matrixUserId == identity.matrixUserId)
        val request =
            CollaborationSpaceBootstrap(
                group.getString("bootstrapOperationId"),
                CollaborationSpaceDeviceBinding(
                    parent.audience,
                    parent.accountId,
                    owner.spaceId,
                    parent.nativeSessionId,
                ),
                CollaborationNativePlatform.ANDROID,
                1,
                group.getString("deviceId"),
                1,
                null,
                selected,
                identity.matrixDeviceId,
            )
        val gateway = app.originalBootstrap(owner, request)
        val setup =
            CollaborationSpaceSetupRecord(
                CollaborationSpaceSetupCommand(
                    group.getString("registrationId"),
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
                    identity,
                ),
                CollaborationRuntimeHandoverIntent(
                    group.getString("handoverOperationId"),
                    owner.spaceId,
                    request.deviceId,
                    0,
                    identity.fingerprint,
                ),
            )
        return sender.installOriginal(target, setup)
    }

    private suspend fun physical(owner: ProtectedHrisProofOwner, id: String) =
        expect(
            (owner.repository as CollaborationEventRepository).read(
                owner.currentOwner,
                owner.registeredDeviceId,
                files.roomId,
                id,
            )
        )

    private suspend fun sync(lease: AndroidCollaborationRuntimeLease) {
        withTimeout(30_000) {
            lease.nativeClient.syncOnceV2(SyncSettingsV2(timeoutMs = 1uL, fullState = false))
        }
    }

    private suspend fun send(lease: AndroidCollaborationRuntimeLease, content: String): String =
        withTimeout(45_000) {
            requireNotNull(lease.nativeClient.getRoom(files.roomId)).use {
                it.sendRaw("m.room.message", content, null)
            }
        }

    private suspend fun waitFor(name: String): JSONObject =
        withTimeout(60_000) {
            while (!files.exists(name)) delay(50)
            files.read(name, 16_384)
        }

    private fun identity(value: CollaborationLocalDeviceIdentity) =
        JSONObject()
            .put("matrixUserId", value.matrixUserId)
            .put("matrixDeviceId", value.matrixDeviceId)
            .put("fingerprint", value.fingerprint)

    private fun checkpoint() = JSONObject().put("processId", Process.myPid())

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
