package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationOriginalAcknowledgement
import kotlin.test.*

class CollaborationOriginalAcknowledgementTest {
    @Test
    fun persistedSentCanUseItsExactJournalBeforeArchiveRetirement() {
        val f = OriginalQueueMatchFixture()
        val record = f.sent()
        val journal = receipt(record, listOf("\$accepted"))
        assertEquals(
            CollaborationMessageCompletion(record, listOf("\$accepted")),
            collaborationOriginalAcknowledgement(record, journal, null),
        )
        assertNull(collaborationOriginalAcknowledgement(record, null, null))
        assertNull(collaborationOriginalAcknowledgement(f.record, journal, null))
    }

    @Test
    fun archiveKeepsOriginalPrimaryWhenJournalContainsOnlyALaterAlias() {
        val f = OriginalQueueMatchFixture()
        val record = f.sent()
        val archived = CollaborationMessageCompletion(record, listOf("\$accepted"))
        val journal = receipt(record, listOf("\$later"))
        assertEquals(archived, collaborationOriginalAcknowledgement(record, null, archived))
        assertEquals(
            CollaborationMessageCompletion(record, listOf("\$accepted", "\$later")),
            collaborationOriginalAcknowledgement(record, journal, archived),
        )
        assertNull(collaborationOriginalAcknowledgement(record, journal, null))
    }

    @Test
    fun foreignImmutableReceiptOrArchiveNeverRepairsACommand() {
        val f = OriginalQueueMatchFixture()
        val record = f.sent()
        val journal = receipt(record, listOf("\$accepted"))
        val archived = CollaborationMessageCompletion(record, listOf("\$accepted"))
        val invalid =
            listOf(
                journal.copy(intent = journal.intent.copy(text = "Changed")),
                journal.copy(intent = journal.intent.copy(id = f.id(999))),
                journal.copy(device = journal.device.copy(deviceId = f.id(999))),
                journal.copy(transactionId = "another-transaction"),
                journal.copy(eventIds = listOf("\$accepted", "\$accepted")),
                journal.copy(eventIds = listOf("not-an-event")),
            )
        for (value in invalid) {
            assertNull(collaborationOriginalAcknowledgement(record, value, null))
            assertNull(collaborationOriginalAcknowledgement(record, value, archived))
        }
        assertNull(
            collaborationOriginalAcknowledgement(
                record,
                journal,
                archived.copy(record = record.copy(revision = record.revision + 1)),
            )
        )
    }

    @Test
    fun combinedAliasesStayBoundedAndInputsRemainUnchanged() {
        val f = OriginalQueueMatchFixture()
        val record = f.sent()
        val original = mutableListOf("\$accepted")
        val archived = CollaborationMessageCompletion(record, original)
        val alias = receipt(record, listOf("\$later", "\$accepted"))
        val evidence = assertNotNull(collaborationOriginalAcknowledgement(record, alias, archived))
        original.clear()
        assertEquals(listOf("\$accepted", "\$later"), evidence.eventIds)
        val many = receipt(record, (1..128).map { "\$alias-$it" })
        assertNull(
            collaborationOriginalAcknowledgement(
                record,
                many,
                CollaborationMessageCompletion(record, listOf("\$accepted")),
            )
        )
    }

    private fun receipt(
        record: CollaborationMessageRecord,
        ids: List<String>,
    ): CollaborationMessageReceipt {
        val sent = record.state as CollaborationMessageState.Sent
        return CollaborationMessageReceipt(record.intent, sent.device, sent.transactionId, ids)
    }
}
