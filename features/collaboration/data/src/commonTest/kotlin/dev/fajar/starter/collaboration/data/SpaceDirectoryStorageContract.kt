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

    @Test
    fun oneContactReadsNoKeyBeforePublicationAndRejectsInvalidTargetsBeforeStorage() =
        withDatabase { f ->
            var reads = 0
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ): Flow<ReplicaPage> {
                        reads++
                        return f.database.replicas.observeDocument(scope, partition, id)
                    }
                }
            val repository = f.repository(store)
            for (target in listOf("bad", f.access.accountId)) {
                assertIs<AppResult.Failed>(repository.observeContact(f.access, target).first())
            }
            assertEquals(0, reads)
            assertEquals(
                AppResult.Success(null),
                repository.observeContact(f.access, spaceDirectoryId(1)).first(),
            )
            assertEquals(1, reads)
            assertNull(f.keys.read())
            assertNull(f.checkpoint())
        }

    @Test
    fun oneContactUsesAnExactPublishedIndexWithoutReadingTheRestOfTheDirectory() =
        withDatabase { f ->
            f.seed(400)
            val target = spaceDirectoryId(399)
            val queries = mutableListOf<String>()
            val store =
                object : ReplicaStore by f.database.replicas {
                    override fun observe(
                        scope: ReplicaScope,
                        partition: String,
                        after: String?,
                        limit: Int,
                    ): Flow<ReplicaPage> = error("One contact cannot page the directory")

                    override fun observeDocument(
                        scope: ReplicaScope,
                        partition: String,
                        id: String,
                    ): Flow<ReplicaPage> {
                        assertEquals(directoryScope(f.access), scope)
                        assertEquals(directoryPartition(f.access), partition)
                        queries += id
                        return f.database.replicas.observeDocument(scope, partition, id).onEach {
                            page ->
                            assertTrue(page.documents.size <= 1)
                            assertTrue(page.documents.all { it.id == target })
                        }
                    }
                }
            assertEquals(
                AppResult.Success(spaceDirectoryContact(399)),
                f.repository(store).observeContact(f.access, target).first(),
            )
            assertEquals(listOf(target), queries)
            assertEquals(400, f.snapshot().contacts.size)
        }

    @Test
    fun contactObservationKeepsPublishedLabelsUntilReplacementAndThenObservesRemoval() =
        withDatabase { f ->
            f.seed(50)
            val target = spaceDirectoryId(7)
            val before = spaceDirectoryContact(7)
            val updated =
                before.copy(
                    displayName = "Updated fictional colleague",
                    companies =
                        listOf(
                            CollaborationSpaceContactCompany(
                                spaceDirectoryId(91005),
                                "Another fictional company",
                            )
                        ),
                )
            withContext(Dispatchers.Default) {
                val values =
                    kotlinx.coroutines.channels.Channel<AppResult<CollaborationSpaceContact?>>(8)
                val job = launch {
                    f.repository.observeContact(f.access, target).collect { values.send(it) }
                }
                try {
                    assertEquals(AppResult.Success(before), withTimeout(5_000) { values.receive() })
                    f.begin(80001)
                    f.append(
                        (1..50).map { if (it == 7) updated else spaceDirectoryContact(it) },
                        more = true,
                    )
                    assertEquals(
                        AppResult.Success(before),
                        f.repository.observeContact(f.access, target).first(),
                    )
                    f.append(listOf(spaceDirectoryContact(51)))
                    assertEquals(
                        AppResult.Success(updated),
                        withTimeout(5_000) { values.receive() },
                    )
                    assertTrue(
                        f.database.replicas.clear(
                            directoryScope(f.access),
                            directoryPartition(f.access),
                        )
                    )
                    assertEquals(AppResult.Success(null), withTimeout(5_000) { values.receive() })
                } finally {
                    job.cancelAndJoin()
                    values.close()
                }
            }
        }

    @Test
    fun equalSelectedContactDoesNotPublishAgainWhenAnotherContactChanges() = withDatabase { f ->
        f.seed(3)
        val target = spaceDirectoryId(2)
        val before =
            f.database.replicas
                .observeDocument(directoryScope(f.access), directoryPartition(f.access), target)
                .first()
        f.begin(80001)
        f.append(
            (1..3).map {
                spaceDirectoryContact(it).let { contact ->
                    if (it == 1) contact.copy(displayName = "Changed other colleague") else contact
                }
            }
        )
        val after =
            f.database.replicas
                .observeDocument(directoryScope(f.access), directoryPartition(f.access), target)
                .first()
        assertNotEquals(before.checkpoint, after.checkpoint)
        val firstDelivered = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> = flow {
                    emit(before)
                    firstDelivered.await()
                    emit(after)
                }
            }
        val values =
            f.repository(store)
                .observeContact(f.access, target)
                .onEach { firstDelivered.complete(Unit) }
                .toList()
        assertEquals(listOf(AppResult.Success(spaceDirectoryContact(2))), values)
    }

    @Test
    fun aSelectedContactSurvivesRenewedLoginWithoutBecomingReadableToAnotherAccount() =
        withDatabase { f ->
            f.seed(3)
            val keys = f.keys.read()
            val target = spaceDirectoryId(2)
            val expected = AppResult.Success(spaceDirectoryContact(2))
            val renewed = f.access.copy(sessionId = "renewed-contact-login")
            f.reopen(renewed)
            assertEquals(expected, f.repository.observeContact(renewed, target).first())
            assertEquals(
                AppResult.Success(null),
                f.repository.observeContact(f.access, target).first(),
            )
            assertEquals(
                AppResult.Success(null),
                f.repository
                    .observeContact(renewed.copy(spaceId = spaceDirectoryId(99999)), target)
                    .first(),
            )
            val other =
                renewed.copy(
                    sessionId = "another-contact-account",
                    accountId = spaceDirectoryId(90003),
                )
            f.activate(other)
            assertEquals(
                AppResult.Success(null),
                f.repository.observeContact(other, target).first(),
            )
            assertEquals(
                AppResult.Success(null),
                f.repository.observeContact(renewed, target).first(),
            )
            f.activate(renewed)
            assertEquals(expected, f.repository.observeContact(renewed, target).first())
            assertEquals(keys, f.keys.read())
        }

    @Test
    fun selectedContactRejectsMalformedRowsAndCheckpointsWithoutReplacingCiphertext() =
        withDatabase { f ->
            f.seed(3)
            val target = spaceDirectoryId(2)
            val page =
                f.database.replicas
                    .observeDocument(directoryScope(f.access), directoryPartition(f.access), target)
                    .first()
            val raw = page.documents.single()
            val checkpoint = assertNotNull(page.checkpoint)
            for (altered in
                listOf(
                    page.copy(checkpoint = null),
                    page.copy(checkpoint = checkpoint.copy(published = false)),
                    page.copy(checkpoint = checkpoint.copy(revision = checkpoint.revision + 1)),
                    page.copy(documents = listOf(raw, raw)),
                    page.copy(documents = listOf(raw.copy(id = spaceDirectoryId(1)))),
                    page.copy(documents = listOf(raw.copy(sortKey = spaceDirectoryId(1)))),
                    page.copy(documents = listOf(raw.copy(tags = setOf("unexpected")))),
                    page.copy(documents = listOf(raw.copy(payload = "corrupted"))),
                )) {
                val store =
                    object : ReplicaStore by f.database.replicas {
                        override fun observeDocument(
                            scope: ReplicaScope,
                            partition: String,
                            id: String,
                        ) = flowOf(altered)
                    }
                assertEquals(
                    FailureKind.Storage,
                    assertIs<AppResult.Failed>(
                            f.repository(store).observeContact(f.access, target).first()
                        )
                        .failure
                        .kind,
                )
            }
            assertEquals(
                page,
                f.database.replicas
                    .observeDocument(directoryScope(f.access), directoryPartition(f.access), target)
                    .first(),
            )
            assertEquals(
                AppResult.Success(spaceDirectoryContact(2)),
                f.repository.observeContact(f.access, target).first(),
            )
            f.keys.write(null)
            assertEquals(
                FailureKind.Storage,
                assertIs<AppResult.Failed>(f.repository.observeContact(f.access, target).first())
                    .failure
                    .kind,
            )
            assertNull(f.keys.read())
            assertEquals(
                page,
                f.database.replicas
                    .observeDocument(directoryScope(f.access), directoryPartition(f.access), target)
                    .first(),
            )
        }

    @Test
    fun cancellingSelectedContactDecryptionJoinsTheReadWithoutPublishingLateContent() =
        withDatabase { f ->
            f.seed(1)
            val before = f.raw()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val encryption =
                object : ContentCipher by f.cipher {
                    override suspend fun decrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        ciphertext: ByteArray,
                    ): ByteArray =
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            f.cipher.decrypt(key, associatedData, ciphertext)
                        }
                }
            coroutineScope {
                val values = mutableListOf<AppResult<CollaborationSpaceContact?>>()
                val pending = launch {
                    f.repository(encryption = encryption)
                        .observeContact(f.access, spaceDirectoryId(1))
                        .toList(values)
                }
                entered.await()
                pending.cancel()
                assertFalse(pending.isCompleted)
                release.complete(Unit)
                pending.join()
                assertTrue(pending.isCancelled)
                assertTrue(values.isEmpty())
            }
            assertEquals(before, f.raw())
        }

    @Test
    fun selectedContactStorageFailureCompletesWithoutRetryOrAnEmptySuccess() = withDatabase { f ->
        var reads = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observeDocument(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): Flow<ReplicaPage> = flow {
                    reads++
                    error("Unavailable contact storage")
                }
            }
        val values = f.repository(store).observeContact(f.access, spaceDirectoryId(1)).toList()
        assertEquals(1, values.size)
        assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(values.single()).failure.kind)
        assertEquals(1, reads)
        assertNull(f.keys.read())
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
