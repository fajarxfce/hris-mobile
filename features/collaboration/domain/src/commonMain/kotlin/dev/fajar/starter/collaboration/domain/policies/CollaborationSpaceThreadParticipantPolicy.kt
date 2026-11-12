package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Capture one exact peer's public labels; labels never grant HR or communication access. */
fun collaborationSpaceThreadContact(
    access: CollaborationSpaceLocalAccess,
    peerAccountId: String,
    value: CollaborationSpaceContact?,
): AppResult<CollaborationSpaceContact?> {
    if (value == null) return AppResult.Success(null)
    if (
        value.accountId != peerAccountId || !validCollaborationSpaceContact(value, access.accountId)
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    return AppResult.Success(value.copy(companies = value.companies.toList()))
}

/** All retained native parents must agree on the immutable selected provider user. */
fun collaborationSpaceThreadSelectedIdentity(
    access: CollaborationSpaceLocalAccess,
    records: List<CollaborationSpaceRegistrationRecord>,
): AppResult<CollaborationSpaceIdentity?> {
    if (
        records.size > 128 ||
            records.map { it.command.id }.distinct().size != records.size ||
            records.map { it.command.binding.nativeSessionId }.distinct().size != records.size ||
            records.any { !validCollaborationSpaceRegistrationRecord(access, it) }
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_command",
            FailureKind.Unexpected,
        )
    val identities = records.map { it.command.provider }.distinct()
    if (identities.size > 1)
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    return AppResult.Success(identities.singleOrNull())
}
