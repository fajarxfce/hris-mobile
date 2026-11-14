package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationInboxOwner
import dev.fajar.starter.app.work.CollaborationSpaceInboxHistoryTurnWorker
import dev.fajar.starter.app.work.CollaborationSpaceInboxRepairWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Actual application DI, admitted operations, independent keys and SQLite; no native transport. */
class CollaborationSpaceInboxHistoryTurnIntegrationTest {
    @Test
    fun admittedAnchoredInboxWindowUsesActualStorageAndSurvivesReopening() = runBlocking {
        val f = HistoryTurnStorageFixture()
        try {
            f.initialize(withBinding = false)
            val labels = f.seedContacts(5)
            for ((ordinal, label) in labels.withIndex()) {
                val draft =
                    CollaborationDraft(
                        label.conversationId,
                        UUID.randomUUID().toString(),
                        "Local draft $ordinal",
                        Instant.fromEpochMilliseconds(10_000L - ordinal),
                    )
                assertEquals(
                    AppResult.Success(draft),
                    f.base.koin.get<EditCollaborationSpaceDraft>()(f.access, null, draft),
                )
            }
            val anchor = CollaborationSpaceInboxWindowAnchor.Conversation(labels[2].conversationId)
            val before =
                assertIs<CollaborationSpaceInboxWindow.Items>(
                    assertIs<AppResult.Success<CollaborationSpaceInboxWindow>>(
                            f.base.koin.get<ReadCollaborationSpaceInboxWindow>()(
                                f.access,
                                anchor,
                                3,
                            )
                        )
                        .value
                )
            assertEquals(
                labels.subList(1, 4).map { it.conversationId },
                before.entries.map { it.conversationId },
            )
            assertNotNull(before.previous)
            assertNotNull(before.next)
            f.base.reopen()
            val after =
                assertIs<CollaborationSpaceInboxWindow.Items>(
                    assertIs<AppResult.Success<CollaborationSpaceInboxWindow>>(
                            f.base.koin.get<ReadCollaborationSpaceInboxWindow>()(
                                f.access,
                                anchor,
                                3,
                            )
                        )
                        .value
                )
            assertEquals(before.version, after.version)
            assertEquals(before.entries, after.entries)
            val missing =
                CollaborationSpaceInboxWindowAnchor.Conversation(UUID.randomUUID().toString())
            val fallback =
                assertIs<CollaborationSpaceInboxWindow.Items>(
                    assertIs<AppResult.Success<CollaborationSpaceInboxWindow>>(
                            f.base.koin.get<ReadCollaborationSpaceInboxWindow>()(
                                f.access,
                                missing,
                                3,
                            )
                        )
                        .value
                )
            assertEquals(
                labels.take(3).map { it.conversationId },
                fallback.entries.map { it.conversationId },
            )
            assertNull(fallback.previous)
            assertEquals(0, f.base.sdk.childOpens)
            assertEquals(0, f.base.sdk.deliveries)
        } finally {
            f.base.close()
        }
    }

    @Test
    fun fiftyPublishedInboxLabelsUseExactLocalIndexesAndSurviveReopeningWithoutNativeAcquisition() =
        runBlocking {
            val f = HistoryTurnStorageFixture()
            try {
                f.initialize(withBinding = false)
                val expected = f.seedContacts(50)
                val ids = expected.map { it.conversationId }
                val started = TimeSource.Monotonic.markNow()
                val before =
                    withTimeout(15_000) {
                        f.base.koin.get<ObserveCollaborationSpaceInboxLabels>()(f.access, ids)
                            .first {
                                it is AppResult.Success &&
                                    it.value.size == 50 &&
                                    it.value.all { label -> label.displayName != null }
                            }
                    }
                val firstMillis = started.elapsedNow().inWholeMilliseconds
                assertEquals(AppResult.Success(expected), before)
                f.base.reopen()
                val reopened = TimeSource.Monotonic.markNow()
                val after =
                    withTimeout(15_000) {
                        f.base.koin.get<ObserveCollaborationSpaceInboxLabels>()(f.access, ids)
                            .first {
                                it is AppResult.Success &&
                                    it.value.size == 50 &&
                                    it.value.all { label -> label.displayName != null }
                            }
                    }
                val reopenedMillis = reopened.elapsedNow().inWholeMilliseconds
                assertEquals(before, after)
                assertEquals(0, f.base.sdk.childOpens)
                assertEquals(0, f.base.sdk.deliveries)
                println(
                    "INBOX_LABEL_LOCAL_SAMPLE count=50 initialMs=$firstMillis reopenedMs=$reopenedMillis"
                )
            } finally {
                f.base.close()
            }
        }

