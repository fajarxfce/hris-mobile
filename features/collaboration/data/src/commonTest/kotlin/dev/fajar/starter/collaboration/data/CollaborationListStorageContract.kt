package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import dev.fajar.starter.collaboration.data.replica.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationListRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

/**
 * Identical contract runs against real SQLite and IndexedDB, with actual authenticated encryption.
 */
abstract class CollaborationListStorageContract {
    protected abstract fun databaseOwner(): CollaborationListDatabaseOwner

    @Test
    fun newerDenialAtTheFinalClearCannotBeErasedByEitherOlderCompletion() = withDatabase { f ->
        for (admitting in listOf(false, true)) {
            assertEquals(AppResult.Success(true), f.repository.restrict(f.access, f.denied))
            val before = f.admission()
            val newer = CollaborationListAdmissionDto(listId(7777), "AccessDenied", "mfa_required")
            val pending =
                object : CollaborationListAdmissionDataSource by f.admissions {
                    override fun clearIfCurrent(
                        key: String,
                        expected: CollaborationListAdmissionDto?,
                    ): Boolean {
                        f.admissions.write(key, newer)
                        return f.admissions.clearIfCurrent(key, expected)
                    }
                }
            val repository = f.repository(pending = pending)
            if (admitting)
                assertEquals(AppResult.Success(false), repository.admit(f.access, before.revision))
            else assertEquals(AppResult.Success(true), repository.restrict(f.access, f.denied))
            assertEquals(newer.code, f.admission().denial!!.code)
            assertEquals(newer.revision, f.admission().revision)
            assertEquals(AppResult.Success(true), f.repository.admit(f.access, newer.revision))
        }
    }

    @Test
    fun exactRetainedConversationLookupSharesOwnedCheckpointAndSurvivesReopen() =
        withDatabase { f ->
            assertEquals(
                AppResult.Success(null),
                f.repository.get(f.access, listSummary(1).conversation.id),
            )
            f.merge(listOf(listSummary(1), listSummary(2)))
            assertEquals(
                AppResult.Success(listSummary(2)),
                f.repository.get(f.access, listSummary(2).conversation.id),
            )
            val renewed = f.access.copy(sessionId = "another-login")
            f.reopen(renewed)
            assertEquals(
                AppResult.Success(listSummary(2)),
                f.repository.get(renewed, listSummary(2).conversation.id),
            )
            assertEquals(
                AppResult.Success(null),
                f.repository.get(f.access, listSummary(2).conversation.id),
            )
            assertEquals(
                AppResult.Success(null),
                f.repository.get(renewed, listSummary(3).conversation.id),
            )
        }

    @Test
    fun boundedPagesAndAccountFactsSurviveDatabaseAndLocalSessionReplacement() = withDatabase { f ->
        for (batch in (1..85).map(::listSummary).chunked(50)) f.merge(batch)
        assertEquals(85, f.checkpoint()!!.count)
        val first = f.page()
        assertEquals((1..30).map(::listSummary), first.items)
        val second = f.page(CollaborationListSelection(first.nextCursor))
        assertEquals((31..60).map(::listSummary), second.items)
        val third = f.page(CollaborationListSelection(second.nextCursor))
        assertEquals((61..85).map(::listSummary), third.items)
        assertNull(third.nextCursor)
        val renewed = f.access.copy(sessionId = "new-login")
        f.reopen(renewed)
        assertEquals(first, f.page(owner = renewed))
        assertTrue(f.page().items.isEmpty())
        assertEquals(
            AppResult.Success(false),
            f.repository.merge(f.access, null, listOf(listSummary(90)), f.now),
        )
        val other = renewed.copy(accountId = listId(9998))
        f.activate(other)
        assertTrue(f.page(owner = other).items.isEmpty())
        assertTrue(f.page(owner = renewed).items.isEmpty())
        f.activate(renewed)
        assertEquals(first, f.page(owner = renewed))
        assertTrue(f.page(owner = renewed.copy(companyId = listId(9997))).items.isEmpty())
    }

    @Test
    fun mergingLivePagesUpdatesLabelsRetainsUnseenRowsAndRejectsLateWrites() = withDatabase { f ->
        f.merge(listOf(listSummary(1), listSummary(2)))
        val old = f.checkpoint()
        val renamed =
            listSummary(1).copy(peer = listSummary(1).peer.copy(displayName = "Updated colleague"))
        f.merge(listOf(renamed))
        assertEquals(
            AppResult.Success(false),
            f.repository.merge(f.access, old, listOf(listSummary(1)), f.now),
        )
        f.merge(emptyList())
        assertEquals(listOf(renamed, listSummary(2)), f.page().items)
        assertEquals(2, f.checkpoint()!!.count)
    }

