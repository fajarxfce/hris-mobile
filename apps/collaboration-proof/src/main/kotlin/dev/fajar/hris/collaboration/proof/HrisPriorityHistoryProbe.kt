package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import java.util.UUID
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Injected push metadata, actual retained queue, HRIS admission and native original acquisition.
 */
internal class HrisPriorityHistoryProbe(
    private val open: () -> HrisWorkerProofContainer,
    private val runtime: CollaborationRuntimeRepository,
    private val owner: CollaborationOwner,
    private val deviceId: String,
    private val roomId: String,
) {
    suspend fun run(historical: List<HrisHistoricalMessage>): JSONObject {
        check(historical.size == 175)
        val target = historical.first()
        var app: HrisWorkerProofContainer? = null
        var primary: Exception? = null
        try {
            val queued = open().also { app = it }
            queued.activate()
            val before = queued.capturedOriginals()
            check(before.none { target.eventId in it.eventIds })
            check(queued.capturedPending().isEmpty())
            check(queued.prioritySnapshot().priority?.hints.isNullOrEmpty())
            val position = queued.priorityPosition(deviceId, roomId)
            queued.queuePriority(fields(target))
            val snapshot = queued.prioritySnapshot()
            check(snapshot.priority?.hints?.single()?.matrixEventId == target.eventId)
            val wake = requireNotNull(queued.priorityWake())
            check(queued.scheduledKeys == listOf(CollaborationRecoveryWake.WORK_KEY))
            queued.close()
            app = null
            repeat(3) { attempt ->
                check(!expect(runtime.isOpen(owner, deviceId)))
                val current = open().also { app = it }
                current.activate()
                check(current.prioritySnapshot() == snapshot)
                check(current.priorityWake() == wake)
                check(current.priorityPosition(deviceId, roomId) == position)
                check(current.capturedPending().isEmpty())
                val result = current.runPriority(snapshot.parent)
                check(!expect(runtime.isOpen(owner, deviceId)))
                check(current.priorityPosition(deviceId, roomId) == position)
                check(current.priorityWake() == wake)
                check(current.capturedPending().isEmpty() && current.scheduledKeys.isEmpty())
                if (result == SyncResult.Complete) {
                    check(current.prioritySnapshot().priority?.hints?.isEmpty() == true)
                    val originals = current.capturedOriginals()
                    check(originals.single { target.eventId in it.eventIds }.text == target.body)
                    check(originals.none { historical.last().eventId in it.eventIds })
                    check(originals.containsAll(before))
                    current.close()
                    app = null
                    val reopened = open().also { app = it }
                    reopened.activate()
                    check(reopened.capturedOriginals() == originals)
                    check(reopened.prioritySnapshot().priority?.hints?.isEmpty() == true)
                    check(reopened.priorityWake() == wake)
                    check(reopened.priorityPosition(deviceId, roomId) == position)
                    check(!expect(runtime.isOpen(owner, deviceId)))
                    return evidence(attempt + 1, originals.size, target.eventId)
                }
                check(current.prioritySnapshot() == snapshot)
                current.close()
                app = null
                when (result) {
                    is SyncResult.Blocked -> throw HrisProofFailure(result.failure)
                    is SyncResult.Retry -> {
                        if (attempt == 2) throw HrisProofFailure(result.failure ?: exhausted())
                        delay(1_000)
                    }
                    SyncResult.Complete -> error("Completion already handled")
                }
            }
            error("Priority proof did not complete")
        } catch (failure: Exception) {
            primary = failure
            throw failure
        } finally {
            closeHrisProofResources(primary, listOf({ app?.close() }))
        }
    }

    private fun fields(target: HrisHistoricalMessage): Map<String, String> {
        val now = System.currentTimeMillis() / 1000
        return mapOf(
            "schemaVersion" to "1",
            "eventCode" to "collaboration.chat_available",
            "eventId" to UUID.randomUUID().toString(),
            "accountId" to owner.accountId,
            "companyId" to owner.companyId,
            "nativeSessionId" to owner.nativeSessionId,
            "deviceId" to deviceId,
            "revision" to "1",
            "issuedAt" to now.toString(),
            "expiresAt" to (now + 300).toString(),
            "matrixRoomId" to roomId,
            "matrixEventId" to target.eventId,
        )
    }

    private fun evidence(passes: Int, originals: Int, event: String) =
        JSONObject()
            .put("priorityWithoutOutbox", true)
            .put("priorityWithoutSelection", true)
            .put("priorityQueueRetainedAfterReopen", true)
            .put("priorityOriginalRetainedAfterReopen", true)
            .put("priorityOldestOfflineEventRecovered", true)
            .put("priorityRuntimeReleased", true)
            .put("priorityTraversalUnchanged", true)
            .put("priorityGlobalWakeRetained", true)
            .put("priorityHintCompleted", true)
            .put("priorityPasses", passes)
            .put("prioritySavedOriginals", originals)
            .put("priorityEventId", event)

    private fun exhausted() =
        Failure(
            FailureKind.Unavailable,
            "Priority proof attempts exhausted",
            code = "client.collaboration_proof_retry_exhausted",
        )

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
