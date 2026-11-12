package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.os.Process
import dev.fajar.starter.app.work.CollaborationSpaceIncomingWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.koin.core.Koin

/** Resume the persisted late-key obligation in a new process using the registered worker. */
internal class HrisLateKeyRecoveryProbe(
    private val context: Context,
    private val files: HrisWorkerProofFiles,
    private val sender: ProtectedHrisProofOwner,
    private val http: HttpClient,
) {
    suspend fun run(offline: Boolean) {
        check(files.fixture.getBoolean("recoverLateKeys"))
        val group = files.read("original-group", 16_384)
        val ready = files.read("original-ready", 16_384)
        val previous =
            files.read(if (offline) "original-late-recovered" else "original-local", 16_384)
        check(previous.getInt("processId") != Process.myPid())
        val app =
            HrisWorkerProofContainer(
                context,
                sender.currentOwner,
                files.conversationId,
                files.fixture.getJSONObject("sender").getString("hrisAccessToken"),
                sender.repository,
                http,
            )
        var parent: HrisSpaceRecoveryProofHost? = null
        var primary: Exception? = null
        val result =
            try {
                app.activate()
                val journal = app.receipts()
                val seed = app.spaceRecoverySeed(sender, ready, group)
                val runtime =
                    CollaborationSpaceRuntimeReady(
                        seed.owner,
                        group.getString("deviceId"),
                        seed.operationId,
                    )
                val native = sender.repository as CollaborationSpaceRuntimeRepository
                check(
                    !expect(
                        native.isSpaceOpen(runtime.owner, runtime.deviceId, runtime.operationId)
                    )
                )
                val recovery = app.lateKeyRecovery()
                if (!offline) {
                    recovery.verifyRetained(runtime.owner, previous.getJSONObject("retained"))
                    // Registration/bootstrap already committed in the prior process; never reseed
                    // them.
                    parent = app.spaceRecovery(seed)
                    parent.start()
                    app.prepareOriginalRoom(seed.owner, group.getString("conversationId"))
                    recovery.consume(runtime.owner)
                }
                val snapshot =
                    recovery.read(runtime.owner, group.getString("conversationId"), files, sender)
                check(app.receipts() == journal)
                if (offline) {
                    check(snapshot.toString() == previous.getJSONObject("recovered").toString())
                    check(
                        !expect(
                            native.isSpaceOpen(runtime.owner, runtime.deviceId, runtime.operationId)
                        )
                    )
                }
                JSONObject()
                    .put("processId", Process.myPid())
                    .put("recovered", snapshot)
                    .put("runtimeNeverOpened", offline)
            } catch (failure: Exception) {
                primary = failure
                throw failure
            } finally {
                closeHrisProofResources(primary, listOf({ parent?.close() }, { app.close() }))
            }
        files.write(
            if (offline) "original-late-recovered-local" else "original-late-recovered",
            result.put("resourcesReleased", true),
        )
    }

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}

/** Assertions over the actual worker and canonical encrypted Company projection. */
internal class HrisLateKeyRecoveryProofHost(private val koin: Koin) {
    private val demand = koin.get<ReadCollaborationSpaceIncomingDemand>()
    private val wake = koin.get<ReadCollaborationSpaceIncomingWake>()
    private val worker = koin.get<CollaborationSpaceIncomingWorker>()
    private val history = HrisSpaceIncomingProofHost(koin)
    private val facts = koin.get<CollaborationHistoryRepository>()
    private val edits = koin.get<CollaborationMessageEditRepository>()
    private val traversals = koin.get<CollaborationHistoryTraversalRepository>()
    private val spaceHistory = koin.get<CollaborationSpaceHistoryRepository>()
    private val spaceMessages = koin.get<CollaborationSpaceMessageRepository>()
    private val spaceReceipts = koin.get<CollaborationSpaceReceiptRepository>()

    suspend fun verifyRetained(owner: CollaborationSpaceOwner, previous: JSONObject) {
        val current = requireNotNull(expect(demand(owner.localDirectoryAccess())).snapshot).demand
        val receipt = requireNotNull(current.roomKeyReceipt)
        val recovery = requireNotNull(current.historyRecovery)
        check(
            HrisSpaceIncomingSourcesProofHost.digest(receipt.receiptId) ==
                previous.getString("receiptDigest")
        )
        check(
            HrisSpaceIncomingSourcesProofHost.digest(recovery.generation) ==
                previous.getString("recoveryGenerationDigest")
        )
        check(
            HrisSpaceIncomingSourcesProofHost.digest(
                requireNotNull(recovery.requestedGeneration)
            ) == previous.getString("requestedGenerationDigest")
        )
        check(!recovery.complete && expect(wake()) != null)
    }

