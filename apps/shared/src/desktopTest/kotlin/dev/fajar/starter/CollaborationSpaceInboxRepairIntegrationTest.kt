package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationSpaceInboxRepairWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** Real application use cases, encrypted SQLite and independent content keys; no native send. */
class CollaborationSpaceInboxRepairIntegrationTest {
    @Test
    fun atomicCancellationRepairsThePendingIndexWhileTheObservedInventoryStaysEmptyAndSurvivesReopen() =
        runBlocking {
            val f = CollaborationWorkerFixture()
            var running: Job? = null
            try {
                f.initialize()
                val session = f.session
                val parent =
                    CollaborationNativeParent(
                        session.id,
                        session.binding.audience,
                        session.user.id,
                        session.binding.serverId,
                    )
                val owner = CollaborationSpaceOwner(parent, UUID.randomUUID().toString())
                val access = owner.localDirectoryAccess()
                val pair = listOf(access.accountId, UUID.randomUUID().toString()).sorted()
                val conversation =
                    CollaborationSpaceConversation(
                        owner,
                        UUID.randomUUID().toString(),
                        pair[0],
                        pair[1],
                        access.accountId,
                        Instant.fromEpochMilliseconds(1000),
                        0,
                    )
                assertEquals(
                    AppResult.Success(true),
                    f.koin
                        .get<CollaborationSpaceRootRepository>()
                        .retain(
                            parent.localSpaceAccountAccess(),
                            CollaborationSpaceRoot(owner.spaceId),
                        ),
                )
                assertEquals(
                    AppResult.Success(true),
                    f.koin
                        .get<CollaborationSpaceConversationMetadataRepository>()
                        .retain(access, conversation),
                )

                val messages = f.koin.get<CollaborationSpaceMessageRepository>()
                val inbox = f.koin.get<CollaborationSpaceInboxRepository>()
                val progress = f.koin.get<CollaborationSpaceInboxRepairProgressRepository>()
                val observeMessages = f.koin.get<ObserveCollaborationSpaceMessages>()
                val requests = f.koin.get<ObserveCollaborationSpaceInboxRepairDemand>()
                val readProgress = f.koin.get<ReadCollaborationSpaceInboxRepairProgress>()
                val changeProgress = f.koin.get<ChangeCollaborationSpaceInboxRepairProgress>()
                val readPage = f.koin.get<ReadCollaborationSpaceInboxRepairs>()
                val readReference = f.koin.get<ReadCollaborationSpaceInboxRepair>()
                val reconcile = f.koin.get<ReconcileCollaborationSpaceInboxOriginal>()
                val observedQueued = CompletableDeferred<Unit>()
                val outcomes = Channel<AppResult<Unit>>(Channel.UNLIMITED)
                val worker =
                    CollaborationSpaceInboxRepairWorker(
                        messages = { local ->
                            // Force the admissible snapshot-conflation case. Actual database
                            // notifications
                            // still run, but the repair owner never receives an original ID to
                            // target.
                            observeMessages(local)
                                .map { result ->
                                    when (result) {
                                        is AppResult.Failed -> result
                                        is AppResult.Success -> {
                                            if (
                                                result.value.any {
                                                    it.state == CollaborationMessageState.Queued
                                                }
                                            )
                                                observedQueued.complete(Unit)
                                            AppResult.Success(
                                                emptyList<CollaborationSpaceMessageRecord>()
                                            )
                                        }
                                    }
                                }
                                .distinctUntilChanged()
                        },
                        requests = { requests(it) },
                        readProgress = { readProgress(it) },
                        changeProgress = { local, change -> changeProgress(local, change) },
                        readPage = { local, after, limit -> readPage(local, after, limit) },
                        readReference = { local, chat, original ->
                            readReference(local, chat, original)
                        },
                        reconcile = { local, original -> reconcile(local, original) },
                    )
                running =
                    launch(Dispatchers.Default) { worker(access).collect { outcomes.send(it) } }
                assertEquals(AppResult.Success(Unit), withTimeout(5_000) { outcomes.receive() })

                val original =
                    CollaborationDraft(
                        conversation.id,
                        UUID.randomUUID().toString(),
                        "Fictional offline original",
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
                    f.koin.get<SubmitCollaborationSpaceComposer>()(access, null, submission),
                )
                val pending =
                    assertIs<AppResult.Success<CollaborationSpaceInboxConversation>>(
                            inbox.conversation(access, conversation.id)
                        )
                        .value
                assertEquals(
                    CollaborationMessageDelivery.PENDING,
                    pending.entry?.newest?.preview?.delivery,
                )
                assertIs<AppResult.Success<*>>(
                    messages.insert(access, collaborationSpaceComposerIntent(access, submission))
                )
                withTimeout(5_000) { observedQueued.await() }
                val queued =
                    assertNotNull(
                        assertIs<AppResult.Success<CollaborationSpaceMessageRecord?>>(
                                messages.get(access, submission.id)
                            )
                            .value
                    )
                assertEquals(CollaborationMessageState.Queued, queued.state)

                val before =
                    assertNotNull(
                        assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress?>>(
                                readProgress(access)
                            )
                            .value
                    )
                assertEquals(before.latestDemand, before.completedDemand)
                assertEquals(
                    AppResult.Success(Unit),
                    f.koin.get<CancelCollaborationSpaceText>()(access, queued),
                )
                val demand =
                    assertNotNull(
                            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress?>>(
                                    readProgress(access)
                                )
                                .value
                        )
                        .latestDemand
                assertNotEquals(before.latestDemand, demand)
                withTimeout(5_000) {
                    progress.observe(access).first { result ->
                        result is AppResult.Success &&
                            result.value?.let { it.completedDemand == demand && it.pass == null } ==
                                true
                    }
                }
                assertEquals(
                    AppResult.Success(null),
                    f.koin
                        .get<CollaborationSpaceInboxRepairRepository>()
                        .get(access, conversation.id, submission.id),
                )
                val repaired =
                    assertIs<AppResult.Success<CollaborationSpaceInboxConversation>>(
                            inbox.conversation(access, conversation.id)
                        )
                        .value
                assertNull(repaired.entry?.newest)
                assertEquals(submission.nextDraft, repaired.draft?.value)
                assertFalse(outcomes.tryReceive().getOrNull() is AppResult.Failed)
                assertEquals(0, f.sdk.childOpens)
                assertEquals(0, f.sdk.deliveries)
                running.cancelAndJoin()
                running = null

                f.reopen()
                assertEquals(
                    CollaborationMessageState.Cancelled,
                    assertIs<AppResult.Success<CollaborationSpaceMessageRecord?>>(
                            f.koin
                                .get<CollaborationSpaceMessageRepository>()
                                .get(access, submission.id)
                        )
                        .value
                        ?.state,
                )
                assertNull(
                    assertIs<AppResult.Success<CollaborationSpaceInboxConversation>>(
                            f.koin
                                .get<CollaborationSpaceInboxRepository>()
                                .conversation(access, conversation.id)
                        )
                        .value
                        .entry
                        ?.newest
                )
                assertEquals(
                    demand,
                    assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress?>>(
                            f.koin
                                .get<CollaborationSpaceInboxRepairProgressRepository>()
                                .current(access)
                        )
                        .value
                        ?.completedDemand,
                )
            } finally {
                running?.cancelAndJoin()
                f.close()
            }
        }

