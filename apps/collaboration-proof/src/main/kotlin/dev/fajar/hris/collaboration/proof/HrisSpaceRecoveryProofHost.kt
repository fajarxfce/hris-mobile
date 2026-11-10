package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.app.work.CollaborationSpaceRecoveryWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.Koin
import org.koin.dsl.module

/**
 * Actual app parent/readiness/worker; fixture replaces interactive activation and OS scheduling.
 */
internal class HrisSpaceRecoveryProofHost(
    private val koin: Koin,
    private val seed: HrisSpaceRecoverySeed,
    private val scheduled: () -> List<String>,
) {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.Default)
    private val firstSignal = CompletableDeferred<CollaborationSpaceReadinessSignal>()
    private val signals = AtomicInteger()
    private val native = koin.get<CollaborationSpaceRuntimeRepository>()
    private val wake = koin.get<ReadCollaborationSpaceRecoveryWake>()
    private val progress = koin.get<ReadCollaborationSpaceRecoveryProgress>()
    private val process: ApplicationCollaborationLifetime
    private val worker: CollaborationSpaceRecoveryWorker

    init {
        koin.loadModules(
            listOf(
                module {
                    single<ApplicationCollaborationLifetime> {
                        val readiness = inject<QueueCollaborationSpaceReadinessSignal>()
                        val recovery = inject<RequestCollaborationSpaceRecovery>()
                        ApplicationCollaborationLifetime(
                            get(),
                            get(),
                            get(),
                            get(),
                            observePush = { emptyFlow() },
                            activate = { emptyFlow() },
                            observeSpaceReadiness = { readiness.value.observe() },
                            requestSpaceRecovery = { recovery.value() },
                        )
                    }
                }
            )
        )
        process = koin.get()
        worker = koin.get()
        check(koin.getAll<SyncTask>().single { it.key == worker.key } === worker)
    }

    suspend fun start() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            native.spaceReadinessSignals().collect {
                check(signals.incrementAndGet() <= 8) { "Unbounded fictional readiness stream" }
                firstSignal.complete(it)
            }
        }
        process.start()
        withTimeout(5_000) {
            process.state.first {
                val current = (it as? ApplicationCollaborationState.Observed)?.publication
                (current as? AppResult.Success)?.value?.retention?.parent == seed.owner.parent
            }
            while (expect(wake()) == null) delay(10)
        }
    }

    /**
     * First process only: establish real sync, then distinguish a new control wake from startup.
     */
    suspend fun awaitReadyForControl() {
        val original =
            requireNotNull(
                expect(koin.get<LoadCollaborationSpaceRecoveryDevice>()(seed.owner.parent))
            )
        check(
            original.owner == seed.owner &&
                original.registrationId == seed.registrationId &&
                original.operationId == seed.operationId
        )
        val ready =
            expect(
                koin.get<AdoptCollaborationSpaceRuntime>()(
                    seed.owner,
                    seed.registrationId,
                    seed.operationId,
                )
            )
        expect(koin.get<SynchronizeCollaborationSpaceDevice>()(ready))
        expect(koin.get<ActivateCollaborationSpacePusher>()(ready))
        val startup = requireNotNull(expect(wake()))
        expect(koin.get<CompleteCollaborationSpaceRecoveryWake>()(startup))
        check(expect(wake()) == null && signals.get() == 0)
    }

    suspend fun awaitControl(): Long {
        val signal = withTimeout(60_000) { firstSignal.await() }
        check(signal.source.owner == seed.owner && signal.source.operationId == seed.operationId)
        val receipts = koin.get<CollaborationSpaceReadinessReceiptRepository>()
        val key =
            CollaborationSpaceReadinessScope(
                seed.owner.parent,
                seed.owner.spaceId,
                signal.source.deviceId,
            )
        withTimeout(5_000) {
            while (true) {
                val saved = expect(receipts.current(key))
                if (
                    saved != null &&
                        saved.progress.requestedRevision >= signal.control.publicationRevision
                )
                    break
                delay(10)
            }
        }
        val before = requireNotNull(expect(wake()))
        val receipt = requireNotNull(expect(receipts.current(key)))
        val count = scheduled().count { it == worker.key }
        // Explicit replay of the captured real signal, distinct from the initial native transport.
        check(!expect(koin.get<QueueCollaborationSpaceReadinessSignal>()(signal)))
        check(expect(wake()) == before && expect(receipts.current(key)) == receipt)
        check(scheduled().count { it == worker.key } == count)
        return receipt.progress.requestedRevision
    }

    suspend fun checkpoint() = expect(progress(seed.owner))

    suspend fun pendingWake() = expect(wake())

    suspend fun recover() {
        // Manual fixture OS scheduler: each callback is the actual registered task. New startup
        // wake can outlive an older captured sweep; at most three turns for this two-index fixture.
        repeat(3) {
            check(worker(SyncTrigger.Requested) == SyncResult.Complete)
            if (expect(wake()) == null) {
                check(expect(progress(seed.owner)).progress?.sweep == null)
                check(worker(SyncTrigger.Requested) == SyncResult.Complete)
                return
            }
        }
        error("Fictional recovery did not exhaust its bounded inventory")
    }

    suspend fun verifyIdleRecovery() {
        check(expect(wake()) == null)
        expect(koin.get<RequestCollaborationSpaceRecovery>()())
        check(expect(wake()) != null)
        recover()
        check(expect(wake()) == null && expect(progress(seed.owner)).progress?.sweep == null)
    }

    suspend fun close() =
        withContext(NonCancellable) {
            process.closeAndJoin()
            lifetime.cancelAndJoin()
            expect(native.closeSpace(seed.owner))
        }

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
