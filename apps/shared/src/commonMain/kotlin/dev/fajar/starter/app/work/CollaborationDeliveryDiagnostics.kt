package dev.fajar.starter.app.work

import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.time.TimeSource
import kotlinx.coroutines.*

internal enum class CollaborationDeliveryStage {
    RecoverDrafts,
    DiscoverWork,
    LoadSetup,
    RenewWorkspace,
    EnsureRuntime,
    AcquireBackgroundRuntime,
    ProcessRooms,
    Reconcile,
    ReadPending,
    PrepareRoom,
    EnterTimeline,
    RecoverOriginal,
    SettleCompletedQueue,
    ReadPrepared,
    Flush,
    StageMessage,
    DeliverMessages,
    Retire,
    AcquireRetirementHistory,
    RetireCommands,
}

/** Room-attempt timings use closed stage names and an ephemeral trace, never message IDs. */
internal suspend fun traceCollaborationDelivery(
    timeSource: TimeSource = TimeSource.Monotonic,
    action: suspend ((CollaborationDeliveryStage) -> Unit) -> AppResult<Unit>,
): AppResult<Unit> =
    traceDeliveryStages(
        CollaborationDeliveryStage.Reconcile,
        timeSource,
        outcome = {
            if (it is AppResult.Success) PerformanceOutcome.Succeeded else PerformanceOutcome.Failed
        },
        failure = { (it as? AppResult.Failed)?.failure },
        action,
    )

/** Includes local recovery and runtime preparation before a room attempt can start. */
internal suspend fun traceCollaborationDeliveryWork(
    initial: CollaborationDeliveryStage,
    timeSource: TimeSource = TimeSource.Monotonic,
    action: suspend ((CollaborationDeliveryStage) -> Unit) -> SyncResult,
): SyncResult =
    traceDeliveryStages(
        initial,
        timeSource,
        outcome = {
            if (it == SyncResult.Complete) PerformanceOutcome.Succeeded
            else PerformanceOutcome.Failed
        },
        failure = {
            when (it) {
                is SyncResult.Retry -> it.failure
                is SyncResult.Blocked -> it.failure
                SyncResult.Complete -> null
            }
        },
        action,
    )

/** The invoking coroutine owns this finite trace; nested room timings have separate spans. */
private suspend fun <T> traceDeliveryStages(
    initial: CollaborationDeliveryStage,
    timeSource: TimeSource,
    outcome: (T) -> PerformanceOutcome,
    failure: (T) -> Failure?,
    action: suspend ((CollaborationDeliveryStage) -> Unit) -> T,
): T {
    val trace = OperationTrace.create(currentCoroutineContext()[OperationTrace])
    return withContext(trace) {
        val invocation = currentCoroutineContext()
        var stage = initial
        var started = timeSource.markNow()
        var stageTrace = OperationTrace.create(trace)
        var finalOutcome = PerformanceOutcome.Failed
        fun recordTiming(result: PerformanceOutcome?) {
            Diagnostics.record(
                Diagnostic(
                    DiagnosticArea.Worker,
                    DiagnosticKind.OperationCompleted,
                    category = "ChatDeliveryStage_${stage.name}",
                    durationMillis = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0),
                    outcome = result,
                    traceId = stageTrace.traceId,
                    spanId = stageTrace.spanId,
                )
            )
        }
        try {
            val result = action { next ->
                invocation.ensureActive()
                // Advancing can enter recovery after a rejected read. Only the final
                // interval carries the attempt result; a transition makes no success claim.
                recordTiming(null)
                stage = next
                started = timeSource.markNow()
                stageTrace = OperationTrace.create(trace)
            }
            currentCoroutineContext().ensureActive()
            finalOutcome = outcome(result)
            failure(result)?.let { recordDelivery(stage, deliveryFailureReason(it)) }
            result
        } catch (timeout: TimeoutCancellationException) {
            finalOutcome = PerformanceOutcome.Cancelled
            recordDelivery(stage, "Timeout")
            throw timeout
        } catch (cancelled: CancellationException) {
            finalOutcome = PerformanceOutcome.Cancelled
            throw cancelled
        } finally {
            recordTiming(finalOutcome)
        }
    }
}

private fun deliveryFailureReason(failure: Failure): String =
    when (failure.code) {
        "client.collaboration_sdk_unauthorized" -> "SdkAuthentication"
        "client.collaboration_queue_unresolved" -> "QueueUnresolved"
        "client.collaboration_queue_mapping_bounds" -> "QueueMappingBounds"
        "client.collaboration_queue_mapping_owner" -> "QueueMappingOwner"
        "client.collaboration_queue_mapping_invalid_record" -> "QueueMappingInvalidRecord"
        "client.collaboration_queue_mapping_cancelled" -> "QueueMappingCancelled"
        "client.collaboration_queue_mapping_staging" -> "QueueMappingStaging"
        "client.collaboration_queue_mapping_device" -> "QueueMappingDevice"
        "client.collaboration_queue_mapping_native_family" -> "QueueMappingNativeFamily"
        "client.collaboration_queue_mapping_room" -> "QueueMappingRoom"
        "client.collaboration_queue_mapping_legacy_receipt" -> "QueueMappingLegacyReceipt"
        "client.collaboration_queue_mapping_missing_receipt" -> "QueueMappingMissingReceipt"
        "client.collaboration_queue_mapping_duplicate_transaction" ->
            "QueueMappingDuplicateTransaction"
        "client.collaboration_delivery_pending" -> "DeliveryPending"
        "client.collaboration_history_pending" -> "HistoryPending"
        "client.collaboration_room_retry_required" -> "RoomPreparation"
        else -> failure.kind.name
    }

private fun recordDelivery(stage: CollaborationDeliveryStage, reason: String) {
    Diagnostics.record(
        Diagnostic(
            DiagnosticArea.Worker,
            DiagnosticKind.OperationFailed,
            "ChatDelivery_${stage.name}_$reason",
        )
    )
}
