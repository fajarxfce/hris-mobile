package dev.fajar.hris.collaboration.proof

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** An emulator-only, debug-only protocol fixture with no HRIS/Firebase integration. */
class MatrixProofInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
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
            runBlocking { withTimeout(90_000) { NativeMatrixProbe(directory).run() } }
            output.put("complete", true)
        } catch (failure: Exception) {
            output.put("complete", false)
            output.put("failureCategory", failure.javaClass.simpleName)
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
