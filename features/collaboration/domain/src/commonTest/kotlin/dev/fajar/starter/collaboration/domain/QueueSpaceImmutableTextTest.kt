@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceMessageRepository
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationSpaceText
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class QueueSpaceImmutableTextTest {
    @Test
    fun delayedLocalReadsCannotRetimestampTheCapturedSubmission() = runTest {
        val f = SpaceMessageUseCaseFixture()
        val original =
            f.record().intent.copy(text = "Captured before local reads", replyToEventId = "\$reply")
        f.beforeRead = { f.native.setup.now += 10.days }
        f.beforeCanonical = { f.native.setup.now += 100.days }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(
                f.queue()(f.access, original)
            )
        assertEquals(original, result.value.intent)
        assertEquals(listOf(original), f.writes)
        assertNotEquals(f.native.setup.now, original.createdAt)
    }

    @Test
    fun serializedSubmissionsPreserveTheirOwnCapturedTimesAcrossALocalWait() = runTest {
        val f = SpaceMessageUseCaseFixture()
        val first = f.record().intent
        val second =
            first.copy(
                id = "e1000000-0000-4000-8000-000000000002",
                createdAt = first.createdAt + 1.milliseconds,
                text = "Second gesture",
            )
        val retained = mutableMapOf<String, CollaborationSpaceMessageRecord>()
        val written = mutableListOf<CollaborationSpaceMessageIntent>()
        val messages =
            object : CollaborationSpaceMessageRepository by f.messages {
                override suspend fun get(
                    access: CollaborationSpaceLocalAccess,
                    id: String,
                ): AppResult<CollaborationSpaceMessageRecord?> {
                    assertEquals(f.access, access)
                    return AppResult.Success(retained[id])
                }

                override suspend fun insert(
                    access: CollaborationSpaceLocalAccess,
                    intent: CollaborationSpaceMessageIntent,
                ): AppResult<CollaborationMessageInsertion> {
                    assertEquals(f.access, access)
                    written += intent
                    retained[intent.id] =
                        CollaborationSpaceMessageRecord(intent, 0, CollaborationMessageState.Queued)
                    return AppResult.Success(CollaborationMessageInsertion.STORED)
                }
            }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeCanonical = {
            if (f.canonicalReads == 1) {
                entered.complete(Unit)
                release.await()
            }
        }
        val queue =
            QueueCollaborationSpaceText(
                messages,
                f.metadata,
                f.base.directory,
                f.sessions,
                f.native.server,
                f.scheduler,
            )
        val one = async { queue(f.access, first) }
        entered.await()
        val two = async { queue(f.access, second) }
        runCurrent()
        assertTrue(written.isEmpty())
        f.native.setup.now += 100.days
        release.complete(Unit)
        assertEquals(
            first,
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(one.await()).value.intent,
        )
        assertEquals(
            second,
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(two.await()).value.intent,
        )
        assertEquals(listOf(first, second), written)
        assertEquals(2, f.schedules.size)
    }

    @Test
    fun changingOnlySubmissionTimeConflictsWithoutOverwritingTheOriginal() = runTest {
        val f = SpaceMessageUseCaseFixture()
        val original = f.record().intent
        val queue = f.queue()
        assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(queue(f.access, original))
        val failure =
            assertIs<AppResult.Failed>(
                    queue(f.access, original.copy(createdAt = original.createdAt + 1.milliseconds))
                )
                .failure
        assertEquals(FailureKind.Conflict, failure.kind)
        assertEquals("client.collaboration_message_changed", failure.code)
        assertEquals(original, assertNotNull(f.stored).intent)
        assertEquals(listOf(original), f.writes)
        assertEquals(1, f.schedules.size)
    }

    @Test
    fun changedReadbackTimeCannotConfirmOrScheduleTheSubmittedGesture() = runTest {
        val f = SpaceMessageUseCaseFixture()
        val original = f.record().intent
        f.onInsert = {
            f.stored =
                CollaborationSpaceMessageRecord(
                    it.copy(createdAt = it.createdAt + 1.milliseconds),
                    0,
                    CollaborationMessageState.Queued,
                )
            AppResult.Success(CollaborationMessageInsertion.STORED)
        }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.queue()(f.access, original)).failure.kind,
        )
        assertEquals(listOf(original), f.writes)
        assertTrue(f.schedules.isEmpty())
    }

    @Test
    fun cancelledCommitReplaysTheOriginalTimeAfterLocalLoginReplacement() = runTest {
        val f = SpaceMessageUseCaseFixture()
        val original = f.record().intent
        val entered = CompletableDeferred<Unit>()
        f.onInsert = {
            f.stored = CollaborationSpaceMessageRecord(it, 0, CollaborationMessageState.Queued)
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.queue()(f.access, original) }
        entered.await()
        job.cancelAndJoin()
        f.native.setup.now += 100.days
        val renewed = f.access.copy(sessionId = "renewed-local-login")
        f.identity.value = AppResult.Success(f.native.session.copy(id = renewed.sessionId))
        f.canonical = null
        val replay =
            assertIs<AppResult.Success<CollaborationSpaceMessageRecord>>(
                    f.queue()(renewed, original)
                )
                .value
        assertEquals(original, replay.intent)
        assertEquals(listOf(original), f.writes)
        assertEquals(1, f.canonicalReads)
        assertEquals(1, f.schedules.size)
    }

    @Test
    fun foreignMalformedAndUnsupportedIntentsFailBeforeStorageOrScheduling() = runTest {
        val f = SpaceMessageUseCaseFixture()
        val valid = f.record().intent
        for (intent in
            listOf(
                valid.copy(createdAt = Instant.fromEpochMilliseconds(-1)),
                valid.copy(createdAt = Instant.fromEpochMilliseconds(253_402_300_800_000L)),
                valid.copy(
                    owner = valid.owner.copy(spaceId = "e1000000-0000-4000-8000-000000000002")
                ),
                valid.copy(id = "invalid"),
                valid.copy(text = ""),
                valid.copy(replyToEventId = "invalid"),
                valid.copy(forwarded = true),
            )) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.queue()(f.access, intent)).failure.kind,
        )
        assertEquals(0, f.reads)
        assertEquals(0, f.canonicalReads)
        assertTrue(f.writes.isEmpty())
        assertTrue(f.schedules.isEmpty())
    }
}
