package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRuntimeRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Actual incoming worker, real native admission/SDK and encrypted history with no selected UI. */
internal class HrisIncomingHistoryProbe(
    private val open: () -> HrisWorkerProofContainer,
    private val runtime: CollaborationRuntimeRepository,
    private val owner: CollaborationOwner,
    private val deviceId: String,
) {
    suspend fun run(latest: HrisHistoricalMessage, oldest: HrisHistoricalMessage): JSONObject {
        var app: HrisWorkerProofContainer? = null
        var primary: Exception? = null
        try {
            check(!expect(runtime.isOpen(owner, deviceId)))
            val first = open().also { app = it }
            first.activate()
            check(first.capturedPending().isEmpty())
            check(first.capturedHistory().messages.none { latest.eventId in it.eventIds })
            val passes = recover(first)
            val saved = first.capturedHistory()
            check(saved.messages.single { latest.eventId in it.eventIds }.text == latest.body)
            check(saved.messages.none { oldest.eventId in it.eventIds })
            check(first.capturedPending().isEmpty())
            check(first.scheduledKeys.isEmpty())
            // Final borrowed runtime release must drain the SDK even before container shutdown.
            check(!expect(runtime.isOpen(owner, deviceId)))
            first.close()
            app = null
            val reopened = open().also { app = it }
            reopened.activate()
            check(reopened.capturedHistory() == saved)
            check(reopened.capturedPending().isEmpty())
            check(!expect(runtime.isOpen(owner, deviceId)))
            return JSONObject()
                .put("incomingWithoutOutbox", true)
                .put("incomingWithoutSelection", true)
                .put("incomingFreshNativeAdmission", true)
                .put("incomingOriginalAfterOfflineSeed", true)
                .put("incomingHistoryRetainedAfterReopen", true)
                .put("incomingRuntimeReleased", true)
                .put("incomingPasses", passes)
                .put("incomingSavedOriginals", saved.messages.size)
        } catch (failure: Exception) {
            primary = failure
            throw failure
        } finally {
            closeHrisProofResources(primary, listOf({ app?.close() }))
        }
    }

    private suspend fun recover(app: HrisWorkerProofContainer): Int {
        repeat(4) { attempt ->
            when (val result = app.runIncoming(deviceId)) {
                SyncResult.Complete -> return attempt + 1
                is SyncResult.Blocked -> throw HrisProofFailure(result.failure)
                is SyncResult.Retry -> {
                    if (attempt == 3)
                        throw HrisProofFailure(
                            result.failure
                                ?: Failure(
                                    FailureKind.Unavailable,
                                    "Incoming proof attempts exhausted",
                                    code = "client.collaboration_proof_retry_exhausted",
                                )
                        )
                    delay(if (result.failure == null) 100 else 1_000)
                }
            }
        }
        error("Incoming proof did not complete")
    }

    private fun <T> expect(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