    @Test
    fun admittedInboxPageReopensPendingMessageAndIndependentDraftWithoutNativeAcquisition() =
        runBlocking {
            val f = HistoryTurnStorageFixture()
            try {
                f.initialize()
                val original =
                    CollaborationDraft(
                        f.conversation.id,
                        UUID.randomUUID().toString(),
                        "Offline original",
                        Instant.fromEpochMilliseconds(2000),
                    )
                val submission =
                    CollaborationComposerSubmission(
                        original,
                        UUID.randomUUID().toString(),
                        original.savedAt,
                    )
                assertEquals(
                    AppResult.Success(submission),
                    f.base.koin.get<SubmitCollaborationSpaceComposer>()(f.access, null, submission),
                )
                val pending =
                    assertIs<CollaborationSpaceInboxPage.Items>(
                        assertIs<AppResult.Success<CollaborationSpaceInboxPage>>(
                                withTimeout(5_000) {
                                    f.base.koin.get<ObserveCollaborationSpaceInboxPage>()(f.access)
                                        .first()
                                }
                            )
                            .value
                    )
                assertEquals(
                    CollaborationMessageDelivery.PENDING,
                    pending.entries.single().newest?.preview?.delivery,
                )
                assertNull(pending.next)
                val drafts = f.base.koin.get<CollaborationSpaceDraftRepository>()
                val current =
                    assertIs<AppResult.Success<CollaborationDraft?>>(
                            drafts.get(f.access, f.conversation.id)
                        )
                        .value
                val draft =
                    CollaborationDraft(
                        f.conversation.id,
                        UUID.randomUUID().toString(),
                        "Independent next message",
                        Instant.fromEpochMilliseconds(3000),
                    )
                assertEquals(
                    AppResult.Success(draft),
                    f.base.koin.get<EditCollaborationSpaceDraft>()(f.access, current, draft),
                )
                val before =
                    withTimeout(5_000) {
                        f.base.koin.get<ObserveCollaborationSpaceInboxPage>()(f.access).first()
                    }
                val row =
                    assertIs<CollaborationSpaceInboxPage.Items>(
                            assertIs<AppResult.Success<CollaborationSpaceInboxPage>>(before).value
                        )
                        .entries
                        .single()
                assertEquals(draft, row.draft?.value)
                assertEquals(pending.entries.single().newest, row.newest)
                f.base.reopen()
                val after =
                    withTimeout(5_000) {
                        f.base.koin.get<ObserveCollaborationSpaceInboxPage>()(f.access).first()
                    }
                assertEquals(before, after)
                assertEquals(0, f.base.sdk.childOpens)
                assertEquals(0, f.base.sdk.deliveries)
            } finally {
                f.base.close()
            }
        }

    @Test
    fun applicationInboxFactoryFindsItsLocalRootAndCompletesWithoutNativeAcquisition() =
        runBlocking {
            val f = HistoryTurnStorageFixture()
            var running: Job? = null
            try {
                f.initialize()
                running = launch { f.base.koin.get<ApplicationCollaborationInboxOwner>().collect() }
                val completed = f.awaitOwnerCompletion()
                assertTrue(f.source().progress.historyComplete)
                assertTrue(assertNotNull(f.source().progress.pendingWindow).complete)
                running.cancelAndJoin()
                running = null
                f.base.reopen()
                assertEquals(completed, f.global())
                val located =
                    withTimeout(5_000) {
                        f.base.koin.get<ObserveLocalCollaborationSpace>()().first {
                            it is AppResult.Success && it.value != null
                        }
                    }
                assertEquals(AppResult.Success(f.access), located)
                assertEquals(0, f.base.sdk.childOpens)
                assertEquals(0, f.base.sdk.deliveries)
            } finally {
                running?.cancelAndJoin()
                f.base.close()
            }
        }

