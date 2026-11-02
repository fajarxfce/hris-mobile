package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Actual incoming worker/native SDK, reopened after every bounded turn with no selected UI. */
internal class HrisIncomingHistoryProbe(
    private val open: () -> HrisWorkerProofContainer,
    private val runtime: CollaborationRuntimeRepository,
    private val owner: CollaborationOwner,
    private val deviceId: String,
    private val roomId: String,
    private val progress: (JSONObject) -> Unit,
) {
    suspend fun run(historical: List<HrisHistoricalMessage>): JSONObject {
        check(historical.size == 175 && historical.map { it.eventId }.distinct().size == 175)
        var app: HrisWorkerProofContainer? = null
        var primary: Exception? = null
        var saved: List<CollaborationHistoryMessage>? = null
        var references: CollaborationHistoryReferences? = null
        try {
            repeat(40) { attempt ->
                check(!expect(runtime.isOpen(owner, deviceId)))
                val current = open().also { app = it }
                current.activate()
                check(current.capturedPending().isEmpty())
                check(
                    current
                        .capturedReferences(deviceId, roomId, CollaborationHistoryPurpose.Browse)
                        .progress == null
                )
                if (saved == null) {
                    check(
                        current.capturedOriginals().none {
                            historical.last().eventId in it.eventIds
                        }
                    )
                } else {
                    check(current.capturedOriginals() == saved)
                    check(current.capturedReferences(deviceId, roomId) == references)
                }
                val result = current.runIncoming(deviceId)
                saved = current.capturedOriginals()
                references = current.capturedReferences(deviceId, roomId)
                progress(checkpoint(attempt + 1, requireNotNull(saved).size, references, result))
                check(
                    current
                        .capturedReferences(deviceId, roomId, CollaborationHistoryPurpose.Browse)
                        .progress == null
                )
                check(current.capturedPending().isEmpty() && current.scheduledKeys.isEmpty())
                check(!expect(runtime.isOpen(owner, deviceId)))
                current.close()
                app = null
                when (result) {
                    SyncResult.Complete -> {
                        check(attempt >= 1)
                        val originals = requireNotNull(saved)
                        check(originals.size == 176)
                        for (event in historical) check(
                            originals.single { event.eventId in it.eventIds }.text == event.body
                        )
                        check(references?.progress?.exhausted == true)
                        val reopened = open().also { app = it }
                        reopened.activate()
                        check(reopened.capturedOriginals() == originals)
                        check(reopened.capturedReferences(deviceId, roomId) == references)
                        check(reopened.capturedPending().isEmpty())
                        check(!expect(runtime.isOpen(owner, deviceId)))
                        return evidence(attempt + 1, originals.size)
                    }
                    is SyncResult.Blocked -> throw HrisProofFailure(result.failure)
                    is SyncResult.Retry -> {
                        if (attempt == 39) throw HrisProofFailure(result.failure ?: exhausted())
                        delay(if (result.failure == null) 100 else 1_000)
                    }
                }
            }
            error("Incoming proof did not complete")
        } catch (failure: Exception) {
            primary = failure
            throw failure
        } finally {
            closeHrisProofResources(primary, listOf({ app?.close() }))
        }
    }

    private fun checkpoint(
        pass: Int,
        originals: Int,
        references: CollaborationHistoryReferences?,
        result: SyncResult,
    ): JSONObject {
        val failure =
            when (result) {
                SyncResult.Complete -> null
                is SyncResult.Retry -> result.failure
                is SyncResult.Blocked -> result.failure
            }
        return JSONObject()
            .put("pass", pass)
            .put("originals", originals)
            .put("references", references?.progress?.references ?: 0)
            .put("hasNext", references?.progress?.next != null)
            .put("hasPendingSeek", references?.progress?.incoming?.pendingAfter != null)
            .put("retryRequired", references?.progress?.incoming?.pendingRetryRequired == true)
            .put(
                "result",
                when (result) {
                    SyncResult.Complete -> "Complete"
                    is SyncResult.Retry -> "Retry"
                    is SyncResult.Blocked -> "Blocked"
                },
            )
            .put("failureKind", failure?.kind?.name ?: "None")
            .put(
                "failureCode",
                failure?.code?.takeIf { it.matches(Regex("[a-z][a-z0-9_.]{0,95}")) } ?: "none",
            )
    }

    private fun evidence(passes: Int, originals: Int) =
        JSONObject()
            .put("incomingWithoutOutbox", true)
            .put("incomingWithoutSelection", true)
            .put("incomingFreshNativeAdmission", true)
            .put("incomingOriginalAfterOfflineSeed", true)
            .put("incomingHistoryRetainedAfterReopen", true)
            .put("incomingTraversalRetainedAfterReopen", true)
            .put("incomingBrowseUnchanged", true)
            .put("incomingAllOriginalsRecovered", true)
            .put("incomingRuntimeReleased", true)
            .put("incomingPasses", passes)
            .put("incomingContainerReopens", passes)
            .put("incomingSavedOriginals", originals)

    private fun exhausted() =
        Failure(
            FailureKind.Unavailable,
            "Incoming proof attempts exhausted",
            code = "client.collaboration_proof_retry_exhausted",
        )

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
