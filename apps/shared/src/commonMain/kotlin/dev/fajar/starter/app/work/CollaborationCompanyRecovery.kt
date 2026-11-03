package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.CollaborationBackgroundAdmission
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** Each lane receives a finite turn even if the other fails or retains a long queue. */
class CollaborationCompanyRecovery(
    private val incoming: suspend (CollaborationBackgroundAdmission) -> SyncResult,
    private val outgoing: suspend (CollaborationBackgroundAdmission) -> SyncResult,
    private val push: suspend (CollaborationBackgroundAdmission) -> SyncResult,
) {
    suspend operator fun invoke(admission: CollaborationBackgroundAdmission): SyncResult {
        currentCoroutineContext().ensureActive()
        val received = turn("incoming") { incoming(admission) }
        currentCoroutineContext().ensureActive()
        val sent = turn("outgoing") { outgoing(admission) }
        currentCoroutineContext().ensureActive()
        val registered = turn("push_registration") { push(admission) }
        currentCoroutineContext().ensureActive()
        return combineCollaborationRecovery(
            combineCollaborationRecovery(received, sent),
            registered,
        )
    }

    private suspend fun turn(lane: String, action: suspend () -> SyncResult): SyncResult =
        withTimeoutOrNull(15.seconds) { action() }
            ?: SyncResult.Retry(
                Failure(
                    FailureKind.Timeout,
                    "Chat recovery is pending.",
                    code = "client.collaboration_${lane}_timeout",
                )
            )
}

internal fun combineCollaborationRecovery(incoming: SyncResult, outgoing: SyncResult): SyncResult {
    val results = listOf(incoming, outgoing)
    return results.filterIsInstance<SyncResult.Retry>().firstOrNull { it.failure != null }
        ?: results.filterIsInstance<SyncResult.Retry>().firstOrNull()
        ?: results.filterIsInstance<SyncResult.Blocked>().firstOrNull()
        ?: SyncResult.Complete
}