    @Test
    fun aPreviouslyRetiredOriginalSeedsAfterTheWorkerIsIdleAndItsReferenceStillCompletes() =
        runBlocking {
            val f = CollaborationWorkerFixture()
            var running: Job? = null
            try {
                f.initialize()
                val session = f.session
                val parent =
                    CollaborationNativeParent(
                        session.id,
                        session.binding.audience,
                        session.user.id,
                        session.binding.serverId,
                    )
                val owner = CollaborationSpaceOwner(parent, UUID.randomUUID().toString())
                val access = owner.localDirectoryAccess()
                val pair = listOf(access.accountId, UUID.randomUUID().toString()).sorted()
                val conversation =
                    CollaborationSpaceConversation(
                        owner,
                        UUID.randomUUID().toString(),
                        pair[0],
                        pair[1],
                        access.accountId,
                        Instant.fromEpochMilliseconds(1000),
                        0,
                    )
                assertEquals(
                    AppResult.Success(true),
                    f.koin
                        .get<CollaborationSpaceRootRepository>()
                        .retain(
                            parent.localSpaceAccountAccess(),
                            CollaborationSpaceRoot(owner.spaceId),
                        ),
                )
                assertEquals(
                    AppResult.Success(true),
                    f.koin
                        .get<CollaborationSpaceConversationMetadataRepository>()
                        .retain(access, conversation),
                )

                val messages = f.koin.get<CollaborationSpaceMessageRepository>()
                val inbox = f.koin.get<CollaborationSpaceInboxRepository>()
                val progress = f.koin.get<CollaborationSpaceInboxRepairProgressRepository>()
                val repairs = f.koin.get<CollaborationSpaceInboxRepairRepository>()
                val intent =
                    CollaborationSpaceMessageIntent(
                        UUID.randomUUID().toString(),
                        access.messageOwner(),
                        conversation.id,
                        Instant.fromEpochMilliseconds(2000),
                        "Fictional retained original",
                    )
                assertEquals(
                    AppResult.Success(CollaborationMessageInsertion.STORED),
                    messages.insert(access, intent),
                )
                val queued =
                    assertNotNull(
                        assertIs<AppResult.Success<CollaborationSpaceMessageRecord?>>(
                                messages.get(access, intent.id)
                            )
                            .value
                    )
                assertEquals(
                    AppResult.Success(Unit),
                    f.koin.get<CancelCollaborationSpaceText>()(access, queued),
                )
                val draft =
                    CollaborationDraft(
                        conversation.id,
                        UUID.randomUUID().toString(),
                        "Independent next message",
                        Instant.fromEpochMilliseconds(3000),
                    )
                assertEquals(
                    AppResult.Success(draft),
                    f.koin.get<EditCollaborationSpaceDraft>()(access, null, draft),
                )
                val outcomes = Channel<AppResult<Unit>>(Channel.UNLIMITED)
                val worker = f.koin.get<CollaborationSpaceInboxRepairWorker>()
                running =
                    launch(Dispatchers.Default) { worker(access).collect { outcomes.send(it) } }
                withTimeout(15_000) {
                    assertEquals(AppResult.Success(Unit), outcomes.receive())
                    val idle =
                        assertNotNull(
                            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress?>>(
                                    progress.current(access)
                                )
                                .value
                        )
                    assertEquals(idle.latestDemand, idle.completedDemand)
                    assertNull(idle.pass)
                    assertEquals(
                        AppResult.Success(null),
                        repairs.get(access, conversation.id, intent.id),
                    )
                    assertEquals(
                        AppResult.Success(true),
                        f.koin.get<SeedCollaborationSpaceInboxOriginal>()(access, intent),
                    )
                    val seeded =
                        assertNotNull(
                            assertIs<AppResult.Success<CollaborationSpaceInboxRepairProgress?>>(
                                    progress.current(access)
                                )
                                .value
                        )
                    assertNotEquals(idle.latestDemand, seeded.latestDemand)
                    progress.observe(access).first {
                        it is AppResult.Success &&
                            it.value?.let { value ->
                                value.completedDemand == seeded.latestDemand && value.pass == null
                            } == true
                    }
                    assertEquals(
                        AppResult.Success(null),
                        repairs.get(access, conversation.id, intent.id),
                    )
                    val indexed =
                        assertIs<AppResult.Success<CollaborationSpaceInboxConversation>>(
                                inbox.conversation(access, conversation.id)
                            )
                            .value
                    assertNull(indexed.entry?.newest)
                    assertEquals(draft, indexed.draft?.value)
                    assertEquals(draft, indexed.entry?.draft?.value)
                    assertFalse(outcomes.tryReceive().getOrNull() is AppResult.Failed)
                    requireNotNull(running).cancelAndJoin()
                    running = null
                    val completed = progress.current(access)
                    assertEquals(
                        AppResult.Success(true),
                        f.koin.get<SeedCollaborationSpaceInboxOriginal>()(access, intent),
                    )
                    assertEquals(completed, progress.current(access))
                    assertEquals(0, f.sdk.childOpens)
                    assertEquals(0, f.sdk.deliveries)
                    f.reopen()
                    assertEquals(
                        completed,
                        f.koin
                            .get<CollaborationSpaceInboxRepairProgressRepository>()
                            .current(access),
                    )
                    assertEquals(
                        AppResult.Success(null),
                        f.koin
                            .get<CollaborationSpaceInboxRepairRepository>()
                            .get(access, conversation.id, intent.id),
                    )
                    val reopened =
                        assertIs<AppResult.Success<CollaborationSpaceInboxConversation>>(
                                f.koin
                                    .get<CollaborationSpaceInboxRepository>()
                                    .conversation(access, conversation.id)
                            )
                            .value
                    assertEquals(indexed, reopened)
                }
            } finally {
                running?.cancelAndJoin()
                f.close()
            }
        }
}
