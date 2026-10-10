package dev.fajar.starter.observability

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** Firebase setup is optional; SDK collection is explicitly controlled by the host. */
class AndroidCrashSink(context: Context, private val enabled: Boolean) : DiagnosticSink {
    private val client =
        if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseCrashlytics.getInstance() else null

    init {
        client?.setCrashlyticsCollectionEnabled(enabled)
    }

    override fun record(event: Diagnostic, error: Exception?) {
        ConsoleDiagnosticSink.record(event, null)
        if (!enabled) return
        client?.log(encodeDiagnostic(event))
        if (error != null && event.kind == DiagnosticKind.OperationFailed) {
            val sanitized =
                RuntimeException(encodeDiagnostic(event)).apply { stackTrace = error.stackTrace }
            client?.recordException(sanitized)
        }
    }
}
