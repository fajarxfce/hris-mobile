package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationOriginalSenderMatchTest {
    private val f = OriginalQueueMatchFixture()

    @Test
    fun exactSenderFactsRetainTheSameWholeOriginalMappingWithoutASelectionObservation() {
        val original = assertIs<AppResult.Success<CollaborationOriginalQueueMatch>>(f.match()).value
        val records =
            assertIs<AppResult.Success<List<CollaborationMatchedOriginalMessage>>>(match()).value
        assertEquals(original.messages, records)
        assertEquals(f.record, records.single().record)
        assertEquals(f.original, records.single().original)
    }

    @Test
    fun foreignSenderAndContradictoryCapturedOriginAreRejected() {
        for (user in
            listOf(
                "@foreign:example.test",
                f.identity.matrixUserId.replace(
                    f.position.companyId.replace("-", ""),
                    "a".repeat(32),
                ),
                f.identity.matrixUserId.replace(
                    f.owner.parent.accountId.replace("-", ""),
                    f.peer.replace("-", ""),
                ),
                f.identity.matrixUserId.replace("example.test", "foreign.test"),
            )) assertIs<AppResult.Failed>(match(user = user))
        val changed = f.original.copy(origin = f.origin.copy(sender = "@foreign:example.test"))
        assertIs<AppResult.Failed>(
            match(
                snapshot =
                    f.snapshot.copy(original = f.snapshot.original.copy(messages = listOf(changed)))
            )
        )
    }

    @Test
    fun missingCompletionAndDuplicateInventoryRemainUnresolved() {
        assertIs<AppResult.Failed>(match(records = listOf(f.sent())))
        assertIs<AppResult.Failed>(
            match(
                snapshot =
                    f.snapshot.copy(
                        original =
                            f.snapshot.original.copy(messages = listOf(f.original, f.original))
                    )
            )
        )
        val sent = f.sent()
        val proof = CollaborationMessageCompletion(sent, listOf("\$accepted"))
        val matched =
            assertIs<AppResult.Success<List<CollaborationMatchedOriginalMessage>>>(
                    match(records = listOf(sent), completions = listOf(proof))
                )
                .value
        assertEquals(proof, matched.single().completion)
    }

    private fun match(
        user: String = f.identity.matrixUserId,
        snapshot: CollaborationSpaceOriginalQueueSnapshot = f.snapshot,
        records: List<CollaborationMessageRecord> = listOf(f.record),
        completions: List<CollaborationMessageCompletion> = emptyList(),
    ) =
        matchCollaborationOriginalMessages(
            f.runtime,
            f.conversation,
            user,
            snapshot,
            records,
            completions,
        )
}
