package dev.fajar.starter.security.domain.access.policy

import dev.fajar.starter.security.domain.access.entities.*

/** Absence, expiry and another session all deny access. The server rechecks every mutation. */
fun allows(
    snapshot: AccessSnapshot?,
    permission: Permission,
    sessionId: String,
    now: Long,
): Boolean =
    snapshot != null &&
        snapshot.sessionId == sessionId &&
        snapshot.expiresAtEpochMillis > now &&
        permission in snapshot.permissions
