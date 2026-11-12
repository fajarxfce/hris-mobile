package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationIncomingSources
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.AdoptCollaborationSpaceRuntime
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.koin.core.Koin
import org.koin.dsl.module

/**
 * Actual receipt/storage/runtime; only an already committed return and seeded login are controlled.
 */
internal class HrisAccountReplacementProofHost(
    private val koin: Koin,
    private val originalSession: Session,
    private val replaceSession: suspend (Session) -> Unit,
) {
    private val demands = koin.get<CollaborationSpaceIncomingDemandRepository>()
    private val keys = koin.get<CollaborationSpaceRoomKeyRepository>()
    private val native = koin.get<CollaborationSpaceRuntimeRepository>()
    private val committed = CompletableDeferred<CollaborationSpaceRoomKeyReceipt>()
    private val releaseReply = CompletableDeferred<Unit>()
    private val replyReturned = CompletableDeferred<Unit>()
    private val keyConsumerStopped = CompletableDeferred<Unit>()
    private val states = CompletableDeferred<StateFlow<ApplicationCollaborationState>>()
    private val writes = AtomicInteger()
    private val acknowledgements = AtomicInteger()

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
                                val result = demands.replace(access, expected, demand, wake)
                                val incoming = demand.roomKeyReceipt
                                if (
                                    incoming == null ||
                                        incoming == expected?.demand?.roomKeyReceipt ||
                                        (result as? AppResult.Success)?.value == null
                                )
                                    return result
                                check(writes.incrementAndGet() == 1) {
                                    "Unexpected replacement-proof receipt rewrite"
                                }
                                check(demand.historyRecovery != null && wake != null)
                                check(committed.complete(incoming))
                                try {
                                    withContext(NonCancellable) { releaseReply.await() }
                                } finally {
                                    replyReturned.complete(Unit)
                                }
                                return result
                            }
                        }
                    }
                    single<CollaborationSpaceRoomKeyRepository> {
                        object : CollaborationSpaceRoomKeyRepository by keys {
                            override fun observeKeys(
                                runtime: CollaborationSpaceRuntimeReady,
                                source: CollaborationSpaceIncomingSource,
                            ) =
                                keys.observeKeys(runtime, source).onCompletion {
                                    keyConsumerStopped.complete(Unit)
                                }

                            override suspend fun acknowledgeKeys(
                                batch: CollaborationSpaceRoomKeyBatch
                            ): AppResult<Boolean> {
                                acknowledgements.incrementAndGet()
                                return keys.acknowledgeKeys(batch)
                            }
                        }
                    }
                }
            )
        )
    }

    suspend fun collect(current: StateFlow<ApplicationCollaborationState>) {
        check(states.complete(current))
        koin.get<ApplicationCollaborationIncomingSources>().collect(current)
    }

    suspend fun adopt(seed: HrisSpaceRecoverySeed): CollaborationSpaceRuntimeReady =
        expect(
                koin.get<AdoptCollaborationSpaceRuntime>()(
                    seed.owner,
                    seed.registrationId,
                    seed.operationId,
                )
            )
            .also { awaitCommitted(it) }

    suspend fun awaitCommitted(
        runtime: CollaborationSpaceRuntimeReady
    ): CollaborationSpaceRoomKeyReceipt {
        val receipt = withTimeout(5_000) { committed.await() }
        check(
            receipt.runtimeDeviceId == runtime.deviceId &&
                receipt.runtimeOperationId == runtime.operationId
        )
        check(
            expect(demands.current(runtime.owner.localDirectoryAccess()))?.demand?.roomKeyReceipt ==
                receipt
        )
        check(acknowledgements.get() == 0)
        return receipt
    }

    suspend fun replaceAndRestore(
        runtime: CollaborationSpaceRuntimeReady,
        replacement: CollaborationOwner,
        replacementAccessToken: String,
    ): JSONObject {
        val receipt = awaitCommitted(runtime)
        val old = runtime.owner.parent
        check(originalSession.id == old.sessionId && originalSession.user.id == old.accountId)
        check(replacement.audience == old.audience && replacement.accountId != old.accountId)
        check(
            replacement.sessionId != old.sessionId &&
                replacement.nativeSessionId != old.nativeSessionId
        )
        check(
            replacementAccessToken.isNotBlank() &&
                replacementAccessToken.none { it.isWhitespace() || it.isISOControl() }
        )
        val next =
            originalSession.copy(
                id = replacement.sessionId,
                user = originalSession.user.copy(id = replacement.accountId),
                tokens = originalSession.tokens.copy(accessToken = replacementAccessToken),
                binding = originalSession.binding.copy(serverId = replacement.nativeSessionId),
            )
        val parent =
            CollaborationNativeParent(next.id, old.audience, next.user.id, next.binding.serverId)
        replaceSession(next)
        awaitParent(parent)
        check(!expect(native.isSpaceOpen(runtime.owner, runtime.deviceId, runtime.operationId)))
        check(expect(native.observePublication(runtime).first()) == null)
        check(acknowledgements.get() == 0 && !replyReturned.isCompleted)
        val stale = demands.current(runtime.owner.localDirectoryAccess())
        check(stale is AppResult.Failed || (stale as AppResult.Success).value == null)
        val other = CollaborationSpaceOwner(parent, runtime.owner.spaceId)
        check(expect(demands.current(other.localDirectoryAccess())) == null)
        releaseReply.complete(Unit)
        withTimeout(5_000) {
            replyReturned.await()
            keyConsumerStopped.await()
        }
        check(acknowledgements.get() == 0)
        replaceSession(originalSession)
        awaitParent(old)
        check(!expect(native.isSpaceOpen(runtime.owner, runtime.deviceId, runtime.operationId)))
        check(
            expect(demands.current(runtime.owner.localDirectoryAccess()))?.demand?.roomKeyReceipt ==
                receipt
        )
        check(writes.get() == 1 && acknowledgements.get() == 0)
        return JSONObject()
            .put("receiptCommittedBeforeReplacement", true)
            .put("replacementParentPublished", true)
            .put("previousRuntimeClosed", true)
            .put("replacementCannotReadOriginalReceipt", true)
            .put("lateReplyNotAcknowledged", true)
            .put("originalReceiptRestored", true)
            .put("receiptWrites", writes.get())
            .put("nativeAcknowledgements", acknowledgements.get())
            .put("receiptDigest", HrisSpaceIncomingSourcesProofHost.digest(receipt.receiptId))
    }

    private suspend fun awaitParent(parent: CollaborationNativeParent) {
        val current = withTimeout(5_000) { states.await() }
        withTimeout(5_000) {
            current.first {
                val publication =
                    ((it as? ApplicationCollaborationState.Observed)?.publication
                            as? AppResult.Success)
                        ?.value
                publication?.retention?.parent == parent && publication.cleanup is AppResult.Success
            }
        }
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
