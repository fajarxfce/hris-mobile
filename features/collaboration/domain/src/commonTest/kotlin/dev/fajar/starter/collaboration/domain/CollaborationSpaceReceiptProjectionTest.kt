package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationSpaceReceiptProjectionTest {
    private val f = SpaceReceiptProjectionFixture()

    @Test
    fun exactSentProofCanComeFromJournalArchiveOrBothButNeverAbsence() {
        for ((journal, archive) in
            listOf(f.original to null, null to f.completion, f.original to f.completion)) {
            assertEquals(
                f.completion,
                collaborationSpaceOriginalAcknowledgement(f.access, f.sent, journal, archive),
            )
        }
        assertNull(collaborationSpaceOriginalAcknowledgement(f.access, f.sent, null, null))
        assertNull(collaborationSpaceOriginalAcknowledgement(f.access, f.staged, f.original, null))
    }

    @Test
    fun archiveAllowsLateAliasWithoutReplacingItsAuthenticatedPrimary() {
        val late = f.original.copy(eventIds = listOf("\$late"))
        assertNull(collaborationSpaceOriginalAcknowledgement(f.access, f.sent, late, null))
        val merged =
            assertNotNull(
                collaborationSpaceOriginalAcknowledgement(f.access, f.sent, late, f.completion)
            )
        assertEquals(f.sent, merged.record)
        assertEquals(f.original.eventIds + late.eventIds, merged.eventIds)
        assertEquals(merged, collaborationSpaceCompletionWithReceipt(f.access, f.completion, late))
    }

    @Test
    fun changedOwnerContentMappingOriginOrRevisionCannotAdoptCompletion() {
        val candidates =
            listOf(
                f.completion.copy(record = f.sent.copy(revision = 4)),
                f.completion.copy(
                    record = f.sent.copy(intent = f.sent.intent.copy(text = "changed"))
                ),
                f.completion.copy(
                    record =
                        f.sent.copy(
                            state =
                                (f.sent.state as CollaborationMessageState.Sent).copy(
                                    transactionId = "other"
                                )
                        )
                ),
                f.completion.copy(eventIds = listOf("\$other")),
            )
        for (archive in candidates) assertNull(
            collaborationSpaceOriginalAcknowledgement(f.access, f.sent, f.original, archive)
        )
        for (access in
            listOf(
                f.access.copy(spaceId = SpaceReceiptProjectionFixture.id(8)),
                f.access.copy(accountId = SpaceReceiptProjectionFixture.id(8)),
                f.access.copy(audience = "https://other.example.test/"),
            )) {
            assertNull(
                collaborationSpaceOriginalAcknowledgement(access, f.sent, f.original, f.completion)
            )
        }
        for (receipt in
            listOf(
                f.original.copy(origin = f.origin.copy(contentHash = "b".repeat(64))),
                f.original.copy(
                    device = f.device.copy(deviceId = SpaceReceiptProjectionFixture.id(8))
                ),
                f.original.copy(transactionId = "other"),
            )) {
            assertNull(collaborationSpaceCompletedReceiptState(f.access, f.completion, receipt))
        }
    }

    @Test
    fun completionUnionIsBoundedAndDetached() {
        val events = mutableListOf("\$event-1")
        val archive = f.completion.copy(eventIds = events)
        val aliases = (2..128).map { "\$event-$it" }.toMutableList()
        val merged =
            assertNotNull(
                collaborationSpaceCompletionWithReceipt(
                    f.access,
                    archive,
                    f.original.copy(eventIds = aliases),
                )
            )
        val receipt = assertNotNull(collaborationSpaceCompletionReceipt(f.access, archive))
        events.clear()
        aliases.clear()
        assertEquals(128, merged.eventIds.size)
        assertEquals(f.original.eventIds, receipt.eventIds)
        assertNull(
            collaborationSpaceCompletionWithReceipt(
                f.access,
                merged,
                f.original.copy(eventIds = listOf("\$overflow")),
            )
        )
    }

    @Test
    fun stagedAndPreviouslySentFactsPreserveJournalOrder() {
        val other = f.receipt(2)
        val records = listOf(f.staged(other), f.sent)
        val plan =
            assertNotNull(
                collaborationSpaceReceiptProjection(
                    f.access,
                    f.conversation,
                    records,
                    listOf(f.original, other),
                    listOf(f.completion),
                )
            )
        assertEquals(listOf(f.original.intent.id, other.intent.id), plan.map { it.first.intent.id })
        assertEquals(f.sent.state, plan[0].second)
        assertEquals("\$event-2", plan[1].second.eventId)
    }

    @Test
    fun validEmptyJournalIsNoWorkAndMalformedScopeStillFails() {
        assertEquals(
            emptyList(),
            collaborationSpaceReceiptProjection(
                f.access,
                f.conversation,
                emptyList(),
                emptyList(),
                emptyList(),
            ),
        )
        assertNull(
            collaborationSpaceReceiptProjection(
                f.access,
                "invalid",
                emptyList(),
                emptyList(),
                emptyList(),
            )
        )
        assertNull(
            collaborationSpaceReceiptProjection(
                f.access.copy(sessionId = ""),
                f.conversation,
                emptyList(),
                emptyList(),
                emptyList(),
            )
        )
    }

    @Test
    fun everyReceiptNeedsExactlyOneUnchangedOriginalBeforeAnyProjection() {
        for (records in
            listOf(
                emptyList(),
                listOf(f.staged, f.staged),
                listOf(
                    f.staged.copy(
                        intent = f.staged.intent.copy(id = SpaceReceiptProjectionFixture.id(8))
                    )
                ),
                listOf(f.staged.copy(intent = f.staged.intent.copy(text = "changed"))),
            )) {
            assertNull(
                collaborationSpaceReceiptProjection(
                    f.access,
                    f.conversation,
                    records,
                    listOf(f.original),
                    emptyList(),
                )
            )
        }
        assertNull(
            collaborationSpaceReceiptProjection(
                f.access,
                f.conversation,
                listOf(f.staged),
                listOf(f.original, f.original),
                emptyList(),
            )
        )
    }

    @Test
    fun queuedCancelledAndUnresolvedStagingNeverBecomeSent() {
        val records =
            listOf(
                f.staged.copy(revision = 0, state = CollaborationMessageState.Queued),
                f.staged.copy(revision = 0, state = CollaborationMessageState.Cancelled),
                f.staged.copy(
                    revision = 1,
                    state = CollaborationMessageState.Staging(f.device, emptySet(), f.origin),
                ),
                f.staged.copy(revision = 9_007_199_254_740_991L),
            )
        for (record in records) assertNull(
            collaborationSpaceReceiptProjection(
                f.access,
                f.conversation,
                listOf(record),
                listOf(f.original),
                emptyList(),
            )
        )
    }

    @Test
    fun unrelatedDuplicateOrWrongRevisionArchiveRejectsTheWholePlan() {
        for (proofs in
            listOf(
                listOf(f.completion, f.completion),
                listOf(f.completion.copy(record = f.sent.copy(revision = 4))),
                listOf(
                    f.completion.copy(
                        record =
                            f.sent.copy(
                                intent =
                                    f.sent.intent.copy(id = SpaceReceiptProjectionFixture.id(8))
                            )
                    )
                ),
            )) {
            assertNull(
                collaborationSpaceReceiptProjection(
                    f.access,
                    f.conversation,
                    listOf(f.sent),
                    listOf(f.original),
                    proofs,
                )
            )
        }
    }

    @Test
    fun lateAliasUsesExactArchiveAndKeepsOriginalSentState() {
        val journal = listOf(f.original.copy(eventIds = listOf("\$late")))
        assertNull(
            collaborationSpaceReceiptProjection(
                f.access,
                f.conversation,
                listOf(f.sent),
                journal,
                emptyList(),
            )
        )
        assertEquals(
            listOf(f.sent to assertIs<CollaborationMessageState.Sent>(f.sent.state)),
            collaborationSpaceReceiptProjection(
                f.access,
                f.conversation,
                listOf(f.sent),
                journal,
                listOf(f.completion),
            ),
        )
    }

    @Test
    fun archiveAliasesCannotAlsoBelongToAnotherJournalOriginal() {
        val other = f.receipt(2)
        val archive = f.completion.copy(eventIds = f.original.eventIds + other.eventIds)
        assertNull(
            collaborationSpaceReceiptProjection(
                f.access,
                f.conversation,
                listOf(f.sent, f.staged(other)),
                listOf(f.original, other),
                listOf(archive),
            )
        )
    }

    @Test
    fun entireOriginalAndAliasInventoryHonorsExactUpperBounds() {
        val journal = (1..128).map(f::receipt)
        assertEquals(
            128,
            assertNotNull(
                    collaborationSpaceReceiptProjection(
                        f.access,
                        f.conversation,
                        journal.map(f::staged),
                        journal,
                        emptyList(),
                    )
                )
                .size,
        )
        val overflow = journal + f.receipt(129)
        assertNull(
            collaborationSpaceReceiptProjection(
                f.access,
                f.conversation,
                overflow.map(f::staged),
                overflow,
                emptyList(),
            )
        )
        val full =
            (1..4).map { n -> f.receipt(n).copy(eventIds = (1..128).map { "\$event-$n-$it" }) }
        assertEquals(
            4,
            assertNotNull(
                    collaborationSpaceReceiptProjection(
                        f.access,
                        f.conversation,
                        full.map(f::staged),
                        full,
                        emptyList(),
                    )
                )
                .size,
        )
        val extra = full + f.receipt(5)
        assertNull(
            collaborationSpaceReceiptProjection(
                f.access,
                f.conversation,
                extra.map(f::staged),
                extra,
                emptyList(),
            )
        )
    }

    @Test
    fun archivedAliasesCountTowardTheWholeProjectionLimit() {
        val journal = (1..5).map(f::receipt)
        val records =
            journal.map { receipt ->
                f.staged(receipt)
                    .copy(
                        revision = 3,
                        state =
                            CollaborationMessageState.Sent(
                                receipt.device,
                                receipt.transactionId,
                                receipt.eventIds.first(),
                                receipt.origin,
                            ),
                    )
            }
        val archives =
            records.mapIndexed { index, record ->
                CollaborationSpaceMessageCompletion(
                    record,
                    journal[index].eventIds + (1..127).map { "\$archive-$index-$it" },
                )
            }
        assertEquals(
            4,
            assertNotNull(
                    collaborationSpaceReceiptProjection(
                        f.access,
                        f.conversation,
                        records.take(4),
                        journal.take(4),
                        archives.take(4),
                    )
                )
                .size,
        )
        assertNull(
            collaborationSpaceReceiptProjection(
                f.access,
                f.conversation,
                records,
                journal,
                archives,
            )
        )
    }

    @Test
    fun reauthenticatedOwnedFactsNeedNoNativeCredentialAgeOrCompany() {
        val access = f.access.copy(sessionId = "replacement")
        assertEquals(
            listOf(f.staged to assertIs<CollaborationMessageState.Sent>(f.sent.state)),
            collaborationSpaceReceiptProjection(
                access,
                f.conversation,
                listOf(f.staged),
                listOf(f.original),
                emptyList(),
            ),
        )
    }
}
