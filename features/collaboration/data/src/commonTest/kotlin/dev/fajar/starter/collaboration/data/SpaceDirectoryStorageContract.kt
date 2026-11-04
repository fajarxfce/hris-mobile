package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.replica.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.selectCollaborationSpaceDirectory
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/** Identical SQLite/IndexedDB contract, using the platform's real authenticated encryption. */
abstract class SpaceDirectoryStorageContract {
    protected abstract fun databaseOwner(): SpaceDirectoryDatabaseOwner

    @Test
    fun emptyReadsCreateNoKeysAndFirstPageSurvivesReauthenticationWithItsExactCursor() =
        withDatabase { f ->
            assertEquals(CollaborationSpaceDirectorySnapshot(null, emptyList()), f.snapshot())
            assertNull(f.keys.read())
            f.begin()
            assertTrue(f.snapshot().contacts.isEmpty())
            f.append((1..50).map(::spaceDirectoryContact), more = true)
            val first = f.snapshot()
            val firstCheckpoint = assertNotNull(first.checkpoint)
            assertEquals(spaceDirectoryId(50), firstCheckpoint.nextCursor)
            assertFalse(firstCheckpoint.complete)
            val renewed = f.access.copy(sessionId = "another-login")
            f.reopen(renewed)
            assertEquals(first, f.snapshot(renewed))
            assertEquals(first.checkpoint, f.checkpoint(renewed))
            f.append((51..73).map(::spaceDirectoryContact), value = renewed)
            val completed = f.snapshot(renewed)
            assertEquals((1..73).map(::spaceDirectoryContact), completed.contacts)
            val completedCheckpoint = assertNotNull(completed.checkpoint)
            assertTrue(completedCheckpoint.complete)
            assertNull(completedCheckpoint.nextCursor)
            assertEquals(
                AppResult.Success(false),
                f.repository.append(
                    renewed,
                    completedCheckpoint,
                    f.remote(emptyList(), value = renewed),
                    f.now,
                ),
            )
            assertEquals(completed, f.snapshot(renewed))
        }

    @Test
    fun replacementKeepsPreviousVisibleUntilTerminalCommitAndDropsRemovedContacts() =
        withDatabase { f ->
            f.seed(230)
            val before = f.snapshot()
            f.begin(80001)
            f.append((101..150).map(::spaceDirectoryContact), more = true)
            assertFalse(f.checkpoint()!!.published)
            assertEquals(before, f.snapshot())
            f.reopen()
            assertEquals(before, f.snapshot())
            assertEquals(spaceDirectoryId(150), f.checkpoint()!!.nextCursor)
            f.append((151..160).map(::spaceDirectoryContact))
            assertEquals((101..160).map(::spaceDirectoryContact), f.snapshot().contacts)
            f.begin(80002)
            f.append(emptyList())
            assertTrue(f.snapshot().contacts.isEmpty())
            assertTrue(f.snapshot().checkpoint!!.complete)
        }

    @Test
    fun incompleteWorkCannotRestartAndLateOrMalformedPagesCannotAdvanceProgress() =
        withDatabase { f ->
            f.begin()
            val before = f.checkpoint()!!
            assertEquals(
                AppResult.Success(false),
                f.repository.begin(f.access, before, spaceDirectoryId(80001), f.now),
            )
            f.append((1..50).map(::spaceDirectoryContact), more = true)
            val saved = f.snapshot()
            assertEquals(
                AppResult.Success(false),
                f.repository.append(
                    f.access,
                    before,
                    f.remote((1..50).map(::spaceDirectoryContact), true),
                    f.now,
                ),
            )
            for (page in
                listOf(
                    f.remote((50..55).map(::spaceDirectoryContact)),
                    f.remote((51..101).map(::spaceDirectoryContact)),
                    f.remote(listOf(spaceDirectoryContact(51)), more = true),
                    f.remote(listOf(spaceDirectoryContact(51).copy(companies = emptyList()))),
                    f.remote(
                        listOf(spaceDirectoryContact(51)),
                        value = f.access.copy(spaceId = spaceDirectoryId(99999)),
                    ),
                )) assertIs<AppResult.Failed>(
                f.repository.append(f.access, f.checkpoint()!!, page, f.now)
            )
            assertEquals(saved, f.snapshot())
        }

