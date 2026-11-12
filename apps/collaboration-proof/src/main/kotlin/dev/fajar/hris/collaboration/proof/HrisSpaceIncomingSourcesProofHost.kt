package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationIncomingSources
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AdoptCollaborationSpaceRuntime
import dev.fajar.starter.common.result.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.koin.core.Koin
import org.koin.dsl.module

/** Real SDK, application owner and encrypted storage, with one controlled lost write reply. */
internal class HrisSpaceIncomingSourcesProofHost(private val koin: Koin) {
    private val demands = koin.get<CollaborationSpaceIncomingDemandRepository>()
    private val keys = koin.get<CollaborationSpaceRoomKeyRepository>()
    private val native = koin.get<CollaborationSpaceRuntimeRepository>()
    private val sync = koin.get<CollaborationSpaceSyncRepository>()
    private val writeEntered = CompletableDeferred<CollaborationSpaceRoomKeyReceipt>()
    private val releaseWrite = CompletableDeferred<Unit>()
    private val writes = AtomicInteger()
    private val starts = AtomicInteger()
    private val acknowledgements = AtomicInteger()
    private val publications = AtomicInteger()
    private val stops = AtomicInteger()
    private var saved: CollaborationSpaceIncomingDemandSnapshot? = null
    private var receipt: CollaborationSpaceRoomKeyReceipt? = null

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
                                val incoming = demand.roomKeyReceipt
                                if (
                                    incoming == null || incoming == expected?.demand?.roomKeyReceipt
                                )
                                    return demands.replace(access, expected, demand, wake)
                                check(writes.incrementAndGet() == 1) {
                                    "Unexpected native key receipt rewrite"
                                }
                                check(demand.historyRecovery != null && wake != null)
                                writeEntered.complete(incoming)
                                releaseWrite.await()
                                saved =
                                    requireNotNull(
                                        expect(demands.replace(access, expected, demand, wake))
                                    )
                                // The transaction really committed. Only its reply is replaced by a
                                // failure.
                                return AppResult.Failed(
                                    Failure(FailureKind.Storage, "fixture.lost_write_reply")
                                )
                            }
                        }
                    }
                    single<CollaborationSpaceRoomKeyRepository> {
                        object : CollaborationSpaceRoomKeyRepository by keys {
                            override suspend fun startKeyObservation(
                                runtime: CollaborationSpaceRuntimeReady,
                                source: CollaborationSpaceIncomingSource,
                            ): AppResult<Unit> {
                                starts.incrementAndGet()
                                return keys.startKeyObservation(runtime, source)
                            }

                            override suspend fun acknowledgeKeys(
                                batch: CollaborationSpaceRoomKeyBatch
                            ): AppResult<Boolean> {
                                val durable =
                                    requireNotNull(
                                        expect(
                                            demands.current(
                                                batch.runtime.owner.localDirectoryAccess()
                                            )
                                        )
                                    )
                                val witness = requireNotNull(durable.demand.roomKeyReceipt)
                                check(
                                    witness.source == batch.source &&
                                        witness.receiptId == batch.receiptId
                                )
                                check(
                                    witness.runtimeDeviceId == batch.runtime.deviceId &&
                                        witness.runtimeOperationId == batch.runtime.operationId
                                )
                                val result = keys.acknowledgeKeys(batch)
                                check(expect(result))
                                acknowledgements.incrementAndGet()
                                return result
                            }
                        }
                    }
                    single<CollaborationSpaceRuntimeRepository> {
                        object : CollaborationSpaceRuntimeRepository by native {
                            override fun observePublication(
                                runtime: CollaborationSpaceRuntimeReady
                            ) =
                                native.observePublication(runtime).onStart {
                                    publications.incrementAndGet()
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

    suspend fun verifyInitial(seed: HrisSpaceRecoverySeed): JSONObject {
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
        val received = withTimeout(5_000) { writeEntered.await() }
        receipt = received
        check(
            received.source == source &&
                received.runtimeDeviceId == runtime.deviceId &&
                received.runtimeOperationId == runtime.operationId
        )
        check(
            expect(demands.current(runtime.owner.localDirectoryAccess()))?.demand?.roomKeyReceipt ==
                null
        )
        val pending = expect(withTimeout(1_000) { keys.observeKeys(runtime, source).first() })
        check(
            pending.receiptId == received.receiptId &&
                pending.reconcileRequired &&
                pending.roomIds.isEmpty()
        )
        check(acknowledgements.get() == 0)
        releaseWrite.complete(Unit)
        withTimeout(5_000) { while (acknowledgements.get() != 1) delay(10) }
        check(saved == expect(demands.current(runtime.owner.localDirectoryAccess())))
        check(withTimeoutOrNull(200) { keys.observeKeys(runtime, source).first() } == null)
        val initialPass = publications.get()
        repeat(2) { index ->
            koin.get<ApplicationCollaborationIncomingSources>().foregroundEntered()
            withTimeout(5_000) {
                while (
                    publications.get() < initialPass + index + 1 || starts.get() < index + 2
                ) delay(10)
            }
            check(
                requireNotNull(expect(native.observePublication(runtime).first())).instanceId ==
                    publication.instanceId
            )
        }
        // A bounded observation window catches listener recreation or registration hot loops.
        delay(1_000)
        check(writes.get() == 1 && starts.get() == 3 && acknowledgements.get() == 1)
        check(saved == expect(demands.current(runtime.owner.localDirectoryAccess())))
        return JSONObject()
            .put("nativeInitialReconciliation", true)
            .put("durableReceiptBeforeAck", true)
            .put("lostWriteReplyConfirmed", true)
            .put("rawReceiptDrained", true)
            .put("foregroundResumes", 2)
            .put("receiptWrites", writes.get())
            .put("nativeAcknowledgements", acknowledgements.get())
            .put("sameRuntimeInstance", true)
            .put("receiptDigest", digest(received.receiptId))
    }

    fun release() {
        releaseWrite.complete(Unit)
    }

    suspend fun verifyStopped(owner: CollaborationSpaceOwner): JSONObject {
        check(stops.get() == 1)
        check(writes.get() == 1 && acknowledgements.get() == 1)
        check(
            expect(demands.current(owner.localDirectoryAccess()))?.demand?.roomKeyReceipt == receipt
        )
        return JSONObject().put("processCollectorJoined", true).put("receiptSurvivesStop", true)
    }

    companion object {
        fun digest(receiptId: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(receiptId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
