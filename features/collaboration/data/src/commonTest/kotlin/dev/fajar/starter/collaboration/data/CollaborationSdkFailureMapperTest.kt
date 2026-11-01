package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.mappers.collaborationSdkAcquisitionFailure
import dev.fajar.starter.collaboration.data.mappers.collaborationSdkFailure
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSdkFailureMapperTest {
    @Test
    fun shutdownAndCredentialFaultsDoNotEraseAnAlreadyReceivedDenial() {
        val denied = AppResult.Failed(collaborationSdkFailure("M_FORBIDDEN", null))
        for (kind in listOf(FailureKind.Storage, FailureKind.Unavailable)) {
            assertEquals(
                denied.failure,
                collaborationSdkAcquisitionFailure(denied, Failure(kind, "Local gate")),
            )
        }
        val replaced = Failure(FailureKind.Unauthorized, "Session changed")
        assertEquals(replaced, collaborationSdkAcquisitionFailure(denied, replaced))
        val storage = Failure(FailureKind.Storage, "Unavailable")
        assertEquals(storage, collaborationSdkAcquisitionFailure(AppResult.Success(Unit), storage))
    }

    @Test
    fun tokenAndAccessRejectionsRetainTheirAuthoritativeClassification() {
        for (code in listOf("M_UNKNOWN_TOKEN", "M_MISSING_TOKEN", "M_UNAUTHORIZED")) {
            val failure = collaborationSdkFailure(code, null)
            assertEquals(FailureKind.Unauthorized, failure.kind)
            assertEquals("client.collaboration_sdk_unauthorized", failure.code)
        }
        for (code in
            listOf(
                "M_FORBIDDEN",
                "M_GUEST_ACCESS_FORBIDDEN",
                "M_USER_DEACTIVATED",
                "M_USER_LOCKED",
                "M_USER_SUSPENDED",
            )) {
            val failure = collaborationSdkFailure(code, 10u)
            assertEquals(FailureKind.AccessDenied, failure.kind)
            assertEquals("client.collaboration_sdk_access_denied", failure.code)
            assertNull(failure.retryAfterSeconds)
        }
    }

    @Test
    fun rateLimitDelaysAreRoundedUpAndBoundedBeforeUnsignedConversion() {
        assertEquals(0L, collaborationSdkFailure("M_LIMIT_EXCEEDED", 0u).retryAfterSeconds)
        assertEquals(1L, collaborationSdkFailure("M_LIMIT_EXCEEDED", 1u).retryAfterSeconds)
        assertEquals(2L, collaborationSdkFailure("M_LIMIT_EXCEEDED", 1001u).retryAfterSeconds)
        val longest = collaborationSdkFailure("M_LIMIT_EXCEEDED", ULong.MAX_VALUE)
        assertEquals(86_400L, longest.retryAfterSeconds)
        assertEquals(FailureKind.Service, longest.kind)
    }

    @Test
    fun missingRoomAndConnectivityDoNotInventAnAccessDenial() {
        val missing = collaborationSdkFailure("M_NOT_FOUND", null)
        assertEquals(FailureKind.Unavailable, missing.kind)
        assertEquals("client.collaboration_sdk_not_found", missing.code)
        assertEquals(
            FailureKind.Timeout,
            collaborationSdkFailure("M_CONNECTION_TIMEOUT", null).kind,
        )
        assertEquals(FailureKind.Network, collaborationSdkFailure("M_CONNECTION_FAILED", null).kind)
    }

    @Test
    fun unrecognizedProviderContentIsNotRetainedAsADomainCodeOrParameter() {
        val untrusted = "fictional-private-provider-field".repeat(1000)
        val failure = collaborationSdkFailure(untrusted, ULong.MAX_VALUE)
        assertEquals(FailureKind.Unexpected, failure.kind)
        assertEquals("client.collaboration_sdk_unavailable", failure.code)
        assertTrue(failure.parameters.isEmpty())
        assertNull(failure.retryAfterSeconds)
        assertFalse(failure.toString().contains("fictional-private"))
        assertFalse(failure.message.contains("fictional-private"))
    }
}
