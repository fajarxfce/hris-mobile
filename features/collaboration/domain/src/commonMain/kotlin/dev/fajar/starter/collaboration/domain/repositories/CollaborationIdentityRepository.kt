package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/** Account/company identity survives credential expiry and native device replacement. */
interface CollaborationIdentityRepository {
    suspend fun current(access: CollaborationLocalAccess): AppResult<CollaborationSelfIdentity?>

    /**
     * First-write or exact replay only. A changed provider identity requires explicit migration.
     */
    suspend fun retain(
        access: CollaborationLocalAccess,
        identity: CollaborationSelfIdentity,
    ): AppResult<Boolean>
}
