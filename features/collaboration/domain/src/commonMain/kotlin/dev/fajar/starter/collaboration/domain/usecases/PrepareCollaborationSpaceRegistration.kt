@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/** Explicit original-device preparation. Missing metadata/runtime never creates a SPACE device. */
class PrepareCollaborationSpaceRegistration(
    private val commands: CollaborationSpaceRegistrationCommandRepository,
    private val identities: CollaborationSpaceIdentityRepository,
    private val devices: CollaborationSpaceDeviceRepository,
    private val local: CollaborationLocalDeviceIdentityRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        owner: CollaborationSpaceOwner,
        originalOwner: CollaborationOwner,
        originalDeviceId: String,
        label: String,
    ): AppResult<CollaborationSpaceRegistrationRecord> {
        currentCoroutineContext().ensureActive()
        val source = Source(owner, originalOwner, originalDeviceId, label.trim())
        if (
            !validCollaborationMessageId(owner.spaceId) ||
                !validCollaborationMessageId(originalDeviceId) ||
                !validCollaborationMessageId(originalOwner.companyId) ||
                source.label.length !in 1..64 ||
                source.label.any { it.code in 0..31 || it.code in 127..159 } ||
                build.number !in 1..999_999_999L
        )
            return problem("client.collaboration_invalid_request", FailureKind.Validation)
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var attempt: Preparation? = null
        val result =
            withTimeoutOrNull(15_000) {
                val captured =
                    sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
                if (captured is AppResult.Failed) return@withTimeoutOrNull captured
                val deadline =
                    Instant.fromEpochMilliseconds(
                        (captured as AppResult.Success).value.binding.expiresAtEpochMillis
                    )
                Preparation(source, requestedAt, started, deadline).also { attempt = it }.run()
            }
        val timeout = problem("client.collaboration_recovery_timeout", FailureKind.Timeout)
        return result ?: attempt?.failed(timeout.failure) ?: timeout
    }

    private data class Source(
        val owner: CollaborationSpaceOwner,
        val original: CollaborationOwner,
        val deviceId: String,
        val label: String,
    )

    /** One invocation owns its fixed deadline and observed denial; nothing outlives the caller. */
    private inner class Preparation(
        private val source: Source,
        private val requestedAt: Instant,
        private val started: TimeMark,
        private val deadline: Instant,
    ) {
        private val owner = source.owner
        private val access = owner.localDirectoryAccess()
        private val platform =
            if (build.platform == AppPlatform.Android) CollaborationNativePlatform.ANDROID
            else CollaborationNativePlatform.IOS
        private var denial: Failure? = null

        fun failed(value: Failure) = AppResult.Failed(denial ?: value)

        suspend fun run(): AppResult<CollaborationSpaceRegistrationRecord> {
            val admission = directory.admission(access)
            if (admission is AppResult.Failed) return admission
            val previous = (admission as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(previous)) return invalid()
            denial = previous.denial
            val before = current()
            if (before is AppResult.Failed) return failed(before.failure)
            val existing = commands.current(access, owner.parent.nativeSessionId)
            val after = current()
            if (after is AppResult.Failed) return failed(after.failure)
            if (existing is AppResult.Failed) return failed(existing.failure)
            (existing as AppResult.Success).value?.let {
                return retained(access, owner, it)
            }
            // Hard-parent recovery is separate. Never replace the original physical family.
            val original = source.original
            if (
                original.sessionId != owner.parent.sessionId ||
                    original.audience != owner.parent.audience ||
                    original.accountId != owner.parent.accountId ||
                    original.nativeSessionId != owner.parent.nativeSessionId
            )
                return failed(originalRequired().failure)
            val selected = readIdentity()
            if (selected is AppResult.Failed) return selected
            val provider = (selected as AppResult.Success).value
            if (
                provider.origin != CollaborationSpaceIdentityOrigin.LEGACY ||
                    provider.legacyCompanyId != original.companyId
            )
                return failed(originalRequired().failure)
            val status = checkDevice()
            if (status is AppResult.Failed) return status
            val actual = local.readLocalIdentity(original, source.deviceId)
            val afterLocal = current()
            if (afterLocal is AppResult.Failed) return failed(afterLocal.failure)
            if (actual is AppResult.Failed) return failed(actual.failure)
            val command =
                CollaborationSpaceRegistrationCommand(
                    Uuid.random().toString(),
                    CollaborationSpaceDeviceBinding(
                        owner.parent.audience,
                        owner.parent.accountId,
                        owner.spaceId,
                        owner.parent.nativeSessionId,
                    ),
                    platform,
                    build.number,
                    clock.now(),
                    provider,
                    source.label,
                    CollaborationSpaceDeviceOrigin.LEGACY,
                    CollaborationSpaceLegacySdk(
                        original.setupBinding(),
                        source.deviceId,
                        (actual as AppResult.Success).value,
                    ),
                )
            if (!validCollaborationSpaceRegistrationCommand(access, command))
                return failed(invalid().failure)
            return persist(command, previous)
        }

        private suspend fun readIdentity(): AppResult<CollaborationSpaceIdentity> {
            val selected = identities.read(owner)
            restrict(selected)
            val after = current()
            if (after is AppResult.Failed) return failed(after.failure)
            if (selected is AppResult.Failed) return failed(selected.failure)
            val observed = (selected as AppResult.Success).value
            if (observed.owner != owner) return failed(invalid().failure)
            val provider =
                observed.identity
                    ?: return failed(
                        problem("collaboration_identity_selection_required", FailureKind.Conflict)
                            .failure
                    )
            return if (validCollaborationSpaceIdentity(owner, provider)) AppResult.Success(provider)
            else failed(invalid().failure)
        }

        private suspend fun checkDevice(): AppResult<Unit> {
            val status = devices.status(owner)
            restrict(status)
            val after = current()
            if (after is AppResult.Failed) return failed(after.failure)
            if (status is AppResult.Failed) return failed(status.failure)
            val value = (status as AppResult.Success).value
            if (
                value.owner != owner ||
                    value.device?.let {
                        !validCollaborationSpaceDeviceStatus(it) || it.platform != platform
                    } == true
            )
                return failed(invalid().failure)
            if (
                value.device?.let {
                    it.state == CollaborationDeviceState.REVOKED ||
                        it.origin != CollaborationSpaceDeviceOrigin.LEGACY
                } == true
            )
                return failed(originalRequired().failure)
            return AppResult.Success(Unit)
        }

        private suspend fun persist(
            command: CollaborationSpaceRegistrationCommand,
            previous: CollaborationSpaceDirectoryAdmission,
        ): AppResult<CollaborationSpaceRegistrationRecord> {
            val before = admissionStillCurrent(previous)
            if (before is AppResult.Failed) return failed(before.failure)
            val inserted = commands.insert(access, command)
            val after = admissionStillCurrent(previous)
            if (after is AppResult.Failed) return failed(after.failure)
            if (inserted is AppResult.Failed) return failed(inserted.failure)
            when ((inserted as AppResult.Success).value) {
                CollaborationSetupInsertion.STORED,
                CollaborationSetupInsertion.OCCUPIED -> Unit
                CollaborationSetupInsertion.CAPACITY_EXCEEDED ->
                    return failed(problem("client.command_queue_full", FailureKind.Storage).failure)
                CollaborationSetupInsertion.SCOPE_CHANGED ->
                    return failed(
                        problem("client.session_changed", FailureKind.Unauthorized).failure
                    )
            }
            val saved = commands.current(access, owner.parent.nativeSessionId)
            val finalAdmission = admissionStillCurrent(previous)
            if (finalAdmission is AppResult.Failed) return failed(finalAdmission.failure)
            if (saved is AppResult.Failed) return failed(saved.failure)
            return (saved as AppResult.Success).value?.let { retained(access, owner, it) }
                ?: failed(
                    problem("client.collaboration_command_changed", FailureKind.Conflict).failure
                )
        }

        private suspend fun admissionStillCurrent(
            previous: CollaborationSpaceDirectoryAdmission
        ): AppResult<Unit> {
            val latest = directory.admission(access)
            val checked = current()
            if (checked is AppResult.Failed) return checked
            if (latest is AppResult.Failed) return latest
            val gate = (latest as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(gate)) return invalid()
            if (gate.denial != null && gate.revision != previous.revision) {
                denial = gate.denial
                return AppResult.Failed(gate.denial)
            }
            return AppResult.Success(Unit)
        }

        private suspend fun current(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val captured =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            if (captured is AppResult.Failed) return captured
            val now = clock.now()
            if (
                now < requestedAt ||
                    now >= deadline ||
                    now - requestedAt >= 15.seconds ||
                    started.elapsedNow() >= minOf(15.seconds, deadline - requestedAt)
            )
                return problem(
                    "client.collaboration_revalidation_required",
                    FailureKind.Unavailable,
                )
            return AppResult.Success(Unit)
        }

        private suspend fun restrict(received: AppResult<*>) {
            if (
                received is AppResult.Failed &&
                    collaborationSpaceDirectoryResponseClosesRead(received.failure)
            ) {
                denial = received.failure
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) { directory.restrict(access, received.failure) }
                }
            }
        }
    }

    private fun retained(
        access: CollaborationSpaceLocalAccess,
        owner: CollaborationSpaceOwner,
        record: CollaborationSpaceRegistrationRecord,
    ): AppResult<CollaborationSpaceRegistrationRecord> =
        if (
            validCollaborationSpaceRegistrationRecord(access, record) &&
                record.command.binding.nativeSessionId == owner.parent.nativeSessionId
        )
            AppResult.Success(record)
        else invalid()

    private fun originalRequired() =
        problem("client.collaboration_original_device_required", FailureKind.Conflict)

    private fun invalid() = problem("client.collaboration_invalid_response", FailureKind.Unexpected)

    private fun problem(code: String, kind: FailureKind) = collaborationRecoveryFailure(code, kind)
}
