package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class CollaborationForwardTest {
    @Test
    fun recipientPagingStopsAtTheEndAndRejectsRepeatedCursorsOrMixedSnapshots() = runTest {
        val f = ForwardFixture()
        val point = CollaborationListCheckpoint("generation", 0, 3, f.owner.clock.now())
        val reader =
            ReadCollaborationForwardTargets(
                f.lists,
                f.owner.setup.sessions,
                f.owner.setup.workspaces,
                f.owner.server,
                f.owner.build,
                f.owner.clock,
            )
        f.pages = { selection ->
            if (selection.after == null) CollaborationLocalList(point, listOf(f.original), "next")
            else CollaborationLocalList(point, f.targets, null)
        }
        assertEquals(
            AppResult.Success(listOf(f.original) + f.targets),
            reader("selection", f.access),
        )
        assertEquals(2, f.targetReads)
        for (changed in listOf(false, true)) {
            f.targetReads = 0
            f.pages = { selection ->
                if (selection.after == null)
                    CollaborationLocalList(point, listOf(f.original), "next")
                else
                    CollaborationLocalList(
                        if (changed) point.copy(revision = 1) else point,
                        f.targets,
                        "next",
                    )
            }
            assertIs<AppResult.Failed>(reader("selection", f.access))
            assertEquals(2, f.targetReads)
        }
    }

    @Test
    fun acquiredTextForMultipleTargetsCanBePreparedAndQueuedWithExpiredCredentials() = runTest {
        val f = ForwardFixture()
        f.owner.setup.now += 365.days
        val batch = f.prepared()
        assertEquals(4, batch.intents.size)
        assertTrue(batch.intents.all { it.forwarded && it.replyToEventId == null })
        assertEquals(listOf("One", "Two", "One", "Two"), batch.intents.map { it.text })
        assertTrue(batch.intents.take(2).zipWithNext().all { (a, b) -> a.createdAt < b.createdAt })
        assertEquals(0, f.writes)
        assertEquals(AppResult.Success(Unit), f.queue(batch))
        assertEquals(batch.intents, f.owner.records.value.map { it.intent })
        assertEquals(1, f.writes)
        assertEquals(listOf(QueueCollaborationText.DELIVERY_KEY), f.owner.scheduledKeys)
        assertEquals(0, f.activeQuoteReaders)
    }

    @Test
    fun hiddenRedactedChangedOrForeignSourceCannotBePreparedOrQueued() = runTest {
        for (change in 0..2) {
            val f = ForwardFixture()
            val batch = f.prepared()
            f.visible =
                when (change) {
                    0 -> f.visible.dropLast(1)
                    1 -> f.visible.map { it.copy(text = "Changed after selection") }
                    else -> f.visible.map { it.copy(sender = "@foreign:example.test") }
                }
            assertIs<AppResult.Failed>(f.prepare(f.selection))
            assertIs<AppResult.Failed>(f.queue(batch))
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun recipientAndSessionReplacementAfterPreparationNeverPublish() = runTest {
        for (replacement in 0..2) {
            val f = ForwardFixture()
            val batch = f.prepared()
            when (replacement) {
                0 -> f.targets = emptyList()
                1 -> f.owner.setup.session = null
                else -> f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
            }
            assertIs<AppResult.Failed>(f.queue(batch))
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun lateDenialAfterCommitKeepsBatchWithoutAcknowledgingOrSchedulingIt() = runTest {
        val f = ForwardFixture()
        val batch = f.prepared()
        f.afterWrite = { f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied) }
        assertEquals(AppResult.Failed(f.owner.denied), f.queue(batch))
        assertEquals(batch.intents, f.owner.records.value.map { it.intent })
        assertTrue(f.owner.scheduledKeys.isEmpty())
        f.afterWrite = {}
        f.owner.gate.value = CollaborationListAdmission("admitted", null)
        assertEquals(AppResult.Success(Unit), f.queue(batch))
        assertEquals(1, f.writes)
    }

    @Test
    fun lostCommitReplyRecoversOriginalBatchEvenIfItsSourceWasDeletedLater() = runTest {
        val f = ForwardFixture()
        val batch = f.prepared()
        f.writeResult = AppResult.Failed(f.owner.storage)
        assertEquals(AppResult.Failed(f.owner.storage), f.queue(batch))
        f.visible = emptyList()
        assertEquals(AppResult.Success(Unit), f.queue(batch))
        assertEquals(1, f.writes)
        assertEquals(batch.intents, f.owner.records.value.map { it.intent })
    }

    @Test
    fun uncertainCancellationCanOnlyLeaveTheCompleteBatchAndRetryNeverReplacesIt() = runTest {
        val f = ForwardFixture()
        val batch = f.prepared()
        val entered = CompletableDeferred<Unit>()
        f.afterWrite = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = async { f.queue(batch) }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        f.afterWrite = {}
        assertEquals(AppResult.Success(Unit), f.queue(batch))
        assertEquals(1, f.writes)
        assertEquals(batch.intents, f.owner.records.value.map { it.intent })
    }

    @Test
    fun fullQueueAndUncertainPartialOrConflictingRecordsNeverCreateTheRemainingMessages() =
        runTest {
            for (mode in 0..2) {
                val f = ForwardFixture()
                val batch = f.prepared()
                when (mode) {
                    0 ->
                        f.writeResult =
                            AppResult.Success(CollaborationMessageInsertion.CAPACITY_EXCEEDED)
                    1 ->
                        f.owner.records.value =
                            listOf(
                                CollaborationMessageRecord(
                                    batch.intents.first(),
                                    0,
                                    CollaborationMessageState.Queued,
                                )
                            )
                    else ->
                        f.owner.records.value =
                            batch.intents.map {
                                CollaborationMessageRecord(
                                    it.copy(forwarded = false),
                                    0,
                                    CollaborationMessageState.Queued,
                                )
                            }
                }
                assertIs<AppResult.Failed>(f.queue(batch))
                assertEquals(if (mode == 0) 1 else 0, f.writes)
                assertTrue(f.owner.scheduledKeys.isEmpty())
            }
        }

    @Test
    fun sourceReadTimeoutAndOwnerLossNeverLeaveAnObserverOrPreparation() = runTest {
        val f = ForwardFixture()
        f.beforeQuotes = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.prepare(f.selection)).failure.kind,
        )
        assertEquals(0, f.activeQuoteReaders)
        f.beforeQuotes = { f.owner.setup.session = null }
        assertIs<AppResult.Failed>(f.prepare(f.selection))
        assertEquals(0, f.writes)
        assertEquals(0, f.activeQuoteReaders)
    }

    @Test
    fun preparationDetachesCallerSelectionsBeforeAnySuspension() = runTest {
        val f = ForwardFixture()
        val messages = f.selection.messages.toMutableList()
        val destinations = f.selection.destinationIds.toMutableList()
        f.owner.afterConversation = {
            messages.clear()
            destinations.clear()
        }
        val batch =
            assertIs<AppResult.Success<CollaborationForwardBatch>>(
                    f.prepare(f.selection.copy(messages = messages, destinationIds = destinations))
                )
                .value
        assertEquals(f.selection, batch.selection)
        assertEquals(4, batch.intents.size)
    }

    @Test
    fun invalidSelectionsCannotReadPrivateSourcesOrPublishUnreviewedBodies() = runTest {
        val f = ForwardFixture()
        val one = f.selection.messages.first()
        for (selection in
            listOf(
                f.selection.copy(messages = emptyList()),
                f.selection.copy(messages = listOf(one, one)),
                f.selection.copy(
                    destinationIds = f.selection.destinationIds + f.selection.destinationIds
                ),
                f.selection.copy(destinationIds = (1..6).map(::forwardId)),
                f.selection.copy(
                    messages =
                        (1..50).map {
                            one.copy(reference = one.reference.copy(eventId = "\$event-$it"))
                        },
                    destinationIds = (1..3).map(::forwardId),
                ),
            )) assertIs<AppResult.Failed>(f.prepare(selection))
        assertEquals(0, f.quoteReads)
        val batch = f.prepared()
        assertIs<AppResult.Failed>(
            f.queue(batch.copy(intents = batch.intents.map { it.copy(text = "Unreviewed") }))
        )
        assertIs<AppResult.Failed>(
            f.queue(
                batch.copy(intents = batch.intents.map { it.copy(replyToEventId = "\$foreign") })
            )
        )
        assertEquals(0, f.writes)
    }
}

private class ForwardFixture {
    val owner = MessageIntentFixture()
    val access =
        CollaborationLocalAccess(
            owner.setup.session!!.id,
            owner.server.audience,
            owner.setup.session!!.user.id,
            owner.setup.workspace!!.company.id,
        )
    val original = owner.summary!!
    var targets =
        (1..2).map { index ->
            val peer = forwardId(index + 60)
            original.copy(
                conversation =
                    original.conversation.copy(id = forwardId(index + 70), secondAccountId = peer),
                peer = CollaborationParticipant(peer, "Target $index"),
            )
        }
    val selection =
        CollaborationForwardSelection(
            original.conversation.id,
            "!source:example.test",
            listOf(
                CollaborationForwardSource(
                    CollaborationMessageReference("@peer:example.test", "\$one"),
                    "One",
                ),
                CollaborationForwardSource(
                    CollaborationMessageReference("@peer:example.test", "\$two"),
                    "Two",
                ),
            ),
            targets.map { it.conversation.id },
        )
    var visible =
        selection.messages.map {
            CollaborationQuotedMessage(it.reference.eventId, it.reference.sender, it.text)
        }
    var quoteReads = 0
    var targetReads = 0
    var pages: (CollaborationListSelection) -> CollaborationLocalList = {
        CollaborationLocalList(null, listOf(original) + targets, null)
    }
    var activeQuoteReaders = 0
    var writes = 0
    var beforeQuotes: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var writeResult: AppResult<CollaborationMessageInsertion> =
        AppResult.Success(CollaborationMessageInsertion.STORED)
    val quotes =
        object : CollaborationQuoteRepository {
            override fun observeQuotes(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventIds: List<String>,
            ): Flow<AppResult<List<CollaborationQuotedMessage>>> = flow {
                quoteReads++
                activeQuoteReaders++
                try {
                    beforeQuotes()
                    emit(AppResult.Success(visible))
                } finally {
                    activeQuoteReaders--
                }
            }
        }
    val lists =
        object : CollaborationListRepository by owner.lists {
            override fun observe(
                access: CollaborationLocalAccess,
                selection: CollaborationListSelection,
            ): Flow<AppResult<CollaborationLocalList>> = flow {
                targetReads++
                emit(AppResult.Success(pages(selection)))
            }

            override suspend fun get(
                access: CollaborationLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationDirectSummary?> {
                val found =
                    (listOf(original) + targets).find { it.conversation.id == conversationId }
                owner.afterConversation()
                return AppResult.Success(found)
            }
        }
    val batches =
        object : CollaborationMessageBatchRepository {
            override suspend fun insertBatch(
                access: CollaborationLocalAccess,
                intents: List<CollaborationMessageIntent>,
            ): AppResult<CollaborationMessageInsertion> {
                writes++
                if (
                    writeResult is AppResult.Failed ||
                        (writeResult as AppResult.Success).value ==
                            CollaborationMessageInsertion.STORED
                )
                    owner.records.value +=
                        intents.map {
                            CollaborationMessageRecord(it, 0, CollaborationMessageState.Queued)
                        }
                afterWrite()
                return writeResult
            }
        }
    private val prepare =
        PrepareCollaborationForward(
            quotes,
            lists,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
        )
    private val queue =
        QueueCollaborationForward(
            batches,
            owner.messages,
            quotes,
            lists,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
            owner.schedules,
        )

    suspend fun prepare(selection: CollaborationForwardSelection) =
        prepare("selection", access, selection)

    suspend fun prepared() =
        assertIs<AppResult.Success<CollaborationForwardBatch>>(prepare(selection)).value

    suspend fun queue(batch: CollaborationForwardBatch) = queue("selection", batch)
}

private fun forwardId(index: Int) = "00000000-0000-0000-0000-" + index.toString().padStart(12, '0')
