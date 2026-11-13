package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.data.messages.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

abstract class CollaborationSpaceEditInboxStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun groupEditRetainsCommandPreviewAndWakeAcrossReopen() = withDatabase { f ->
        f.seed()
        val before = f.current()
        val source = f.rawSource()
        val historyKey = f.base.keys.read()
        val prepared = f.prepare()
        assertEquals(
            AppResult.Success(CollaborationSpaceEditPublicationResult.STORED),
            f.publish(prepared),
        )
        assertEquals(prepared.intent, f.command(prepared.intent.id)?.intent)
        assertEquals(prepared.publication.entry, f.current().entry)
        assertTrue(
            assertNotNull(f.base.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
                .pending
        )
        assertEquals(before.entry!!.position, f.current().entry!!.position)
        assertEquals(before.version!!.lastOrdinal, f.current().version!!.lastOrdinal)
        assertEquals(source, f.rawSource())
        assertEquals(historyKey, f.base.keys.read())
        f.base.reopen()
        assertEquals(prepared.intent, f.command(prepared.intent.id)?.intent)
        assertEquals(prepared.publication.entry, f.current().entry)
        assertEquals(source, f.rawSource())
    }

    @Test
    fun companyHistoryKeepsItsNamespaceWhileTheEditIsAGroupCommand() =
        withDatabase(company = true) { f ->
            f.seed()
            assertNotEquals(f.source.conversationId, f.conversation)
            val source = f.rawSource()
            val prepared = f.prepare()
            assertEquals(
                AppResult.Success(CollaborationSpaceEditPublicationResult.STORED),
                f.publish(prepared),
            )
            assertEquals(prepared.intent, f.command(prepared.intent.id)?.intent)
            assertIs<CollaborationSpaceInboxSource.History>(prepared.intent.inboxOrigin!!.activity)
                .let {
                    assertEquals(f.base.access.companyId, it.companyId)
                    assertEquals(f.base.conversation, it.conversationId)
                }
            assertEquals(source, f.rawSource())
            assertNull(
                f.base.database.replicas.checkpoint(
                    spaceHistoryScope(f.access),
                    spaceHistoryPartition(f.access, f.conversation),
                )
            )
            assertTrue(
                f.base.database.commands
                    .observeAll(messageScope(f.base.access), messagePartition(f.base.access))
                    .first()
                    .isEmpty()
            )
            f.base.reopen()
            assertEquals(prepared.intent, f.command(prepared.intent.id)?.intent)
        }