    @Test
    fun actualSessionClearJoinsLocalRepairAndRestoredIdentityGetsOneNewOwner() = runBlocking {
        val f = HistoryTurnStorageFixture()
        var running: Job? = null
        try {
            f.initialize()
            val started = Channel<CollaborationSpaceLocalAccess>(Channel.UNLIMITED)
            val stopped = Channel<CollaborationSpaceLocalAccess>(Channel.UNLIMITED)
            val owner =
                ApplicationCollaborationInboxOwner(
                    observe = { f.base.koin.get<ObserveLocalCollaborationSpace>()() },
                    repair = { local ->
                        f.base.koin.get<CollaborationSpaceInboxRepairWorker>()(local)
                            .onStart { started.send(local) }
                            .onCompletion { stopped.trySend(local).getOrThrow() }
                    },
                )
            running = launch { owner.collect() }
            assertEquals(f.access, withTimeout(5_000) { started.receive() })
            f.awaitOwnerCompletion()
            val sessions = f.base.koin.get<SessionRepository>()
            assertEquals(AppResult.Success(true), sessions.clear(f.access.sessionId))
            assertEquals(f.access, withTimeout(5_000) { stopped.receive() })
            assertTrue(started.tryReceive().isFailure)
            assertEquals(AppResult.Success(true), sessions.compareAndSet(null, f.base.session))
            assertEquals(f.access, withTimeout(5_000) { started.receive() })
            running.cancelAndJoin()
            running = null
            assertEquals(f.access, withTimeout(5_000) { stopped.receive() })
            assertTrue(started.tryReceive().isFailure)
            assertEquals(0, f.base.sdk.childOpens)
            assertEquals(0, f.base.sdk.deliveries)
        } finally {
            running?.cancelAndJoin()
            f.base.close()
        }
    }

    @Test
    fun collectedOwnerCompletesBothLocalLanesAndAnUnchangedReopenRetainsTheSamePass() =
        runBlocking {
            val f = HistoryTurnStorageFixture()
            var running: Job? = null
            try {
                f.initialize()
                running = launch {
                    f.base.koin.get<CollaborationSpaceInboxRepairWorker>()(f.access).collect()
                }
                val completed =
                    withTimeout(10_000) {
                        var retained: CollaborationSpaceInboxHistoryCheckpoint? = null
                        while (
                            retained?.progress?.completed == null || retained.progress.pass != null
                        ) {
                            delay(10)
                            retained =
                                success(
                                    f.base.koin.get<ReadCollaborationSpaceInboxHistoryProgress>()(
                                        f.access
                                    )
                                )
                        }
                        retained
                    }
                running.cancelAndJoin()
                running = null
                val source = f.source()
                assertTrue(source.progress.historyComplete)
                assertTrue(assertNotNull(source.progress.pendingWindow).complete)
                f.base.reopen()
                assertEquals(completed, f.global())
                val idle = CompletableDeferred<Unit>()
                val reopening =
                    f.reopeningWorker { result ->
                        assertEquals(
                            AppResult.Success(
                                CollaborationSpaceInboxHistoryTurnWorker.Outcome.Idle
                            ),
                            result,
                        )
                        idle.complete(Unit)
                    }
                running = launch { reopening(f.access).collect() }
                withTimeout(10_000) { idle.await() }
                running.cancelAndJoin()
                running = null
                assertEquals(completed, f.global())
                assertEquals(source, f.source())
                assertEquals(0, f.base.sdk.childOpens)
                assertEquals(0, f.base.sdk.deliveries)
            } finally {
                running?.cancelAndJoin()
                f.base.close()
            }
        }

