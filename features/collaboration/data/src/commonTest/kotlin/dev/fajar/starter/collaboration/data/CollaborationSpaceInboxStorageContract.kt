package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.inbox.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceInboxRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceInboxStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun emptyIndexDoesNotCreateKeysAndReadsUseOnlyTheOwnedIndex() = withDatabase { f ->
        assertEquals(CollaborationSpaceInboxPage.Items(null, emptyList(), null), f.page())
        assertEquals(
            CollaborationSpaceInboxConversation(null, null, null, emptyList()),
            f.conversation(1),
        )
        assertEquals(
            CollaborationSpaceInboxActivities(null, emptyList()),
            f.value(
                f.index()
                    .activities(
                        f.local(),
                        historyId(1),
                        listOf(CollaborationSpaceInboxSource.GroupOriginal(historyId(10))),
                    )
            ),
        )
        assertNull(f.keys.read())
    }

    @Test
    fun pendingAndDraftOutsideTheFirstPageJoinTheGloballyRankedList() = withDatabase { f ->
        for (i in 1..61) f.seed(i, 10_000L - i)
        assertFalse(f.page().entries.any { it.conversationId == historyId(61) })
        val pending = f.add(61, 100, 30_000)
        assertEquals(historyId(61), f.page().entries.first().conversationId)
        assertEquals(
            CollaborationMessageDelivery.PENDING,
            f.page().entries.first().newest?.preview?.delivery,
        )
        f.edit(60, "Unsent local draft", 40_000)
        assertEquals(historyId(60), f.page().entries.first().conversationId)
        f.edit(60, "", 50_000)
        assertEquals(historyId(61), f.page().entries.first().conversationId)
        assertEquals(pending, f.page().entries.first().newest)
        assertFalse(f.page().entries.any { it.conversationId == historyId(60) })
    }

    @Test
    fun acknowledgementsOutOfOrderNeverReplaceNewerPendingOrMoveAnchors() = withDatabase { f ->
        val a = f.add(1, 101, 1000)
        val b = f.add(1, 102, 1000)
        val c = f.add(1, 103, 1000)
        assertEquals(c, f.page().entries.single().newest)
        val acknowledgedA =
            a.copy(
                preview =
                    a.preview!!.copy(timestamp = 9000, delivery = CollaborationMessageDelivery.SENT)
            )
        f.update(1, acknowledgedA)
        assertEquals(c, f.page().entries.single().newest)
        f.update(
            1,
            c.copy(
                preview =
                    c.preview!!.copy(timestamp = 100, delivery = CollaborationMessageDelivery.SENT)
            ),
        )
        f.update(
            1,
            b.copy(preview = b.preview!!.copy(delivery = CollaborationMessageDelivery.SENT)),
        )
        val entry = f.page().entries.single()
        assertEquals(c.position, entry.position)
        assertEquals(100L, entry.newest?.preview?.timestamp)
        assertEquals(
            listOf(c.source, b.source, a.source),
            f.conversation(1).candidates.map { it.source },
        )
    }

    @Test
    fun hiddenLatestKeepsItsAnchorAndRevealsThePrecedingCandidateAfterRestart() =
        withDatabase { f ->
            val a = f.add(1, 101, 1000)
            val b = f.add(1, 102, 1001)
            val hidden = b.copy(preview = null)
            f.update(1, hidden)
            f.reopen()
            assertEquals(a, f.page().entries.single().newest)
            assertEquals(listOf(a), f.conversation(1).candidates)
            assertEquals(
                listOf(hidden),
                f.value(f.index().activities(f.local(), historyId(1), listOf(b.source))).values,
            )
            val restored =
                b.copy(preview = b.preview!!.copy(delivery = CollaborationMessageDelivery.SENT))
            f.update(1, restored)
            assertEquals(b.position, f.page().entries.single().position)
        }

    @Test
    fun frozenAndEmptyComposerSlotsDoNotBecomeEditablePreviews() = withDatabase { f ->
        val activity = f.add(1, 101, 1000)
        f.edit(1, "An editable draft", 2000)
        assertNotNull(f.page().entries.single().draft)
        f.edit(1, "A frozen send body", 3000, frozen = true)
        assertNull(f.page().entries.single().draft)
        assertEquals(activity.position, f.page().entries.single().position)
        assertNotNull(f.conversation(1).draft?.value?.submissionId)
        f.edit(1, "", 4000)
        f.reopen()
        assertEquals("", f.conversation(1).draft?.value?.text)
        assertNull(f.page().entries.single().draft)
    }

    @Test
    fun finitePaginationEndsAndRevisionChangesExplicitlyRestartTheCapturedPage() =
        withDatabase { f ->
            for (i in 1..7) f.seed(i, 1000L + i)
            val first = f.page(limit = 3)
            val second = f.page(first.next, limit = 3)
            val final = f.page(second.next, limit = 3)
            assertNull(final.next)
            assertEquals(
                (7 downTo 1).map(::historyId),
                (first.entries + second.entries + final.entries).map { it.conversationId },
            )
            f.reopen()
            assertEquals(final.entries, f.page(second.next, limit = 3).entries)
            f.add(1, 101, 8000)
            assertEquals(
                AppResult.Success(CollaborationSpaceInboxPage.Restart),
                f.index().observePage(f.local(), first.next, 3).first(),
            )
        }

    @Test
    fun equalPublicationsAndDuplicateDatabaseEmissionsDoNotReplaceRowsOrCursors() =
        withDatabase { f ->
            for (i in 1..3) f.add(i, 100 + i, 1000L + i)
            val current = f.conversation(1)
            val before = f.rawInbox()
            assertEquals(
                AppResult.Success(true),
                f.index()
                    .publish(
                        f.local(),
                        CollaborationSpaceInboxPublication(
                            current.version,
                            current.version!!.lastOrdinal,
                            current.entry!!,
                            current.draft,
                            current.candidates,
                        ),
                    ),
            )
            assertEquals(before, f.rawInbox())
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) = flow {
                        val page =
                            f.database.replicas.observeQueries(scope, partition, queries).first()
                        emit(page)
                        emit(page.copy())
                    }
                }
            assertEquals(1, f.index(store).observePage(f.local(), limit = 1).toList().size)
        }

    @Test
    fun exactCommandNamespacesCannotShareAnchorsAndExistingAnchorsCannotMove() = withDatabase { f ->
        val group = f.add(1, 101, 1000)
        val current = f.conversation(1)
        val company =
            group.copy(
                source =
                    CollaborationSpaceInboxSource.CompanyOriginal(
                        historyId(600),
                        historyId(601),
                        historyId(101),
                    ),
                position = CollaborationSpaceInboxPosition(1001, current.version!!.lastOrdinal + 1),
            )
        f.update(1, company)
        assertEquals(2, f.conversation(1).candidates.size)
        val saved = f.conversation(1)
        val moved = group.copy(position = group.position.copy(timestamp = 9000))
        val proposal =
            CollaborationSpaceInboxPublication(
                saved.version,
                saved.version!!.lastOrdinal,
                collaborationSpaceInboxEntry(
                    historyId(1),
                    saved.entry!!.createdAt,
                    saved.draft,
                    moved,
                ),
                activities = listOf(moved),
            )
        val before = f.rawInbox()
        assertIs<AppResult.Failed>(f.index().publish(f.local(), proposal))
        assertEquals(before, f.rawInbox())
        assertEquals(listOf(company, group), f.conversation(1).candidates)
    }

    @Test
    fun staleConcurrentProposalsHaveOneWinnerAndRetryAllocatesTheNextOrdinal() = withDatabase { f ->
        val arrivals = Channel<Unit>(capacity = 2)
        val release = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun mutate(
                    scope: ReplicaScope,
                    mutations: List<ReplicaMutation>,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    arrivals.send(Unit)
                    release.await()
                    return f.database.replicas.mutate(scope, mutations, wake)
                }
            }
        val first = f.initial(1, 101)
        val second = f.initial(2, 102)
        coroutineScope {
            val a = async { f.index(store).publish(f.local(), first) }
            val b = async { f.index(store).publish(f.local(), second) }
            withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    repeat(2) {
                        select<Unit> {
                            arrivals.onReceive {}
                            a.onAwait { fail("First writer completed before both arrivals: $it") }
                            b.onAwait { fail("Second writer completed before both arrivals: $it") }
                        }
                    }
                }
            }
            release.complete(Unit)
            assertEquals(
                setOf(AppResult.Success(true), AppResult.Success(false)),
                setOf(a.await(), b.await()),
            )
        }
        arrivals.close()
        val winner = f.page().entries.single().conversationId
        val loser = if (winner == historyId(1)) 2 else 1
        val next = f.add(loser, 200, 1000)
        assertEquals(2, next.position.ordinal)
        assertEquals(historyId(loser), f.page().entries.first().conversationId)
    }

    @Test
    fun committedLostReplyReopensCoherentComponentsAndDoesNotDuplicateOnReadback() =
        withDatabase { f ->
            val proposal = f.initial(1, 101)
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun mutate(
                        scope: ReplicaScope,
                        mutations: List<ReplicaMutation>,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertTrue(f.database.replicas.mutate(scope, mutations, wake))
                        throw CancellationException("caller disposed after commit")
                    }
                }
            assertFailsWith<CancellationException> { f.index(store).publish(f.local(), proposal) }
            f.reopen()
            val current = f.conversation(1)
            assertEquals(proposal.entry, current.entry)
            assertEquals(proposal.activities, current.candidates)
            assertEquals(1, current.version?.lastOrdinal)
            assertEquals(AppResult.Success(false), f.index().publish(f.local(), proposal))
            assertEquals(1, f.page().entries.size)
        }

    @Test
    fun mutableCallerListsAreCapturedBeforeStorageSuspension() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? {
                    entered.complete(Unit)
                    release.await()
                    return f.database.replicas.checkpoint(scope, partition)
                }
            }
        val original = f.initial(1, 101)
        val mutable = original.activities.toMutableList()
        coroutineScope {
            val pending = async {
                f.index(store).publish(f.local(), original.copy(activities = mutable))
            }
            withContext(Dispatchers.Default) { withTimeout(5000) { entered.await() } }
            mutable.clear()
            release.complete(Unit)
            assertEquals(AppResult.Success(true), pending.await())
        }
        assertEquals(original.activities, f.conversation(1).candidates)
    }

    @Test
    fun payloadSortAndTagDamageCannotPublishPartialPreviews() = withDatabase { f ->
        f.add(1, 101, 1000)
        val before = f.rawInbox()
        for (mode in 0..2) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) =
                        f.database.replicas.observeQueries(scope, partition, queries).map { page ->
                            page.copy(
                                pages =
                                    page.pages.map { rows ->
                                        rows.map { row ->
                                            when (mode) {
                                                0 -> row.copy(payload = "damaged")
                                                1 -> row.copy(sortKey = "wrong-position")
                                                else -> row.copy(tags = emptySet())
                                            }
                                        }
                                    }
                            )
                        }
                }
            assertIs<AppResult.Failed>(f.index(store).observePage(f.local()).first())
        }
        assertEquals(before, f.rawInbox())
    }

    @Test
    fun missingKeysAndInvalidBoundsCannotCreateReplacementMaterial() = withDatabase { f ->
        assertIs<AppResult.Failed>(f.index().observePage(f.local(), limit = 51).first())
        assertIs<AppResult.Failed>(f.index().conversation(f.local(), historyId(1), 34))
        assertNull(f.keys.read())
        f.add(1, 101, 1000)
        val before = f.rawInbox()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.index().observePage(f.local()).first())
        assertIs<AppResult.Failed>(f.index().publish(f.local(), f.initial(2, 102)))
        assertNull(f.keys.read())
        assertEquals(before, f.rawInbox())
    }

    @Test
    fun pageQueriesStayBoundedAndNeverReadHistoryOrOpenPerConversationReaders() =
        withDatabase { f ->
            for (i in 1..5) f.add(i, 100 + i, 1000L + i)
            var reads = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ): Flow<ReplicaQueryPage> {
                        reads++
                        assertEquals(spaceInboxPartition(f.local()), partition)
                        assertEquals(listOf(ReplicaQuery(SPACE_INBOX_ENTRIES, limit = 4)), queries)
                        return f.database.replicas.observeQueries(scope, partition, queries)
                    }

                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ): Flow<ReplicaPage> = error("No history scan")

                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ): Flow<ReplicaPage> = error("No per-row reader")
                }
            assertEquals(
                3,
                assertIs<CollaborationSpaceInboxPage.Items>(
                        f.value(f.index(store).observePage(f.local(), limit = 3).first())
                    )
                    .entries
                    .size,
            )
            assertEquals(1, reads)
        }

    @Test
    fun indexAndCursorReopenForTheSameAccountButCannotCrossAccountOrGroup() = withDatabase { f ->
        f.add(1, 101, 1000)
        f.add(2, 102, 1001)
        val cursor = assertNotNull(f.page(limit = 1).next)
        val owner = f.access
        val renewed = owner.copy(sessionId = "inbox-renewed", companyId = historyId(9091))
        f.reopen(renewed)
        assertIs<AppResult.Success<CollaborationSpaceInboxPage>>(
            f.index().observePage(f.local(renewed), cursor, 1).first()
        )
        assertIs<AppResult.Failed>(
            f.index()
                .observePage(f.local(renewed).copy(spaceId = historyId(9092)), cursor, 1)
                .first()
        )
        val foreign = owner.copy(sessionId = "foreign-inbox", accountId = historyId(9093))
        f.activate(foreign)
        // Raw ownership fencing returns an exact empty projection for the retired session.
        assertEquals(
            AppResult.Success(CollaborationSpaceInboxPage.Items(null, emptyList(), null)),
            f.index().observePage(f.local(renewed)).first(),
        )
        assertEquals(
            CollaborationSpaceInboxPage.Items(null, emptyList(), null),
            f.value(f.index().observePage(f.local(foreign)).first()),
        )
        assertIs<AppResult.Failed>(f.index().observePage(f.local(foreign), cursor, 1).first())
        f.activate(owner)
        assertEquals(2, f.page().entries.size)
    }

    private fun CollaborationHistoryFixture.local(owner: CollaborationLocalAccess = access) =
        CollaborationSpaceLocalAccess(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            historyId(9000),
        )

    private fun CollaborationHistoryFixture.index(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationSpaceInboxRepository(store, cipher, keys)

    private fun <T> CollaborationHistoryFixture.value(result: AppResult<T>): T =
        assertIs<AppResult.Success<T>>(result).value

    private suspend fun CollaborationHistoryFixture.page(after: String? = null, limit: Int = 50) =
        assertIs<CollaborationSpaceInboxPage.Items>(
            value(index().observePage(local(), after, limit).first())
        )

    private suspend fun CollaborationHistoryFixture.conversation(id: Int) =
        value(index().conversation(local(), historyId(id), 33))

    private suspend fun CollaborationHistoryFixture.rawInbox(): ReplicaPage {
        val page =
            database.replicas
                .observe(spaceHistoryScope(local()), spaceInboxPartition(local()), limit = 200)
                .first()
        assertEquals(spaceInboxPartition(local()), assertNotNull(page.checkpoint).handle.partition)
        assertTrue(page.documents.isNotEmpty())
        return page
    }

    private suspend fun CollaborationHistoryFixture.seed(id: Int, createdAt: Long) {
        val current = conversation(id)
        assertEquals(
            AppResult.Success(true),
            index()
                .publish(
                    local(),
                    CollaborationSpaceInboxPublication(
                        current.version,
                        current.version?.lastOrdinal ?: 0,
                        collaborationSpaceInboxEntry(historyId(id), createdAt, null, null),
                    ),
                ),
        )
    }

    private fun activity(id: Int, timestamp: Long, ordinal: Long) =
        CollaborationSpaceInboxActivity(
            CollaborationSpaceInboxSource.GroupOriginal(historyId(id)),
            CollaborationSpaceInboxPosition(timestamp, ordinal),
            CollaborationSpaceInboxPreview(
                CollaborationSpaceInboxContent.TEXT,
                "Message $id",
                timestamp,
                CollaborationMessageDelivery.PENDING,
            ),
        )

    private fun CollaborationHistoryFixture.initial(
        id: Int,
        message: Int,
    ): CollaborationSpaceInboxPublication {
        val candidate = activity(message, 1000, 1)
        return CollaborationSpaceInboxPublication(
            null,
            1,
            collaborationSpaceInboxEntry(historyId(id), 0, null, candidate),
            activities = listOf(candidate),
        )
    }

    private suspend fun CollaborationHistoryFixture.add(
        id: Int,
        message: Int,
        timestamp: Long,
    ): CollaborationSpaceInboxActivity {
        val candidate =
            activity(message, timestamp, (conversation(id).version?.lastOrdinal ?: 0) + 1)
        update(id, candidate)
        return candidate
    }

    private suspend fun CollaborationHistoryFixture.update(
        id: Int,
        activity: CollaborationSpaceInboxActivity,
    ) {
        val current = conversation(id)
        val candidates =
            (current.candidates.filter { it.source != activity.source } + activity).filter {
                it.preview != null
            }
        val newest =
            candidates.maxWithOrNull { a, b ->
                compareCollaborationSpaceInboxPositions(a.position, b.position)
            }
        val entry =
            collaborationSpaceInboxEntry(
                historyId(id),
                current.entry?.createdAt ?: 0,
                current.draft,
                newest,
            )
        val last = maxOf(current.version?.lastOrdinal ?: 0, activity.position.ordinal)
        assertEquals(
            AppResult.Success(true),
            index()
                .publish(
                    local(),
                    CollaborationSpaceInboxPublication(
                        current.version,
                        last,
                        entry,
                        activities = listOf(activity),
                    ),
                ),
        )
    }

    private suspend fun CollaborationHistoryFixture.edit(
        id: Int,
        text: String,
        timestamp: Long,
        frozen: Boolean = false,
    ) {
        val current = conversation(id)
        val ordinal = (current.version?.lastOrdinal ?: 0) + 1
        val revision = historyId(20_000 + ordinal.toInt())
        val draft =
            CollaborationSpaceInboxDraft(
                CollaborationDraft(
                    historyId(id),
                    revision,
                    text,
                    Instant.fromEpochMilliseconds(timestamp),
                    if (frozen) revision else null,
                ),
                CollaborationSpaceInboxPosition(timestamp, ordinal),
            )
        val entry =
            collaborationSpaceInboxEntry(
                historyId(id),
                current.entry?.createdAt ?: 0,
                draft,
                current.candidates.firstOrNull(),
            )
        assertEquals(
            AppResult.Success(true),
            index()
                .publish(
                    local(),
                    CollaborationSpaceInboxPublication(current.version, ordinal, entry, draft),
                ),
        )
    }

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        val fixture = CollaborationHistoryFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
