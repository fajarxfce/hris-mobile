package dev.fajar.starter.observability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*

/** Closed fields deliberately exclude free text, URLs, bodies, headers and user identifiers. */
enum class DiagnosticArea {
    Network,
    Storage,
    Notification,
    Worker,
    FeatureFlags,
    Application,
}

enum class DiagnosticKind {
    OperationFailed,
    HttpCompleted,
    OperationCompleted,
}

data class Diagnostic(
    val area: DiagnosticArea,
    val kind: DiagnosticKind,
    val category: String? = null,
    val httpStatus: Int? = null,
    val durationMillis: Long? = null,
    val operation: PerformanceOperation? = null,
    val outcome: PerformanceOutcome? = null,
    val traceId: String? = null,
    val spanId: String? = null,
)

fun interface DiagnosticSink {
    fun record(event: Diagnostic, error: Exception?)
}

/** Process-owned logging. Only the platform host installs a sink, before opening containers. */
object Diagnostics {
    private val sink = MutableStateFlow<DiagnosticSink>(ConsoleDiagnosticSink)

    fun install(value: DiagnosticSink) {
        sink.value = value
    }

    fun failure(area: DiagnosticArea, error: Exception) {
        record(Diagnostic(area, DiagnosticKind.OperationFailed, error::class.simpleName), error)
    }

    fun record(event: Diagnostic, error: Exception? = null) {
        try {
            sink.value.record(event, error)
        } catch (_: Exception) {
            ConsoleDiagnosticSink.record(event, null)
        }
    }
}

object ConsoleDiagnosticSink : DiagnosticSink {
    override fun record(event: Diagnostic, error: Exception?) {
        println(encodeDiagnostic(event))
    }
}

/** Exception messages, causes and toString() never participate in encoding. */
fun encodeDiagnostic(event: Diagnostic): String =
    buildJsonObject {
            put("area", event.area.name)
            put("event", event.kind.name)
            event.category
                ?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,79}")) }
                ?.let { put("category", it) }
            event.httpStatus?.takeIf { it in 100..599 }?.let { put("http_status", it) }
            event.durationMillis?.takeIf { it >= 0 }?.let { put("duration_ms", it) }
            event.operation?.let { put("operation", it.name) }
            event.outcome?.let { put("outcome", it.name) }
            event.traceId
                ?.takeIf { it.matches(Regex("[0-9a-f]{32}")) && it.any { c -> c != '0' } }
                ?.let { put("trace_id", it) }
            event.spanId
                ?.takeIf { it.matches(Regex("[0-9a-f]{16}")) && it.any { c -> c != '0' } }
                ?.let { put("span_id", it) }
        }
        .toString()
