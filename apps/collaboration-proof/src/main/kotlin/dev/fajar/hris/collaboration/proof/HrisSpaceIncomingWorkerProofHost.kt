package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.app.work.CollaborationSpaceIncomingWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.koin.core.Koin

/** Executes the registered application task over real admission, encrypted storage and SDK I/O. */
internal class HrisSpaceIncomingWorkerProofHost(
    private val koin: Koin,
    private val scheduled: () -> List<String>,
) {
    private val worker = koin.get<CollaborationSpaceIncomingWorker>()
    private val readWake = koin.get<ReadCollaborationSpaceIncomingWake>()
    private val readDemand = koin.get<ReadCollaborationSpaceIncomingDemand>()
    private val history = koin.get<CollaborationSpaceHistoryTraversalRepository>()
    private val request = koin.get<QueueCollaborationSpaceIncomingRoom>()

    suspend fun consume(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
        roomId: String,
        headId: String,
    ): JSONObject {
        check(koin.getAll<SyncTask>().single { it.key == worker.key } === worker)
        val access = runtime.owner.localDirectoryAccess()
        val key =
            CollaborationHistoryTraversal(
                conversation.id,
                roomId,
                runtime.deviceId,
                CollaborationHistoryPurpose.Incoming,
            )
        check(expect(history.progress(access, key)) == null)
        check(expect(readWake()) == null)
        check(
            expect(
                request(
                    access,
                    key,
                    CollaborationSpaceIncomingDemandChange.Request(
                        conversation.id,
                        UUID.randomUUID().toString(),
                        headId,
                    ),
                )
            )
        )
        check(expect(readWake()) != null)
        check(scheduled().count { it == worker.key } == 1)
        // The manual fixture scheduler invokes only the real registered task; no acquisition
        // usecase is called here. The first page must retain visible content before provider EOF.
        runRegisteredTurn()
        val first = requireNotNull(expect(history.progress(access, key)))
        check(!first.exhausted)
        val visible =
            expect(koin.get<ObserveCollaborationSpaceHistory>()(access, conversation.id).first())
        check(visible.messages.isNotEmpty())
        check(expect(readWake()) != null)
        check(scheduled().count { it == worker.key } == 2)
        var turns = 1
        while (expect(readWake()) != null && turns < 4) {
            runRegisteredTurn()
            turns += 1
        }
        check(expect(readWake()) == null)
        check(
            expect(
                koin
                    .get<CollaborationSpaceRuntimeRepository>()
                    .isSpaceOpen(runtime.owner, runtime.deviceId, runtime.operationId)
            )
        )
        val final = expect(readDemand(access))
        check(
            final.snapshot != null &&
                final.ready.isEmpty() &&
                !final.roomsPending &&
                !final.scanPending
        )
        val references = expect(koin.get<ReadCollaborationSpaceIncomingReferences>()(access, key))
        check(
            references.progress?.exhausted == true &&
                references.events.isEmpty() &&
                references.after == null
        )
        runRegisteredTurn()
        check(
            !expect(
                request(
                    access,
                    key,
                    CollaborationSpaceIncomingDemandChange.Request(
                        conversation.id,
                        UUID.randomUUID().toString(),
                        headId,
                    ),
                )
            )
        )
        check(expect(readWake()) == null && expect(readDemand(access)) == final)
        check(
            expect(koin.get<ReadCollaborationSpaceIncomingReferences>()(access, key)) == references
        )
        check(scheduled().count { it == worker.key } == 2)
        return JSONObject()
            .put("turns", turns)
            .put("firstTurnMessages", visible.messages.size)
            .put("contentBeforeProviderEof", true)
            .put("registeredTask", true)
            .put("emptyRepeatUnchanged", true)
            .put("coveredHintUnchanged", true)
    }

    private suspend fun runRegisteredTurn() {
        when (val result = worker(SyncTrigger.Requested)) {
            SyncResult.Complete -> Unit
            is SyncResult.Retry -> throw HrisProofFailure(requireNotNull(result.failure))
            is SyncResult.Blocked -> throw HrisProofFailure(result.failure)
        }
    }

    private fun <T> expect(value: AppResult<T>): T =
        when (value) {
            is AppResult.Success -> value.value
            is AppResult.Failed -> throw HrisProofFailure(value.failure)
        }
}