    @Test
    fun ownedRecordsStayIsolatedWhileLateLocalSessionWritesAreRejected() = withDatabase { f ->
        f.seed(3)
        val original = f.snapshot()
        val other = f.access.copy(accountId = spaceDirectoryId(90003), sessionId = "other-account")
        f.activate(other)
        assertTrue(f.snapshot(other).contacts.isEmpty())
        assertTrue(f.snapshot().contacts.isEmpty())
        assertEquals(
            AppResult.Success(false),
            f.repository.begin(f.access, null, spaceDirectoryId(80001), f.now),
        )
        val renewed = f.access.copy(sessionId = "renewed")
        f.activate(renewed)
        assertEquals(original, f.snapshot(renewed))
        assertTrue(f.snapshot(renewed.copy(spaceId = spaceDirectoryId(99999))).contacts.isEmpty())
        assertEquals(
            listOf(spaceDirectoryContact(2)),
            selectCollaborationSpaceDirectory(original, " employee 2 "),
        )
        assertEquals(original, f.snapshot(renewed))
    }

    @Test
    fun snapshotUsesAuthenticatedCountWithoutAnExtraEndPage() = withDatabase { f ->
        f.seed(400)
        val offsets = mutableListOf<String?>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> {
                    assertEquals(200, limit)
                    offsets += after
                    return f.database.replicas.observe(scope, partition, after, limit)
                }
            }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceDirectorySnapshot>>(
                    f.repository(store).observe(f.access).first()
                )
                .value
        assertEquals(400, result.contacts.size)
        assertEquals(listOf(null, spaceDirectoryId(200)), offsets)
    }

    @Test
    fun aGenerationChangeBetweenStoragePagesCannotPublishMixedContacts() = withDatabase { f ->
        f.seed(230)
        val oldFirst = f.raw()
        f.begin(80001)
        for (batch in (1001..1230).map(::spaceDirectoryContact).chunked(50)) f.append(
            batch,
            more = batch.last().accountId != spaceDirectoryId(1230),
        )
        val expected = f.snapshot()
        val newFirst = f.raw()
        val newSecond = f.raw(spaceDirectoryId(1200))
        val changedPageRead = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> =
                    if (after == null)
                        flow {
                            emit(oldFirst)
                            changedPageRead.await()
                            emit(newFirst)
                        }
                    else flowOf(newSecond).onCompletion { changedPageRead.complete(Unit) }
            }
        val emissions = f.repository(store).observe(f.access).toList()
        assertEquals(listOf(AppResult.Success(expected)), emissions)
    }

    @Test
    fun ciphertextIndexesAndExactRowCountRejectTampering() = withDatabase { f ->
        f.seed(3)
        val raw = f.raw()
        val checkpoint = assertNotNull(raw.checkpoint)
        assertFalse(checkpoint.payload.contains("2026"))
        assertTrue(raw.documents.none { it.payload!!.contains("Fictional") })
        val first = raw.documents.first()
        for (altered in
            listOf(
                raw.copy(documents = raw.documents.dropLast(1)),
                raw.copy(
                    documents = listOf(first.copy(id = spaceDirectoryId(7))) + raw.documents.drop(1)
                ),
                raw.copy(
                    documents =
                        listOf(first.copy(sortKey = spaceDirectoryId(7))) + raw.documents.drop(1)
                ),
                raw.copy(checkpoint = checkpoint.copy(revision = 2)),
                raw.copy(
                    checkpoint =
                        checkpoint.copy(
                            handle = checkpoint.handle.copy(generation = spaceDirectoryId(80009))
                        )
                ),
            )) {
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ) = flowOf(altered)
                }
            assertIs<AppResult.Failed>(f.repository(store).observe(f.access).first())
        }
        assertEquals((1..3).map(::spaceDirectoryContact), f.snapshot().contacts)
    }

    @Test
    fun missingKeyCannotReplaceCiphertextBelongingToAnInactiveAccount() = withDatabase { f ->
        f.seed(1)
        val raw = f.raw()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.repository.observe(f.access).first())
        val other = f.access.copy(accountId = spaceDirectoryId(90003))
        f.activate(other)
        assertIs<AppResult.Failed>(f.repository.begin(other, null, spaceDirectoryId(80001), f.now))
        assertNull(f.keys.read())
        f.activate()
        assertEquals(raw, f.raw())
    }

    @Test
    fun cancelledEncryptionCannotPublishDocumentsOrAdvanceTheCursor() = withDatabase { f ->
        f.begin()
        val before = f.checkpoint()!!
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cipher =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray =
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                        f.cipher.encrypt(key, associatedData, plaintext)
                    }
            }
        coroutineScope {
            val pending = async {
                f.repository(encryption = cipher)
                    .append(f.access, before, f.remote(listOf(spaceDirectoryContact(1))), f.now)
            }
            entered.await()
            pending.cancel()
            release.complete(Unit)
            pending.join()
            assertTrue(pending.isCancelled)
        }
        assertEquals(before, f.checkpoint())
        assertTrue(f.snapshot().contacts.isEmpty())
    }

    private fun withDatabase(block: suspend (SpaceDirectoryStorageFixture) -> Unit) = runTest {
        val fixture = SpaceDirectoryStorageFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