    @Test
    fun aBoundRoomCompletesItsEmptyLocalHistoryAndReopensWithoutAnotherPass() = runBlocking {
        val f = HistoryTurnStorageFixture()
        try {
            f.initialize()
            val frame = f.frame()
            f.drain(frame)
            val global = f.global()
            val source = f.source()
            assertNotNull(global.progress.completed)
            assertTrue(source.progress.historyComplete)
            assertTrue(assertNotNull(source.progress.pendingWindow).complete)
            f.base.reopen()
            assertEquals(frame, f.frame())
            assertEquals(global, f.global())
            assertEquals(source, f.source())
            assertEquals(
                AppResult.Success(CollaborationSpaceInboxHistoryTurnWorker.Outcome.Idle),
                f.turn(frame),
            )
            assertEquals(global, f.global())
            assertEquals(0, f.base.sdk.childOpens)
            assertEquals(0, f.base.sdk.deliveries)
        } finally {
            f.base.close()
        }
    }

    @Test
    fun failedPreparationParksAcrossReopenAndActualBindingPublicationResumesIt() = runBlocking {
        val f = HistoryTurnStorageFixture()
        try {
            f.initialize(withBinding = false)
            val first = f.frame()
            f.drain(first, allowDeferrals = true)
            val parked = f.global()
            assertNull(parked.progress.completed)
            assertEquals(
                CollaborationSpaceInboxHistoryTraversal.Parked,
                parked.progress.pass?.traversal,
            )
            assertTrue(assertNotNull(parked.progress.pass).catalogDeferred)
            f.base.reopen()
            assertEquals(parked, f.global())
            assertEquals(
                AppResult.Success(CollaborationSpaceInboxHistoryTurnWorker.Outcome.Idle),
                f.turn(first),
            )
            f.bind()
            val available = f.frame()
            assertNotEquals(first.first.historySignal, available.first.historySignal)
            f.drain(available)
            assertEquals(available.first, f.global().progress.completed?.trigger)
            assertNull(f.global().progress.pass)
            assertTrue(f.source().progress.historyComplete)
            assertEquals(0, f.base.sdk.childOpens)
            assertEquals(0, f.base.sdk.deliveries)
        } finally {
            f.base.close()
        }
    }

    @Test
    fun aPreparedSourceSurvivesInterruptionBeforeItsGlobalQueueAcknowledgement() = runBlocking {
        val f = HistoryTurnStorageFixture()
        try {
            f.initialize()
            val frame = f.frame()
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryTurnWorker.Outcome>>(
                f.turn(frame)
            )
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryTurnWorker.Outcome>>(
                f.turn(frame)
            )
            val queued = f.global()
            val pass = assertNotNull(queued.progress.pass)
            assertEquals(
                listOf(f.conversation.id),
                assertIs<CollaborationSpaceInboxHistoryTraversal.Catalog>(pass.traversal)
                    .batch
                    ?.remaining,
            )
            val prepared =
                success(
                    f.base.koin.get<PrepareCollaborationSpaceInboxHistorySource>()(
                        f.access,
                        f.conversation.id,
                        pass.id,
                    )
                )
            f.base.reopen()
            assertEquals(queued, f.global())
            assertEquals(prepared, f.source())
            f.drain(frame)
            val completed = f.source()
            assertEquals(prepared.version.generation, completed.version.generation)
            assertTrue(completed.progress.historyComplete)
            assertTrue(assertNotNull(completed.progress.pendingWindow).complete)
            assertNull(f.global().progress.pass)
            assertEquals(0, f.base.sdk.childOpens)
            assertEquals(0, f.base.sdk.deliveries)
        } finally {
            f.base.close()
        }
    }
}

private class HistoryTurnStorageFixture {
    val base = CollaborationWorkerFixture()
    lateinit var access: CollaborationSpaceLocalAccess
    lateinit var conversation: CollaborationSpaceConversation
    lateinit var binding: CollaborationSpaceRoomBinding

