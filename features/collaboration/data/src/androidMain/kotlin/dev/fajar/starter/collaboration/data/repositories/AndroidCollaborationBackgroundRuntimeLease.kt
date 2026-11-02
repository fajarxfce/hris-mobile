package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease
import dev.fajar.starter.common.result.*
import kotlin.time.TimeMark
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * A finite reference owns its timer; the registry continues owning every unfinished native drain.
 */
internal class AndroidCollaborationBackgroundRuntimeLease(
    private val registry: SdkRuntimeRegistry<AndroidCollaborationRuntimeLease>,
    private val borrow: SdkRuntimeBorrow<AndroidCollaborationRuntimeLease>,
    private val deadline: TimeMark,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CollaborationBackgroundRuntimeLease {
    override val owner = borrow.reservation.owner
    override val deviceId = borrow.reservation.deviceId
    override val interruptions = borrow.failures
    override val credentialFailures
        get() = checkNotNull(borrow.reservation.resource).credentialFailures

    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + dispatcher)
    private val expiration =
        scope.launch {
            try {
                val remaining = (-deadline.elapsedNow()).inWholeMilliseconds.coerceAtLeast(0)
                val reason =
                    withTimeoutOrNull(remaining) { interruptions.filterNotNull().first() }
                        ?: backgroundRuntimeExpired()
                release(reason)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The native registry retains failed cleanup; a later explicit open/drain can
                // retry.
            } finally {
                lifetime.complete()
            }
        }

    override fun publish(): AppResult<Unit> {
        if (deadline.hasPassedNow()) {
            borrow.failure.compareAndSet(null, backgroundRuntimeExpired())
            return AppResult.Failed(checkNotNull(interruptions.value))
        }
        return registry.publish(borrow) { it.publish() }
    }

    override suspend fun close(): AppResult<Unit> =
        withContext(NonCancellable) {
            try {
                expiration.cancelAndJoin()
                release(
                    Failure(
                        FailureKind.Unavailable,
                        "Chat recovery has ended.",
                        code = "client.collaboration_background_closed",
                    )
                )
            } finally {
                lifetime.complete()
            }
        }

    private suspend fun release(reason: Failure): AppResult<Unit> =
        registry.releaseBorrow(borrow, reason)?.close() ?: AppResult.Success(Unit)
}

internal fun backgroundRuntimeExpired() =
    Failure(
        FailureKind.Unavailable,
        "Chat recovery requires current access.",
        code = "client.collaboration_revalidation_required",
    )
