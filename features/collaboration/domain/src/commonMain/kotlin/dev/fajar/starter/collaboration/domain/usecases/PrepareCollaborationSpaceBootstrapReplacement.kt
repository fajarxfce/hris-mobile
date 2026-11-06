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
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/** Explicit recovery of an uncertain bootstrap. Local handoff precedes any later HTTP replay. */
class PrepareCollaborationSpaceBootstrapReplacement(
    private val commands: CollaborationSpaceSetupCommandRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val identities: CollaborationSpaceIdentityRepository,
    private val devices: CollaborationSpaceDeviceRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        owner: CollaborationSpaceOwner,
        commandId: String,
    ): AppResult<CollaborationSpaceSetupRecord> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationMessageId(owner.spaceId) || !validCollaborationMessageId(commandId))
            return changedSpaceSetup()
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        val access = owner.localDirectoryAccess()
        var restriction: CollaborationSpaceSetupRestriction? = null
        var attempt: CollaborationSpaceSetupAttempt? = null
        fun failed(failure: Failure) =
            attempt?.failed(failure) ?: AppResult.Failed(restriction?.failure() ?: failure)
        return withTimeoutOrNull(15_000) {
            val local = sessions.current()
            currentCoroutineContext().ensureActive()
            if (local is AppResult.Failed) return@withTimeoutOrNull local
            val owned =
                localCollaborationSpaceAccess(
                    access,
                    (local as AppResult.Success).value,
                    server.audience,
                )
            if (owned is AppResult.Failed) return@withTimeoutOrNull owned
            val read = commands.find(access, commandId)
            currentCoroutineContext().ensureActive()
            val after = sessions.current()
            if (after is AppResult.Failed) return@withTimeoutOrNull after
            val stillOwned =
                localCollaborationSpaceAccess(
                    access,
                    (after as AppResult.Success).value,
                    server.audience,
                )
            if (stillOwned is AppResult.Failed) return@withTimeoutOrNull stillOwned
            if (read is AppResult.Failed) return@withTimeoutOrNull read
            val original =
                (read as AppResult.Success).value ?: return@withTimeoutOrNull changedSpaceSetup()
            if (
                original.command.id != commandId ||
                    !validCollaborationSpaceSetupRecord(access, original)
            )
                return@withTimeoutOrNull invalidSpaceSetup()
            restriction = collaborationSpaceSetupRestriction(original)
            // Retained links are owned local facts, even after an ordinary credential/build change.
            if (original.successorId != null)
                return@withTimeoutOrNull retainedSuccessor(access, original)
            if (original.command.binding.nativeSessionId != owner.parent.nativeSessionId)
                return@withTimeoutOrNull failed(changedSpaceSetup().failure)
            val captured =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            if (captured is AppResult.Failed) return@withTimeoutOrNull failed(captured.failure)
            val hard =
                minOf(
                    requireNotNull(local.value).binding.expiresAtEpochMillis,
                    (captured as AppResult.Success).value.binding.expiresAtEpochMillis,
                )
            val current =
                CollaborationSpaceSetupAttempt(
                    owner,
                    directory,
                    sessions,
                    server,
                    build,
                    clock,
                    requestedAt,
                    started,
                    Instant.fromEpochMilliseconds(hard),
                )
            current.remember(restriction?.failure())
            attempt = current
            prepare(current, original)
        } ?: failed(timeoutSpaceSetup().failure)
    }

    private suspend fun prepare(
        attempt: CollaborationSpaceSetupAttempt,
        original: CollaborationSpaceSetupRecord,
    ): AppResult<CollaborationSpaceSetupRecord> {
        val old =
            original.command.request.bootstrapEffect()
                ?: return attempt.failed(changedSpaceSetup().failure)
        if (
            original.outcome !is CollaborationSpaceSetupOutcome.Pending &&
                original.outcome !is CollaborationSpaceSetupOutcome.Unconfirmed
        )
            return attempt.failed(changedSpaceSetup().failure)
        val admission = attempt.admission()
        if (admission is AppResult.Failed) return admission
        val gate = (admission as AppResult.Success).value
        val read = registrations.current(attempt.access, attempt.owner.parent.nativeSessionId)
        val afterRead = attempt.current()
        if (afterRead is AppResult.Failed) return afterRead
        if (read is AppResult.Failed) return attempt.failed(read.failure)
        val registered =
            spaceSetupOriginal(
                attempt.owner,
                original.command.registrationId,
                (read as AppResult.Success).value,
            )
        if (registered is AppResult.Failed) return attempt.failed(registered.failure)
        val source = (registered as AppResult.Success).value
        val platform =
            if (build.platform == AppPlatform.Android) CollaborationNativePlatform.ANDROID
            else CollaborationNativePlatform.IOS
        if (
            source.command.platform != platform ||
                old.platform != platform ||
                old.deviceId != source.receipt.id ||
                old.deviceVersion < source.receipt.version ||
                old.provider != source.command.provider ||
                old.matrixDeviceId != source.command.legacy?.identity?.matrixDeviceId
        )
            return attempt.failed(changedSpaceSetup().failure)
        val identity = identities.read(attempt.owner)
        attempt.restrict(identity)
        val afterIdentity = attempt.current()
        if (afterIdentity is AppResult.Failed) return afterIdentity
        if (identity is AppResult.Failed) return attempt.failed(identity.failure)
        if (
            (identity as AppResult.Success).value.owner != attempt.owner ||
                identity.value.identity != source.command.provider
        )
            return attempt.failed(changedSpaceSetup().failure)
        val observed = devices.status(attempt.owner)
        attempt.restrict(observed)
        val afterStatus = attempt.current()
        if (afterStatus is AppResult.Failed) return afterStatus
        if (observed is AppResult.Failed) return attempt.failed(observed.failure)
        val status = spaceSetupStatus(attempt.owner, source, (observed as AppResult.Success).value)
        if (status is AppResult.Failed) return attempt.failed(status.failure)
        val prepared =
            spaceSetupRequest(
                source,
                (status as AppResult.Success).value,
                CollaborationSpaceSetupKind.BOOTSTRAP,
                Uuid.random().toString(),
                build.number,
                clock.now(),
            )
        if (prepared is AppResult.Failed) return attempt.failed(prepared.failure)
        val effect = (prepared as AppResult.Success).value as CollaborationSpaceBootstrap
        val next =
            CollaborationSpaceSetupCommand(
                original.command.registrationId,
                platform,
                build.number,
                clock.now(),
                CollaborationSpaceBootstrapReplacement(
                    old,
                    effect,
                    collaborationSpaceSetupRestriction(original) ?: gate.denial?.setupRestriction(),
                ),
            )
        if (!validCollaborationSpaceSetupHandoff(attempt.access, original, next))
            return attempt.failed(changedSpaceSetup().failure)
        val beforeWrite = attempt.unchangedAdmission(gate)
        if (beforeWrite is AppResult.Failed) return beforeWrite
        val written = commands.prepareReplacement(attempt.access, original, next)
        val afterWrite = attempt.unchangedAdmission(gate)
        if (afterWrite is AppResult.Failed) return afterWrite
        // Includes a competing writer and a lost local write response. Never create another ID
        // here.
        val retained = commands.find(attempt.access, original.command.id)
        val afterRetained = attempt.current()
        if (afterRetained is AppResult.Failed) return afterRetained
        if (retained is AppResult.Failed) return attempt.failed(retained.failure)
        val linked = (retained as AppResult.Success).value
        if (linked == null || linked.command != original.command || linked.successorId == null) {
            return if (written is AppResult.Failed) attempt.failed(written.failure)
            else attempt.failed(changedSpaceSetup().failure)
        }
        val successor = retainedSuccessor(attempt.access, linked)
        val finalGate = attempt.unchangedAdmission(gate)
        return if (finalGate is AppResult.Failed) finalGate else successor
    }

    private suspend fun retainedSuccessor(
        access: CollaborationSpaceLocalAccess,
        original: CollaborationSpaceSetupRecord,
    ): AppResult<CollaborationSpaceSetupRecord> {
        if (!validCollaborationSpaceSetupRecord(access, original)) return invalidSpaceSetup()
        val id = original.successorId ?: return changedSpaceSetup()
        val read = commands.find(access, id)
        currentCoroutineContext().ensureActive()
        val current = sessions.current()
        if (current is AppResult.Failed) return current
        val owned =
            localCollaborationSpaceAccess(
                access,
                (current as AppResult.Success).value,
                server.audience,
            )
        if (owned is AppResult.Failed) return owned
        if (read is AppResult.Failed) return read
        val next = (read as AppResult.Success).value ?: return changedSpaceSetup()
        val request =
            next.command.request as? CollaborationSpaceBootstrapReplacement
                ?: return invalidSpaceSetup()
        return if (
            next.command.id == id &&
                validCollaborationSpaceSetupRecord(access, next) &&
                next.command.registrationId == original.command.registrationId &&
                request.supersedes == original.command.request.bootstrapEffect() &&
                validCollaborationSpaceSetupHandoff(
                    access,
                    original.copy(successorId = null),
                    next.command,
                )
        )
            AppResult.Success(next)
        else invalidSpaceSetup()
    }
}
