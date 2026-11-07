package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.observability.*

/** Instrumentation-owned, bounded context for an otherwise unclassified storage failure. */
internal class HrisProofStorageDiagnostics : DiagnosticSink {
    private val recent = ArrayDeque<HrisProofStorageFailure>()

    override fun record(event: Diagnostic, error: Exception?) {
        ConsoleDiagnosticSink.record(event, null)
        if (event.area != DiagnosticArea.Storage || error == null) return
        val categories = mutableListOf<String>()
        var cause: Throwable? = error
        repeat(4) {
            val current = cause ?: return@repeat
            categories += current.javaClass.simpleName.takeIf { it.matches(CATEGORY) } ?: "Other"
            cause = current.cause
        }
        val frames =
            error.stackTrace
                .asSequence()
                .take(64)
                .map { "${it.className}.${it.methodName}:${it.lineNumber}" }
                .filter { it.length <= 255 && it.matches(FRAME) }
                .take(6)
                .toList()
        synchronized(recent) {
            if (recent.size == 8) recent.removeFirst()
            recent.addLast(HrisProofStorageFailure(categories.toList(), frames))
        }
    }

    fun snapshot(): List<HrisProofStorageFailure> = synchronized(recent) { recent.toList() }

    private companion object {
        val CATEGORY = Regex("[A-Za-z_][A-Za-z0-9_$]{0,79}")
        val FRAME = Regex("(?:dev\\.fajar\\.|org\\.matrix\\.)[A-Za-z0-9_.$]+:-?[0-9]{1,6}")
    }
}

internal data class HrisProofStorageFailure(val categories: List<String>, val frames: List<String>)
