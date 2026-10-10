package dev.fajar.starter.observability

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.perf.FirebasePerformance

/** Application context only; automatic bytecode network instrumentation is not installed. */
class AndroidPerformanceSink(context: Context, private val enabled: Boolean) : PerformanceSink {
    private val client =
        if (FirebaseApp.getApps(context).isNotEmpty()) FirebasePerformance.getInstance() else null

    init {
        client?.setPerformanceCollectionEnabled(enabled)
    }

    override fun start(operation: PerformanceOperation): PerformanceSample? {
        if (!enabled) return null
        val trace = client?.newTrace("starter_" + operation.name) ?: return null
        trace.start()
        return PerformanceSample { outcome ->
            try {
                trace.putAttribute("outcome", outcome.name)
            } finally {
                trace.stop()
            }
        }
    }
}