    @Test
    fun immutableConversationAndCounterpartValidationRejectsWholeBatch() = withDatabase { f ->
        f.merge(listOf(listSummary(1)))
        for (invalid in
            listOf(
                listSummary(1).copy(conversation = listSummary(1).conversation.copy(createdAt = 1)),
                listSummary(2)
                    .copy(peer = CollaborationParticipant(listId(123), "Wrong counterpart")),
                listSummary(2)
                    .copy(conversation = listSummary(2).conversation.copy(companyId = listId(99))),
                listSummary(2).copy(peer = listSummary(2).peer.copy(displayName = "bad\nlabel")),
            )) {
            assertIs<AppResult.Failed>(
                f.repository.merge(f.access, f.checkpoint(), listOf(listSummary(3), invalid), f.now)
            )
            assertEquals(listOf(listSummary(1)), f.page().items)
        }
    }

    @Test
    fun ciphertextAndAuthenticatedIndexesRejectTamperingAndLostKeys() = withDatabase { f ->
        f.merge(listOf(listSummary(1)))
        val raw = f.raw()
        assertFalse(raw.checkpoint!!.payload.contains("2026"))
        assertFalse(raw.documents.single().payload!!.contains("Fictional"))
        val altered = raw.documents.single().copy(version = 1)
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ) = flowOf(raw.copy(documents = listOf(altered)))
            }
        assertIs<AppResult.Failed>(
            f.repository(store).observe(f.access, CollaborationListSelection()).first()
        )
        f.keys.write(null)
        assertIs<AppResult.Failed>(
            f.repository.observe(f.access, CollaborationListSelection()).first()
        )
        assertIs<AppResult.Failed>(
            f.repository.merge(f.access.copy(companyId = listId(9997)), null, emptyList(), f.now)
        )
        assertNull(f.keys.read())
        assertEquals(raw, f.raw())
    }

    @Test
    fun durableDenialSurvivesRestartAndOnlyItsObservedRevisionCanReopen() = withDatabase { f ->
        f.merge(listOf(listSummary(1)))
        assertEquals(AppResult.Success(true), f.repository.restrict(f.access, f.denied))
        val denied = f.admission()
        val renewed = f.access.copy(sessionId = "new-login")
        f.reopen(renewed)
        assertEquals(denied, f.admission(renewed))
        assertEquals(AppResult.Success(false), f.repository.admit(renewed, null))
        assertEquals(AppResult.Success(true), f.repository.admit(renewed, denied.revision))
        assertNull(f.admission(renewed).denial)
        assertEquals(listOf(listSummary(1)), f.page(owner = renewed).items)
    }

    @Test
    fun failedGateWritesStayClosedThroughSameAccountReauthentication() = withDatabase { f ->
        f.merge(listOf(listSummary(1)))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean = error("Fixture storage unavailable")
            }
        val repository = f.repository(store)
        assertIs<AppResult.Failed>(repository.restrict(f.access, f.denied))
        assertEquals(
            f.denied.code,
            assertIs<AppResult.Success<CollaborationListAdmission>>(repository.admission(f.access))
                .value
                .denial!!
                .code,
        )
        val renewed = f.access.copy(sessionId = "new-login")
        f.activate(renewed)
        val gate =
            assertIs<AppResult.Success<CollaborationListAdmission>>(repository.admission(renewed))
                .value
        assertEquals(f.denied.code, gate.denial!!.code)
        assertIs<AppResult.Failed>(repository.admit(renewed, gate.revision))
        assertEquals(
            gate,
            assertIs<AppResult.Success<CollaborationListAdmission>>(repository.admission(renewed))
                .value,
        )
        assertEquals(listOf(listSummary(1)), f.page(owner = renewed).items)
    }

    @Test
    fun rejectedCompareAndSetCannotDiscardAReceivedDenial() = withDatabase { f ->
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ) = false
            }
        val repository = f.repository(store)
        assertEquals(AppResult.Success(false), repository.restrict(f.access, f.denied))
        val renewed = f.access.copy(sessionId = "new-login")
        f.activate(renewed)
        val admission =
            assertIs<AppResult.Success<CollaborationListAdmission>>(repository.admission(renewed))
                .value
        assertEquals(f.denied.code, admission.denial!!.code)
        assertEquals(AppResult.Success(false), repository.admit(renewed, admission.revision))
        assertEquals(
            admission,
            assertIs<AppResult.Success<CollaborationListAdmission>>(repository.admission(renewed))
                .value,
        )
        assertEquals(AppResult.Success(true), f.repository.admit(renewed, admission.revision))
        assertNull(f.admission(renewed).denial)
    }

    @Test
    fun olderAdmissionCannotClearDenialArrivingDuringItsPendingWrite() = withDatabase { f ->
        assertEquals(AppResult.Success(true), f.repository.restrict(f.access, f.denied))
        val old = f.admission()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                ): Boolean {
                    if (snapshot == null) {
                        entered.complete(Unit)
                        release.await()
                    }
                    return f.database.replicas.replace(scope, partition, expected, snapshot)
                }
            }
        val repository = f.repository(store)
        coroutineScope {
            val reopen = async { repository.admit(f.access, old.revision) }
            entered.await()
            val later = f.denied.copy(code = "mfa_required")
            val denied =
                async(start = CoroutineStart.UNDISPATCHED) { repository.restrict(f.access, later) }
            assertEquals(
                later.code,
                assertIs<AppResult.Success<CollaborationListAdmission>>(
                        repository.admission(f.access)
                    )
                    .value
                    .denial!!
                    .code,
            )
            release.complete(Unit)
            assertEquals(AppResult.Success(false), reopen.await())
            assertEquals(AppResult.Success(true), denied.await())
            assertEquals(later.code, f.admission().denial!!.code)
        }
    }

    @Test
    fun cancelledEncryptionCannotPublishAPartialPage() = withDatabase { f ->
        f.merge(listOf(listSummary(1)))
        val previous = f.page()
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
            val repository =
                EncryptedCollaborationListRepository(
                    f.database.replicas,
                    cipher,
                    f.keys,
                    f.admissions,
                )
            val operation = async {
                repository.merge(f.access, f.checkpoint(), listOf(listSummary(2)), f.now)
            }
            entered.await()
            operation.cancel()
            release.complete(Unit)
            operation.join()
            assertTrue(operation.isCancelled)
        }
        assertEquals(previous, f.page())
    }

    @Test
    fun emptyAndInvalidReadsDoNotCreateKeysOrStartUnboundedQueries() = withDatabase { f ->
        assertEquals(CollaborationLocalList(null, emptyList(), null), f.page())
        assertNull(f.keys.read())
        for (selection in
            listOf(
                CollaborationListSelection(limit = 0),
                CollaborationListSelection(limit = 51),
                CollaborationListSelection("invalid"),
            )) {
            assertIs<AppResult.Failed>(f.repository.observe(f.access, selection).first())
        }
        assertNull(f.keys.read())
        assertIs<AppResult.Failed>(
            f.repository.merge(f.access, null, (1..51).map(::listSummary), f.now)
        )
        assertNull(f.keys.read())
    }

    @Test
    fun pendingGateCapacityFailsClosedWithoutEvictingAnotherAccount() = withDatabase { f ->
        for (index in 1..128) f.admissions.write(
            "owner-$index",
            CollaborationListAdmissionDto(listId(index), "AccessDenied", "permission_denied"),
        )
        assertFailsWith<IllegalStateException> {
            f.admissions.write(
                "overflow",
                CollaborationListAdmissionDto(listId(200), "AccessDenied", null),
            )
        }
        assertIs<AppResult.Failed>(f.repository.admission(f.access))
        assertFailsWith<IllegalStateException> { f.admissions.current("owner-1") }
    }

    private fun withDatabase(block: suspend (CollaborationListFixture) -> Unit) = runTest {
        val owner = databaseOwner()
        val fixture = CollaborationListFixture(owner)
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}

