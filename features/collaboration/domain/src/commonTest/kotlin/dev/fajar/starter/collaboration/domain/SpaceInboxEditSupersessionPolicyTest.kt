package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class SpaceInboxEditSupersessionPolicyTest {
    @Test
    fun newerGroupAndCompanyCompletionsSupersedeEitherNamespaceWithoutProviderClockComparison() {
        for (oldCompany in listOf(false, true)) for (newCompany in listOf(false, true)) {
            val f = Fixture(oldCompany, newCompany)
            assertTrue(f.visible.timestamp < f.base.activity.pendingEdit!!.createdAt)
            assertEquals(AppResult.Success(true), f.result())
        }
    }

    @Test
    fun twoMatchingNamespacesOrNoCompletionCannotSelectASuccessor() {
        val f = Fixture(false, false)
        f.company = f.companyCompletion
        assertEquals(AppResult.Success(false), f.result())
        f.group = null
        f.company = null
        assertEquals(AppResult.Success(false), f.result())
    }

    @Test
    fun equalOrEarlierLocalActionsCannotSupersedeALaterPendingEdit() {
        for (company in listOf(false, true)) for (offset in listOf(-1, 0)) {
            val f = Fixture(false, company)
            val time =
                Instant.fromEpochMilliseconds(f.base.activity.pendingEdit!!.createdAt + offset)
            f.group =
                f.group?.let {
                    it.copy(
                        record = it.record.copy(intent = it.record.intent.copy(createdAt = time))
                    )
                }
            f.company =
                f.company?.let {
                    it.copy(
                        record = it.record.copy(intent = it.record.intent.copy(createdAt = time))
                    )
                }
            assertEquals(AppResult.Success(false), f.result())
        }
    }

    @Test
    fun anotherPhysicalRoomCannotSupersedeTheVisibleOriginal() {
        for (company in listOf(false, true)) {
            val f = Fixture(false, company)
            val room = "!different:example.test"
            val state = f.sent.copy(device = f.sent.device.copy(roomId = room))
            val target = f.base.base.target.copy(roomId = room)
            f.group =
                f.group?.let {
                    it.copy(
                        record =
                            it.record.copy(
                                intent =
                                    it.record.intent.copy(editTarget = target, inboxOrigin = null),
                                state = state,
                            )
                    )
                }
            f.company =
                f.company?.let {
                    it.copy(
                        record =
                            it.record.copy(
                                intent = it.record.intent.copy(editTarget = target),
                                state = state,
                            )
                    )
                }
            assertEquals(AppResult.Success(false), f.result())
        }
    }

    @Test
    fun differentVisibleContentOrUnobservedReceiptAliasesDoNotProveSupersession() {
        for (company in listOf(false, true)) for (change in 0..2) {
            val f = Fixture(false, company)
            val edit =
                when (change) {
                    0 -> f.visible.copy(contentHash = "d".repeat(64))
                    1 -> f.visible.copy(text = "Another replacement body")
                    else -> f.visible.copy(eventId = "\$unretained-alias")
                }
            f.original =
                f.original.copy(
                    latestEdit = CollaborationMessageRevision(edit, f.original.timestamp)
                )
            assertEquals(AppResult.Success(false), f.result())
        }
    }

    @Test
    fun foreignAccountsAndWrongExactLookupIdsFailWithoutChoosingAnotherNamespace() {
        for (company in listOf(false, true)) for (change in 0..1) {
            val f = Fixture(false, company)
            val other = "e9000000-0000-4000-8000-000000000088"
            if (company) {
                val c = f.company!!
                f.company =
                    c.copy(
                        record =
                            c.record.copy(
                                intent =
                                    if (change == 0)
                                        c.record.intent.copy(
                                            owner = c.record.intent.owner.copy(accountId = other)
                                        )
                                    else c.record.intent.copy(id = other)
                            )
                    )
            } else {
                val c = f.group!!
                f.group =
                    c.copy(
                        record =
                            c.record.copy(
                                intent =
                                    if (change == 0)
                                        c.record.intent.copy(
                                            owner = c.record.intent.owner.copy(accountId = other)
                                        )
                                    else c.record.intent.copy(id = other)
                            )
                    )
            }
            assertIs<AppResult.Failed>(f.result())
        }
    }

    @Test
    fun anUnconfirmedCommandIsNotACompletionEvenWhenItsTextIsVisible() {
        for (company in listOf(false, true)) {
            val f = Fixture(false, company)
            f.group =
                f.group?.let {
                    it.copy(
                        record =
                            it.record.copy(revision = 0, state = CollaborationMessageState.Queued)
                    )
                }
            f.company =
                f.company?.let {
                    it.copy(
                        record =
                            it.record.copy(revision = 0, state = CollaborationMessageState.Queued)
                    )
                }
            assertIs<AppResult.Failed>(f.result())
        }
    }

    @Test
    fun anOlderRevisionWithoutAVisibleReplacementCannotReleasePendingText() {
        val f = Fixture(false, false)
        f.original = f.original.copy(latestEdit = null)
        f.group = null
        assertEquals(AppResult.Success(false), f.result())
    }

    private class Fixture(oldCompany: Boolean, newCompany: Boolean) {
        val base = SpaceInboxEditOutcomeFixture(oldCompany)
        val id = "e9000000-0000-4000-8000-000000000077"
        val sent =
            CollaborationMessageState.Sent(
                base.device,
                "newer",
                "\$newer",
                base.origin.copy(contentHash = "c".repeat(64)),
            )
        val groupCompletion =
            CollaborationSpaceMessageCompletion(
                base.group!!.let {
                    it.copy(
                        intent =
                            it.intent.copy(
                                id = id,
                                createdAt = it.intent.createdAt + 1.milliseconds,
                                text = "Newer edit",
                                inboxOrigin = null,
                            ),
                        state = sent,
                    )
                },
                listOf(sent.eventId),
            )
        val companyCompletion =
            CollaborationMessageCompletion(
                base.companyRecord!!.let {
                    it.copy(
                        intent =
                            it.intent.copy(
                                id = id,
                                createdAt = it.intent.createdAt + 1.milliseconds,
                                text = "Newer edit",
                            ),
                        state = sent,
                    )
                },
                listOf(sent.eventId),
            )
        val visible =
            base.original!!.let { old ->
                CollaborationMessageEdit(
                    sent.eventId,
                    old.sender,
                    old.primaryEventId,
                    old.timestamp + 1,
                    "Newer edit",
                    sent.origin!!.contentHash,
                    id,
                    old.logicalId,
                    old.replyToEventId,
                    old.forwarded,
                )
            }
        var original =
            base.original!!.let {
                it.copy(latestEdit = CollaborationMessageRevision(visible, it.timestamp))
            }
        var group: CollaborationSpaceMessageCompletion? = groupCompletion.takeUnless { newCompany }
        var company: CollaborationMessageCompletion? = companyCompletion.takeIf { newCompany }

        fun result() =
            collaborationSpaceInboxEditSuperseded(
                base.base.access,
                base.base.conversationId,
                base.activity,
                original,
                group,
                base.companyAccess,
                base.base.thread.room.position.conversationId,
                company,
            )
    }
}
