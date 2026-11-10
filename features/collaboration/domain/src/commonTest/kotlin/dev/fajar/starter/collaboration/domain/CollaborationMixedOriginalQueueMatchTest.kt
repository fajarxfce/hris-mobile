package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.matchCollaborationMixedOriginalQueue
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationMixedOriginalQueueMatchTest {
    @Test
    fun mixedCapturePreservesBothLogicalOwnersAndPhysicalOrder() {
        val f = Fixture()
        val value =
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(f.match()).value
        assertEquals(f.base.queue.runtime, value.runtime)
        assertEquals(f.base.conversation, value.conversation)
        assertEquals(f.base.queue.binding, value.binding)
        assertEquals(f.base.queue.device, value.device)
        assertEquals(
            f.base.queue.record,
            assertIs<CollaborationMatchedQueueMessage.Company>(value.messages[0]).record,
        )
        assertEquals(
            f.record,
            assertIs<CollaborationMatchedQueueMessage.Space>(value.messages[1]).record,
        )
        val reversed =
            f.snapshot.copy(
                original =
                    f.snapshot.original.copy(messages = f.snapshot.original.messages.reversed())
            )
        assertEquals(
            value.messages.reversed(),
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(
                    f.match(snapshot = reversed)
                )
                .value
                .messages,
        )
    }

    @Test
    fun emptyAndSingleLedgerCapturesKeepCompleteInventoryRequirements() {
        val f = Fixture()
        assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(
            f.match(snapshot = f.base.queue.snapshot, space = emptyList())
        )
        assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(
            f.match(
                snapshot =
                    f.snapshot.copy(
                        original = f.snapshot.original.copy(messages = listOf(f.original))
                    ),
                company = emptyList(),
            )
        )
        val empty = f.snapshot.copy(original = f.snapshot.original.copy(messages = emptyList()))
        assertTrue(
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(
                    f.match(snapshot = empty, company = emptyList(), space = emptyList())
                )
                .value
                .messages
                .isEmpty()
        )
        assertIs<AppResult.Failed>(f.match(space = emptyList()))
    }

    @Test
    fun aLogicalIdCannotBelongToBothLedgers() {
        val f = Fixture()
        assertIs<AppResult.Failed>(
            f.match(
                space =
                    listOf(
                        f.record.copy(
                            intent = f.record.intent.copy(id = f.base.queue.record.intent.id)
                        )
                    )
            )
        )
        assertIs<AppResult.Failed>(
            f.match(company = listOf(f.base.queue.record, f.base.queue.record), space = emptyList())
        )
    }

    @Test
    fun missingOrForeignLogicalRecordsCannotMatchOnlyPartOfTheQueue() {
        val f = Fixture()
        assertIs<AppResult.Failed>(f.match(company = emptyList()))
        assertIs<AppResult.Failed>(
            f.match(
                space =
                    listOf(f.record.copy(intent = f.record.intent.copy(id = f.base.queue.id(999))))
            )
        )
        assertIs<AppResult.Failed>(f.match(company = emptyList(), space = emptyList()))
    }

    @Test
    fun spaceCommandsCannotBeRetargetedToAnotherAccountAudienceOrSpace() {
        val f = Fixture()
        val owner = f.record.intent.owner
        for (other in
            listOf(
                owner.copy(accountId = f.base.queue.id(999)),
                owner.copy(spaceId = f.base.queue.id(999)),
                owner.copy(audience = "https://another.example.test/"),
            )) {
            assertIs<AppResult.Failed>(
                f.match(space = listOf(f.record.copy(intent = f.record.intent.copy(owner = other))))
            )
        }
    }

    @Test
    fun canonicalConversationAndOriginalCompanyCoordinatesRemainDistinct() {
        val f = Fixture()
        assertNotEquals(f.record.intent.conversationId, f.base.queue.record.intent.conversationId)
        assertIs<AppResult.Failed>(
            f.match(
                space =
                    listOf(
                        f.record.copy(
                            intent =
                                f.record.intent.copy(
                                    conversationId = f.base.queue.position.conversationId
                                )
                        )
                    )
            )
        )
        val wrong =
            f.base.queue.record.copy(
                intent = f.base.queue.record.intent.copy(conversationId = f.base.conversation.id)
            )
        assertIs<AppResult.Failed>(f.match(company = listOf(wrong)))
    }

    @Test
    fun queuedStagingAndCancelledDoNotBecomeNativeOriginals() {
        val f = Fixture()
        for (state in
            listOf(
                CollaborationMessageState.Queued,
                CollaborationMessageState.Cancelled,
                CollaborationMessageState.Staging(
                    f.base.queue.device,
                    emptySet(),
                    f.base.queue.origin,
                ),
            )) {
            val revision = if (state is CollaborationMessageState.Staging) 1L else 0L
            assertIs<AppResult.Failed>(
                f.match(space = listOf(f.record.copy(revision = revision, state = state)))
            )
        }
    }

    @Test
    fun originalTransactionAndDeviceCannotBeReplacedByLogicalRuntimeIds() {
        val f = Fixture()
        val staged = f.record.state as CollaborationMessageState.Staged
        for (state in
            listOf(
                staged.copy(transactionId = "different"),
                staged.copy(device = staged.device.copy(deviceId = f.base.queue.runtime.deviceId)),
                staged.copy(device = staged.device.copy(nativeSessionId = f.base.queue.id(999))),
                staged.copy(
                    device = staged.device.copy(bootstrapOperationId = f.base.queue.id(999))
                ),
                staged.copy(device = staged.device.copy(roomId = "!other:example.test")),
            )) {
            assertIs<AppResult.Failed>(f.match(space = listOf(f.record.copy(state = state))))
        }
    }

    @Test
    fun missingOrChangedContentOriginCannotAdoptAnOriginal() {
        val f = Fixture()
        val staged = f.record.state as CollaborationMessageState.Staged
        for (origin in
            listOf(
                null,
                f.base.queue.origin.copy(sender = "@other:example.test"),
                f.base.queue.origin.copy(contentHash = "b".repeat(64)),
            )) {
            assertIs<AppResult.Failed>(
                f.match(space = listOf(f.record.copy(state = staged.copy(origin = origin))))
            )
        }
    }

    @Test
    fun exactCompletionIsRequiredForEverySentOriginal() {
        val f = Fixture()
        val sent = f.sent()
        assertIs<AppResult.Failed>(f.match(space = listOf(sent)))
        val proof = CollaborationSpaceMessageCompletion(sent, listOf("\$space-accepted", "\$alias"))
        val result =
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(
                    f.match(space = listOf(sent), spaceCompleted = listOf(proof))
                )
                .value
        assertEquals(
            proof,
            assertIs<CollaborationMatchedQueueMessage.Space>(result.messages.last()).completion,
        )
        assertIs<AppResult.Failed>(f.match(spaceCompleted = listOf(proof)))
        assertIs<AppResult.Failed>(
            f.match(
                space = listOf(sent),
                spaceCompleted = listOf(proof.copy(eventIds = listOf("\$other"))),
            )
        )
        assertIs<AppResult.Failed>(
            f.match(
                space = listOf(sent),
                spaceCompleted = listOf(proof.copy(record = sent.copy(revision = 4))),
            )
        )
    }

    @Test
    fun primaryAndAliasEventsCannotBeSharedAcrossLedgerTypes() {
        val f = Fixture()
        val old = f.base.queue.sent(event = "\$company-accepted")
        val sent = f.sent()
        for (shared in listOf("\$company-accepted", "\$space-accepted", "\$alias")) {
            val companyProof =
                CollaborationMessageCompletion(old, listOf("\$company-accepted", shared).distinct())
            val spaceProof =
                CollaborationSpaceMessageCompletion(
                    sent,
                    listOf("\$space-accepted", shared).distinct(),
                )
            assertIs<AppResult.Failed>(
                f.match(
                    company = listOf(old),
                    companyCompleted = listOf(companyProof),
                    space = listOf(sent),
                    spaceCompleted = listOf(spaceProof),
                )
            )
        }
    }

    @Test
    fun completionAliasListsAreDetachedFromCallerMutation() {
        val f = Fixture()
        val sent = f.sent()
        val events = mutableListOf("\$space-accepted", "\$alias")
        val proof = CollaborationSpaceMessageCompletion(sent, events)
        val result =
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(
                    f.match(space = listOf(sent), spaceCompleted = listOf(proof))
                )
                .value
        events.clear()
        assertEquals(
            listOf("\$space-accepted", "\$alias"),
            assertIs<CollaborationMatchedQueueMessage.Space>(result.messages.last())
                .completion!!
                .eventIds,
        )
    }

    @Test
    fun oversizedOrDuplicateProviderInventoryFailsBeforeMatchingSubsets() {
        val f = Fixture()
        for (originals in
            listOf(
                List(129) { f.original },
                listOf(
                    f.base.queue.original,
                    f.original.copy(transactionId = f.base.queue.original.transactionId),
                ),
                listOf(
                    f.base.queue.original,
                    f.original.copy(logicalId = f.base.queue.original.logicalId),
                ),
            )) {
            assertIs<AppResult.Failed>(
                f.match(
                    snapshot =
                        f.snapshot.copy(original = f.snapshot.original.copy(messages = originals))
                )
            )
        }
    }

    @Test
    fun foreignRuntimeOrRoomCannotUseLocallyValidRecords() {
        val f = Fixture()
        assertIs<AppResult.Failed>(
            f.match(
                snapshot =
                    f.snapshot.copy(
                        runtime = f.base.queue.runtime.copy(operationId = f.base.queue.id(999))
                    )
            )
        )
        assertIs<AppResult.Failed>(
            f.match(
                snapshot =
                    f.snapshot.copy(
                        original =
                            f.snapshot.original.copy(
                                device = f.base.queue.device.copy(roomId = "!other:example.test")
                            )
                    )
            )
        )
    }

    @Test
    fun physicalOwnerIsNotChangedWhenOnlySpaceMessagesRemain() {
        val f = Fixture()
        val capture =
            f.snapshot.copy(original = f.snapshot.original.copy(messages = listOf(f.original)))
        val value =
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(
                    f.match(snapshot = capture, company = emptyList())
                )
                .value
        assertEquals(f.base.queue.binding, value.binding)
        assertEquals(f.base.queue.device, value.device)
        assertEquals(
            f.base.queue.origin,
            assertIs<CollaborationMatchedQueueMessage.Space>(value.messages.single())
                .record
                .state
                .origin,
        )
    }

    @Test
    fun aCompleteInventoryAllows128DistinctOriginalsButNot129() {
        val f = Fixture()
        for (count in listOf(128, 129)) {
            val originals =
                (1..count).map { index ->
                    f.original.copy(
                        transactionId = "space-$index",
                        logicalId = f.base.queue.id(1000 + index),
                    )
                }
            val records =
                originals.map {
                    f.record.copy(
                        intent = f.record.intent.copy(id = requireNotNull(it.logicalId)),
                        state =
                            CollaborationMessageState.Staged(
                                f.base.queue.device,
                                it.transactionId,
                                it.origin,
                            ),
                    )
                }
            val snapshot =
                f.snapshot.copy(original = f.snapshot.original.copy(messages = originals))
            val result = f.match(snapshot = snapshot, company = emptyList(), space = records)
            if (count == 128)
                assertEquals(
                    128,
                    assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(result)
                        .value
                        .messages
                        .size,
                )
            else assertIs<AppResult.Failed>(result)
        }
    }

    @Test
    fun completionAliasesAreBoundedAcrossTheCompleteCapture() {
        val f = Fixture()
        for (count in listOf(4, 5)) {
            val originals =
                (1..count).map { index ->
                    f.original.copy(
                        transactionId = "space-$index",
                        logicalId = f.base.queue.id(1000 + index),
                    )
                }
            val records =
                originals.mapIndexed { index, original ->
                    f.record.copy(
                        intent = f.record.intent.copy(id = requireNotNull(original.logicalId)),
                        revision = 3,
                        state =
                            CollaborationMessageState.Sent(
                                f.base.queue.device,
                                original.transactionId,
                                "\$event-$index-1",
                                original.origin,
                            ),
                    )
                }
            val completions =
                records.mapIndexed { index, record ->
                    CollaborationSpaceMessageCompletion(
                        record,
                        (1..if (index < 4) 128 else 1).map { "\$event-$index-$it" },
                    )
                }
            val snapshot =
                f.snapshot.copy(original = f.snapshot.original.copy(messages = originals))
            val result =
                f.match(
                    snapshot = snapshot,
                    company = emptyList(),
                    space = records,
                    spaceCompleted = completions,
                )
            if (count == 4)
                assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(result)
            else assertIs<AppResult.Failed>(result)
        }
    }

    private class Fixture {
        val base = OriginalTransportFixture()
        val original =
            base.queue.original.copy(
                transactionId = "space-transaction",
                logicalId = base.queue.id(301),
            )
        val record =
            CollaborationSpaceMessageRecord(
                CollaborationSpaceMessageIntent(
                    requireNotNull(original.logicalId),
                    base.queue.owner.localDirectoryAccess().messageOwner(),
                    base.conversation.id,
                    base.queue.record.intent.createdAt,
                    base.queue.record.intent.text,
                ),
                2,
                CollaborationMessageState.Staged(
                    base.queue.device,
                    original.transactionId,
                    original.origin,
                ),
            )
        val snapshot =
            base.queue.snapshot.copy(
                original =
                    base.queue.snapshot.original.copy(
                        messages = listOf(base.queue.original, original)
                    )
            )

        fun sent() =
            record.copy(
                revision = 3,
                state =
                    CollaborationMessageState.Sent(
                        base.queue.device,
                        original.transactionId,
                        "\$space-accepted",
                        original.origin,
                    ),
            )

        fun match(
            snapshot: CollaborationSpaceOriginalQueueSnapshot = this.snapshot,
            company: List<CollaborationMessageRecord> = listOf(base.queue.record),
            companyCompleted: List<CollaborationMessageCompletion> = emptyList(),
            space: List<CollaborationSpaceMessageRecord> = listOf(record),
            spaceCompleted: List<CollaborationSpaceMessageCompletion> = emptyList(),
        ) =
            matchCollaborationMixedOriginalQueue(
                base.queue.runtime,
                base.conversation,
                base.binding,
                snapshot,
                company,
                companyCompleted,
                space,
                spaceCompleted,
            )
    }
}