class CollaborationListDatabaseOwner(val open: () -> AppDatabase, val dispose: () -> Unit = {})

private class CollaborationListFixture(private val databaseOwner: CollaborationListDatabaseOwner) {
    var database = databaseOwner.open()
    val access =
        CollaborationLocalAccess("local", "https://hris.example.test/", listId(9000), listId(9001))
    val now = Instant.parse("2026-11-01T00:00:00Z")
    val denied = Failure(FailureKind.AccessDenied, "Denied", code = "permission_denied")
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()
    var admissions = MemoryCollaborationListAdmissionDataSource()
    var repository = repository()

    fun repository(
        store: ReplicaStore = database.replicas,
        pending: CollaborationListAdmissionDataSource = admissions,
    ) = EncryptedCollaborationListRepository(store, cipher, keys, pending)

    suspend fun activate(owner: CollaborationLocalAccess = access) =
        database.accounts.activate(owner.sessionId, listScope(owner).accountKey)

    suspend fun checkpoint() =
        assertIs<AppResult.Success<CollaborationListCheckpoint?>>(repository.checkpoint(access))
            .value

    suspend fun merge(items: List<CollaborationDirectSummary>) {
        assertEquals(AppResult.Success(true), repository.merge(access, checkpoint(), items, now))
    }

    suspend fun page(
        selection: CollaborationListSelection = CollaborationListSelection(),
        owner: CollaborationLocalAccess = access,
    ) =
        assertIs<AppResult.Success<CollaborationLocalList>>(
                repository.observe(owner, selection).first()
            )
            .value

    suspend fun admission(owner: CollaborationLocalAccess = access) =
        assertIs<AppResult.Success<CollaborationListAdmission>>(repository.admission(owner)).value

    suspend fun raw() = database.replicas.observe(listScope(access), listPartition(access)).first()

    suspend fun reopen(owner: CollaborationLocalAccess) {
        database.close()
        database = databaseOwner.open()
        admissions = MemoryCollaborationListAdmissionDataSource()
        repository = repository()
        activate(owner)
    }

    fun close() {
        database.close()
        keys.close()
        databaseOwner.dispose()
    }
}

private fun listId(index: Int) = "a1000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

private fun listSummary(index: Int) =
    CollaborationDirectSummary(
        CollaborationDirectConversation(
            listId(index),
            listId(9001),
            listId(index + 1000),
            listId(9000),
            listId(9000),
            0,
            0,
        ),
        CollaborationParticipant(listId(index + 1000), "Fictional colleague $index"),
    )
