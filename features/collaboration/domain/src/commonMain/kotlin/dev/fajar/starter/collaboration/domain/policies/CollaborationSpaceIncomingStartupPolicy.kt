package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*

/** A completed local bootstrap can wake recovery; it never grants content or SDK ownership. */
fun collaborationSpaceIncomingStartup(
    owner: CollaborationSpaceOwner,
    build: AppBuild,
    admission: CollaborationSpaceDirectoryAdmission,
    registration: CollaborationSpaceRegistrationRecord?,
    setups: List<CollaborationSpaceSetupRecord>,
): AppResult<CollaborationSpaceIncomingStartup?> {
    val access = owner.localDirectoryAccess()
    if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios))
        return invalidIncomingStartup()
    if (!validCollaborationSpaceDirectoryAdmission(admission)) return invalidIncomingStartup()
    admission.denial?.let {
        return AppResult.Failed(it)
    }
    if (
        setups.size > 128 ||
            setups.map { it.command.id }.distinct().size != setups.size ||
            setups.map { it.command.binding.nativeSessionId }.distinct().size != setups.size ||
            setups.any { !validCollaborationSpaceSetupRecord(access, it) }
    )
        return invalidIncomingStartup()
    if (registration == null) return AppResult.Success(null)
    if (
        !validCollaborationSpaceRegistrationRecord(access, registration) ||
            registration.command.binding.nativeSessionId != owner.parent.nativeSessionId ||
            registration.command.platform.name != build.platform.name.uppercase()
    )
        return invalidIncomingStartup()
    val registered =
        registration.outcome as? CollaborationSpaceRegistrationOutcome.Registered
            ?: return AppResult.Success(null)
    val setup =
        setups.singleOrNull { it.command.binding.nativeSessionId == owner.parent.nativeSessionId }
            ?: return AppResult.Success(null)
    if (setup.successorId != null) return AppResult.Success(null)
    if (
        setup.command.registrationId != registration.command.id ||
            setup.command.binding != registration.command.binding ||
            setup.command.platform != registration.command.platform ||
            setup.command.request.deviceId != registered.receipt.id
    )
        return invalidIncomingStartup()
    if (
        setup.outcome !is CollaborationSpaceSetupOutcome.Bootstrapped &&
            setup.outcome !is CollaborationSpaceSetupOutcome.BootstrapReplaced
    )
        return AppResult.Success(null)
    val bootstrap = setup.command.request.bootstrapEffect() ?: return invalidIncomingStartup()
    if (bootstrap.provider != registration.command.provider) return invalidIncomingStartup()
    return AppResult.Success(
        CollaborationSpaceIncomingStartup(
            owner,
            registration.command.id,
            setup.command.id,
            registered.receipt.id,
        )
    )
}

private fun invalidIncomingStartup() =
    collaborationRecoveryFailure(
        "client.collaboration_incoming_startup_changed",
        FailureKind.Conflict,
    )
