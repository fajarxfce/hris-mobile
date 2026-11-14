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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Actual application DI, admitted operations, independent keys and SQLite; no native transport. */
class CollaborationSpaceInboxHistoryTurnIntegrationTest {
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
