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
                withTimeout(90_000) {
                    when (scenario) {
                        "provider" -> NativeMatrixProbe(directory).run()
                        "hris-stage" ->
                            HrisNativeMatrixProbe(targetContext, directory).run(resuming = false)
                        "hris-resume" ->
                            HrisNativeMatrixProbe(targetContext, directory).run(resuming = true)
                        else -> error("Unknown owned protocol scenario")
                    }
                }
            }
            output.put("complete", true)
        } catch (failure: Exception) {
            output.put("complete", false)
            output.put("failureCategory", failure.javaClass.simpleName)
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
