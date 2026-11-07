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
    fun structuredQueueFailuresNeverExposePrivateErrorFields() {
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
    fun pinnedMatrixStatusPrefixesExposeOnlyFiniteDiagnosticCategories() {
        val cases =
            mapOf(
                400 to SdkSendFailure.ApiBadRequest,
                401 to SdkSendFailure.ApiUnauthorized,
                403 to SdkSendFailure.ApiForbidden,
                404 to SdkSendFailure.ApiNotFound,
                409 to SdkSendFailure.ApiConflict,
                413 to SdkSendFailure.ApiTooLarge,
                429 to SdkSendFailure.ApiRateLimited,
                500 to SdkSendFailure.ApiServerError,
                599 to SdkSendFailure.ApiServerError,
            )
        for ((status, expected) in cases) {
            for (suffix in listOf(" / M_FORBIDDEN", "")) {
                val error =
                    QueueWedgeError.GenericApiError(
                        "the server returned an error: [$status$suffix] private-token @private:user"
                    )
                for (recoverable in listOf(false, true)) {
                    val classified = error.closedSendFailure(recoverable)
                    assertEquals(expected, classified)
                    assertFalse(classified.name.contains("private"))
                }
            }
        }
    }

    @Test
    fun serverProseMalformedPrefixesAndOversizedDetailsRemainGeneric() {
        for (message in
            listOf(
                "private the server returned an error: [403 / M_FORBIDDEN] denied",
                "the server returned an error: [403 / private-token] denied",
                "the server returned an error: [403 / M_" + "A".repeat(65) + "] denied",
                "the server returned an error: [999 / M_FORBIDDEN] denied",
                "the server returned an error: [200 / M_FORBIDDEN] denied",
                "the server returned an error: [403 / M_FORBIDDEN]".repeat(400),
                "private deserialization failed: private-key",
                "private wrong room state: private-room",
            )) {
            val error = QueueWedgeError.GenericApiError(message)
            assertEquals(SdkSendFailure.Rejected, error.closedSendFailure(false))
            assertEquals(SdkSendFailure.Retryable, error.closedSendFailure(true))
        }
    }

    @Test
    fun localSdkFailuresRemainDistinctFromAnApiRejectionWithoutEmittingDetails() {
        val cases =
            mapOf(
                "deserialization failed: private-body" to SdkSendFailure.ResponseDecoding,
                "encryption failed because the device does not have a valid Olm session with us" to
                    SdkSendFailure.MissingOlmSession,
                "Local cache doesn't contain all necessary data to perform the action." to
                    SdkSendFailure.MissingLocalData,
                "failed to read or write to the crypto store private-path" to
                    SdkSendFailure.CryptoStore,
                "wrong room state: private-room" to SdkSendFailure.WrongRoomState,
                "a concurrent request failed; see logs for details" to
                    SdkSendFailure.ConcurrentRequest,
                "local echo failed to send in a previous session" to SdkSendFailure.PreviousSession,
            )
        for ((message, expected) in cases) {
            assertEquals(
                expected,
                QueueWedgeError.GenericApiError(message).closedSendFailure(false),
            )
            val diagnostic =
                Diagnostic(
                    DiagnosticArea.Network,
                    DiagnosticKind.OperationFailed,
                    "ChatQueueSend_$expected",
                )
            assertFalse(encodeDiagnostic(diagnostic).contains("private"))
        }
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
