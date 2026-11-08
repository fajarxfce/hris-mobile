package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationHistoryRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.AtomicCredentialStore
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/** Actual SQLite/IndexedDB reads while history and local ownership change. */
abstract class CollaborationHistoryLogicalReadContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun anUnrelatedHistoryCommitDoesNotInvalidateTheCapturedOriginal() = withDatabase { f ->
        f.merge(f.event())
        val expected = f.lookup()
        var commits = 0
        val store =
            interceptRead(f) {
                f.merge(f.event(2))
                commits++
            }
        assertEquals(AppResult.Success(expected), read(f, store))
        assertEquals(1, commits)
        assertEquals(2, f.read().messages.size)
    }

    @Test
    fun anOriginalUpdatedDuringKeyAcquisitionUsesItsMatchingCheckpoint() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        val alias = original.copy(eventId = "\$later-alias", timestamp = original.timestamp + 1)
        var reads = 0
        val credentials =
            object : AtomicCredentialStore by f.keys {
                override suspend fun read(): String? {
                    val retained = f.keys.read()
                    if (++reads == 1) f.merge(alias)
                    return retained
                }
            }
        val repository =
            EncryptedCollaborationHistoryRepository(f.database.replicas, f.cipher, credentials)
        val actual =
            repository.getByLogicalId(
                f.access,
                f.conversation,
                f.room,
                original.sender,
                requireNotNull(original.logicalId),
            )
        assertEquals(AppResult.Success(f.lookup()), actual)
        assertEquals(setOf(original.eventId, alias.eventId), f.lookup()!!.eventIds.toSet())
    }

    @Test
    fun ownerReplacementAfterTheSnapshotCannotPublishTheOldMessage() = withDatabase { f ->
        f.merge(f.event())
        val retained = f.raw()
        val store =
            interceptRead(f) {
                f.activate(f.access.copy(sessionId = "new-session", accountId = historyId(888)))
            }
        assertIs<AppResult.Failed>(read(f, store))
        f.activate()
        assertEquals(retained, f.raw())
        assertNotNull(f.lookup())
    }

    @Test
    fun cancellationDuringTheSnapshotStopsWithoutChangingHistory() = withDatabase { f ->
        f.merge(f.event())
        val retained = f.raw()
        val entered = CompletableDeferred<Unit>()
        val store =
            interceptRead(f) {
                entered.complete(Unit)
                awaitCancellation()
            }
        coroutineScope {
            val pending = async { read(f, store) }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
        assertEquals(retained, f.raw())
    }

    @Test
    fun aCorruptDocumentCannotBecomeAnAbsentOrConfirmedOriginal() = withDatabase { f ->
        f.merge(f.event())
        val retained = f.raw()
        val key = f.keys.read()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun document(scope: ReplicaScope, partition: String, id: String) =
                    f.database.replicas
                        .document(scope, partition, id)
                        ?.copy(payload = "invalid ciphertext")

                override fun observeDocument(scope: ReplicaScope, partition: String, id: String) =
                    f.database.replicas.observeDocument(scope, partition, id).map { page ->
                        page.copy(
                            documents =
                                page.documents.map { it.copy(payload = "invalid ciphertext") }
                        )
                    }
            }
        assertIs<AppResult.Failed>(read(f, store))
        assertEquals(retained, f.raw())
        assertEquals(key, f.keys.read())
    }

    @Test
    fun missingHistoryDoesNotCreateAKeyAndMissingKeysDoNotReplaceHistory() = withDatabase { f ->
        assertEquals(AppResult.Success(null), read(f, f.database.replicas))
        assertNull(f.keys.read())
        f.merge(f.event())
        val retained = f.raw()
        f.keys.write(null)
        assertIs<AppResult.Failed>(read(f, f.database.replicas))
        assertNull(f.keys.read())
        assertEquals(retained, f.raw())
    }

    private fun interceptRead(f: CollaborationHistoryFixture, afterRead: suspend () -> Unit) =
        object : ReplicaStore by f.database.replicas {
            override suspend fun document(
                scope: ReplicaScope,
                partition: String,
                id: String,
            ): ReplicaDocument? {
                val value = f.database.replicas.document(scope, partition, id)
                afterRead()
                return value
            }

            override fun observeDocument(scope: ReplicaScope, partition: String, id: String) =
                f.database.replicas.observeDocument(scope, partition, id).map { page ->
                    afterRead()
                    page
                }
        }

    private suspend fun read(f: CollaborationHistoryFixture, store: ReplicaStore) =
        f.repository(store)
            .getByLogicalId(
                f.access,
                f.conversation,
                f.room,
                f.event().sender,
                requireNotNull(f.event().logicalId),
            )

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
