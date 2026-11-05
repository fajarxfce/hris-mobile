package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.SdkSendFailure
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.observability.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.QueueWedgeError

class AndroidCollaborationDeliveryDiagnosticsTest {
    @Test
    fun structuredQueueFailuresNeverReadPrivateErrorFields() {
        assertEquals(
            SdkSendFailure.InsecureDevices,
            QueueWedgeError.InsecureDevices(mapOf("private-user" to listOf("private-device")))
                .closedSendFailure(false),
        )
        assertEquals(
            SdkSendFailure.IdentityChanged,
            QueueWedgeError.IdentityViolations(listOf("private-user")).closedSendFailure(false),
        )
        assertEquals(
            SdkSendFailure.Retryable,
            QueueWedgeError.GenericApiError("private-token").closedSendFailure(true),
        )
        assertEquals(
            SdkSendFailure.Rejected,
            QueueWedgeError.GenericApiError("private-token").closedSendFailure(false),
        )
        assertEquals(
            SdkSendFailure.InvalidMediaType,
            QueueWedgeError.InvalidMimeType("private-type").closedSendFailure(false),
        )
    }

    @Test
    fun genericDiagnosticOnlyRecognizesClosedBoundedPrefixes() {
        assertEquals(
            "GenericEventCache",
            sdkExceptionCategory(ClientException.Generic("private", "EventCache(private-data)")),
        )
        for (details in
            listOf(
                null,
                "private-user",
                "prefix EventCache(private)",
                "EventCache(" + "x".repeat(16_384),
            )) {
            assertEquals(
                "Generic",
                sdkExceptionCategory(ClientException.Generic("Store(secret)", details)),
            )
        }
    }

    @Test
    fun acquisitionKeepsOriginalFailureAndCancellationWithoutPrivateDiagnostics() = runBlocking {
        val recorded = mutableListOf<Diagnostic>()
        Diagnostics.install { event, exception ->
            assertNull(exception)
            recorded += event
        }
        try {
            val original = ClientException.Generic("private-body", "EventCache(private-details)")
            try {
                traceSdkAcquisition(SdkAcquisitionStage.TimelineBuild) { throw original }
                fail("Expected original failure")
            } catch (failure: ClientException.Generic) {
                assertSame(original, failure)
            }
            assertEquals("ChatTimelineBuild_GenericEventCache", recorded.single().category)
            assertFalse(encodeDiagnostic(recorded.single()).contains("private"))
            recorded.clear()
            val cancelled = CancellationException("private")
            try {
                traceSdkAcquisition(SdkAcquisitionStage.QueueBuild) { throw cancelled }
                fail("Cancellation was swallowed")
            } catch (failure: CancellationException) {
                assertSame(cancelled, failure)
            }
            assertTrue(recorded.isEmpty())
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }
}
