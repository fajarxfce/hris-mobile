package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.common.result.*
import org.junit.Assert.*
import org.junit.Test

class HrisProofFailureTest {
    @Test
    fun notificationSourceFailureHasAControlledCategoryWithoutProviderDetail() {
        val failed =
            HrisProofFailure(
                Failure(
                    FailureKind.Unavailable,
                    "Fictional provider payload that must not enter evidence",
                    code = "client.collaboration_notification_changes_unavailable",
                )
            )
        assertEquals("HrisProof_Unavailable_NotificationChangesUnavailable", failed.category)
        assertEquals("Owned SDK operation failed", failed.message)
        assertNull(failed.cause)
    }

    @Test
    fun unrecognizedCodeCannotSmuggleProviderDataIntoEvidence() {
        val marker = "fictional-sensitive-provider-value"
        val failed =
            HrisProofFailure(
                Failure(
                    FailureKind.Unavailable,
                    marker,
                    code = "client.collaboration_notification_changes_unavailable\n$marker",
                )
            )
        assertEquals("HrisProof_Unavailable_Unclassified", failed.category)
        assertFalse(failed.toString().contains(marker))
        assertFalse(failed.category.contains(marker))
        assertNull(failed.cause)
    }
}
