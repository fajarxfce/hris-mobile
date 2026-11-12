package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationIncomingStartup
import dev.fajar.starter.app.work.CollaborationSpaceIncomingWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.koin.core.Koin

/** Actual process sources and registered task; canonical Company content stays in its owner. */
internal class HrisSpaceIncomingWorkerProofHost(
    private val koin: Koin,
    private val scheduled: () -> List<String>,
    private val awaitStartup: suspend () -> Unit,
) {
    private val worker = koin.get<CollaborationSpaceIncomingWorker>()
    private val readWake = koin.get<ReadCollaborationSpaceIncomingWake>()
    private val readDemand = koin.get<ReadCollaborationSpaceIncomingDemand>()
    private val history = koin.get<CollaborationHistoryTraversalRepository>()
    private val request = koin.get<QueueCollaborationSpaceIncomingRoom>()
    private val retained = HrisSpaceIncomingProofHost(koin)

    suspend fun consume(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
        roomId: String,
        headId: String,
    ): JSONObject = coroutineScope {
        check(koin.getAll<SyncTask>().single { it.key == worker.key } === worker)
        val foreground = koin.get<ApplicationCollaborationIncomingStartup>().start(this)
        try {
            val device =
                requireNotNull(
                    expect(koin.get<LoadCollaborationSpaceRecoveryDevice>()(runtime.owner.parent))
                )
            check(device.owner == runtime.owner && device.operationId == runtime.operationId)
            val adopted =
                expect(
                    koin.get<AdoptCollaborationSpaceRuntime>()(
                        runtime.owner,
                        device.registrationId,
                        runtime.operationId,
                    )
                )
            check(adopted == runtime)
            expect(koin.get<SynchronizeCollaborationSpaceDevice>()(runtime))
            val source =
                requireNotNull(koin.get<CollaborationSpaceSyncRepository>().incomingSource(runtime))
            withTimeout(5_000) {
                while (
                    expect(readDemand(runtime.owner.localDirectoryAccess()))
                        .snapshot
                        ?.demand
                        ?.sources
                        ?.any { it.source == source } != true
                ) delay(10)
            }
            awaitStartup()
            check(expect(readWake()) != null)
            check(scheduled().any { it == worker.key })
            consumeScheduled(runtime, conversation, roomId, headId)
                .put("automaticStartup", true)
                .put("actualSourceRetained", true)
        } finally {
            foreground.cancelAndJoin()
        }
    }

    private suspend fun consumeScheduled(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
        roomId: String,
        headId: String,
    ): JSONObject {
        val location = retained.originalLocation(runtime.owner, conversation.id)
        val access = location.companyAccess
        val key = location.traversal
        check(key.roomId == roomId && key.deviceId != runtime.deviceId)
        check(expect(history.progress(access, key)) == null)
        var turns = 0
        var firstTurnMessages: Int? = null
        // Preserve the previous finite bound. A preparatory canonical-routing turn has no page;
        // the first non-exhausted provider checkpoint must already expose real Company content.
        while (expect(readWake()) != null && turns < 4) {
            runRegisteredTurn()
            turns += 1
            val progress = expect(history.progress(access, key))
            if (progress != null && !progress.exhausted && firstTurnMessages == null) {
                check(progress.provider == null)
                val page = retained.readOriginalPage(location)
                check(page.messages.size == 1)
                firstTurnMessages = page.messages.size
            }
        }
        check(firstTurnMessages == 1)
        check(expect(readWake()) == null)
        check(
            expect(
                koin
                    .get<CollaborationSpaceRuntimeRepository>()
                    .isSpaceOpen(runtime.owner, runtime.deviceId, runtime.operationId)
            )
        )
        val local = runtime.owner.localDirectoryAccess()
        val final = expect(readDemand(local))
        check(
            final.snapshot != null &&
                final.ready.isEmpty() &&
                !final.roomsPending &&
                !final.scanPending
        )
        val references = expect(history.observeReferences(access, key, limit = 50).first())
        check(
            references.progress?.exhausted == true &&
                references.progress?.provider == null &&
                references.events.isEmpty() &&
                references.after == null
        )
        val scheduledBeforeRepeat = scheduled().count { it == worker.key }
        runRegisteredTurn()
        val published =
            CollaborationHistoryTraversal(
                conversation.id,
                roomId,
                runtime.deviceId,
                CollaborationHistoryPurpose.Incoming,
            )
        check(
            !expect(
                request(
                    local,
                    published,
                    CollaborationSpaceIncomingDemandChange.Request(
                        conversation.id,
                        UUID.randomUUID().toString(),
                        headId,
                    ),
                )
            )
        )
        check(expect(readWake()) == null && expect(readDemand(local)) == final)
        check(expect(history.observeReferences(access, key, limit = 50).first()) == references)
        check(scheduled().count { it == worker.key } == scheduledBeforeRepeat)
        return JSONObject()
            .put("turns", turns)
            .put("firstTurnMessages", firstTurnMessages)
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
