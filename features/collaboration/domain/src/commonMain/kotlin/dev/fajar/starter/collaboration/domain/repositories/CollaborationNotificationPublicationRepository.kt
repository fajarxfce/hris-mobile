package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Raw account-owned journal IO. Reading or replacing a record grants no company or OS action. */
interface CollaborationNotificationPublicationRepository {
    suspend fun read(
        access: CollaborationRecoveryAccess,
        companyId: String,
        conversationId: String,
    ): AppResult<CollaborationNotificationPublication?>

    /** Exact document CAS within the current account checkpoint; no hidden retries or eviction. */
    suspend fun replace(
        access: CollaborationRecoveryAccess,
        expected: CollaborationNotificationPublication?,
        body: CollaborationNotificationPublicationBody,
    ): AppResult<CollaborationNotificationPublicationWrite>

    /**
     * Only active publications are indexed here; inactive watermarks do not consume active
     * capacity.
     */
    suspend fun page(
        access: CollaborationRecoveryAccess,
        after: String? = null,
        limit: Int = 4,
        preparedOnly: Boolean = true,
    ): AppResult<CollaborationNotificationPublicationPage>
}
