package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class SpaceInboxEditOutcomePolicyTest {
    @Test
    fun sentCommandsWithoutReplacementEvidenceRemainAwaitingProvider() {
        for (company in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            f.evidence = emptyList()
            assertEquals(CollaborationEditOutcomeKind.AWAITING_PROVIDER, f.outcome())
            assertIs<CollaborationMessageState.Sent>(f.group!!.state)
        }
    }

    @Test
    fun actualProviderFactsDistinguishObservedRejectedAndRemovedReplacements() {
        for (company in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            assertEquals(CollaborationEditOutcomeKind.OBSERVED, f.outcome())
            f.evidence = f.evidence.map { it.copy(eligible = false) }
            assertEquals(CollaborationEditOutcomeKind.NOT_APPLIED, f.outcome())
            f.evidence = f.evidence.map { it.copy(redacted = true) }
            assertEquals(CollaborationEditOutcomeKind.OBSERVED, f.outcome())
        }
    }

    @Test
    fun mixedAliasesRequireAnEligibleAliasOrEveryAliasToBeRedacted() {
        for (company in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            val original = f.evidence.single()
            f.evidence =
                listOf(
                    original.copy(eligible = false, redacted = true),
                    original.copy(eventId = "\$other", eligible = false),
                )
            assertEquals(CollaborationEditOutcomeKind.NOT_APPLIED, f.outcome())
            f.evidence = f.evidence + original.copy(eventId = "\$eligible")
            assertEquals(CollaborationEditOutcomeKind.OBSERVED, f.outcome())
        }
    }

    @Test
    fun mismatchedOperationSenderTargetHashAndOriginalAliasCannotSettleAnEdit() {
        for (company in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            val fact = f.evidence.single()
            for (bad in
                listOf(
                    fact.copy(operationId = null),
                    fact.copy(operationId = "e9000000-0000-4000-8000-000000000099"),
                    fact.copy(sender = "@another:example.test"),
                    fact.copy(targetEventId = "\$elsewhere"),
                    fact.copy(contentHash = "c".repeat(64)),
                    fact.copy(eventId = f.original!!.primaryEventId),
                    fact.copy(eventId = "invalid"),
                    fact.copy(timestamp = -1),
                )) {
                f.evidence = listOf(bad)
                assertIs<AppResult.Failed>(f.result())
            }
        }
    }

    @Test
    fun duplicateAndOverflowingAliasesAreNeverTreatedAsPartialSuccess() {
        for (company in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            val fact = f.evidence.single()
            f.evidence = listOf(fact, fact)
            assertIs<AppResult.Failed>(f.result())
            f.evidence = (1..128).map { fact.copy(eventId = "${'$'}alias-$it") }
            assertEquals(CollaborationEditOutcomeKind.OBSERVED, f.outcome())
            f.evidence = f.evidence + fact
            assertIs<AppResult.Failed>(f.result())
        }
    }

    @Test
    fun missingCommandsCannotEstablishAnOutcomeFromQueueAbsence() {
        for (company in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            f.group = null
            f.companyRecord = null
            assertIs<AppResult.Failed>(f.result())
            f.evidence = emptyList()
            assertEquals(CollaborationEditOutcomeKind.AWAITING_PROVIDER, f.outcome())
        }
    }

    @Test
    fun unstampedQueuedCommandsCannotBorrowProviderEvidence() {
        for (company in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            f.group = f.group!!.copy(revision = 0, state = CollaborationMessageState.Queued)
            f.companyRecord =
                f.companyRecord!!.copy(revision = 0, state = CollaborationMessageState.Queued)
            assertIs<AppResult.Failed>(f.result())
            f.evidence = emptyList()
            assertEquals(CollaborationEditOutcomeKind.AWAITING_PROVIDER, f.outcome())
        }
    }

    @Test
    fun aNewerPendingPointerCannotBeSettledByTheOlderOperationsFacts() {
        for (company in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            val pending = f.activity.pendingEdit!!
            val next = "e9000000-0000-4000-8000-000000000099"
            val source =
                when (val previous = pending.source) {
                    is CollaborationSpaceInboxEditSource.Group -> previous.copy(id = next)
                    is CollaborationSpaceInboxEditSource.Company -> previous.copy(id = next)
                }
            f.activity = f.activity.copy(pendingEdit = pending.copy(source = source))
            assertIs<AppResult.Failed>(f.result())
        }
    }

    @Test
    fun commandsFromAnotherOwnerOrConversationCannotSettleTheCurrentPreview() {
        for (company in listOf(false, true)) for (change in 0..1) {
            val f = SpaceInboxEditOutcomeFixture(company)
            val foreign = "e9000000-0000-4000-8000-000000000099"
            if (company) {
                val record = f.companyRecord!!
                f.companyRecord =
                    record.copy(
                        intent =
                            if (change == 0)
                                record.intent.copy(
                                    owner = record.intent.owner.copy(accountId = foreign)
                                )
                            else record.intent.copy(conversationId = foreign)
                    )
            } else {
                val record = f.group!!
                f.group =
                    record.copy(
                        intent =
                            if (change == 0)
                                record.intent.copy(
                                    owner = record.intent.owner.copy(accountId = foreign)
                                )
                            else record.intent.copy(conversationId = foreign)
                    )
            }
            assertIs<AppResult.Failed>(f.result())
        }
    }

    @Test
    fun oldUnmarkedGroupCommandsAndDifferentPublicationRevisionsDoNotProveTheOverlay() {
        for (change in 0..2) {
            val f = SpaceInboxEditOutcomeFixture(false)
            val intent = f.group!!.intent
            val origin = intent.inboxOrigin!!
            f.group =
                f.group!!.copy(
                    intent =
                        when (change) {
                            0 -> intent.copy(inboxOrigin = null)
                            1 ->
                                intent.copy(
                                    inboxOrigin =
                                        origin.copy(
                                            publishedAtRevision = origin.publishedAtRevision + 1
                                        )
                                )
                            else -> intent.copy(createdAt = intent.createdAt + 1.milliseconds)
                        }
                )
            assertIs<AppResult.Failed>(f.result())
        }
    }

    @Test
    fun contradictoryRedactionFactsCannotComeFromOneAcceptedHistoryRevision() {
        for (company in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            val fact = f.evidence.single()
            f.evidence = listOf(fact.copy(redacted = true))
            assertIs<AppResult.Failed>(f.result())
            f.evidence = listOf(fact)
            f.original = f.original!!.copy(redacted = true, text = null)
            assertIs<AppResult.Failed>(f.result())
        }
    }

    @Test
    fun providerObservationNeverRestoresHiddenOrDeletedPreviewContent() {
        for (company in listOf(false, true)) for (hidden in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            f.original =
                if (hidden) f.original!!.copy(locallyHidden = true)
                else f.original!!.copy(redacted = true, text = null)
            f.activity =
                f.activity.copy(
                    preview =
                        if (hidden) null
                        else
                            f.activity.preview!!.copy(
                                kind = CollaborationSpaceInboxContent.DELETED,
                                text = null,
                            )
                )
            if (!hidden) f.evidence = f.evidence.map { it.copy(eligible = false, redacted = true) }
            val before = f.activity
            assertEquals(CollaborationEditOutcomeKind.OBSERVED, f.outcome())
            assertEquals(before, f.activity)
        }
    }

    @Test
    fun unrelatedOrMissingOriginalsCannotSettleTheReplacement() {
        for (company in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            f.original = f.original!!.copy(contentHash = "c".repeat(64))
            assertIs<AppResult.Failed>(f.result())
            f.original = null
            assertIs<AppResult.Failed>(f.result())
            f.evidence = emptyList()
            assertEquals(CollaborationEditOutcomeKind.AWAITING_PROVIDER, f.outcome())
        }
    }

    @Test
    fun matchingVisibleTextAloneNeverSubstitutesForExactProviderFacts() {
        for (company in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            val old = f.original!!
            f.original =
                old.copy(
                    latestEdit =
                        CollaborationMessageRevision(
                            CollaborationMessageEdit(
                                "\$visible",
                                old.sender,
                                old.primaryEventId,
                                old.timestamp + 1,
                                f.activity.preview!!.text!!,
                                "b".repeat(64),
                                f.operation,
                                old.logicalId,
                                old.replyToEventId,
                                old.forwarded,
                            ),
                            old.timestamp,
                        )
                )
            f.evidence = emptyList()
            assertEquals(CollaborationEditOutcomeKind.AWAITING_PROVIDER, f.outcome())
        }
    }

    @Test
    fun aDifferentOptimisticBodyCannotBeAcknowledgedThroughAnOlderCommand() {
        for (company in listOf(false, true)) {
            val f = SpaceInboxEditOutcomeFixture(company)
            f.activity =
                f.activity.copy(preview = f.activity.preview!!.copy(text = "Later optimistic edit"))
            assertIs<AppResult.Failed>(f.result())
        }
    }
}