    suspend fun seedContacts(count: Int): List<CollaborationSpaceInboxLabel> {
        require(count in 1..50)
        val conversations =
            listOf(conversation) +
                (1 until count).map {
                    val pair = listOf(access.accountId, UUID.randomUUID().toString()).sorted()
                    conversation.copy(
                        id = UUID.randomUUID().toString(),
                        firstAccountId = pair[0],
                        secondAccountId = pair[1],
                        createdBy = access.accountId,
                    )
                }
        val metadata = base.koin.get<CollaborationSpaceConversationMetadataRepository>()
        val contacts =
            conversations.mapIndexed { index, value ->
                assertEquals(AppResult.Success(true), metadata.retain(access, value))
                val peer =
                    if (value.firstAccountId == access.accountId) value.secondAccountId
                    else value.firstAccountId
                CollaborationSpaceContact(
                    peer,
                    "Fictional colleague $index",
                    listOf(CollaborationSpaceContactCompany(access.spaceId, "Fictional employer")),
                    false,
                )
            }
        val directory = base.koin.get<CollaborationSpaceDirectoryRepository>()
        val at = Instant.fromEpochMilliseconds(5000)
        assertEquals(
            AppResult.Success(true),
            directory.begin(access, null, UUID.randomUUID().toString(), at),
        )
        val checkpoint =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationSpaceDirectoryCheckpoint?>>(
                        directory.checkpoint(access)
                    )
                    .value
            )
        assertEquals(
            AppResult.Success(true),
            directory.append(
                access,
                checkpoint,
                CollaborationSpaceContactPage(
                    conversation.owner,
                    contacts.sortedBy { it.accountId },
                    null,
                ),
                at,
            ),
        )
        return conversations.zip(contacts) { item, contact ->
            CollaborationSpaceInboxLabel(item.id, contact.accountId, contact.displayName)
        }
    }

    suspend fun initialize(withBinding: Boolean = true) {
        base.initialize()
        val session = base.session
        val parent =
            CollaborationNativeParent(
                session.id,
                session.binding.audience,
                session.user.id,
                session.binding.serverId,
            )
        val owner = CollaborationSpaceOwner(parent, UUID.randomUUID().toString())
        access = owner.localDirectoryAccess()
        val pair = listOf(access.accountId, UUID.randomUUID().toString()).sorted()
        val at = Instant.fromEpochMilliseconds(1000)
        conversation =
            CollaborationSpaceConversation(
                owner,
                UUID.randomUUID().toString(),
                pair[0],
                pair[1],
                access.accountId,
                at,
                0,
            )
        assertEquals(
            AppResult.Success(true),
            base.koin
                .get<CollaborationSpaceRootRepository>()
                .retain(parent.localSpaceAccountAccess(), CollaborationSpaceRoot(owner.spaceId)),
        )
        assertEquals(
            AppResult.Success(true),
            base.koin
                .get<CollaborationSpaceConversationMetadataRepository>()
                .retain(access, conversation),
        )
        binding =
            CollaborationSpaceRoomBinding(
                CollaborationSpaceConversationMetadata(
                    access.spaceId,
                    conversation.id,
                    pair[0],
                    pair[1],
                    access.accountId,
                    at,
                    0,
                ),
                CollaborationSpacePublishedRoom(
                    "!history:example.test",
                    "example.test",
                    CollaborationSpaceRoomOrigin.SPACE,
                    at,
                ),
            )
        if (withBinding) bind()
    }

    /** Real callbacks with one test observation of the completed history turn. */
    fun reopeningWorker(
        onHistory: (AppResult<CollaborationSpaceInboxHistoryTurnWorker.Outcome>) -> Unit
    ) =
        CollaborationSpaceInboxRepairWorker(
            messages = { base.koin.get<ObserveCollaborationSpaceMessages>()(it) },
            signals = { base.koin.get<ObserveCollaborationSpaceInboxRepairSignals>()(it) },
            historySignals = { base.koin.get<ObserveCollaborationSpaceHistoryChanges>()(it) },
            catalog = { base.koin.get<ObserveCollaborationSpaceConversationCatalog>()(it) },
            destination = { base.koin.get<ObserveCollaborationSpaceInboxDestination>()(it) },
            historyTurn = { local, frame, passId, recover ->
                base.koin.get<CollaborationSpaceInboxHistoryTurnWorker>()(
                        local,
                        frame.trigger,
                        frame.destination,
                        passId,
                        recover,
                    )
                    .also(onHistory)
            },
            readProgress = { base.koin.get<ReadCollaborationSpaceInboxRepairProgress>()(it) },
            changeProgress = { local, change ->
                base.koin.get<ChangeCollaborationSpaceInboxRepairProgress>()(local, change)
            },
            readPage = { local, after, limit ->
                base.koin.get<ReadCollaborationSpaceInboxRepairs>()(local, after, limit)
            },
            readReference = { local, chat, original ->
                base.koin.get<ReadCollaborationSpaceInboxRepair>()(local, chat, original)
            },
            seed = { local, original ->
                base.koin.get<SeedCollaborationSpaceInboxOriginal>()(local, original)
            },
            reconcile = { local, original ->
                base.koin.get<ReconcileCollaborationSpaceInboxOriginal>()(local, original)
            },
        )

    suspend fun bind() {
        assertEquals(
            AppResult.Success(true),
            base.koin.get<CollaborationSpaceRoomBindingRepository>().retain(access, binding),
        )
    }

    suspend fun frame():
        Pair<CollaborationSpaceInboxHistoryTrigger, CollaborationSpaceInboxVersion?> {
        val history =
            success(base.koin.get<ObserveCollaborationSpaceHistoryChanges>()(access).first())
        val catalog =
            success(base.koin.get<ObserveCollaborationSpaceConversationCatalog>()(access).first())
        val repair =
            success(base.koin.get<ObserveCollaborationSpaceInboxRepairSignals>()(access).first())
        val destination =
            success(base.koin.get<ObserveCollaborationSpaceInboxDestination>()(access).first())
        return CollaborationSpaceInboxHistoryTrigger(
            history,
            catalog,
            repair.completedDemand,
            destination?.generation,
        ) to destination
    }

    suspend fun turn(
        frame: Pair<CollaborationSpaceInboxHistoryTrigger, CollaborationSpaceInboxVersion?>
    ) =
        base.koin.get<CollaborationSpaceInboxHistoryTurnWorker>()(
            access,
            frame.first,
            frame.second,
            UUID.randomUUID().toString(),
            false,
        )

    suspend fun drain(
        frame: Pair<CollaborationSpaceInboxHistoryTrigger, CollaborationSpaceInboxVersion?>,
        allowDeferrals: Boolean = false,
    ) =
        withTimeout(15_000) {
            repeat(32) {
                when (val outcome = success(turn(frame))) {
                    CollaborationSpaceInboxHistoryTurnWorker.Outcome.Idle -> return@withTimeout
                    is CollaborationSpaceInboxHistoryTurnWorker.Outcome.Advanced ->
                        if (!allowDeferrals) assertNull(outcome.deferredFailure)
                }
            }
            fail("The finite history pass did not terminate")
        }

    suspend fun awaitOwnerCompletion(): CollaborationSpaceInboxHistoryCheckpoint =
        withTimeout(10_000) {
            var retained: CollaborationSpaceInboxHistoryCheckpoint? = null
            while (retained?.progress?.completed == null || retained.progress.pass != null) {
                delay(10)
                retained =
                    success(base.koin.get<ReadCollaborationSpaceInboxHistoryProgress>()(access))
            }
            requireNotNull(retained)
        }

    suspend fun global() =
        assertNotNull(success(base.koin.get<ReadCollaborationSpaceInboxHistoryProgress>()(access)))

    suspend fun source() =
        assertNotNull(
            success(
                base.koin.get<ReadCollaborationSpaceInboxHistorySource>()(access, conversation.id)
            )
        )
}

private fun <T> success(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value
