package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class SpaceInboxEditOutcomeFixture(val company: Boolean) {
    val base = SpaceEditInboxAdmissionFixture(legacy = true)
    val companyAccess = base.thread.commands.originalAccess()
    val prepared =
        assertIs<AppResult.Success<CollaborationSpacePreparedEdit>>(
                prepareCollaborationSpaceInboxEdit(
                    base.access,
                    base.request(),
                    base.original.sender,
                    base.now,
                    base.history,
                    base.source,
                    base.current,
                    CollaborationSpaceInboxActivities(base.current.version, listOf(base.activity)),
                    emptyList(),
                    companyAccess,
                    base.thread.room.position.conversationId,
                    emptyList(),
                )
            )
            .value
    val operation = prepared.intent.id
    val origin = CollaborationMessageOrigin(base.original.sender, "b".repeat(64))
    val device =
        CollaborationMessageDevice(
            "e9000000-0000-4000-8000-000000000001",
            "e9000000-0000-4000-8000-000000000002",
            "e9000000-0000-4000-8000-000000000003",
            base.location.roomId,
        )
    var group: CollaborationSpaceMessageRecord? =
        CollaborationSpaceMessageRecord(
            prepared.intent,
            3,
            CollaborationMessageState.Sent(device, "txn", "\$replacement", origin),
        )
    var companyRecord: CollaborationMessageRecord? =
        CollaborationMessageRecord(
            CollaborationMessageIntent(
                operation,
                companyAccess.messageOwner(),
                base.thread.room.position.conversationId,
                prepared.intent.createdAt,
                prepared.intent.text,
                prepared.intent.replyToEventId,
                prepared.intent.forwarded,
                prepared.intent.editTarget,
            ),
            3,
            group!!.state,
        )
    var activity =
        prepared.publication.activities.single().let { value ->
            if (company)
                value.copy(
                    pendingEdit =
                        value.pendingEdit!!.copy(
                            source =
                                CollaborationSpaceInboxEditSource.Company(
                                    companyAccess.companyId,
                                    base.thread.room.position.conversationId,
                                    operation,
                                )
                        )
                )
            else value
        }
    var original: CollaborationHistoryMessage? = base.original
    var evidence =
        listOf(
            CollaborationEditEvidence(
                "\$replacement",
                origin.sender,
                base.target.eventId,
                base.original.timestamp + 1,
                origin.contentHash,
                operation,
                eligible = true,
                redacted = false,
            )
        )

    fun result() =
        if (company)
            collaborationSpaceInboxEditOutcome(
                companyAccess,
                activity,
                original,
                companyRecord,
                evidence,
            )
        else
            collaborationSpaceInboxEditOutcome(
                base.access,
                base.conversationId,
                activity,
                original,
                group,
                evidence,
            )

    fun outcome() = assertIs<AppResult.Success<CollaborationEditOutcomeKind>>(result()).value
}
