package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationOriginalWindow
import dev.fajar.starter.common.result.*
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
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

    fun lateKeyContent() = textContent(files.body + "-late-key", canonical("lateLogicalId"))

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

    /** Compare only the durable receipt identity, without installing source observers. */
    fun retainedReceipt(receipt: CollaborationSpaceRoomKeyReceipt?, previous: JSONObject): String {
        val digest = HrisSpaceIncomingSourcesProofHost.digest(requireNotNull(receipt).receiptId)
        check(digest == previous.getString("receiptDigest"))
        return digest
    }

    /** Independent body/hash assertions over encrypted app storage, including its exact aliases. */
    fun retained(
        snapshot: HrisSpaceIncomingProofHost.Snapshot,
        staged: JSONObject,
        owner: ProtectedHrisProofOwner,
        pendingCompleted: Boolean = false,
    ): JSONObject {
        val ids = staged.getJSONObject("events")
        val originalId = ids.getString("original")
        val editId = ids.getString("latestEdit")
        val redactedId = ids.getString("peer")
        val message = snapshot.page.messages.single()
        check(message.sender == sender && message.logicalId == files.logicalId)
        check(message.primaryEventId == originalId && message.eventIds == listOf(originalId))
        check(!message.redacted && message.text == files.body + "-original")
        check(
            message.contentHash == hash(originalContent()) &&
                message.contentHash == staged.getString("originalHash")
        )
        check(
            snapshot.original ==
                CollaborationHistoryEventFact(originalId, sender, message.timestamp, false)
        )
        val edit = requireNotNull(message.latestEdit).edit
        check(edit.eventId == editId && edit.targetEventId == originalId && edit.sender == sender)
        check(edit.originalLogicalId == files.logicalId && edit.operationId == latestEditId)
        check(
            edit.text == files.body + "-latest" &&
                edit.contentHash == hash(editContent(originalId, latest = true))
        )
        check(edit.contentHash == staged.getString("latestEditHash"))
        check(
            snapshot.replacement ==
                CollaborationReplacementEventFact(editId, sender, edit.timestamp, false)
        )
        check(snapshot.redacted == CollaborationHistoryEventFact(redactedId, recipient, null, true))
        return frontier(
                snapshot.references,
                staged,
                owner,
                pendingCompleted,
                snapshot.physicalProvider,
                snapshot.companyOwned,
            )
            .put("messages", 1)
            .put("originalEventId", originalId)
            .put("replacementEventId", editId)
            .put("redactedEventId", redactedId)
            .put("originalHash", message.contentHash)
            .put("replacementHash", edit.contentHash)
            .put("revisionHash", hash(requireNotNull(snapshot.page.revision)))
            .put("originalTextPreserved", true)
            .put("replacementTextPreserved", true)
            .put("originalSenderTombstone", true)
            .put("noOriginalFactForEdit", true)
            .put("noSpaceSendWork", true)
    }

    private fun frontier(
        references: CollaborationHistoryReferences,
        staged: JSONObject,
        owner: ProtectedHrisProofOwner,
        pendingCompleted: Boolean,
        physicalProvider: CollaborationHistoryProviderIdentity,
        companyOwned: Boolean,
    ): JSONObject {
        val ids = staged.getJSONObject("events")
        val progress = requireNotNull(references.progress)
        val position = requireNotNull(progress.incoming)
        check(progress.exhausted && references.after == null && progress.references in 5..50)
        check(
            physicalProvider ==
                CollaborationHistoryProviderIdentity(
                    owner.currentOwner.companyId,
                    owner.registeredDeviceId,
                )
        )
        check(progress.provider == if (companyOwned) null else physicalProvider)
        if (pendingCompleted) check(references.events.isEmpty())
        else check(progress.references == references.events.size.toLong() + 2)
        check(
            position.until == null &&
                position.pendingAfter == null &&
                position.pendingThrough == null &&
                !position.pendingRetryRequired
        )
        val pending = references.events.map { it.event.eventId }.toSet()
        if (!pendingCompleted) {
            check(
                listOf(ids.getString("original"), ids.getString("firstEdit"), ids.getString("peer"))
                    .all { it in pending }
            )
        }
        check(ids.getString("latestEdit") !in pending && ids.getString("redaction") !in pending)
        val frontier =
            JSONArray(
                listOf(
                    progress.generation,
                    progress.revision,
                    progress.next,
                    progress.references,
                    position.head,
                    position.until,
                    position.pendingAfter,
                    position.pendingThrough,
                    position.pendingRetryRequired,
                    physicalProvider.companyId,
                    physicalProvider.deviceId,
                )
            )
        val pendingFacts = JSONArray()
        for (reference in references.events) {
            check(!reference.resolved)
            pendingFacts.put(
                JSONArray(
                    listOf(
                        reference.sequence,
                        reference.event.eventId,
                        reference.event.sender,
                        reference.event.timestamp,
                        reference.event.type,
                        reference.resolved,
                    )
                )
            )
        }
        return JSONObject()
            .put("providerReferences", progress.references)
            .put("pendingReferences", references.events.size)
            .put("frontierHash", hash(frontier.toString()))
            .put("pendingHash", hash(pendingFacts.toString()))
            .put("physicalProviderPreserved", true)
            .put("providerExhaustedWithPending", !pendingCompleted)
            .also { if (companyOwned) it.put("companyStorage", true) }
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
