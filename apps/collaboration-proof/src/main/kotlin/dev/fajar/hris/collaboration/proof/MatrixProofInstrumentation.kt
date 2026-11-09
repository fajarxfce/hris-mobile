package dev.fajar.hris.collaboration.proof

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.fajar.starter.observability.ConsoleDiagnosticSink
import dev.fajar.starter.observability.Diagnostics
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** An emulator-only, debug-only fixture; gateway mode accepts only fictional HRIS owners. */
class MatrixProofInstrumentation : Instrumentation() {
    private var scenario = "provider"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        scenario = arguments?.getString("scenario") ?: "provider"
        start()
    }

    private fun scenarioTimeoutMillis(): Long =
        when {
            scenario in setOf("hris-product-peer", "hris-readiness-peer") -> 300_000
            scenario == "hris-auth-rejection" -> 180_000
            scenario == "hris-worker-resume" -> 420_000
            scenario == "hris-mixed-room" -> 300_000
            scenario.startsWith("hris-worker-") || scenario.startsWith("company-recovery") ->
                210_000
            else -> 120_000
        }

    override fun onStart() {
        val directory = File(targetContext.filesDir, "protocol-proof")
        val output = JSONObject()
        val storageDiagnostics = HrisProofStorageDiagnostics()
        try {
            check(
                android.os.Build.FINGERPRINT.contains("generic") ||
                    android.os.Build.MODEL.contains("sdk")
            )
            Diagnostics.install(storageDiagnostics)
            runBlocking { withTimeout(scenarioTimeoutMillis()) { runScenario(directory) } }
            output.put("complete", true)
        } catch (failure: Exception) {
            output.put("complete", false)
            output.put(
                "failureCategory",
                (failure as? HrisProofFailure)?.category
                    ?: (failure as? HrisForegroundTimeout)?.category
                    ?: failure.javaClass.simpleName,
            )
            output.put(
                "suppressedFailureCategories",
                JSONArray(failure.suppressed.take(4).map { it.javaClass.simpleName }),
            )
            output.put(
                "failureFrames",
                JSONArray(
                    failure.stackTrace
                        .filter {
                            it.className.startsWith("dev.fajar.") ||
                                it.className.startsWith("org.matrix.")
                        }
                        .take(12)
                        .map { "${it.className}.${it.methodName}:${it.lineNumber}" }
                ),
            )
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
            if (!output.optBoolean("complete"))
                output.put(
                    "recentStorageFailures",
                    JSONArray(
                        storageDiagnostics.snapshot().map {
                            JSONObject()
                                .put("categories", JSONArray(it.categories))
                                .put("frames", JSONArray(it.frames))
                        }
                    ),
                )
            directory.mkdirs()
            File(directory, "instrumentation.json").writeText(output.toString())
            finish(
                if (output.optBoolean("complete")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("proof", output.toString()) },
            )
        }
    }

    private suspend fun runScenario(directory: File) {
        when (scenario) {
            "workspace-read-cost" -> WorkspaceReadCostProbe(targetContext, directory).run()
            "foreground-scheduling" -> ForegroundSchedulingProbe(targetContext, directory).run()
            "sync-trigger" -> SyncTriggerProbe(targetContext, directory).run()
            "hris-product-peer" -> HrisProductNotificationPeer(targetContext, directory).run()
            "hris-readiness-peer" -> HrisReadinessPeer(targetContext, directory).run()
            "notification-input-verify" -> {
                val owner =
                    (targetContext.applicationContext as HrisScheduledProofApplication).inputProof
                try {
                    owner.verifyRecovery()
                } finally {
                    owner.close()
                }
            }
            "native-notification" ->
                NativeNotificationProbe(targetContext, directory).run(denied = false)
            "notification-runtime" -> NotificationRuntimeProbe(targetContext, directory).run()
            "native-notification-denied" ->
                NativeNotificationProbe(targetContext, directory).run(denied = true)
            "company-recovery" -> CompanyRecoveryProbe(targetContext, directory).run()
            "company-recovery-stage" -> CompanyRecoveryProbe(targetContext, directory).stage()
            "company-recovery-resume" ->
                CompanyRecoveryProbe(targetContext, directory)
                    .run(CompanyRecoveryProbe.Scenario.Resume)
            "company-recovery-backlog" ->
                CompanyRecoveryProbe(targetContext, directory)
                    .run(CompanyRecoveryProbe.Scenario.Backlog)
            "work-wake" -> CollapsedSchedulingProbe(targetContext, directory).run()
            "work-wake-stage" -> CollapsedSchedulingProbe(targetContext, directory).stage()
            "work-wake-resume" ->
                CollapsedSchedulingProbe(targetContext, directory).run(resuming = true)
            "space-queue-adoption-stage" ->
                LocalSpaceRuntimeAdoptionProbe(
                        targetContext,
                        directory,
                        OriginalQueueAdoptionProbe(directory),
                    )
                    .stage()
            "space-queue-adoption-resume" ->
                LocalSpaceRuntimeAdoptionProbe(
                        targetContext,
                        directory,
                        OriginalQueueAdoptionProbe(directory),
                    )
                    .resume()
            "space-adoption-stage" ->
                LocalSpaceRuntimeAdoptionProbe(targetContext, directory).stage()
            "space-adoption-resume" ->
                LocalSpaceRuntimeAdoptionProbe(targetContext, directory).resume()
            "local-handover-stage" -> LocalRuntimeHandoverProbe(targetContext, directory).stage()
            "local-handover-resume" -> LocalRuntimeHandoverProbe(targetContext, directory).resume()
            "existing-handover-stage" ->
                LocalExistingRuntimeHandoverProbe(targetContext, directory).stage()
            "existing-handover-resume" ->
                LocalExistingRuntimeHandoverProbe(targetContext, directory).resume()
            "rotation-handover-stage" ->
                LocalExistingRuntimeHandoverProbe(targetContext, directory, rotationPending = true)
                    .stage()
            "rotation-handover-resume" ->
                LocalExistingRuntimeHandoverProbe(targetContext, directory, rotationPending = true)
                    .resume()
            "local-identity-stage" -> LocalSdkIdentityProbe(directory).stage()
            "local-identity-resume" -> LocalSdkIdentityProbe(directory).resume()
            "provider" -> NativeMatrixProbe(directory).run()
            "space-original-stage",
            "space-original-interrupt",
            "space-original-recover",
            "space-original-offline" ->
                HrisSpaceOriginalDeliveryProbe(targetContext, directory).run(scenario)
            "space-recovery-interrupt" ->
                HrisSpaceRecoveryProbe(targetContext, directory).run(interrupt = true)
            "space-recovery-recover" ->
                HrisSpaceRecoveryProbe(targetContext, directory).run(interrupt = false)
            "hris-worker-stage" -> HrisWorkerMatrixProbe(targetContext, directory).stage()
            "hris-worker-resume" -> HrisWorkerMatrixProbe(targetContext, directory).resume()
            "hris-mixed-room" -> HrisMixedRoomProbe(targetContext, directory).run()
            "hris-auth-rejection" -> HrisSdkAuthProbe(targetContext, directory).run()
            "hris-stage" -> HrisNativeMatrixProbe(targetContext, directory).run(resuming = false)
            "hris-resume" -> HrisNativeMatrixProbe(targetContext, directory).run(resuming = true)
            "hris-ack-recover" -> HrisNativeMatrixProbe(targetContext, directory).runAckRecovery()
            "hris-completion-recover" ->
                HrisNativeMatrixProbe(targetContext, directory).runCompletionRecovery()
            else -> {
                check(scenario.startsWith("space-history-"))
                HrisSpaceHistoryProbe(targetContext, directory).run(scenario)
            }
        }
    }
}
