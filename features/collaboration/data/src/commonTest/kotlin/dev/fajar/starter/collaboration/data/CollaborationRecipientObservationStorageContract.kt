package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.historyScope
import dev.fajar.starter.collaboration.data.recipients.recipientObservationPartition
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationRecipientObservationRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationRecipientObservationStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun waitingAndReadyRevisionsSurviveReopenWithoutRewritingOriginalHistory() = withDatabase { f ->
        f.merge(f.event())
        val history = f.raw()
        val key = f.keys.read()
        val waiting = CollaborationRecipientObservation(f.conversation, historyId(1001), false)
        assertEquals(AppResult.Success(null), f.observations().get(f.access, f.conversation))
        assertEquals(AppResult.Success(true), f.observations().replace(f.access, null, waiting))
        val raw = f.observationPage()
        assertFalse(raw.toString().contains(waiting.revision))
        assertFalse(raw.toString().contains("ready"))
        f.reopen()
        assertEquals(AppResult.Success(waiting), f.observations().get(f.access, f.conversation))
        val ready = waiting.copy(revision = historyId(1002), ready = true)
        assertEquals(AppResult.Success(true), f.observations().replace(f.access, waiting, ready))
        f.reopen()
        assertEquals(AppResult.Success(ready), f.observations().get(f.access, f.conversation))
        assertEquals(key, f.keys.read())
        assertEquals(history, f.raw())
    }

    @Test
    fun exactReplayAndCompetingRevisionDoNotRewriteOrClearTheCurrentFact() = withDatabase { f ->
        val waiting = CollaborationRecipientObservation(f.conversation, historyId(1001), false)
        val ready = waiting.copy(revision = historyId(1002), ready = true)
        assertEquals(AppResult.Success(true), f.observations().replace(f.access, null, waiting))
        val raw = f.observationPage()
        assertEquals(AppResult.Success(true), f.observations().replace(f.access, null, waiting))
        assertEquals(raw, f.observationPage())
        assertEquals(AppResult.Success(true), f.observations().replace(f.access, waiting, ready))
        assertEquals(AppResult.Success(false), f.observations().replace(f.access, null, waiting))
        assertEquals(
            AppResult.Success(false),
            f.observations().replace(f.access, waiting, waiting.copy(revision = historyId(1003))),
        )
        assertIs<AppResult.Failed>(
            f.observations().replace(f.access, ready, ready.copy(ready = false))
        )
        assertEquals(AppResult.Success(ready), f.observations().get(f.access, f.conversation))
    }

    @Test
    fun lostCommitResponseReplaysExactOriginalAfterRestart() = withDatabase { f ->
        val waiting = CollaborationRecipientObservation(f.conversation, historyId(1001), false)
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    assertTrue(f.database.replicas.replace(scope, partition, expected, snapshot))
                    error("Lost storage reply")
                }
            }
        assertIs<AppResult.Failed>(f.observations(store).replace(f.access, null, waiting))
        val raw = f.observationPage()
        f.reopen()
        assertEquals(AppResult.Success(true), f.observations().replace(f.access, null, waiting))
        assertEquals(raw, f.observationPage())
    }

    @Test
    fun cancellationBeforeCommitLeavesNoPublishedObservation() = withDatabase { f ->
        val waiting = CollaborationRecipientObservation(f.conversation, historyId(1001), false)
        val entered = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        coroutineScope {
            val job = launch { f.observations(store).replace(f.access, null, waiting) }
            entered.await()
            job.cancelAndJoin()
        }
        assertEquals(AppResult.Success(null), f.observations().get(f.access, f.conversation))
    }

    @Test
    fun renewedLoginKeepsOwnedFactsButOtherAccountCompanyAndConversationDoNotSeeThem() =
        withDatabase { f ->
            val waiting = CollaborationRecipientObservation(f.conversation, historyId(1001), false)
            assertEquals(AppResult.Success(true), f.observations().replace(f.access, null, waiting))
            assertEquals(
                AppResult.Success(null),
                f.observations().get(f.access.copy(companyId = historyId(8001)), f.conversation),
            )
            assertEquals(AppResult.Success(null), f.observations().get(f.access, historyId(8002)))
            val renewed = f.access.copy(sessionId = "replacement-login")
            f.reopen(renewed)
            assertEquals(AppResult.Success(waiting), f.observations().get(renewed, f.conversation))
            assertEquals(AppResult.Success(null), f.observations().get(f.access, f.conversation))
            val other = renewed.copy(accountId = historyId(8003))
            f.activate(other)
            assertEquals(AppResult.Success(null), f.observations().get(other, f.conversation))
        }

    @Test
    fun corruptedCiphertextCannotBecomeAReadyObservation() = withDatabase { f ->
        val waiting = CollaborationRecipientObservation(f.conversation, historyId(1001), false)
        assertEquals(AppResult.Success(true), f.observations().replace(f.access, null, waiting))
        val page = f.observationPage()
        val damaged = page.copy(documents = page.documents.map { it.copy(payload = "broken") })
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> = flowOf(damaged)
            }
        assertIs<AppResult.Failed>(f.observations(store).get(f.access, f.conversation))
        assertIs<AppResult.Failed>(f.observations(store).observe(f.access, f.conversation).first())
        assertEquals(AppResult.Success(waiting), f.observations().get(f.access, f.conversation))
    }

    private fun CollaborationHistoryFixture.observations(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationRecipientObservationRepository(store, cipher, keys)

    private suspend fun CollaborationHistoryFixture.observationPage() =
        database.replicas
            .observeDocument(
                historyScope(access),
                recipientObservationPartition(access),
                conversation,
            )
            .first()

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
