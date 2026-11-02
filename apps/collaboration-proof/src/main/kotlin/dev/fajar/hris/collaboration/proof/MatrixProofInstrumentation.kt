package dev.fajar.hris.collaboration.proof

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
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

    override fun onStart() {
        val directory = File(targetContext.filesDir, "protocol-proof")
        val output = JSONObject()
        try {
            check(
                android.os.Build.FINGERPRINT.contains("generic") ||
                    android.os.Build.MODEL.contains("sdk")
            )
            runBlocking {
                withTimeout(
                    if (scenario == "hris-worker-resume") 420_000
                    else if (
                        scenario.startsWith("hris-worker-") ||
                            scenario.startsWith("company-recovery")
                    )
                        210_000
                    else 120_000
                ) {
                    when (scenario) {
                        "native-notification" ->
                            NativeNotificationProbe(targetContext, directory).run(denied = false)
                        "native-notification-denied" ->
                            NativeNotificationProbe(targetContext, directory).run(denied = true)
                        "company-recovery" -> CompanyRecoveryProbe(targetContext, directory).run()
                        "company-recovery-stage" ->
                            CompanyRecoveryProbe(targetContext, directory).stage()
                        "company-recovery-resume" ->
                            CompanyRecoveryProbe(targetContext, directory)
                                .run(CompanyRecoveryProbe.Scenario.Resume)
                        "company-recovery-backlog" ->
                            CompanyRecoveryProbe(targetContext, directory)
                                .run(CompanyRecoveryProbe.Scenario.Backlog)
                        "work-wake" -> CollapsedSchedulingProbe(targetContext, directory).run()
                        "work-wake-stage" ->
                            CollapsedSchedulingProbe(targetContext, directory).stage()
                        "work-wake-resume" ->
                            CollapsedSchedulingProbe(targetContext, directory).run(resuming = true)
                        "provider" -> NativeMatrixProbe(directory).run()
                        "hris-worker-stage" ->
                            HrisWorkerMatrixProbe(targetContext, directory).stage()
                        "hris-worker-resume" ->
                            HrisWorkerMatrixProbe(targetContext, directory).resume()
                        "hris-stage" ->
                            HrisNativeMatrixProbe(targetContext, directory).run(resuming = false)
                        "hris-resume" ->
                            HrisNativeMatrixProbe(targetContext, directory).run(resuming = true)
                        "hris-ack-recover" ->
                            HrisNativeMatrixProbe(targetContext, directory).runAckRecovery()
                        "hris-completion-recover" ->
                            HrisNativeMatrixProbe(targetContext, directory).runCompletionRecovery()
                        else -> error("Unknown owned protocol scenario")
                    }
                }
            }
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
            directory.mkdirs()
            File(directory, "instrumentation.json").writeText(output.toString())
            finish(
                if (output.optBoolean("complete")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("proof", output.toString()) },
            )
        }
    }
}