    @Test
    fun sourceChangeDuringInsertionLeavesCommandAndPreviewAbsent() = withDatabase { f ->
        f.seed()
        val prepared = f.prepare()
        val before = f.rawInbox()
        val store =
            object : CommandStore by f.base.database.commands {
                override suspend fun insertAndMutate(
                    scope: CommandScope,
                    record: CommandRecord,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest,
                ): CommandInsertResult? {
                    f.merge(f.event.copy(eventId = "\$new-alias"))
                    return f.base.database.commands.insertAndMutate(scope, record, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(CollaborationSpaceEditPublicationResult.CHANGED),
            f.publish(prepared, f.repository(store)),
        )
        assertNull(f.command(prepared.intent.id))
        assertEquals(before, f.rawInbox())
        assertNull(f.base.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun indexChangeDuringInsertionPreservesNewTypingWithoutRetainingTheEdit() = withDatabase { f ->
        f.seed()
        val prepared = f.prepare()
        val store =
            object : CommandStore by f.base.database.commands {
                override suspend fun insertAndMutate(
                    scope: CommandScope,
                    record: CommandRecord,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest,
                ): CommandInsertResult? {
                    f.type("New independent draft")
                    return f.base.database.commands.insertAndMutate(scope, record, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(CollaborationSpaceEditPublicationResult.CHANGED),
            f.publish(prepared, f.repository(store)),
        )
        assertNull(f.command(prepared.intent.id))
        assertEquals("New independent draft", f.current().draft!!.value.text)
        assertEquals(f.event.text, f.current().entry!!.newest!!.preview!!.text)
        assertNull(f.base.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun retainedReplayDoesNotReadObsoleteHistoryOrReplaceALaterEdit() = withDatabase { f ->
        f.seed()
        val first = f.prepare()
        assertEquals(
            AppResult.Success(CollaborationSpaceEditPublicationResult.STORED),
            f.publish(first),
        )
        val second = f.prepare(2)
        assertEquals(
            AppResult.Success(CollaborationSpaceEditPublicationResult.STORED),
            f.publish(second),
        )
        val before = f.rawInbox()
        val wake =
            assertNotNull(f.base.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        assertTrue(f.base.database.workWakes.acknowledge(wake.key, wake.generation))
        val replicas =
            object : ReplicaStore by f.base.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? = error("Retained replay cannot acquire history or inbox")
            }
        assertEquals(
            AppResult.Success(CollaborationSpaceEditPublicationResult.RETAINED),
            f.publish(first, f.repository(replicas = replicas)),
        )
        assertEquals(before, f.rawInbox())
        assertEquals(wake.copy(pending = false), f.base.database.workWakes.get(wake.key))
        assertEquals(second.intent.text, f.current().entry!!.newest!!.preview!!.text)
    }

    @Test
    fun ordinaryInsertionCannotForgePublicationAndOldCommandsCannotProveIt() = withDatabase { f ->
        f.seed()
        val prepared = f.prepare()
        val before = f.rawInbox()
        assertIs<AppResult.Failed>(f.repository().insert(f.access, prepared.intent))
        assertNull(f.command(prepared.intent.id))
        val old = prepared.intent.copy(inboxOrigin = null)
        assertFalse(Json.encodeToString(old.toSpaceMessageDto(f.access)).contains("inboxOrigin"))
        assertEquals(
            AppResult.Success(CollaborationMessageInsertion.STORED),
            f.repository().insert(f.access, old),
        )
        assertIs<AppResult.Failed>(f.publish(prepared))
        assertEquals(old, f.command(old.id)?.intent)
        assertEquals(before, f.rawInbox())
    }

    @Test
    fun missingHistoryKeysNeverReplaceCiphertextOrCreateACommandKey() = withDatabase { f ->
        f.seed()
        val prepared = f.prepare()
        val source = f.rawSource()
        val before = f.rawInbox()
        assertNull(f.base.commandKeys.read())
        val unavailable = MemoryCredentialStore()
        try {
            val repository =
                EncryptedCollaborationSpaceMessageRepository(
                    f.base.database.commands,
                    f.base.cipher,
                    f.base.commandKeys,
                    f.base.database.replicas,
                    unavailable,
                )
            assertIs<AppResult.Failed>(f.publish(prepared, repository))
            assertNull(unavailable.read())
            assertNull(f.base.commandKeys.read())
            assertEquals(source, f.rawSource())
            assertEquals(before, f.rawInbox())
        } finally {
            unavailable.close()
        }
    }

    @Test
    fun replyLossKeepsExactIntentAndDoesNotOverwriteNewTypingOnRetry() = withDatabase { f ->
        f.seed()
        val prepared = f.prepare()
        val interrupted =
            object : CommandStore by f.base.database.commands {
                override suspend fun insertAndMutate(
                    scope: CommandScope,
                    record: CommandRecord,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest,
                ): CommandInsertResult? {
                    assertEquals(
                        CommandInsertResult.Stored,
                        f.base.database.commands.insertAndMutate(scope, record, mutations, wake),
                    )
                    error("Lost local reply after commit")
                }
            }
        assertIs<AppResult.Failed>(f.publish(prepared, f.repository(interrupted)))
        assertEquals(prepared.intent, f.command(prepared.intent.id)?.intent)
        f.base.reopen()
        f.type("Typing after restart")
        val before = f.rawInbox()
        assertEquals(
            AppResult.Success(CollaborationSpaceEditPublicationResult.RETAINED),
            f.publish(prepared),
        )
        assertEquals(before, f.rawInbox())
        assertEquals("Typing after restart", f.current().draft!!.value.text)
    }

    @Test
    fun cancellingTheCallerJoinsLateStorageAndKeepsTheCommittedEdit() = withDatabase { f ->
        f.seed()
        val prepared = f.prepare()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            object : CommandStore by f.base.database.commands {
                override suspend fun insertAndMutate(
                    scope: CommandScope,
                    record: CommandRecord,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest,
                ): CommandInsertResult? =
                    withContext(NonCancellable) {
                        val result =
                            f.base.database.commands.insertAndMutate(scope, record, mutations, wake)
                        assertEquals(CommandInsertResult.Stored, result)
                        entered.complete(Unit)
                        release.await()
                        result
                    }
            }
        coroutineScope {
            var returned = false
            val writer = launch {
                f.publish(prepared, f.repository(store))
                returned = true
            }
            try {
                entered.await()
                writer.cancel()
                yield()
                assertFalse(writer.isCompleted)
            } finally {
                release.complete(Unit)
            }
            writer.join()
            assertTrue(writer.isCancelled)
            assertFalse(returned)
        }
        assertEquals(prepared.intent, f.command(prepared.intent.id)?.intent)
        assertEquals(prepared.publication.entry, f.current().entry)
    }

    @Test
    fun maximumEditTextAndIndependentDraftKeepTheirSeparateStorage() = withDatabase { f ->
        f.seed()
        val maximum = "é".repeat(16_384)
        f.type(maximum)
        val before = f.current()
        val prepared = f.prepare(text = maximum)
        assertEquals(
            AppResult.Success(CollaborationSpaceEditPublicationResult.STORED),
            f.publish(prepared),
        )
        f.base.reopen()
        assertEquals(prepared.intent, f.command(prepared.intent.id)?.intent)
        assertEquals(maximum, f.current().entry!!.newest!!.preview!!.text)
        assertEquals(before.draft, f.current().draft)
        assertEquals(before.entry!!.position, f.current().entry!!.position)
    }

    @Test
    fun absentAndForeignSourcesDoNotCreateKeysOrReturnAnEditableTarget() = withDatabase { f ->
        val empty =
            assertNotNull(value(f.repository().readEditSource(f.access, f.source, f.event.eventId)))
        assertEquals(CollaborationSpaceEditSourceSnapshot(null, null, null), empty)
        assertNull(f.base.keys.read())
        assertNull(f.base.commandKeys.read())
        val space = assertIs<CollaborationSpaceHistoryLocation.Space>(f.source)
        val foreign = space.copy(access = f.access.copy(accountId = historyId(9011)))
        assertIs<AppResult.Failed>(
            f.repository().readEditSource(f.access, foreign, f.event.eventId)
        )
        f.seed()
        val wrongRoom = space.copy(roomId = "!another:example.test")
        assertIs<AppResult.Failed>(
            f.repository().readEditSource(f.access, wrongRoom, f.event.eventId)
        )
        f.merge(CollaborationHistoryChange.Redacted(f.event.eventId, f.event.sender))
        val redacted =
            assertNotNull(value(f.repository().readEditSource(f.access, f.source, f.event.eventId)))
        assertTrue(assertNotNull(redacted.original).redacted)
        assertNull(redacted.candidate)
        assertNull(f.base.commandKeys.read())
    }

    @Test
    fun changedSourceDuringCandidateAcquisitionReturnsNoMixedSnapshot() = withDatabase { f ->
        f.seed()
        var reads = 0
        val partition = spaceHistoryPartition(f.access, f.conversation)
        val replicas =
            object : ReplicaStore by f.base.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    if (
                        partition == spaceHistoryPartition(f.access, f.conversation) && ++reads == 2
                    )
                        f.merge(
                            f.event.copy(
                                eventId = "\$later-alias",
                                timestamp = f.event.timestamp + 1,
                            )
                        )
                    return f.base.database.replicas.checkpoint(scope, partition)
                }
            }
        assertNull(
            value(
                f.repository(replicas = replicas)
                    .readEditSource(f.access, f.source, f.event.eventId)
            )
        )
        assertTrue(reads >= 2)
        assertNotNull(f.base.database.replicas.checkpoint(spaceHistoryScope(f.access), partition))
        assertNull(f.base.commandKeys.read())
    }

    @Test
    fun companyCandidateKeepsEarliestAliasWithoutRewritingLegacyHistory() =
        withDatabase(company = true) { f ->
            val early = f.event
            val late = early.copy(eventId = "\$primary", timestamp = early.timestamp + 600_000)
            f.merge(late)
            f.merge(early)
            val original = assertNotNull(f.base.lookup())
            val raw = assertNotNull(f.base.raw().checkpoint)
            CollaborationHistoryKeys(f.base.database.replicas, f.base.cipher, f.base.keys).use {
                content,
                index ->
                val codec =
                    CollaborationHistoryCodec(
                        f.base.access,
                        f.base.conversation,
                        f.base.room,
                        f.base.cipher,
                        content,
                        index,
                    )
                val checkpoint = codec.checkpoint(raw)
                val revision = raw.revision + 1
                assertTrue(
                    f.base.database.replicas.commit(
                        raw.handle,
                        raw.revision,
                        codec.checkpoint(
                            raw.handle.generation,
                            revision,
                            checkpoint.count,
                            checkpoint.hiddenRules,
                        ),
                        listOf(
                            codec.message(
                                raw.handle.generation,
                                revision,
                                original.copy(firstAcceptedAt = null),
                            )
                        ),
                        true,
                    )
                )
            }
            f.base.reopen()
            val before = f.rawSource()
            val first =
                assertNotNull(
                    value(f.repository().readEditSource(f.access, f.source, early.eventId))
                )
            val alias =
                assertNotNull(
                    value(f.repository().readEditSource(f.access, f.source, late.eventId))
                )
            assertEquals(first, alias)
            val candidate = assertNotNull(first.candidate)
            assertEquals(late.eventId, candidate.target.eventId)
            assertEquals(late.timestamp, candidate.target.targetAcceptedAt)
            assertEquals(early.timestamp, candidate.target.firstAcceptedAt)
            assertNull(assertNotNull(first.original).firstAcceptedAt)
            assertEquals(before, f.rawSource())
            assertNull(f.base.commandKeys.read())
        }

    @Test
    fun hiddenCompanyOriginalKeepsFactsButCannotOfferAnEdit() =
        withDatabase(company = true) { f ->
            f.seed()
            val repository = f.base.repository()
            assertEquals(
                AppResult.Success(true),
                repository.hide(
                    f.base.access,
                    f.base.conversation,
                    f.base.room,
                    listOf(
                        CollaborationMessageReference(
                            f.event.sender,
                            f.event.eventId,
                            f.event.logicalId,
                        )
                    ),
                ),
            )
            f.base.reopen()
            val before = f.rawSource()
            val found =
                assertNotNull(
                    value(f.repository().readEditSource(f.access, f.source, f.event.eventId))
                )
            assertNotNull(found.original)
            assertNull(found.candidate)
            assertEquals(before, f.rawSource())
            assertNull(f.base.commandKeys.read())
        }

    @Test
    fun archivedCancellationRetainsHandoffProofWithoutRewritingANewerPreview() = withDatabase { f ->
        f.seed()
        val first = f.prepare()
        assertEquals(
            AppResult.Success(CollaborationSpaceEditPublicationResult.STORED),
            f.publish(first),
        )
        val second = f.prepare(2)
        assertEquals(
            AppResult.Success(CollaborationSpaceEditPublicationResult.STORED),
            f.publish(second),
        )
        val queued = assertNotNull(f.command(first.intent.id))
        val repair =
            value(
                collaborationSpaceInboxRepairProgress(
                    null,
                    CollaborationSpaceInboxRepairProgressChange.Request(historyId(9931)),
                    historyId(9932),
                )
            )
        assertEquals(
            AppResult.Success(true),
            f.repository()
                .cancel(f.access, queued, CollaborationSpaceInboxRepairPublication(null, repair)),
        )
        f.base.reopen()
        val cancelled = assertNotNull(f.command(first.intent.id))
        assertEquals(CollaborationMessageState.Cancelled, cancelled.state)
        assertEquals(first.intent, cancelled.intent)
        val before = f.rawInbox()
        val wake =
            assertNotNull(f.base.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        assertTrue(f.base.database.workWakes.acknowledge(wake.key, wake.generation))
        assertEquals(
            AppResult.Success(CollaborationSpaceEditPublicationResult.RETAINED),
            f.publish(first),
        )
        assertEquals(cancelled, f.command(first.intent.id))
        assertEquals(before, f.rawInbox())
        assertEquals(second.intent.text, f.current().entry!!.newest!!.preview!!.text)
        assertEquals(wake.copy(pending = false), f.base.database.workWakes.get(wake.key))
    }

    @Test
    fun anotherHistoryPartitionCannotGuardThePreparedOriginal() =
        withDatabase(company = true) { f ->
            f.seed()
            val prepared = f.prepare()
            val before = f.rawInbox()
            val wrongSource =
                (f.source as CollaborationSpaceHistoryLocation.Original).copy(
                    conversationId = historyId(9030)
                )
            assertIs<AppResult.Failed>(
                f.repository()
                    .publishEdit(
                        f.access,
                        prepared.intent,
                        wrongSource,
                        prepared.revision,
                        prepared.publication,
                    )
            )
            assertNull(f.command(prepared.intent.id))
            assertEquals(before, f.rawInbox())
            assertNull(f.base.commandKeys.read())
            assertNull(f.base.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        }

    @Test
    fun accountReplacementBeforeTheJointWriteRetainsNothing() = withDatabase { f ->
        f.seed()
        val prepared = f.prepare()
        val replacement = f.base.access.copy(sessionId = "replacement", accountId = historyId(9040))
        val store =
            object : CommandStore by f.base.database.commands {
                override suspend fun insertAndMutate(
                    scope: CommandScope,
                    record: CommandRecord,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest,
                ): CommandInsertResult? {
                    f.base.activate(replacement)
                    return f.base.database.commands.insertAndMutate(scope, record, mutations, wake)
                }
            }
        assertEquals(
            AppResult.Success(CollaborationSpaceEditPublicationResult.SCOPE_CHANGED),
            f.publish(prepared, f.repository(store)),
        )
        f.base.activate()
        assertNull(f.command(prepared.intent.id))
        assertNull(f.base.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    private fun <T> value(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

    private data class Prepared(
        val intent: CollaborationSpaceMessageIntent,
        val revision: String,
        val publication: CollaborationSpaceInboxPublication,
    )

    private inner class Fixture(owner: CollaborationHistoryDatabaseOwner, company: Boolean) {
        val base = CollaborationHistoryFixture(owner)
        val access =
            CollaborationSpaceLocalAccess(
                base.access.sessionId,
                base.access.audience,
                base.access.accountId,
                historyId(9010),
            )
        val conversation = historyId(9020)
        val source: CollaborationSpaceHistoryLocation =
            if (company)
                CollaborationSpaceHistoryLocation.Original(
                    base.access,
                    base.conversation,
                    base.room,
                )
            else CollaborationSpaceHistoryLocation.Space(access, conversation, base.room)
        val event = base.event()

        fun repository(
            commands: CommandStore = base.database.commands,
            replicas: ReplicaStore = base.database.replicas,
        ) =
            EncryptedCollaborationSpaceMessageRepository(
                commands,
                base.cipher,
                base.commandKeys,
                replicas,
                base.keys,
            )

        fun index() =
            EncryptedCollaborationSpaceInboxRepository(
                base.database.replicas,
                base.cipher,
                base.keys,
            )

        suspend fun current() = value(index().conversation(access, conversation, 33))

        suspend fun command(id: String) = value(repository().get(access, id))

        suspend fun rawInbox() =
            base.database.replicas
                .observe(spaceHistoryScope(access), spaceInboxPartition(access), limit = 200)
                .first()

        suspend fun rawSource() =
            base.database.replicas
                .observe(
                    spaceHistoryScope(access),
                    when (val at = source) {
                        is CollaborationSpaceHistoryLocation.Space ->
                            spaceHistoryPartition(at.access, at.conversationId)
                        is CollaborationSpaceHistoryLocation.Original ->
                            historyPartition(at.access, at.conversationId)
                    },
                    limit = 200,
                )
                .first()

        suspend fun merge(change: CollaborationHistoryChange = event) {
            val result =
                when (val at = source) {
                    is CollaborationSpaceHistoryLocation.Space ->
                        EncryptedCollaborationSpaceHistoryRepository(
                                base.database.replicas,
                                base.cipher,
                                base.keys,
                            )
                            .merge(at.access, at.conversationId, at.roomId, listOf(change))
                    is CollaborationSpaceHistoryLocation.Original ->
                        base
                            .repository()
                            .merge(at.access, at.conversationId, at.roomId, listOf(change))
                }
            assertEquals(AppResult.Success(true), result)
        }

        suspend fun seed() {
            merge()
            val activity =
                CollaborationSpaceInboxActivity(
                    CollaborationSpaceInboxSource.History(
                        (source as? CollaborationSpaceHistoryLocation.Original)?.access?.companyId,
                        source.conversationId,
                        source.roomId,
                        event.sender,
                        event.eventId,
                    ),
                    CollaborationSpaceInboxPosition(event.timestamp, 1),
                    CollaborationSpaceInboxPreview(
                        CollaborationSpaceInboxContent.TEXT,
                        event.text,
                        event.timestamp,
                        null,
                    ),
                )
            val entry = collaborationSpaceInboxEntry(conversation, event.timestamp, null, activity)
            assertEquals(
                AppResult.Success(true),
                index()
                    .publish(
                        access,
                        CollaborationSpaceInboxPublication(
                            null,
                            1,
                            entry,
                            activities = listOf(activity),
                        ),
                    ),
            )
        }

        suspend fun prepare(number: Int = 1, text: String = "Edited $number"): Prepared {
            val snapshot =
                assertNotNull(value(repository().readEditSource(access, source, event.eventId)))
            val candidate = assertNotNull(snapshot.candidate)
            val current = current()
            val original = current.candidates.single()
            val version = assertNotNull(current.version)
            val revision = version.revision + 1
            val intent =
                CollaborationSpaceMessageIntent(
                    historyId(100 + number),
                    access.messageOwner(),
                    conversation,
                    Instant.fromEpochMilliseconds(event.timestamp + 1000 + number),
                    text,
                    candidate.target.replyToEventId,
                    candidate.target.forwarded,
                    candidate.target,
                    CollaborationSpaceEditInboxOrigin(original.source, revision),
                )
            val pending =
                original.copy(
                    preview = original.preview!!.copy(text = text),
                    pendingEdit =
                        CollaborationSpaceInboxPendingEdit(
                            CollaborationSpaceInboxEditSource.Group(intent.id),
                            candidate.target,
                            intent.createdAt.toEpochMilliseconds(),
                            revision,
                        ),
                )
            val entry =
                collaborationSpaceInboxEntry(
                    conversation,
                    current.entry!!.createdAt,
                    current.draft,
                    pending,
                )
            return Prepared(
                intent,
                assertNotNull(snapshot.revision),
                CollaborationSpaceInboxPublication(
                    version,
                    version.lastOrdinal,
                    entry,
                    activities = listOf(pending),
                ),
            )
        }

        suspend fun publish(
            prepared: Prepared,
            repository: EncryptedCollaborationSpaceMessageRepository = repository(),
        ) =
            repository.publishEdit(
                access,
                prepared.intent,
                source,
                prepared.revision,
                prepared.publication,
            )

        suspend fun type(text: String) {
            val current = current()
            val ordinal = current.version!!.lastOrdinal + 1
            val draft =
                CollaborationSpaceInboxDraft(
                    CollaborationDraft(
                        conversation,
                        historyId(10_000 + ordinal.toInt()),
                        text,
                        Instant.fromEpochMilliseconds(event.timestamp + 500),
                    ),
                    CollaborationSpaceInboxPosition(event.timestamp + 500, ordinal),
                )
            val previous = assertNotNull(current.entry)
            val entry =
                collaborationSpaceInboxEntry(
                    conversation,
                    previous.createdAt,
                    draft,
                    previous.newest,
                )
            assertEquals(
                AppResult.Success(true),
                index()
                    .publish(
                        access,
                        CollaborationSpaceInboxPublication(current.version, ordinal, entry, draft),
                    ),
            )
        }
    }

    private fun withDatabase(company: Boolean = false, block: suspend (Fixture) -> Unit) = runTest {
        val f = Fixture(databaseOwner(), company)
        try {
            f.base.activate()
            block(f)
        } finally {
            f.base.close()
        }
    }
}
