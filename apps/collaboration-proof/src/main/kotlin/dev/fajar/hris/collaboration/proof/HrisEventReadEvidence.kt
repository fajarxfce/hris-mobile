package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationOriginalWindow
import dev.fajar.starter.common.result.*
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Independent fictional payloads and comparisons; never exposes event content in checkpoints. */
internal class HrisEventReadEvidence(private val files: HrisWorkerProofFiles) {
    private val peerLogicalId = canonical("secondLogicalId")
    private val firstEditId = canonical("firstEditId")
    private val latestEditId = canonical("latestEditId")
    private val sender = files.fixture.getJSONObject("sender").getString("userId")
    private val recipient = files.fixture.getJSONObject("recipient").getString("userId")

    fun originalContent() = textContent(files.body + "-original", files.logicalId)

    fun peerContent() = textContent(files.body + "-peer", peerLogicalId)

    fun editContent(target: String, latest: Boolean): String {
        val body = files.body + if (latest) "-latest" else "-first"
        val operation = if (latest) latestEditId else firstEditId
        return "{\"body\":" +
            JSONObject.quote("* $body") +
            ",\"m.new_content\":" +
            textContent(body, files.logicalId) +
            ",\"m.relates_to\":{\"event_id\":" +
            JSONObject.quote(target) +
            ",\"rel_type\":\"m.replace\"},\"msgtype\":\"m.text\",\"org.hris.edit_id\":\"$operation\"}"
    }

    fun physical(snapshot: CollaborationEventSnapshot, owner: ProtectedHrisProofOwner) {
        check(snapshot.owner == owner.currentOwner)
        check(snapshot.deviceId == owner.registeredDeviceId && snapshot.roomId == files.roomId)
        check(snapshot.roomJoined && snapshot.roomEncrypted)
    }

    fun original(
        snapshot: CollaborationEventSnapshot,
        owner: ProtectedHrisProofOwner,
        id: String,
    ): String {
        physical(snapshot, owner)
        check(snapshot.event.eventId == id && snapshot.event.sender == sender)
        val message = (snapshot.event.content as CollaborationAcquiredEventContent.Original).message
        check(
            message.eventId == id &&
                message.sender == sender &&
                message.logicalId == files.logicalId
        )
        check(
            message.text == files.body + "-original" &&
                message.contentHash == hash(originalContent())
        )
        return message.contentHash
    }

    fun edit(
        snapshot: CollaborationEventSnapshot,
        owner: ProtectedHrisProofOwner,
        id: String,
        target: String,
        latest: Boolean,
    ): String {
        physical(snapshot, owner)
        check(snapshot.event.eventId == id && snapshot.event.sender == sender)
        val edit = (snapshot.event.content as CollaborationAcquiredEventContent.Replacement).edit
        check(edit.eventId == id && edit.sender == sender && edit.targetEventId == target)
        check(edit.operationId == if (latest) latestEditId else firstEditId)
        check(edit.originalLogicalId == files.logicalId)
        check(edit.text == files.body + if (latest) "-latest" else "-first")
        check(edit.contentHash == hash(editContent(target, latest)))
        return edit.contentHash
    }

    fun redacted(
        snapshot: CollaborationEventSnapshot,
        owner: ProtectedHrisProofOwner,
        target: String,
        redaction: String,
    ) {
        physical(snapshot, owner)
        check(snapshot.event.eventId == target && snapshot.event.sender == recipient)
        check(
            (snapshot.event.content as CollaborationAcquiredEventContent.Redacted)
                .redactionEventId == redaction
        )
    }

    fun redaction(
        snapshot: CollaborationEventSnapshot,
        owner: ProtectedHrisProofOwner,
        id: String,
        target: String,
    ) {
        physical(snapshot, owner)
        check(
            snapshot.event.eventId == id && snapshot.event.sender == sender && sender != recipient
        )
        check(
            (snapshot.event.content as CollaborationAcquiredEventContent.Redaction).targetEventId ==
                target
        )
    }

    fun window(
        result: AppResult<CollaborationOriginalWindow>,
        owner: ProtectedHrisProofOwner,
        originalId: String,
        editId: String,
        latest: Boolean,
    ): JSONObject {
        val window = (result as AppResult.Success).value
        check(
            validCollaborationOriginalWindow(
                window,
                owner.currentOwner,
                owner.registeredDeviceId,
                files.roomId,
                editId,
            )
        )
        val anchor = requireNotNull(window.acquiredAnchor)
        val editHash = edit(anchor, owner, editId, originalId, latest)
        val message =
            window.changes.filterIsInstance<CollaborationHistoryChange.Text>().single {
                it.eventId == originalId
            }
        check(message.sender == sender && message.logicalId == files.logicalId)
        check(
            message.text == files.body + "-original" &&
                message.contentHash == hash(originalContent())
        )
        check(window.edits.any { it.eventId == editId && it.contentHash == editHash })
        check(window.changes.none { it.eventId == editId } && editId !in window.unresolvedEventIds)
        return JSONObject()
            .put("kind", "Success")
            .put("code", "none")
            .put("originals", window.changes.size)
            .put("anchorIncluded", false)
    }

    private fun canonical(name: String) =
        files.fixture.getString(name).also {
            require(UUID.fromString(it).toString() == it && it != files.logicalId)
        }

    /** Sorted JSON with an unknown nested extension, independent of the production mapper. */
    private fun textContent(body: String, logical: String) =
        "{\"body\":" +
            JSONObject.quote(body) +
            ",\"msgtype\":\"m.text\",\"org.hris.message_id\":\"$logical\",\"org.hris.proof_extension\":{\"marker\":\"kept\"}}"

    private fun hash(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) {
            "%02x".format(it)
        }
}
