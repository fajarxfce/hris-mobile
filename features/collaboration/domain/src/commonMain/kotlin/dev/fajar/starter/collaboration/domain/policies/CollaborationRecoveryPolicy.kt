package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

fun collaborationNativeParent(
    session: Session?,
    server: IdentityServer,
    build: AppBuild,
    now: Instant,
): AppResult<CollaborationNativeParent> {
    if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios))
        return collaborationRecoveryFailure(
            "client.collaboration_native_required",
            FailureKind.Unavailable,
        )
    if (
        session == null ||
            session.binding.audience != server.audience ||
            session.id.length !in 1..128 ||
            session.id.any { it.code !in 33..126 } ||
            !validCollaborationMessageId(session.user.id) ||
            !validCollaborationMessageId(session.binding.serverId) ||
            session.binding.expiresAtEpochMillis <= now.toEpochMilliseconds()
    )
        return collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
    return AppResult.Success(
        CollaborationNativeParent(
            session.id,
            server.audience,
            session.user.id,
            session.binding.serverId,
        )
    )
}

fun validCollaborationDevicePage(
    page: CollaborationDevicePage,
    parent: CollaborationNativeParent,
    afterCompanyId: String?,
    limit: Int,
): Boolean =
    limit in 1..32 &&
        page.parent == parent &&
        page.devices.size <= limit &&
        page.devices.all {
            validCollaborationMessageId(it.companyId) &&
                validCollaborationMessageId(it.deviceId) &&
                it.version >= 0 &&
                (afterCompanyId == null || it.companyId > afterCompanyId)
        } &&
        page.devices.zipWithNext().all { (a, b) -> a.companyId < b.companyId } &&
        page.devices.map { it.deviceId }.distinct().size == page.devices.size &&
        (page.nextAfterCompanyId == null ||
            (page.devices.size == limit &&
                page.nextAfterCompanyId == page.devices.last().companyId))

fun admitCollaborationBackgroundObservation(
    observation: CollaborationAdmissionObservation,
    parent: CollaborationNativeParent,
    device: CollaborationDeviceReference,
    requestedAt: Instant,
    receivedAt: Instant,
    parentExpiresAt: Instant,
): AppResult<CollaborationBackgroundAdmission> {
    val duration = observation.expiresAt - observation.admittedAt
    if (
        observation.owner != parent.owner(device.companyId) ||
            observation.deviceId != device.deviceId ||
            observation.deviceVersion < device.version ||
            observation.deviceVersion < 0 ||
            observation.nativeSessionVersion < 0 ||
            observation.credentialVersion < 0 ||
            observation.admittedAt.toEpochMilliseconds() < 0 ||
            duration <= 0.seconds ||
            duration > 60.seconds
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_admission",
            FailureKind.Unexpected,
        )
    // Start the remote lifetime at request dispatch, conservatively including all network delay.
    val deadline = minOf(requestedAt + duration, parentExpiresAt)
    if (
        receivedAt < requestedAt || receivedAt >= deadline || receivedAt - requestedAt >= 15.seconds
    )
        return collaborationRecoveryFailure(
            "client.collaboration_revalidation_required",
            FailureKind.Unavailable,
        )
    return AppResult.Success(CollaborationBackgroundAdmission(observation, requestedAt, deadline))
}

fun collaborationRecoveryFailure(code: String, kind: FailureKind) =
    AppResult.Failed(Failure(kind, "Chat recovery requires current access.", code = code))

/** Rechecks a captured grant for subsequent operations without renewing any deadline. */
fun collaborationBackgroundOwner(
    grant: CollaborationBackgroundAdmission,
    session: Session?,
    server: IdentityServer,
    build: AppBuild,
    now: Instant,
): AppResult<CollaborationOwner> {
    val parent = collaborationNativeParent(session, server, build, now)
    if (parent is AppResult.Failed) return parent
    val owner = grant.owner
    if ((parent as AppResult.Success).value.owner(owner.companyId) != owner)
        return collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
    val observed = grant.observation
    val duration = observed.expiresAt - observed.admittedAt
    if (
        !validCollaborationMessageId(owner.companyId) ||
            !validCollaborationMessageId(observed.deviceId) ||
            observed.deviceVersion < 0 ||
            observed.nativeSessionVersion < 0 ||
            observed.credentialVersion < 0 ||
            observed.admittedAt.toEpochMilliseconds() < 0 ||
            grant.requestedAt.toEpochMilliseconds() < 0 ||
            duration <= 0.seconds ||
            duration > 60.seconds ||
            grant.expiresAt > grant.requestedAt + duration ||
            grant.expiresAt <= grant.requestedAt
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_admission",
            FailureKind.Unexpected,
        )
    if (now < grant.requestedAt || now >= grant.expiresAt)
        return collaborationRecoveryFailure(
            "client.collaboration_revalidation_required",
            FailureKind.Unavailable,
        )
    return AppResult.Success(owner)
}
