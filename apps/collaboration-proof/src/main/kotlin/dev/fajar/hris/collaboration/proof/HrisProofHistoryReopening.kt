package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReopenCollaborationHistory
import dev.fajar.starter.common.result.*
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Only the dedicated fictional fixture fills native focus slots and reopens its protected owner.
 */
internal class HrisProofHistoryReopening(
    private val repository: CollaborationRuntimeRepository,
    private val original: AndroidCollaborationRuntimeLease,
    private val roomId: String,
    private val conversationId: String,
    private val app: HrisProofApplicationAccess,
    private val lists: CollaborationListRepository,
) {
    suspend fun verify(anchor: String, text: String, heldRoom: String, heldTransaction: String) {
        val keys = original.proofKeyFingerprint()
        // The original-history proof already retained one focus. Failed context attempts also
        // consume slots because the SDK can retain an uncertain acquisition internally.
        repeat(15) { index ->
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest("fictional-history-$index".toByteArray())
            val absent =
                "\$" + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(digest)
            val rejected = original.readOriginalHistory(roomId, absent)
            check(
                rejected is AppResult.Failed &&
                    rejected.failure.code != "client.collaboration_history_focus_capacity"
            )
        }
        val full = original.readOriginalHistory(roomId, anchor)
        check(
            full is AppResult.Failed &&
                full.failure.code == "client.collaboration_history_focus_capacity"
        )
        var replacement: AndroidCollaborationRuntimeLease? = null
        val access =
            object : CollaborationRuntimeRepository by repository {
                override suspend fun restore(
                    owner: CollaborationOwner,
                    deviceId: String,
                ): AppResult<CollaborationRuntimeLease?> =
                    repository.restore(owner, deviceId).also {
                        if (it is AppResult.Success)
                            replacement = it.value as? AndroidCollaborationRuntimeLease
                    }
            }
        val reopen =
            ReopenCollaborationHistory(
                access,
                lists,
                app.conversations,
                app.setup,
                app.sessions,
                app.workspaces,
                app.server,
                app.build,
                app.clock,
            )
        try {
            val writer = success(original.acquireMessageQueue(heldRoom))
            try {
                check(success(writer.snapshot()) == setOf(heldTransaction))
                val busy = reopen(app.workspaceId, original.deviceId, conversationId, anchor)
                check(
                    busy is AppResult.Failed &&
                        busy.failure.code == "client.collaboration_queue_busy"
                )
                check(success(repository.isOpen(original.owner, original.deviceId)))
                check(replacement == null && success(writer.snapshot()) == setOf(heldTransaction))
            } finally {
                success(writer.close())
            }
            val reopened = awaitReopen(reopen, anchor)
            check(
                reopened.owner == original.owner &&
                    reopened.value == CollaborationHistoryRuntimePreparation.Reopened
            )
            val current = requireNotNull(replacement)
            check(current !== original && current.proofKeyFingerprint() == keys)
            check(current.installedOperationId == original.installedOperationId)
            check(original.readOriginalHistory(roomId, anchor) is AppResult.Failed)
            val available =
                success(reopen(app.workspaceId, original.deviceId, conversationId, anchor))
            check(
                available.value == CollaborationHistoryRuntimePreparation.Available &&
                    replacement === current
            )
            val history = success(current.readOriginalHistory(roomId, anchor))
            check(
                history.originals.filterIsInstance<CollaborationHistoryChange.Text>().any {
                    it.eventId == anchor && it.text == text
                }
            )
            val held = success(current.acquireMessageQueue(heldRoom))
            try {
                check(success(held.snapshot()) == setOf(heldTransaction))
            } finally {
                success(held.close())
            }
            requireNotNull(current.nativeClient.getRoom(heldRoom)).use {
                check(!it.isSendQueueEnabled())
            }
            check(current.credentialFailures.value == null)
        } finally {
            withContext(NonCancellable) { replacement?.closeProofRuntime() }
        }
    }

    private suspend fun awaitReopen(
        reopen: ReopenCollaborationHistory,
        anchor: String,
    ): CollaborationRead<CollaborationHistoryRuntimePreparation> {
        // Explicit fixture attempts model retry after a bounded close wait, as in the
        // earlier protected-store proof. The product operation itself never retries.
        repeat(6) { attempt ->
            val result = reopen(app.workspaceId, original.deviceId, conversationId, anchor)
            if (
                result is AppResult.Failed &&
                    result.failure.code == "client.collaboration_close_pending" &&
                    attempt < 5
            )
                return@repeat
            return success(result)
        }
        error("Owned history reopening attempts exhausted")
    }

    private fun <T> success(result: AppResult<T>): T =
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failed -> throw HrisProofFailure(result.failure)
        }
}
