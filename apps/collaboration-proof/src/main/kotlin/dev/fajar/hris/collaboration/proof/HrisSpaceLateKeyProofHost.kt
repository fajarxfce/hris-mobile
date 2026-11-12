package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationIncomingSources
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AdoptCollaborationSpaceRuntime
import dev.fajar.starter.collaboration.domain.usecases.SynchronizeCollaborationSpaceDevice
import dev.fajar.starter.common.result.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.koin.core.Koin
import org.koin.dsl.module

/** Real key arrival while an already committed receipt's reply is held and then lost. */
internal class HrisSpaceLateKeyProofHost(private val koin: Koin, private val roomId: String) {
    private val demands = koin.get<CollaborationSpaceIncomingDemandRepository>()
    private val keys = koin.get<CollaborationSpaceRoomKeyRepository>()
    private val native = koin.get<CollaborationSpaceRuntimeRepository>()
    private val sync = koin.get<CollaborationSpaceSyncRepository>()
    private val firstCommit = CompletableDeferred<CollaborationSpaceIncomingDemandSnapshot>()
    private val releaseReply = CompletableDeferred<Unit>()
    private val attempts = AtomicInteger()
    private val stops = AtomicInteger()
    private val committed = CopyOnWriteArrayList<CollaborationSpaceRoomKeyReceipt>()
    private val acknowledged = CopyOnWriteArrayList<CollaborationSpaceRoomKeyReceipt>()

