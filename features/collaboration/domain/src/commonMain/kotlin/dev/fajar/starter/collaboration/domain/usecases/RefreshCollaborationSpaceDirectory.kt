@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One explicit unfiltered page. A durable end cursor never schedules another request. */
class RefreshCollaborationSpaceDirectory(
    private val directory: CollaborationSpaceDirectoryRepository,
    private val spaces: CollaborationSpaceRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val acquisition = Mutex()

    /** False means already complete; only an explicit restart begins a replacement generation. */
    suspend operator fun invoke(
        owner: CollaborationSpaceOwner,
        restart: Boolean = false,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        val access = owner.localDirectoryAccess()
        var retainedDenial: Failure? = null
        fun failed(failure: Failure) = AppResult.Failed(retainedDenial ?: failure)
        return withTimeoutOrNull(15_000) {
            val local = sessions.current()
            if (local is AppResult.Failed) return@withTimeoutOrNull local
            val localSession = (local as AppResult.Success).value
            val owned = localCollaborationSpaceAccess(access, localSession, server.audience)
            if (owned is AppResult.Failed) return@withTimeoutOrNull owned
            val loaded = directory.admission(access)
            if (loaded is AppResult.Failed) return@withTimeoutOrNull loaded
            val admission = (loaded as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(admission))
                return@withTimeoutOrNull invalid()
            retainedDenial = admission.denial
            val initial =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            if (initial is AppResult.Failed) return@withTimeoutOrNull failed(initial.failure)
            val parentDeadline =
                Instant.fromEpochMilliseconds(
                    minOf(
                        requireNotNull(localSession).binding.expiresAtEpochMillis,
                        (initial as AppResult.Success).value.binding.expiresAtEpochMillis,
                    )
                )
            suspend fun current(): AppResult<Unit> {
                val captured =
                    sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
                if (captured is AppResult.Failed) return captured
                val now = clock.now()
                if (
                    now < requestedAt ||
                        now >= parentDeadline ||
                        now - requestedAt >= 15.seconds ||
                        started.elapsedNow() >= minOf(15.seconds, parentDeadline - requestedAt)
                )
                    return collaborationRecoveryFailure(
                        "client.collaboration_revalidation_required",
                        FailureKind.Unavailable,
                    )
                return AppResult.Success(Unit)
            }
            acquisition.withLock {
                acquire(owner, restart, ::current, ::failed) { retainedDenial = it }
            }
        }
            ?: failed(
                Failure(
                    FailureKind.Timeout,
                    "Directory refresh timed out",
                    code = "client.collaboration_recovery_timeout",
                )
            )
    }

    private suspend fun acquire(
        owner: CollaborationSpaceOwner,
        restart: Boolean,
        current: suspend () -> AppResult<Unit>,
        failed: (Failure) -> AppResult.Failed,
        retainDenial: (Failure?) -> Unit,
    ): AppResult<Boolean> {
        val access = owner.localDirectoryAccess()
        val before = current()
        if (before is AppResult.Failed) return failed(before.failure)
        val latestAdmission = directory.admission(access)
        if (latestAdmission is AppResult.Failed) return failed(latestAdmission.failure)
        val admission = (latestAdmission as AppResult.Success).value
        if (!validCollaborationSpaceDirectoryAdmission(admission)) return failed(invalid().failure)
        retainDenial(admission.denial)
        val loaded = directory.checkpoint(access)
        if (loaded is AppResult.Failed) return failed(loaded.failure)
        var checkpoint = (loaded as AppResult.Success).value
        if (checkpoint != null && !validCollaborationSpaceDirectoryCheckpoint(checkpoint))
            return failed(invalid().failure)
        val beforeRead = current()
        if (beforeRead is AppResult.Failed) return failed(beforeRead.failure)
        if (checkpoint?.complete == true && !restart)
            return admission.denial?.let { AppResult.Failed(it) } ?: AppResult.Success(false)
        val after = checkpoint?.takeUnless { it.complete }?.nextCursor
        val received = spaces.contacts(owner, "", after, COLLABORATION_SPACE_DIRECTORY_PAGE_SIZE)
        // Preserve a received server denial even when the route was cancelled at delivery.
        if (
            received is AppResult.Failed &&
                collaborationSpaceDirectoryResponseClosesRead(received.failure)
        ) {
            retainDenial(received.failure)
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { directory.restrict(access, received.failure) }
            }
        }
        currentCoroutineContext().ensureActive()
        val afterRead = current()
        if (received is AppResult.Failed) return failed(received.failure)
        if (afterRead is AppResult.Failed) return failed(afterRead.failure)
        val page = (received as AppResult.Success).value
        val count = (checkpoint?.takeUnless { it.complete }?.count ?: 0) + page.items.size
        if (
            !validCollaborationSpaceContactPage(
                page,
                owner,
                after,
                COLLABORATION_SPACE_DIRECTORY_PAGE_SIZE,
            ) ||
                count > COLLABORATION_SPACE_DIRECTORY_CAPACITY ||
                (page.nextCursor != null && count >= COLLABORATION_SPACE_DIRECTORY_CAPACITY)
        )
            return failed(invalid().failure)
        if (checkpoint == null || checkpoint.complete) {
            val generation = Uuid.random().toString()
            val begun = directory.begin(access, checkpoint, generation, clock.now())
            currentCoroutineContext().ensureActive()
            val afterBegin = current()
            if (afterBegin is AppResult.Failed) return failed(afterBegin.failure)
            if (begun is AppResult.Failed) return failed(begun.failure)
            if (!(begun as AppResult.Success).value) return failed(conflict().failure)
            val next = directory.checkpoint(access)
            if (next is AppResult.Failed) return failed(next.failure)
            checkpoint = (next as AppResult.Success).value
            if (
                checkpoint == null ||
                    !validCollaborationSpaceDirectoryCheckpoint(checkpoint) ||
                    checkpoint.generation != generation ||
                    checkpoint.revision != 0L
            )
                return failed(conflict().failure)
        }
        val beforeWrite = current()
        if (beforeWrite is AppResult.Failed) return failed(beforeWrite.failure)
        val appended = directory.append(access, checkpoint, page, clock.now())
        currentCoroutineContext().ensureActive()
        val afterWrite = current()
        if (afterWrite is AppResult.Failed) return failed(afterWrite.failure)
        if (appended is AppResult.Failed) return failed(appended.failure)
        if (!(appended as AppResult.Success).value) return failed(conflict().failure)
        val reopened = directory.admit(access, admission.revision)
        currentCoroutineContext().ensureActive()
        val finalOwner = current()
        if (finalOwner is AppResult.Failed) return failed(finalOwner.failure)
        if (reopened is AppResult.Failed) return failed(reopened.failure)
        if ((reopened as AppResult.Success).value) return AppResult.Success(true)
        val latest = directory.admission(access)
        if (latest is AppResult.Success) {
            if (!validCollaborationSpaceDirectoryAdmission(latest.value))
                return failed(invalid().failure)
            latest.value.denial?.let {
                return AppResult.Failed(it)
            }
        }
        return failed((latest as? AppResult.Failed)?.failure ?: conflict().failure)
    }

    private fun invalid() =
        collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )

    private fun conflict() =
        collaborationRecoveryFailure("client.collaboration_directory_changed", FailureKind.Conflict)
}