    suspend fun consume(owner: CollaborationSpaceOwner) {
        check(koin.getAll<SyncTask>().single { it.key == worker.key } === worker)
        val access = owner.localDirectoryAccess()
        val before = requireNotNull(expect(demand(access)).snapshot).demand
        val receipt = requireNotNull(before.roomKeyReceipt)
        // Two retained generations, each with the existing four-turn fixture budget.
        // No retry delay or production worker batch limit is changed.
        var turns = 0
        while (expect(wake()) != null && turns < 8) {
            val previous = expect(demand(access)).snapshot
            when (val outcome = worker(SyncTrigger.Requested)) {
                SyncResult.Complete -> Unit
                is SyncResult.Retry -> throw HrisProofFailure(requireNotNull(outcome.failure))
                is SyncResult.Blocked -> throw HrisProofFailure(outcome.failure)
            }
            turns++
            check(expect(wake()) == null || expect(demand(access)).snapshot != previous)
        }
        check(turns in 1..8 && expect(wake()) == null)
        val final = expect(demand(access))
        val saved = requireNotNull(final.snapshot).demand
        check(saved.roomKeyReceipt == receipt)
        check(requireNotNull(saved.historyRecovery).complete)
        check(saved.historyRecovery?.requestedGeneration == null)
        check(
            !final.roomsPending &&
                !final.scanPending &&
                final.historyReady == null &&
                final.historyDeferred == null
        )
        check(worker(SyncTrigger.Requested) == SyncResult.Complete)
        check(expect(demand(access)) == final && expect(wake()) == null)
    }

    suspend fun read(
        owner: CollaborationSpaceOwner,
        conversationId: String,
        files: HrisWorkerProofFiles,
        sender: ProtectedHrisProofOwner,
    ): JSONObject {
        val location = history.originalLocation(owner, conversationId)
        val page = history.readOriginalPage(location)
        check(page.messages.size == 2 && page.next == null)
        val staged = files.read("original-staged", 16_384)
        val ids = staged.getJSONObject("events")
        val lateId = files.read("original-late-key-staged", 16_384).getString("eventId")
        val access = location.companyAccess
        val key = location.traversal
        val references = expect(traversals.observeReferences(access, key, limit = 50).first())
        val snapshot =
            HrisSpaceIncomingProofHost.Snapshot(
                page,
                requireNotNull(
                    expect(
                        facts.getEventFact(
                            access,
                            key.conversationId,
                            key.roomId,
                            ids.getString("original"),
                        )
                    )
                ),
                requireNotNull(
                    expect(
                        edits.getReplacementFact(
                            access,
                            key.conversationId,
                            key.roomId,
                            ids.getString("latestEdit"),
                        )
                    )
                ),
                requireNotNull(
                    expect(
                        facts.getEventFact(
                            access,
                            key.conversationId,
                            key.roomId,
                            ids.getString("peer"),
                        )
                    )
                ),
                references,
                companyOwned = true,
                physicalProvider =
                    CollaborationHistoryProviderIdentity(access.companyId, key.deviceId),
            )
        check(
            expect(
                facts.getEventFact(
                    access,
                    key.conversationId,
                    key.roomId,
                    ids.getString("latestEdit"),
                )
            ) == null
        )
        for (id in
            listOf(
                ids.getString("original"),
                ids.getString("latestEdit"),
                ids.getString("peer"),
                lateId,
            )) {
            check(
                expect(
                    spaceHistory.getEventFact(location.spaceAccess, conversationId, key.roomId, id)
                ) == null
            )
        }
        check(expect(spaceMessages.observe(location.spaceAccess, conversationId).first()).isEmpty())
        check(expect(spaceReceipts.read(location.spaceAccess, conversationId)).isEmpty())
        val saved = requireNotNull(expect(demand(location.spaceAccess)).snapshot).demand
        check(
            requireNotNull(saved.historyRecovery).complete &&
                saved.historyRecovery?.requestedGeneration == null
        )
        check(expect(wake()) == null)
        return HrisEventReadEvidence(files)
            .lateRecovered(
                snapshot,
                staged,
                sender,
                lateId,
                expect(facts.getEventFact(access, key.conversationId, key.roomId, lateId)),
            )
            .put("recoveryComplete", true)
            .put("registeredWorkerDrained", true)
            .put(
                "receiptDigest",
                HrisSpaceIncomingSourcesProofHost.digest(
                    requireNotNull(saved.roomKeyReceipt).receiptId
                ),
            )
    }

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