    init {
        koin.loadModules(
            listOf(
                module {
                    single<CollaborationSpaceIncomingDemandRepository> {
                        object : CollaborationSpaceIncomingDemandRepository by demands {
                            override suspend fun replace(
                                access: CollaborationSpaceLocalAccess,
                                expected: CollaborationSpaceIncomingDemandSnapshot?,
                                demand: CollaborationSpaceIncomingDemand,
                                wake: CollaborationSpaceIncomingWake?,
                            ): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
                                val receipt = demand.roomKeyReceipt
                                if (receipt == null || receipt == expected?.demand?.roomKeyReceipt)
                                    return demands.replace(access, expected, demand, wake)
                                check(attempts.incrementAndGet() <= 4)
                                check(demand.historyRecovery != null && wake != null)
                                val saved = demands.replace(access, expected, demand, wake)
                                val snapshot = (saved as? AppResult.Success)?.value ?: return saved
                                check(committed.none { it.receiptId == receipt.receiptId })
                                committed.add(receipt)
                                check(committed.size <= 2)
                                if (committed.size == 1) {
                                    firstCommit.complete(snapshot)
                                    releaseReply.await()
                                    return AppResult.Failed(
                                        Failure(
                                            FailureKind.Storage,
                                            "fixture.lost_key_receipt_reply",
                                        )
                                    )
                                }
                                return saved
                            }
                        }
                    }
                    single<CollaborationSpaceRoomKeyRepository> {
                        object : CollaborationSpaceRoomKeyRepository by keys {
                            override suspend fun acknowledgeKeys(
                                batch: CollaborationSpaceRoomKeyBatch
                            ): AppResult<Boolean> {
                                val saved =
                                    requireNotNull(
                                        expect(
                                            demands.current(
                                                batch.runtime.owner.localDirectoryAccess()
                                            )
                                        )
                                    )
                                val receipt = requireNotNull(saved.demand.roomKeyReceipt)
                                check(saved.demand.historyRecovery != null)
                                check(
                                    receipt.source == batch.source &&
                                        receipt.receiptId == batch.receiptId
                                )
                                check(receipt.runtimeDeviceId == batch.runtime.deviceId)
                                check(receipt.runtimeOperationId == batch.runtime.operationId)
                                check(receipt in committed && receipt !in acknowledged)
                                if (acknowledged.isEmpty()) {
                                    check(batch.reconcileRequired && batch.roomIds.isEmpty())
                                } else {
                                    check(
                                        !batch.reconcileRequired && batch.roomIds == listOf(roomId)
                                    )
                                }
                                val released = keys.acknowledgeKeys(batch)
                                check(expect(released))
                                acknowledged.add(receipt)
                                check(acknowledged.size <= 2)
                                return released
                            }
                        }
                    }
                }
            )
        )
    }

    suspend fun collect(states: StateFlow<ApplicationCollaborationState>) {
        try {
            koin.get<ApplicationCollaborationIncomingSources>().collect(states)
        } finally {
            stops.incrementAndGet()
        }
    }

    suspend fun receivePreparedKey(seed: HrisSpaceRecoverySeed): JSONObject {
        val runtime =
            expect(
                koin.get<AdoptCollaborationSpaceRuntime>()(
                    seed.owner,
                    seed.registrationId,
                    seed.operationId,
                )
            )
        val source = requireNotNull(sync.incomingSource(runtime))
        val publication = requireNotNull(expect(native.observePublication(runtime).first()))
        val first = withTimeout(5_000) { firstCommit.await() }
        val receipt = requireNotNull(first.demand.roomKeyReceipt)
        check(receipt.source == source && receipt.runtimeDeviceId == runtime.deviceId)
        check(receipt.runtimeOperationId == runtime.operationId)
        check(
            expect(demands.current(runtime.owner.localDirectoryAccess()))?.demand?.roomKeyReceipt ==
                receipt
        )
        check(acknowledged.isEmpty())
        val held = expect(withTimeout(1_000) { keys.observeKeys(runtime, source).first() })
        check(
            held.receiptId == receipt.receiptId && held.reconcileRequired && held.roomIds.isEmpty()
        )

        // The peer prepared a fresh session during staging. Only this sync receives that key.
        // Keep the application's own five-second receipt deadline and finite retry policy intact.
        expect(koin.get<SynchronizeCollaborationSpaceDevice>()(runtime))
        check(acknowledged.isEmpty())
        releaseReply.complete(Unit)
        withTimeout(5_000) { while (acknowledged.size != 2) delay(10) }
        check(committed.size == 2 && committed.toList() == acknowledged.toList())
        check(committed[0].source == source && committed[1].source == source)
        check(committed[0].receiptId != committed[1].receiptId)
        val saved = requireNotNull(expect(demands.current(runtime.owner.localDirectoryAccess())))
        check(saved.demand.roomKeyReceipt == committed.last())
        val recovery = requireNotNull(saved.demand.historyRecovery)
        check(recovery.generation == receipt.receiptId)
        check(recovery.requestedGeneration == committed.last().receiptId && !recovery.complete)
        check(withTimeoutOrNull(200) { keys.observeKeys(runtime, source).first() } == null)
        check(
            requireNotNull(expect(native.observePublication(runtime).first())).instanceId ==
                publication.instanceId
        )
        return JSONObject()
            .put("committedBeforeLostReply", true)
            .put("keyReceivedDuringHeldReply", true)
            .put("durableBeforeBothAcknowledgements", true)
            .put("firstReceiptNotRewritten", true)
            .put("sameRuntimeInstance", true)
            .put("rawReceiptsDrained", true)
            .put("receiptAttempts", attempts.get())
            .put("receiptCommits", committed.size)
            .put("nativeAcknowledgements", acknowledged.size)
            .put("retained", readStored(seed.owner))
    }

    suspend fun readStored(owner: CollaborationSpaceOwner): JSONObject {
        val saved = requireNotNull(expect(demands.current(owner.localDirectoryAccess())))
        val receipt = requireNotNull(saved.demand.roomKeyReceipt)
        val recovery = requireNotNull(saved.demand.historyRecovery)
        return JSONObject()
            .put("receiptDigest", HrisSpaceIncomingSourcesProofHost.digest(receipt.receiptId))
            .put(
                "recoveryGenerationDigest",
                HrisSpaceIncomingSourcesProofHost.digest(recovery.generation),
            )
            .put(
                "requestedGenerationDigest",
                recovery.requestedGeneration?.let { HrisSpaceIncomingSourcesProofHost.digest(it) }
                    ?: JSONObject.NULL,
            )
            .put("recoveryComplete", recovery.complete)
    }

    fun verifyStopped() {
        check(stops.get() == 1)
        check(committed.size == 2 && acknowledged.size == 2)
    }

    suspend fun verifyQuiet(owner: CollaborationSpaceOwner, expected: JSONObject) {
        check(stops.get() == 0)
        check(committed.size == 2 && acknowledged.size == 2)
        check(readStored(owner).toString() == expected.toString())
    }

    fun release() {
        releaseReply.complete(Unit)
    }

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
