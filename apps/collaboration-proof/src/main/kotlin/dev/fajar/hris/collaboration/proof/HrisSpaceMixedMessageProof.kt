package dev.fajar.hris.collaboration.proof

import android.os.Process
import dev.fajar.starter.collaboration.domain.entities.*
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Clock
import org.json.JSONArray
import org.json.JSONObject

/** Optional new fictional Space original beside the unchanged original-company experiment. */
internal class HrisSpaceMixedMessageProof(
    private val files: HrisWorkerProofFiles,
    private val current: () -> CollaborationOwner,
) {
    private val id =
        if (files.fixture.has("spaceLogicalId"))
            files.fixture.getString("spaceLogicalId").also {
                check(
                    UUID.fromString(it).toString() == it &&
                        it != files.logicalId &&
                        it != files.fixture.getString("secondLogicalId")
                )
            }
        else null

    val enabled
        get() = id != null

    private fun scope(): CollaborationSpaceOwner {
        val physical = current()
        val group = files.read("original-group", 16_384)
        return CollaborationSpaceOwner(
            CollaborationNativeParent(
                physical.sessionId,
                physical.audience,
                physical.accountId,
                physical.nativeSessionId,
            ),
            group.getString("spaceId"),
        )
    }

    private fun conversationId() = files.read("original-group", 16_384).getString("conversationId")

    private fun retained() = files.read("original-space-staged", 16_384)

    suspend fun stage(
        host: HrisWorkerProofContainer,
        ready: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
    ) {
        val logical = id ?: return
        check(ready.owner == scope() && conversation.id == conversationId())
        val local = ready.owner.localDirectoryAccess()
        val submission =
            CollaborationSpaceMessageIntent(
                logical,
                local.messageOwner(),
                conversation.id,
                Clock.System.now(),
                files.body + "-2",
            )
        val queued = host.queueSpace(local, submission)
        check(queued.intent == submission)
        check(queued.intent.owner == ready.owner.localDirectoryAccess().messageOwner())
        val record = host.stageSpace(ready, conversation, logical)
        val state = record.state as CollaborationMessageState.Staged
        val capture = host.originalQueue(ready, files.roomId)
        val previous = files.read("original-staged", 16_384).getJSONArray("originals")
        val oldIds =
            (0 until previous.length()).map { previous.getJSONObject(it).getString("logicalId") }
        check(
            capture.original.messages.size == 3 &&
                capture.original.messages.map { it.logicalId }.toSet() == (oldIds + logical).toSet()
        )
        for (index in 0 until previous.length()) {
            val old = previous.getJSONObject(index)
            val original =
                capture.original.messages.single { it.logicalId == old.getString("logicalId") }
            check(
                original.transactionId == old.getString("transactionId") &&
                    original.origin.contentHash == old.getString("originHash") &&
                    original.origin.sender == old.getString("sender")
            )
        }
        val original = capture.original.messages.single { it.logicalId == logical }
        check(
            original.transactionId == state.transactionId &&
                original.origin == state.origin &&
                capture.original.device == state.device
        )
        check(host.spaceReceipts(ready.owner.localDirectoryAccess(), conversation.id).isEmpty())
        assertIsolation(host)
        files.write(
            "original-space-staged",
            stagingCheckpoint(record).put("completeOriginalCount", capture.original.messages.size),
        )
    }

    /** Leave the original intent Queued; only the registered worker may prepare and stage it. */
    suspend fun queueForWorker(
        host: HrisWorkerProofContainer,
        conversation: CollaborationSpaceConversation,
    ) {
        val logical = requireNotNull(id)
        check(conversation.owner == scope() && conversation.id == conversationId())
        val local = scope().localDirectoryAccess()
        val submission =
            CollaborationSpaceMessageIntent(
                logical,
                local.messageOwner(),
                conversation.id,
                Clock.System.now(),
                files.body + "-2",
            )
        val record = host.queueSpace(local, submission)
        check(record.intent == submission)
        check(record.state == CollaborationMessageState.Queued)
        check(record.intent.owner == scope().localDirectoryAccess().messageOwner())
        assertIsolation(host)
        files.write(
            "original-space-queued",
            JSONObject()
                .put("processId", Process.myPid())
                .put("logicalId", logical)
                .put("conversationId", conversation.id)
                .put("createdAt", record.intent.createdAt.toString())
                .put("textHash", hash(record.intent.text)),
        )
    }

    /** Runs after actual committed staging; storage only while the SDK writer is held. */
    suspend fun retainWorkerStaged(
        host: HrisWorkerProofContainer,
        record: CollaborationSpaceMessageRecord,
    ) {
        val logical = requireNotNull(id)
        val queued = files.read("original-space-queued", 16_384)
        check(
            record.intent.id == logical &&
                record.intent.owner == scope().localDirectoryAccess().messageOwner()
        )
        check(
            record.intent.conversationId == conversationId() &&
                record.state is CollaborationMessageState.Staged
        )
        check(record.intent.createdAt.toString() == queued.getString("createdAt"))
        check(hash(record.intent.text) == queued.getString("textHash"))
        check(host.spaceMessage(scope().localDirectoryAccess(), logical) == record)
        val previous = files.read("original-staged", 16_384).getJSONArray("originals")
        check(previous.length() == 2)
        for (index in 0 until previous.length()) {
            val old = previous.getJSONObject(index)
            val retained = requireNotNull(host.message(old.getString("logicalId")))
            val state = retained.state as CollaborationMessageState.Staged
            check(state.transactionId == old.getString("transactionId"))
            check(state.origin?.contentHash == old.getString("originHash"))
        }
        check(host.spaceReceipts(scope().localDirectoryAccess(), conversationId()).isEmpty())
        assertIsolation(host)
        files.write(
            "original-space-staged",
            stagingCheckpoint(record)
                .put("retainedOriginalCount", 3)
                .put("workerStaged", true)
                .put("unboundQueued", true),
        )
    }

    private fun stagingCheckpoint(record: CollaborationSpaceMessageRecord): JSONObject {
        val state = record.state as CollaborationMessageState.Staged
        return JSONObject()
            .put("processId", Process.myPid())
            .put("logicalId", record.intent.id)
            .put("spaceId", scope().spaceId)
            .put("conversationId", conversationId())
            .put("transactionId", state.transactionId)
            .put("originHash", requireNotNull(state.origin).contentHash)
            .put("sender", requireNotNull(state.origin).sender)
            .put("textHash", hash(record.intent.text))
            .put("nativeSessionId", state.device.nativeSessionId)
            .put("deviceId", state.device.deviceId)
            .put("bootstrapOperationId", state.device.bootstrapOperationId)
            .put("companyPartitionEmpty", true)
    }

    fun transaction(): String? = id?.let { retained().getString("transactionId") }

    suspend fun assertUnacknowledged(host: HrisWorkerProofContainer) {
        if (id == null) return
        check(receiptCount(host) == 0 && record(host)?.state is CollaborationMessageState.Staged)
    }

    private suspend fun record(host: HrisWorkerProofContainer): CollaborationSpaceMessageRecord? =
        id?.let { host.spaceMessage(scope().localDirectoryAccess(), it) }

    suspend fun state(host: HrisWorkerProofContainer): String? =
        if (id == null) null
        else
            when (record(host)?.state) {
                is CollaborationMessageState.Sent -> "SENT"
                is CollaborationMessageState.Staged -> "STAGED"
                else -> "OTHER"
            }

    suspend fun receiptCount(host: HrisWorkerProofContainer): Int =
        if (id == null) 0
        else host.spaceReceipts(scope().localDirectoryAccess(), conversationId()).size

    suspend fun confirmed(host: HrisWorkerProofContainer): JSONObject? {
        val logical = id ?: return null
        val previous = retained()
        val record = requireNotNull(record(host))
        val sent = record.state as CollaborationMessageState.Sent
        check(
            record.intent.id == logical &&
                record.intent.owner == scope().localDirectoryAccess().messageOwner() &&
                record.intent.conversationId == conversationId()
        )
        check(
            sent.transactionId == previous.getString("transactionId") &&
                sent.device.nativeSessionId == previous.getString("nativeSessionId") &&
                sent.device.deviceId == previous.getString("deviceId") &&
                sent.device.bootstrapOperationId == previous.getString("bootstrapOperationId")
        )
        check(
            requireNotNull(sent.origin).sender == previous.getString("sender") &&
                requireNotNull(sent.origin).contentHash == previous.getString("originHash") &&
                hash(record.intent.text) == previous.getString("textHash")
        )
        val receipt = host.spaceReceipts(scope().localDirectoryAccess(), conversationId()).single()
        check(
            receipt.intent == record.intent &&
                receipt.transactionId == sent.transactionId &&
                sent.eventId in receipt.eventIds
        )
        assertIsolation(host)
        return JSONObject()
            .put("logicalId", logical)
            .put("transactionId", sent.transactionId)
            .put("events", JSONArray(receipt.eventIds))
    }

    private suspend fun assertIsolation(host: HrisWorkerProofContainer) {
        val logical = id ?: return
        check(host.message(logical) == null)
        for (old in listOf(files.logicalId, files.fixture.getString("secondLogicalId"))) check(
            host.spaceMessage(scope().localDirectoryAccess(), old) == null
        )
    }

    suspend fun offline(host: HrisWorkerProofContainer): Int {
        if (id == null) return 0
        confirmed(host)
        return 1
    }

    private fun hash(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
