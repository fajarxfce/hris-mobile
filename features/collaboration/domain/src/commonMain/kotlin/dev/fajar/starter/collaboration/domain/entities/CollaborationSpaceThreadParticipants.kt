package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.AppResult

/**
 * Observed local display facts, never live admission or proof of encryption-key possession.
 * Success(null) is absence. First history and participant results publish together. Original
 * identity applies only to Bound Original history and comes from its exact physical namespace.
 */
data class CollaborationSpaceThreadParticipants(
    val contact: AppResult<CollaborationSpaceContact?>,
    val selectedIdentity: AppResult<CollaborationSpaceIdentity?>,
    val originalIdentity: AppResult<CollaborationSelfIdentity?>,
) {
    override fun toString() = "CollaborationSpaceThreadParticipants([redacted])"
}
