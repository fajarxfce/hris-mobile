package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationOriginalQueueMatchTest {
    @Test
    fun originalPhysicalNamespaceAndHistoricalBootstrapAreNotRewrittenToLogicalRuntime() {
        val f = OriginalQueueMatchFixture()
        val oldDevice = f.device.copy(bootstrapOperationId = f.id(92))
        val original =
            f.record.copy(
                state =
                    (f.record.state as CollaborationMessageState.Staged).copy(device = oldDevice)
            )
        val value =
            assertIs<AppResult.Success<CollaborationOriginalQueueMatch>>(
                    f.match(records = listOf(original))
                )
                .value
        assertEquals(f.runtime, value.runtime)
        assertEquals(f.binding, value.binding)
        assertEquals(f.device, value.device)
        assertEquals(original, value.messages.single().record)
        assertEquals(f.original, value.messages.single().original)
        assertNull(value.messages.single().completion)
        assertNotEquals(f.owner.parent.nativeSessionId, value.binding.nativeSessionId)
        assertNotEquals(f.runtime.operationId, oldDevice.bootstrapOperationId)
    }

    @Test
    fun emptyQueueIsOnlyAnEmptyLocalMatchAndCannotConfirmAnAbsentTransaction() {
        val f = OriginalQueueMatchFixture()
        val empty = f.snapshot.copy(original = f.snapshot.original.copy(messages = emptyList()))
        val value =
            assertIs<AppResult.Success<CollaborationOriginalQueueMatch>>(
                    f.match(captured = empty, records = emptyList())
                )
                .value
        assertTrue(value.messages.isEmpty())
        assertIs<AppResult.Failed>(f.match(captured = empty))
        assertIs<AppResult.Failed>(f.match(records = emptyList()))
    }

    @Test
    fun anotherLogicalOwnerPhysicalCompanyDeviceRoomOrIdentityCannotBorrowTheQueue() {
        val f = OriginalQueueMatchFixture()
        for (runtime in
            listOf(
                f.runtime.copy(operationId = f.id(72)),
                f.runtime.copy(deviceId = f.id(62)),
                f.runtime.copy(owner = f.owner.copy(spaceId = f.id(32))),
                f.runtime.copy(
                    owner = f.owner.copy(parent = f.owner.parent.copy(sessionId = "other"))
                ),
            )) assertIs<AppResult.Failed>(f.match(expected = runtime))
        for (physical in
            listOf(
                f.snapshot.original.copy(binding = f.binding.copy(companyId = f.owner.spaceId)),
                f.snapshot.original.copy(binding = f.binding.copy(accountId = f.peer)),
                f.snapshot.original.copy(
                    binding = f.binding.copy(audience = "https://other.test/")
                ),
                f.snapshot.original.copy(device = f.device.copy(deviceId = f.id(62))),
                f.snapshot.original.copy(device = f.device.copy(nativeSessionId = f.id(82))),
                f.snapshot.original.copy(device = f.device.copy(roomId = "!other:example.test")),
            )) assertIs<AppResult.Failed>(f.match(captured = f.snapshot.copy(original = physical)))
        for (identity in
            listOf(
                f.identity.copy(serverName = "other.test"),
                f.identity.copy(legacyCompanyId = f.owner.spaceId),
                f.identity.copy(
                    origin = CollaborationSpaceIdentityOrigin.SPACE,
                    legacyCompanyId = null,
                ),
                f.identity.copy(matrixUserId = "@other:example.test"),
            )) assertIs<AppResult.Failed>(f.match(selected = identity))
        assertIs<AppResult.Failed>(f.match(metadata = f.conversation.copy(room = null)))
    }

    @Test
    fun unknownDuplicateNullLogicalOrOversizedOriginalsNeverYieldAPartialMatch() {
        val f = OriginalQueueMatchFixture()
        for (messages in
            listOf(
                listOf(f.original, f.original),
                listOf(f.original.copy(logicalId = null)),
                listOf(f.original.copy(logicalId = f.id(102))),
                listOf(f.original.copy(eventType = "m.reaction")),
                listOf(f.original.copy(serializedContent = "a".repeat(96_001))),
                List(129) { f.original },
            )) {
            assertIs<AppResult.Failed>(
                f.match(
                    captured =
                        f.snapshot.copy(original = f.snapshot.original.copy(messages = messages))
                )
            )
        }
        val second = f.original.copy(logicalId = f.id(102))
        assertIs<AppResult.Failed>(
            f.match(
                captured =
                    f.snapshot.copy(
                        original = f.snapshot.original.copy(messages = listOf(f.original, second))
                    ),
                records =
                    listOf(f.record, f.record.copy(intent = f.record.intent.copy(id = f.id(102)))),
            )
        )
        assertIs<AppResult.Failed>(f.match(records = listOf(f.record, f.record)))
    }

    @Test
    fun stagingQueuedCancelledMissingOriginOrChangedImmutableCommandRemainUnresolved() {
        val f = OriginalQueueMatchFixture()
        val staged = f.record.state as CollaborationMessageState.Staged
        for (state in
            listOf(
                CollaborationMessageState.Queued,
                CollaborationMessageState.Cancelled,
                CollaborationMessageState.Staging(f.device, emptySet(), f.origin),
                staged.copy(origin = null),
                staged.copy(transactionId = "another-transaction"),
                staged.copy(origin = f.origin.copy(contentHash = "b".repeat(64))),
                staged.copy(device = f.device.copy(nativeSessionId = f.id(82))),
                staged.copy(device = f.device.copy(roomId = "!other:example.test")),
                staged.copy(
                    device =
                        f.device.copy(bootstrapOperationId = "00000000-0000-0000-0000-000000000000")
                ),
            )) assertIs<AppResult.Failed>(f.match(records = listOf(f.record.copy(state = state))))
        for (record in
            listOf(
                f.record.copy(revision = 0),
                f.record.copy(intent = f.record.intent.copy(text = " ")),
                f.record.copy(intent = f.record.intent.copy(conversationId = f.id(52))),
                f.record.copy(
                    intent =
                        f.record.intent.copy(
                            owner = f.record.intent.owner.copy(companyId = f.owner.spaceId)
                        )
                ),
            )) assertIs<AppResult.Failed>(f.match(records = listOf(record)))
    }

    @Test
    fun acceptedOriginalNeedsExactImmutableCompletionWithThePrimaryEventAndAliases() {
        val f = OriginalQueueMatchFixture()
        val sent = f.sent()
        val completion = CollaborationMessageCompletion(sent, listOf("\$alias", "\$accepted"))
        val value =
            assertIs<AppResult.Success<CollaborationOriginalQueueMatch>>(
                    f.match(records = listOf(sent), completions = listOf(completion))
                )
                .value
        assertEquals(completion, value.messages.single().completion)
        assertEquals(sent, value.messages.single().record)
        assertIs<AppResult.Failed>(f.match(records = listOf(sent)))
        for (proof in
            listOf(
                completion.copy(eventIds = emptyList()),
                completion.copy(eventIds = listOf("\$alias")),
                completion.copy(eventIds = listOf("\$accepted", "\$accepted")),
                completion.copy(record = sent.copy(intent = sent.intent.copy(text = "Changed"))),
                completion.copy(record = sent.copy(revision = 4)),
            )) assertIs<AppResult.Failed>(
            f.match(records = listOf(sent), completions = listOf(proof))
        )
        assertIs<AppResult.Failed>(f.match(completions = listOf(completion)))
    }

    @Test
    fun completionAliasesRemainBoundedAndCannotConfirmTwoOriginalCommands() {
        val f = OriginalQueueMatchFixture()
        val first = f.sent()
        val secondOriginal = f.original.copy(transactionId = "second", logicalId = f.id(102))
        val secondStaged =
            f.record.copy(
                intent = f.record.intent.copy(id = f.id(102)),
                state =
                    (f.record.state as CollaborationMessageState.Staged).copy(
                        transactionId = "second"
                    ),
            )
        val second = f.sent(secondStaged)
        assertIs<AppResult.Failed>(
            f.match(
                captured =
                    f.snapshot.copy(
                        original =
                            f.snapshot.original.copy(messages = listOf(f.original, secondOriginal))
                    ),
                records = listOf(first, second),
                completions =
                    listOf(
                        CollaborationMessageCompletion(first, listOf("\$accepted")),
                        CollaborationMessageCompletion(second, listOf("\$accepted")),
                    ),
            )
        )
        assertIs<AppResult.Failed>(
            f.match(
                records = listOf(first),
                completions =
                    listOf(CollaborationMessageCompletion(first, List(129) { "\$event$it" })),
            )
        )
    }

    @Test
    fun matchingDetachesInputCollectionsAndPreservesOriginalQueueOrder() {
        val f = OriginalQueueMatchFixture()
        val sent = f.sent()
        val aliases = mutableListOf("\$accepted", "\$alias")
        val secondOriginal = f.original.copy(logicalId = f.id(102), transactionId = "second")
        val second =
            f.record.copy(
                intent = f.record.intent.copy(id = f.id(102)),
                state =
                    (f.record.state as CollaborationMessageState.Staged).copy(
                        transactionId = "second"
                    ),
            )
        val originals = mutableListOf(secondOriginal, f.original)
        val records = mutableListOf(sent, second)
        val completions = mutableListOf(CollaborationMessageCompletion(sent, aliases))
        val value =
            assertIs<AppResult.Success<CollaborationOriginalQueueMatch>>(
                    f.match(
                        captured =
                            f.snapshot.copy(
                                original = f.snapshot.original.copy(messages = originals)
                            ),
                        records = records,
                        completions = completions,
                    )
                )
                .value
        originals.clear()
        records.clear()
        completions.clear()
        aliases.clear()
        assertEquals(listOf(secondOriginal, f.original), value.messages.map { it.original })
        assertEquals(listOf("\$accepted", "\$alias"), value.messages.last().completion?.eventIds)
        assertEquals("CollaborationOriginalQueueMatch([redacted])", value.toString())
        assertEquals(
            "CollaborationMatchedOriginalMessage([redacted])",
            value.messages.last().toString(),
        )
    }

    @Test
    fun complete128OriginalSnapshotFitsWithoutTruncationOrInferringDelivery() {
        val f = OriginalQueueMatchFixture()
        val originals =
            (1..128).map { f.original.copy(logicalId = f.id(1000 + it), transactionId = "txn-$it") }
        val records =
            originals.map {
                f.record.copy(
                    intent = f.record.intent.copy(id = requireNotNull(it.logicalId)),
                    state =
                        (f.record.state as CollaborationMessageState.Staged).copy(
                            transactionId = it.transactionId
                        ),
                )
            }
        val value =
            assertIs<AppResult.Success<CollaborationOriginalQueueMatch>>(
                    f.match(
                        captured =
                            f.snapshot.copy(
                                original = f.snapshot.original.copy(messages = originals)
                            ),
                        records = records.reversed(),
                    )
                )
                .value
        assertEquals(originals, value.messages.map { it.original })
        assertTrue(
            value.messages.all {
                it.completion == null && it.record.state is CollaborationMessageState.Staged
            }
        )
    }

    @Test
    fun evenMatchingWrongSenderCannotReplaceSelectedOriginalIdentity() {
        val f = OriginalQueueMatchFixture()
        val wrong = f.origin.copy(sender = "@other:example.test")
        assertIs<AppResult.Failed>(
            f.match(
                captured =
                    f.snapshot.copy(
                        original =
                            f.snapshot.original.copy(
                                messages = listOf(f.original.copy(origin = wrong))
                            )
                    ),
                records =
                    listOf(
                        f.record.copy(
                            state =
                                (f.record.state as CollaborationMessageState.Staged).copy(
                                    origin = wrong
                                )
                        )
                    ),
            )
        )
        val nil = "00000000-0000-0000-0000-000000000000"
        assertFalse(
            validCollaborationOriginalQueueRequest(f.runtime.copy(deviceId = nil), f.position)
        )
        assertFalse(
            validCollaborationOriginalQueueRequest(f.runtime.copy(operationId = nil), f.position)
        )
    }
}
