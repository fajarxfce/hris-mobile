package dev.fajar.starter.observability

interface AppleCrashClient {
    fun recordDiagnostic(json: String, failure: Boolean)
}

class AppleCrashSink(private val client: AppleCrashClient) : DiagnosticSink {
    override fun record(event: Diagnostic, error: Exception?) {
        ConsoleDiagnosticSink.record(event, null)
        client.recordDiagnostic(encodeDiagnostic(event), error != null)
    }
}
