package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageState
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Retains an actually cancelled original beside the independent message under native proof. */
internal class HrisCancelledWorkerIntent(private val files: HrisWorkerProofFiles) {
    private val id =
        UUID.nameUUIDFromBytes((files.logicalId + ":cancelled").toByteArray()).toString()
    private val body = "fictional-hris-worker-cancelled-${files.logicalId}"
    private val digest =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    suspend fun prepare(app: HrisWorkerProofContainer, checkpoint: JSONObject) {
        val frozen = app.freeze(id, body)
        val original = app.queue(id, body)
        check(original.state == CollaborationMessageState.Queued)
        val scheduled = app.scheduledKeys
        app.cancel(original)
        check(app.pending().isEmpty() && app.draft() == frozen)
        check(app.scheduledKeys == scheduled)
        verifyArchive(app)
        identity(checkpoint)
            .put("cancelledDraftRevision", frozen.revision)
            .put("pendingCommandCount", 1)
    }

    suspend fun restore(app: HrisWorkerProofContainer, queued: JSONObject, checkpoint: JSONObject) {
        check(
            queued.getString("cancelledMessageId") == id &&
                queued.getString("cancelledBodySha256") == digest
        )
        val frozen = requireNotNull(app.draft())
        check(frozen.submissionId == id && frozen.text == body && !frozen.reviewRequired)
        check(frozen.revision == queued.getString("cancelledDraftRevision"))
        check(app.pending().size == 1 && queued.getInt("pendingCommandCount") == 1)
        verifyArchive(app)
        identity(checkpoint)
            .put("cancelledDraftRevision", frozen.revision)
            .put("pendingCommandCount", 1)
    }

    suspend fun completed(app: HrisWorkerProofContainer, queued: JSONObject) {
        verifyArchive(app)
        val cleared = requireNotNull(app.draft())
        check(cleared.text.isEmpty() && cleared.submissionId == null && !cleared.reviewRequired)
        check(cleared.revision != queued.getString("cancelledDraftRevision"))
        check(app.history().messages.none { it.logicalId == id || it.text == body })
        check(app.pending().isEmpty())
    }

    fun evidence(result: JSONObject) {
        identity(result)
            .put("cancelledDraftConfirmedCleared", true)
            .put("cancelledAbsentFromHistory", true)
    }

    private suspend fun verifyArchive(app: HrisWorkerProofContainer) {
        val scheduled = app.scheduledKeys
        val archived = requireNotNull(app.message(id))
        check(archived.intent.id == id && archived.intent.text == body)
        check(archived.state == CollaborationMessageState.Cancelled && archived.revision == 0L)
        check(app.completion(id) == null)
        check(app.queue(id, body) == archived)
        check(app.scheduledKeys == scheduled)
    }

    private fun identity(result: JSONObject): JSONObject =
        result
            .put("cancelledMessageId", id)
            .put("cancelledBodySha256", digest)
            .put("cancelledArchiveRetained", true)
            .put("cancelledReceiptAbsent", true)
            .put("cancelledReplayDidNotSchedule", true)
}
