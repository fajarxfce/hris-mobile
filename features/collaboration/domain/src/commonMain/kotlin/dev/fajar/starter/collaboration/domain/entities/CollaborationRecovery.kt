package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Current local request scope plus native parent; independent of the selected workspace. */
data class CollaborationNativeParent(
    val sessionId: String,
    val audience: String,
    val accountId: String,
    val nativeSessionId: String,
) {
    fun owner(companyId: String) =
        CollaborationOwner(sessionId, audience, accountId, companyId, nativeSessionId)

    override fun toString() = "CollaborationNativeParent([redacted])"
}

/** Retained device metadata does not grant company or content access. */
data class CollaborationDeviceReference(
    val companyId: String,
    val deviceId: String,
    val version: Long,
    val state: CollaborationDeviceState,
    val platform: CollaborationNativePlatform,
)

data class CollaborationDevicePage(
    val parent: CollaborationNativeParent,
    val devices: List<CollaborationDeviceReference>,
    val nextAfterCompanyId: String?,
)

/** Server observation only. Never use server timestamps directly as a local clock deadline. */
data class CollaborationAdmissionObservation(
    val owner: CollaborationOwner,
    val deviceId: String,
    val deviceVersion: Long,
    val nativeSessionVersion: Long,
    val credentialVersion: Long,
    val admittedAt: Instant,
    val expiresAt: Instant,
)

/** Ephemeral permission observation, not a bearer, SDK store lease or local-read authorization. */
data class CollaborationBackgroundAdmission(
    val observation: CollaborationAdmissionObservation,
    val requestedAt: Instant,
    val expiresAt: Instant,
) {
    val owner
        get() = observation.owner

    override fun toString() = "CollaborationBackgroundAdmission([redacted])"
}
