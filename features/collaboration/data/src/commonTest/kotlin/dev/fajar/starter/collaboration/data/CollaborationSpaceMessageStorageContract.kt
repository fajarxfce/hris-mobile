@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationMessageRepository
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceInboxRepairProgressRepository
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceMessageRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

abstract class CollaborationSpaceMessageStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private fun CollaborationHistoryFixture.local() =
        CollaborationSpaceLocalAccess(
            access.sessionId,
            access.audience,
            access.accountId,
            historyId(9200),
        )

    private fun intent(access: CollaborationSpaceLocalAccess, index: Int = 1) =
        CollaborationSpaceMessageIntent(
            historyId(index),
            access.messageOwner(),
            historyId(9201),
            Instant.fromEpochMilliseconds(1_793_836_800_000L + index),
            " Original message $index\n",
            "\$reply",
        )

    private fun CollaborationHistoryFixture.messages(store: CommandStore = database.commands) =
        EncryptedCollaborationSpaceMessageRepository(
            store,
            cipher,
            commandKeys,
            database.replicas,
            keys,
        )

    private suspend fun CollaborationHistoryFixture.readMessage(
        id: String,
        access: CollaborationSpaceLocalAccess = local(),
    ) =
        assertNotNull(
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord?>>(
                    messages().get(access, id)
                )
                .value
        )

    private suspend fun CollaborationHistoryFixture.rawMessages(
        access: CollaborationSpaceLocalAccess = local()
    ) =
        database.commands
            .observeAll(spaceMessageScope(access), spaceMessagePartition(access))
            .first()

    private suspend fun CollaborationHistoryFixture.retainSentRecord(
        value: CollaborationSpaceMessageIntent
    ): CollaborationSpaceMessageRecord {
        val repo = messages()
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            repo.insert(local(), value),
        )
        var previous = readMessage(value.id)
        val device =
            CollaborationMessageDevice(
                historyId(9300),
                historyId(9301),
                historyId(9302),
                "!space:matrix.example.test",
            )
        val origin = CollaborationMessageOrigin("@fictional:matrix.example.test", "b".repeat(64))
        for (next in
            listOf(
                CollaborationMessageState.Staging(device, emptySet(), origin),
                CollaborationMessageState.Staged(device, "txn-${value.id}", origin),
                CollaborationMessageState.Sent(
                    device,
                    "txn-${value.id}",
                    "\$sent-${value.id}",
                    origin,
                ),
            )) {
            assertEquals(AppResult.Success(true), repo.update(local(), previous, next))
            previous = readMessage(value.id)
        }
        return previous
    }

    private fun recipientIntent(access: CollaborationSpaceLocalAccess, index: Int = 1) =
        intent(access, index)
            .copy(
                replyToEventId = null,
                recipientOrigin =
                    CollaborationSpaceRecipientOrigin(
                        historyId(9400),
                        Instant.parse("2026-11-14T00:00:00.123456789Z"),
                        historyId(9401),
                    ),
            )

    @Test
    fun recipientOriginSurvivesEncryptedStorageAndDatabaseReopen() = withDatabase { f ->
        val value = recipientIntent(f.local())
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), value),
        )
        val raw = f.rawMessages().single()
        assertFalse(raw.request.contains(assertNotNull(value.recipientOrigin).peerAccountId))
        assertFalse(raw.request.contains(value.text))
        f.reopen(f.access.copy(sessionId = "recipient-reopened"))
        assertEquals(
            value,
            f.readMessage(value.id, f.local().copy(sessionId = "recipient-reopened")).intent,
        )
    }

    @Test
    fun competingRecipientTargetsRetainOnlyOneImmutableDestinationAndWake() = withDatabase { f ->
        val first = recipientIntent(f.local())
        val other = first.copy(conversationId = historyId(9402))
        val one = f.messages()
        val two = f.messages()
        val outcomes = coroutineScope {
            listOf(async { one.insert(f.local(), first) }, async { two.insert(f.local(), other) })
                .awaitAll()
        }
        assertEquals(1, outcomes.count { it is AppResult.Success })
        assertEquals(1, outcomes.count { it is AppResult.Failed })
        val kept = f.readMessage(first.id)
        assertTrue(kept.intent in listOf(first, other))
        val raw = f.rawMessages()
        val wake = assertNotNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        assertTrue(
            f.database.workWakes.acknowledge(
                CollaborationSpaceRecoveryWake.WORK_KEY,
                wake.generation,
            )
        )
        val acknowledged = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
        val origin = assertNotNull(kept.intent.recipientOrigin)
        for (changed in
            listOf(
                kept.intent.copy(conversationId = historyId(9403)),
                kept.intent.copy(recipientOrigin = origin.copy(peerAccountId = historyId(9404))),
                kept.intent.copy(recipientOrigin = origin.copy(nextRevision = historyId(9405))),
                kept.intent.copy(recipientOrigin = null),
            )) assertIs<AppResult.Failed>(f.messages().insert(f.local(), changed))
        assertEquals(raw, f.rawMessages())
        assertEquals(
            acknowledged,
            f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY),
        )
    }

    @Test
    fun recipientProofSurvivesCompletionCancellationAndTerminalReplay() = withDatabase { f ->
        val first = recipientIntent(f.local())
        val sent = f.retainSentRecord(first)
        val completion = CollaborationSpaceMessageCompletion(sent, listOf("\$sent-${first.id}"))
        assertEquals(
            AppResult.Success(true),
            f.messages().archiveCompletion(f.local(), completion, f.repairRequest()),
        )
        val second = recipientIntent(f.local(), 2)
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), second),
        )
        val queued = f.readMessage(second.id)
        assertEquals(
            AppResult.Success(true),
            f.messages().cancel(f.local(), queued, f.repairRequest()),
        )
        f.reopen(f.access.copy(sessionId = "recipient-terminal-reopen"))
        val access = f.local().copy(sessionId = "recipient-terminal-reopen")
        assertEquals(sent, f.readMessage(first.id, access))
        assertEquals(
            queued.copy(state = CollaborationMessageState.Cancelled),
            f.readMessage(second.id, access),
        )
        val before = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
        for (value in listOf(first, second)) {
            assertEquals(
                AppResult.Success(CollaborationMessageInsertion.STORED),
                f.messages().insert(access, value),
            )
            assertIs<AppResult.Failed>(
                f.messages().insert(access, value.copy(conversationId = historyId(9406)))
            )
        }
        assertTrue(f.rawMessages(access).isEmpty())
        assertEquals(before, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun legacyRequestEncodingOmitsRecipientOriginAndCannotBeRetrofitted() = withDatabase { f ->
        val value = intent(f.local()).copy(replyToEventId = null)
        val encoded = Json.encodeToString(value.toSpaceMessageDto(f.local()))
        assertFalse(encoded.contains("recipientOrigin"))
        val decoded =
            Json.decodeFromString<SpaceMessageIntentDto>(encoded).toSpaceMessageIntent(f.local())
        assertEquals(value, decoded)
        assertNull(decoded.recipientOrigin)
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), value),
        )
        assertIs<AppResult.Failed>(f.messages().insert(f.local(), recipientIntent(f.local())))
        assertEquals(value, f.readMessage(value.id).intent)
    }

    @Test
    fun sameMillisecondMessagesKeepTheirFullTimestampOrderAcrossReopen() = withDatabase { f ->
        val earlier =
            intent(f.local(), 2).copy(createdAt = Instant.parse("2026-11-10T00:00:00.123000001Z"))
        val later =
            intent(f.local(), 1).copy(createdAt = Instant.parse("2026-11-10T00:00:00.123000002Z"))
        assertEquals(earlier.createdAt.toEpochMilliseconds(), later.createdAt.toEpochMilliseconds())
        for (value in listOf(later, earlier)) assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), value),
        )
        assertEquals(listOf(earlier.id, later.id), f.rawMessages().map { it.id })
        f.reopen(f.access.copy(sessionId = "reopened"))
        val access = f.local().copy(sessionId = "reopened")
        val records =
            assertIs<AppResult.Success<List<CollaborationSpaceMessageRecord>>>(
                    f.messages().observe(access, earlier.conversationId).first()
                )
                .value
        assertEquals(listOf(earlier, later), records.map { it.intent })
    }

    @Test
    fun newIntentCommitsEncryptedDataAndFreshWorkTogether() = withDatabase { f ->
        val first = intent(f.local())
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), first),
        )
        assertEquals(
            CollaborationSpaceMessageRecord(first, 0, CollaborationMessageState.Queued),
            f.readMessage(first.id),
        )
        val raw = f.rawMessages().single()
        assertFalse(raw.request.contains("Original message"))
        val key = CollaborationSpaceRecoveryWake.WORK_KEY
        val old = assertNotNull(f.database.workWakes.get(key))
        assertTrue(old.pending)
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), intent(f.local(), 2)),
        )
        val next = assertNotNull(f.database.workWakes.get(key))
        assertNotEquals(old.generation, next.generation)
        assertFalse(f.database.workWakes.acknowledge(key, old.generation))
        assertTrue(next.pending)
    }

    @Test
    fun activeReplayKeepsCiphertextStateAndAcknowledgedWakeUnchanged() = withDatabase { f ->
        val value = intent(f.local())
        val sent = f.retainSentRecord(value)
        val before = f.rawMessages()
        val key = CollaborationSpaceRecoveryWake.WORK_KEY
        val wake = assertNotNull(f.database.workWakes.get(key))
        assertTrue(f.database.workWakes.acknowledge(key, wake.generation))
        repeat(2) {
            assertEquals(
                AppResult.Success(CollaborationMessageInsertion.STORED),
                f.messages().insert(f.local(), value),
            )
        }
        assertEquals(before, f.rawMessages())
        assertEquals(sent, f.readMessage(value.id))
        assertEquals(wake.copy(pending = false), f.database.workWakes.get(key))
        assertIs<AppResult.Failed>(f.messages().insert(f.local(), value.copy(text = "Changed")))
        assertEquals(before, f.rawMessages())
    }

    @Test
    fun completedAndCancelledReplayNeverRecreatesPendingWork() = withDatabase { f ->
        val sent = f.retainSentRecord(intent(f.local()))
        val completion =
            CollaborationSpaceMessageCompletion(
                sent,
                listOf((sent.state as CollaborationMessageState.Sent).eventId, "\$alias"),
            )
        assertEquals(
            AppResult.Success(true),
            f.messages().archiveCompletion(f.local(), completion, f.repairRequest()),
        )
        assertEquals(
            AppResult.Success(true),
            f.messages().archiveCompletion(f.local(), completion, f.repairRequest()),
        )
        assertEquals(
            AppResult.Success(completion),
            f.messages().readCompletion(f.local(), sent.intent.id),
        )
        val other = intent(f.local(), 2)
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), other),
        )
        val queued = f.readMessage(other.id)
        assertEquals(
            AppResult.Success(true),
            f.messages().cancel(f.local(), queued, f.repairRequest()),
        )
        assertEquals(
            AppResult.Success(true),
            f.messages().cancel(f.local(), queued, f.repairRequest()),
        )
        assertEquals(AppResult.Success(null), f.messages().readCompletion(f.local(), other.id))
        val key = CollaborationSpaceRecoveryWake.WORK_KEY
        val wake = assertNotNull(f.database.workWakes.get(key))
        assertTrue(f.database.workWakes.acknowledge(key, wake.generation))
        for (value in listOf(sent.intent, other)) assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), value),
        )
        assertTrue(f.rawMessages().isEmpty())
        assertEquals(sent, f.readMessage(sent.intent.id))
        assertEquals(
            queued.copy(state = CollaborationMessageState.Cancelled),
            f.readMessage(other.id),
        )
        assertEquals(wake.copy(pending = false), f.database.workWakes.get(key))
    }

    @Test
    fun fullWakeCapacityCannotLeaveACommandWithoutRecoveryWork() = withDatabase { f ->
        repeat(32) { assertNotNull(f.database.workWakes.request("occupied.$it", "generation", 32)) }
        val result = assertIs<AppResult.Failed>(f.messages().insert(f.local(), intent(f.local())))
        assertEquals(FailureKind.Conflict, result.failure.kind)
        assertEquals("client.collaboration_space_recovery_capacity", result.failure.code)
        assertTrue(f.rawMessages().isEmpty())
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun anotherAccountCannotWriteOrReadAndReauthenticationRetainsOwnedFacts() = withDatabase { f ->
        val access = f.local()
        val value = intent(access)
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(access, value),
        )
        val before = f.rawMessages()
        val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
        val other = f.access.copy(sessionId = "foreign", accountId = historyId(9999))
        f.activate(other)
        assertEquals(AppResult.Success(null), f.messages().get(access, value.id))
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.SCOPE_CHANGED),
            f.messages().insert(access, intent(access, 2)),
        )
        f.reopen(f.access.copy(sessionId = "renewed"))
        val renewed = access.copy(sessionId = "renewed")
        assertEquals(before, f.rawMessages(renewed))
        assertEquals(value, f.readMessage(value.id, renewed).intent)
        assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun lostCommittedResponseKeepsExactIntentAndPendingWake() = withDatabase { f ->
        val value = intent(f.local())
        val actual = f.database.commands
        val interrupted =
            object : CommandStore by actual {
                override suspend fun insertBatchAndWake(
                    scope: CommandScope,
                    records: List<CommandRecord>,
                    wake: WorkWakeRequest,
                ): CommandInsertResult? {
                    assertEquals(
                        CommandInsertResult.Stored,
                        actual.insertBatchAndWake(scope, records, wake),
                    )
                    throw CancellationException("Committed response lost")
                }
            }
        assertFailsWith<CancellationException> { f.messages(interrupted).insert(f.local(), value) }
        assertEquals(value, f.readMessage(value.id).intent)
        val before = f.rawMessages()
        val wake = assertNotNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        assertTrue(wake.pending)
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), value),
        )
        assertEquals(before, f.rawMessages())
        assertEquals(wake, f.database.workWakes.get(wake.key))
    }

    @Test
    fun missingKeyIsNeverReplacedOverActiveOrArchivedCiphertext() = withDatabase { f ->
        val value = intent(f.local())
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), value),
        )
        val queued = f.readMessage(value.id)
        val key = assertNotNull(f.commandKeys.read())
        for (archived in listOf(false, true)) {
            if (archived)
                assertEquals(
                    AppResult.Success(true),
                    f.messages().cancel(f.local(), queued, f.repairRequest()),
                )
            val before = f.rawMessages()
            f.commandKeys.write(null)
            assertIs<AppResult.Failed>(f.messages().get(f.local(), value.id))
            assertIs<AppResult.Failed>(f.messages().insert(f.local(), intent(f.local(), 2)))
            assertNull(f.commandKeys.read())
            assertEquals(before, f.rawMessages())
            f.commandKeys.write(key)
            assertEquals(
                if (archived) CollaborationMessageState.Cancelled
                else CollaborationMessageState.Queued,
                f.readMessage(value.id).state,
            )
        }
    }

    @Test
    fun ciphertextCannotBeTransplantedAcrossSpacesWithTheSameLogicalId() = withDatabase { f ->
        val access = f.local()
        val other = access.copy(spaceId = historyId(9400))
        val value = intent(access)
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(access, value),
        )
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages()
                .insert(other, value.copy(owner = other.messageOwner(), text = "Other space")),
        )
        val raw = f.rawMessages().single()
        val foreign = f.rawMessages(other).single()
        val tampered =
            object : CommandStore by f.database.commands {
                override fun observeRecord(scope: CommandScope, partition: String, id: String) =
                    flowOf(CommandLookup(raw.copy(request = foreign.request), null))
            }
        assertIs<AppResult.Failed>(f.messages(tampered).get(access, value.id))
        assertEquals(value, f.readMessage(value.id).intent)
        assertEquals("Other space", f.readMessage(value.id, other).intent.text)
    }

    @Test
    fun terminalProofBindsTheExactCiphertextNotJustDecodedContent() = withDatabase { f ->
        val sent = f.retainSentRecord(intent(f.local()))
        val completion =
            CollaborationSpaceMessageCompletion(
                sent,
                listOf((sent.state as CollaborationMessageState.Sent).eventId),
            )
        assertEquals(
            AppResult.Success(true),
            f.messages().archiveCompletion(f.local(), completion, f.repairRequest()),
        )
        val raw =
            assertNotNull(
                f.database.commands.archived(
                    spaceMessageScope(f.local()),
                    spaceMessagePartition(f.local()),
                    sent.intent.id,
                )
            )
        val key = Base64.decode(assertNotNull(f.commandKeys.read()))
        val resealed =
            try {
                val codec = CollaborationSpaceMessageCodec(f.local(), f.cipher, key)
                codec.state(sent.intent.id, sent.revision, sent.state.toDto()).also {
                    assertNotEquals(raw.record.state, it)
                    assertEquals(sent, codec.record(raw.record.copy(state = it)))
                }
            } finally {
                key.fill(0)
            }
        val tampered =
            object : CommandStore by f.database.commands {
                override fun observeRecord(scope: CommandScope, partition: String, id: String) =
                    flowOf(
                        CommandLookup(null, raw.copy(record = raw.record.copy(state = resealed)))
                    )
            }
        assertIs<AppResult.Failed>(f.messages(tampered).get(f.local(), sent.intent.id))
        assertEquals(sent, f.readMessage(sent.intent.id))
    }

    @Test
    fun companyAndSpaceCommandsKeepIndependentOwnedNamespaces() = withDatabase { f ->
        val value = intent(f.local())
        val legacy =
            CollaborationMessageIntent(
                value.id,
                f.access.messageOwner(),
                f.conversation,
                value.createdAt,
                "Original company message",
            )
        val old =
            EncryptedCollaborationMessageRepository(f.database.commands, f.cipher, f.commandKeys)
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            old.insert(f.access, legacy),
        )
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), value),
        )
        assertEquals(value, f.readMessage(value.id).intent)
        assertEquals(
            legacy,
            assertIs<AppResult.Success<CollaborationMessageRecord?>>(old.get(f.access, value.id))
                .value
                ?.intent,
        )
        assertNotEquals(messagePartition(f.access), spaceMessagePartition(f.local()))
    }

    @Test
    fun accountCommandCapacityDoesNotRotateTheWake() = withDatabase { f ->
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), intent(f.local())),
        )
        val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
        val fillers =
            (1..127).map {
                CommandRecord(
                    "another-feature",
                    "id-$it",
                    "resource-$it",
                    "order-$it",
                    "opaque",
                    "opaque",
                )
            }
        assertEquals(
            CommandInsertResult.Stored,
            f.database.commands.insertBatch(spaceMessageScope(f.local()), fillers),
        )
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.CAPACITY_EXCEEDED),
            f.messages().insert(f.local(), intent(f.local(), 2)),
        )
        assertEquals(1, f.rawMessages().size)
        assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun startedStateCannotBeCancelledResetOrRetargeted() = withDatabase { f ->
        val value = intent(f.local())
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), value),
        )
        val queued = f.readMessage(value.id)
        val device =
            CollaborationMessageDevice(
                historyId(9300),
                historyId(9301),
                historyId(9302),
                "!space:matrix.example.test",
            )
        val next =
            CollaborationMessageState.Staging(
                device,
                emptySet(),
                CollaborationMessageOrigin("@fictional:matrix.example.test", "b".repeat(64)),
            )
        assertIs<AppResult.Failed>(f.messages().update(f.local(), queued, next.copy(origin = null)))
        assertEquals(AppResult.Success(true), f.messages().update(f.local(), queued, next))
        assertEquals(AppResult.Success(false), f.messages().update(f.local(), queued, next))
        assertEquals(
            AppResult.Success(false),
            f.messages().cancel(f.local(), queued, f.repairRequest()),
        )
        val current = f.readMessage(value.id)
        assertIs<AppResult.Failed>(
            f.messages().update(f.local(), current, CollaborationMessageState.Queued)
        )
        assertIs<AppResult.Failed>(
            f.messages()
                .update(
                    f.local(),
                    current,
                    CollaborationMessageState.Staged(
                        device.copy(roomId = "!other:matrix.example.test"),
                        "txn",
                        next.origin,
                    ),
                )
        )
        assertEquals(current, f.readMessage(value.id))
    }

    @Test
    fun precancelledInsertionCannotCreateDataOrWork() = withDatabase { f ->
        coroutineScope {
            launch {
                    currentCoroutineContext().cancel()
                    assertFailsWith<CancellationException> {
                        f.messages().insert(f.local(), intent(f.local()))
                    }
                }
                .join()
        }
        assertTrue(f.rawMessages().isEmpty())
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun competingRepositoryInstancesRetainOneOriginalAndWake() = withDatabase { f ->
        val value = intent(f.local())
        val outcomes = coroutineScope {
            val start = CompletableDeferred<Unit>()
            val writers =
                (1..2).map {
                    async {
                        start.await()
                        f.messages().insert(f.local(), value)
                    }
                }
            start.complete(Unit)
            writers.awaitAll()
        }
        // Randomized encryption can lose a concurrent raw identity comparison. Exact readback
        // still finds the one original; the owning use case reconciles that uncertain response.
        assertTrue(outcomes.any { it == AppResult.Success(CollaborationMessageInsertion.STORED) })
        assertEquals(1, f.rawMessages().size)
        assertEquals(value, f.readMessage(value.id).intent)
        val wake = assertNotNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        assertTrue(wake.pending)
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), value),
        )
        assertEquals(wake, f.database.workWakes.get(wake.key))
    }

    @Test
    fun pendingIntentAndTerminalArchiveSurviveDatabaseReopen() = withDatabase { f ->
        val first = intent(f.local())
        val second = intent(f.local(), 2)
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), first),
        )
        val cancelled = f.readMessage(first.id)
        assertEquals(
            AppResult.Success(true),
            f.messages().cancel(f.local(), cancelled, f.repairRequest()),
        )
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.messages().insert(f.local(), second),
        )
        val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
        val savedRepair = f.repairProgress().current(f.local())
        f.reopen(f.access.copy(sessionId = "reopened"))
        val access = f.local().copy(sessionId = "reopened")
        assertEquals(savedRepair, f.repairProgress().current(access))
        assertEquals(
            cancelled.copy(state = CollaborationMessageState.Cancelled),
            f.readMessage(first.id, access),
        )
        assertEquals(second, f.readMessage(second.id, access).intent)
        assertEquals(listOf(second.id), f.rawMessages(access).map { it.id })
        assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun exactObservationPublishesInsertionAndTerminalCancellation() = withDatabase { f ->
        coroutineScope {
            val value = intent(f.local())
            val queued = CollaborationSpaceMessageRecord(value, 0, CollaborationMessageState.Queued)
            val repo = f.messages()
            assertEquals(AppResult.Success(null), repo.observeRecord(f.local(), value.id).first())
            val inserted =
                async(start = CoroutineStart.UNDISPATCHED) {
                    repo.observeRecord(f.local(), value.id).first {
                        it == AppResult.Success(queued)
                    }
                }
            assertEquals(
                AppResult.Success(CollaborationMessageInsertion.STORED),
                repo.insert(f.local(), value),
            )
            assertEquals(AppResult.Success(queued), inserted.await())
            val cancelled = queued.copy(state = CollaborationMessageState.Cancelled)
            val terminal =
                async(start = CoroutineStart.UNDISPATCHED) {
                    repo.observeRecord(f.local(), value.id).first {
                        it == AppResult.Success(cancelled)
                    }
                }
            assertEquals(AppResult.Success(true), repo.cancel(f.local(), queued, f.repairRequest()))
            assertEquals(AppResult.Success(cancelled), terminal.await())
            assertEquals(
                AppResult.Success(emptyList()),
                repo.observe(f.local(), value.conversationId).first(),
            )
        }
    }

    @Test
    fun completedAndCancelledOriginalsRetainEncryptedRepairDemand() = withDatabase { f ->
        val sent = f.retainSentRecord(intent(f.local()))
        val first = f.repairRequest()
        val completion =
            CollaborationSpaceMessageCompletion(
                sent,
                listOf((sent.state as CollaborationMessageState.Sent).eventId),
            )
        assertEquals(
            AppResult.Success(true),
            f.messages().archiveCompletion(f.local(), completion, first),
        )
        assertEquals(AppResult.Success(first.replacement), f.repairProgress().current(f.local()))
        val other = intent(f.local(), 2)
        assertIs<AppResult.Success<*>>(f.messages().insert(f.local(), other))
        val queued = f.readMessage(other.id)
        val second = f.repairRequest()
        assertEquals(AppResult.Success(true), f.messages().cancel(f.local(), queued, second))
        assertEquals(AppResult.Success(second.replacement), f.repairProgress().current(f.local()))
        assertEquals(first.replacement, second.expected)
        val raw = assertNotNull(f.rawRepair())
        assertFalse(raw.payload.contains(second.replacement.latestDemand))
        assertEquals(32, Base64.decode(assertNotNull(f.commandKeys.read())).size)
        assertEquals(64, Base64.decode(assertNotNull(f.keys.read())).size)
        assertTrue(f.rawMessages().isEmpty())
    }

    @Test
    fun terminalDemandPreservesAnExistingCapturedPassAndCompletedDemand() = withDatabase { f ->
        val current =
            CollaborationSpaceInboxRepairProgress(
                historyId(9810),
                historyId(9811),
                historyId(9812),
                CollaborationSpaceInboxRepairPass(historyId(9811), "retained-cursor"),
            )
        assertEquals(
            AppResult.Success(current),
            f.repairProgress().replace(f.local(), null, current),
        )
        val original = intent(f.local())
        assertIs<AppResult.Success<*>>(f.messages().insert(f.local(), original))
        val publication = f.repairRequest()
        assertEquals(
            AppResult.Success(true),
            f.messages().cancel(f.local(), f.readMessage(original.id), publication),
        )
        val after =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress?>>(
                    f.repairProgress().current(f.local())
                )
                .value
        assertEquals(publication.replacement, after)
        assertEquals(current.pass, after?.pass)
        assertEquals(current.completedDemand, after?.completedDemand)
    }

    @Test
    fun staleRepairProposalCannotArchiveAnUnchangedCommand() = withDatabase { f ->
        val original = intent(f.local())
        assertIs<AppResult.Success<*>>(f.messages().insert(f.local(), original))
        val queued = f.readMessage(original.id)
        val stale = f.repairRequest()
        val replacement = f.repairRequest()
        assertEquals(
            AppResult.Success(replacement.replacement),
            f.repairProgress().replace(f.local(), replacement.expected, replacement.replacement),
        )
        val raw = f.rawRepair()
        assertEquals(AppResult.Success(false), f.messages().cancel(f.local(), queued, stale))
        assertEquals(queued, f.readMessage(original.id))
        assertEquals(raw, f.rawRepair())
        assertNull(
            f.database.commands.archived(
                spaceMessageScope(f.local()),
                spaceMessagePartition(f.local()),
                original.id,
            )
        )
    }

    @Test
    fun changedOriginalCannotPublishFreshRepairProgress() = withDatabase { f ->
        val original = intent(f.local())
        assertIs<AppResult.Success<*>>(f.messages().insert(f.local(), original))
        val queued = f.readMessage(original.id)
        val changed = queued.copy(intent = original.copy(text = "Different original"))
        assertEquals(
            AppResult.Success(false),
            f.messages().cancel(f.local(), changed, f.repairRequest()),
        )
        assertEquals(queued, f.readMessage(original.id))
        assertNull(f.rawRepair())
    }

    @Test
    fun exactArchiveReplayPreservesLaterAcknowledgedRepairCiphertext() = withDatabase { f ->
        val original = intent(f.local())
        assertIs<AppResult.Success<*>>(f.messages().insert(f.local(), original))
        val queued = f.readMessage(original.id)
        val publication = f.repairRequest()
        assertEquals(AppResult.Success(true), f.messages().cancel(f.local(), queued, publication))
        val idle =
            publication.replacement.copy(
                generation = historyId(9820),
                completedDemand = publication.replacement.latestDemand,
            )
        assertEquals(
            AppResult.Success(idle),
            f.repairProgress().replace(f.local(), publication.replacement, idle),
        )
        val raw = f.rawRepair()
        assertEquals(
            AppResult.Success(true),
            f.messages().cancel(f.local(), queued, f.repairRequest()),
        )
        assertEquals(raw, f.rawRepair())
        assertEquals(AppResult.Success(idle), f.repairProgress().current(f.local()))
    }

    @Test
    fun failedAtomicArchiveLeavesTheOriginalAndRepairCheckpointUntouched() = withDatabase { f ->
        val original = intent(f.local())
        assertIs<AppResult.Success<*>>(f.messages().insert(f.local(), original))
        val queued = f.readMessage(original.id)
        val store =
            object : CommandStore by f.database.commands {
                override suspend fun archiveAndMutate(
                    scope: CommandScope,
                    expected: CommandRecord,
                    evidence: String,
                    mutations: List<ReplicaMutation>,
                ): Boolean = error("Storage interruption")
            }
        assertIs<AppResult.Failed>(f.messages(store).cancel(f.local(), queued, f.repairRequest()))
        assertEquals(queued, f.readMessage(original.id))
        assertNull(f.rawRepair())
    }

    @Test
    fun lostArchiveResponseCanBeReadBackWithoutCreatingAnotherDemand() = withDatabase { f ->
        val original = intent(f.local())
        assertIs<AppResult.Success<*>>(f.messages().insert(f.local(), original))
        val queued = f.readMessage(original.id)
        val publication = f.repairRequest()
        val actual = f.database.commands
        val store =
            object : CommandStore by actual {
                override suspend fun archiveAndMutate(
                    scope: CommandScope,
                    expected: CommandRecord,
                    evidence: String,
                    mutations: List<ReplicaMutation>,
                ): Boolean {
                    check(actual.archiveAndMutate(scope, expected, evidence, mutations))
                    error("Response interrupted after commit")
                }
            }
        assertIs<AppResult.Failed>(f.messages(store).cancel(f.local(), queued, publication))
        assertEquals(
            queued.copy(state = CollaborationMessageState.Cancelled),
            f.readMessage(original.id),
        )
        assertEquals(
            AppResult.Success(publication.replacement),
            f.repairProgress().current(f.local()),
        )
        val raw = f.rawRepair()
        assertEquals(
            AppResult.Success(true),
            f.messages().cancel(f.local(), queued, f.repairRequest()),
        )
        assertEquals(raw, f.rawRepair())
    }

    @Test
    fun missingRetainedRepairKeyCannotBeReplacedDuringArchival() = withDatabase { f ->
        val initial = f.repairRequest().replacement
        assertEquals(
            AppResult.Success(initial),
            f.repairProgress().replace(f.local(), null, initial),
        )
        val original = intent(f.local())
        assertIs<AppResult.Success<*>>(f.messages().insert(f.local(), original))
        val queued = f.readMessage(original.id)
        val publication = f.repairRequest()
        val key = assertNotNull(f.keys.read())
        val raw = f.rawRepair()
        f.keys.write(null)
        try {
            assertIs<AppResult.Failed>(f.messages().cancel(f.local(), queued, publication))
            assertNull(f.keys.read())
            assertEquals(queued, f.readMessage(original.id))
            assertEquals(raw, f.rawRepair())
        } finally {
            f.keys.write(key)
        }
        assertEquals(AppResult.Success(initial), f.repairProgress().current(f.local()))
    }

    @Test
    fun cancellationAfterAtomicArchivalRetainsBothCommandProofAndDemand() = withDatabase { f ->
        val original = intent(f.local())
        assertIs<AppResult.Success<*>>(f.messages().insert(f.local(), original))
        val queued = f.readMessage(original.id)
        val publication = f.repairRequest()
        val actual = f.database.commands
        val store =
            object : CommandStore by actual {
                override suspend fun archiveAndMutate(
                    scope: CommandScope,
                    expected: CommandRecord,
                    evidence: String,
                    mutations: List<ReplicaMutation>,
                ): Boolean {
                    val committed = actual.archiveAndMutate(scope, expected, evidence, mutations)
                    currentCoroutineContext().cancel()
                    return committed
                }
            }
        coroutineScope {
            launch {
                    assertFailsWith<CancellationException> {
                        f.messages(store).cancel(f.local(), queued, publication)
                    }
                }
                .join()
        }
        assertEquals(
            queued.copy(state = CollaborationMessageState.Cancelled),
            f.readMessage(original.id),
        )
        assertEquals(
            AppResult.Success(publication.replacement),
            f.repairProgress().current(f.local()),
        )
    }

    private fun CollaborationHistoryFixture.repairProgress() =
        EncryptedCollaborationSpaceInboxRepairProgressRepository(database.replicas, cipher, keys)

    private suspend fun CollaborationHistoryFixture.repairRequest():
        CollaborationSpaceInboxRepairPublication {
        val current =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress?>>(
                    repairProgress().current(local())
                )
                .value
        val replacement =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress>>(
                    collaborationSpaceInboxRepairProgress(
                        current,
                        CollaborationSpaceInboxRepairProgressChange.Request(
                            Uuid.random().toString()
                        ),
                        Uuid.random().toString(),
                    )
                )
                .value
        return CollaborationSpaceInboxRepairPublication(current, replacement)
    }

    private suspend fun CollaborationHistoryFixture.rawRepair() =
        database.replicas.checkpoint(
            spaceHistoryScope(local()),
            spaceInboxRepairProgressPartition(local()),
        )

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val f = CollaborationHistoryFixture(databaseOwner())
        try {
            f.activate()
            block(f)
        } finally {
            f.close()
        }
    }
}
