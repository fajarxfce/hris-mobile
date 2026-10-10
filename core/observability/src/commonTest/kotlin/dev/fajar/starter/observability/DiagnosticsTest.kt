package dev.fajar.starter.observability

import kotlin.test.*

class DiagnosticsTest {
    @Test
    fun schemaRejectsFreeTextAndInvalidHttpMetadata() {
        val json =
            encodeDiagnostic(
                Diagnostic(
                    DiagnosticArea.Network,
                    DiagnosticKind.OperationFailed,
                    "Bearer secret@example.com",
                    900,
                    -1,
                )
            )
        assertEquals("{\"area\":\"Network\",\"event\":\"OperationFailed\"}", json)
    }

    @Test
    fun diagnosticEncodingContainsOnlyBoundedMetadata() {
        val json =
            encodeDiagnostic(
                Diagnostic(
                    DiagnosticArea.Network,
                    DiagnosticKind.HttpCompleted,
                    "IOException",
                    503,
                    12,
                )
            )
        assertTrue(json.contains("503"))
        assertTrue(json.contains("IOException"))
        assertFalse(json.contains("http://"))
    }
}
