package dev.fajar.starter.inbox.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.inbox.data.mappers.*
import dev.fajar.starter.inbox.data.repositories.EncryptedInboxCacheRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.securestorage.*
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class EncryptedInboxCacheTest {
    private val now = Instant.parse("2026-10-11T00:00:00Z")
    private val access =
        InboxAccess(
            "session",
            "account",
            "https://hris.example.test/",
            "company",
            setOf("announcements.read"),
            now + 24.hours,
            now + 1.hours,
        )
    private val initial =
        InboxCheckpoint(
            access,
            "generation",
            0,
            InboxSyncPhase.BOOTSTRAP,
            null,
            false,
            null,
            now + 1.hours,
            null,
        )
    private val item =
        InboxItem(
            "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222",
            "33333333-3333-3333-3333-333333333333",
            1,
            "Private notice",
            "Private body",
            true,
            now,
            null,
            null,
        )

    @Test
    fun theSameAccountReadsPublishedFactsAfterLoginWhileOldSessionsCannotWrite() = runTest {
        val directory = Files.createTempDirectory("inbox-owner-reopen").toFile()
        var db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(access.sessionId, inboxReplicaScope(access).accountKey)
            var repository = EncryptedInboxCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(AppResult.Success(true), repository.begin(initial, null))
            val published =
                initial.copy(
                    revision = 1,
                    phase = InboxSyncPhase.CHANGES,
                    cursor = "cursor",
                    published = true,
                    refreshedAt = now,
                )
            assertEquals(
                AppResult.Success(true),
                repository.commit(initial, published, listOf(InboxReplicaEntry(item.id, 1, item))),
            )
            db.accounts.activate(null)
            assertEquals(
                AppResult.Success(InboxCachePage(null, emptyList(), null)),
                repository.observe(access, InboxCacheQuery.Page()).first(),
            )
            db.close()
            db = createAppDatabase(directory)
            val renewed = access.copy(sessionId = "new-login")
            db.accounts.activate(renewed.sessionId, inboxReplicaScope(renewed).accountKey)
            repository = EncryptedInboxCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            val expected = AppResult.Success(InboxCachePage(published, listOf(item), null))
            assertEquals(expected, repository.observe(renewed, InboxCacheQuery.Page()).first())
            assertEquals(
                expected,
                repository.observe(renewed, InboxCacheQuery.Item(item.id)).first(),
            )
            assertEquals(AppResult.Success(published), repository.checkpoint(renewed))
            assertEquals(
                AppResult.Success(false),
                repository.commit(
                    published,
                    published.copy(revision = 2),
                    listOf(InboxReplicaEntry(item.id, 2, item.copy(version = 2))),
                ),
            )
            assertEquals(AppResult.Success(false), repository.clear(access))
            val replacement = initial.copy(access = renewed, generation = "new-login-bootstrap")
            assertEquals(
                AppResult.Success(true),
                repository.begin(replacement, published.generation),
            )
            assertEquals(expected, repository.observe(renewed, InboxCacheQuery.Page()).first())
            assertEquals(AppResult.Success(replacement), repository.checkpoint(renewed))
        } finally {
            db.close()
            keys.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun thePreviousStorageFormatRemainsReadableOfflineUntilItsReplacementIsPublished() = runTest {
        val directory = Files.createTempDirectory("inbox-legacy-read").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        val cipher = AesGcmContentCipher()
        val key = cipher.generateKey()
        try {
            db.accounts.activate(access.sessionId, inboxReplicaScope(access).accountKey)
            keys.write(Base64.encode(key))
            val old =
                initial.copy(
                    revision = 0,
                    phase = InboxSyncPhase.CHANGES,
                    cursor = "old-cursor",
                    published = true,
                    refreshedAt = now,
                )
            val checkpoint =
                cipher.encrypt(
                    key,
                    inboxAssociatedData(
                        access,
                        old.generation,
                        "checkpoint",
                        "",
                        0,
                        legacySessionBound = true,
                    ),
                    Json.encodeToString(old.toDto().copy(syncSessionId = null)).encodeToByteArray(),
                )
            val payload =
                cipher.encrypt(
                    key,
                    inboxAssociatedData(
                        access,
                        old.generation,
                        "item",
                        item.id,
                        item.version,
                        legacySessionBound = true,
                    ),
                    Json.encodeToString(item.toDto()).encodeToByteArray(),
                )
            assertTrue(
                db.replicas.replace(
                    ReplicaScope(access.sessionId),
                    inboxPartition(access),
                    null,
                    ReplicaSnapshot(
                        old.generation,
                        Base64.encode(checkpoint),
                        listOf(ReplicaDocument(item.id, 1, item.sortKey(), Base64.encode(payload))),
                    ),
                )
            )
            val repository = EncryptedInboxCacheRepository(db.replicas, cipher, keys)
            val expected = AppResult.Success(InboxCachePage(old, listOf(item), null))
            assertEquals(expected, repository.observe(access, InboxCacheQuery.Page()).first())
            assertEquals(AppResult.Success(null), repository.checkpoint(access))
            assertEquals(
                AppResult.Success(true),
                repository.begin(initial.copy(generation = "account-generation"), null),
            )
            assertEquals(
                expected,
                repository.observe(access, InboxCacheQuery.Item(item.id)).first(),
            )
            val staged = initial.copy(generation = "account-generation")
            val ready =
                staged.copy(
                    revision = 1,
                    phase = InboxSyncPhase.CHANGES,
                    cursor = "account-cursor",
                    published = true,
                    refreshedAt = now,
                )
            assertEquals(AppResult.Success(true), repository.commit(staged, ready, emptyList()))
            // An empty complete replacement must not resurrect messages from the legacy table.
            assertEquals(
                AppResult.Success(InboxCachePage(ready, emptyList(), null)),
                repository.observe(access, InboxCacheQuery.Page()).first(),
            )
            assertEquals(AppResult.Success(true), repository.clear(access))
            assertEquals(
                AppResult.Success(InboxCachePage(null, emptyList(), null)),
                repository.observe(access, InboxCacheQuery.Page()).first(),
            )
            assertFalse(db.replicas.containsRecords("inbox/"))
        } finally {
            key.fill(0)
            keys.close()
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingKeysCannotBeReplacedWhileAnInactiveAccountStillHasEncryptedMessages() = runTest {
        val directory = Files.createTempDirectory("inbox-inactive-key").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(access.sessionId, inboxReplicaScope(access).accountKey)
            val repository = EncryptedInboxCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(AppResult.Success(true), repository.begin(initial, null))
            keys.write(null)
            val other =
                access.copy(
                    sessionId = "other-session",
                    accountId = "other-account",
                    companyId = "other-company",
                )
            db.accounts.activate(other.sessionId, inboxReplicaScope(other).accountKey)
            assertIs<AppResult.Failed>(repository.begin(initial.copy(access = other), null))
            assertNull(keys.read())
            assertTrue(db.replicas.containsRecords("inbox/"))
        } finally {
            keys.close()
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun copiedCiphertextStillRejectsAnotherAccountEvenIfItsRawStorageKeyIsRewritten() = runTest {
        val directory = Files.createTempDirectory("inbox-owner-aad").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(access.sessionId, inboxReplicaScope(access).accountKey)
            val repository = EncryptedInboxCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(AppResult.Success(true), repository.begin(initial, null))
            val published =
                initial.copy(
                    revision = 1,
                    phase = InboxSyncPhase.CHANGES,
                    cursor = "cursor",
                    published = true,
                    refreshedAt = now,
                )
            assertEquals(
                AppResult.Success(true),
                repository.commit(initial, published, listOf(InboxReplicaEntry(item.id, 1, item))),
            )
            val original =
                db.replicas.observe(inboxReplicaScope(access), inboxPartition(access)).first()
            for (other in
                listOf(
                    access.copy(accountId = "other-account"),
                    access.copy(audience = "https://other.example.test/"),
                )) {
                db.accounts.activate(other.sessionId, inboxReplicaScope(other).accountKey)
                assertTrue(
                    db.replicas.begin(
                        inboxReplicaScope(other),
                        inboxPartition(other),
                        published.generation,
                        "initial",
                        null,
                    )
                )
                assertTrue(
                    db.replicas.commit(
                        ReplicaHandle(
                            inboxReplicaScope(other),
                            inboxPartition(other),
                            published.generation,
                        ),
                        0,
                        original.checkpoint!!.payload,
                        original.documents,
                        true,
                    )
                )
                assertEquals(
                    FailureKind.Storage,
                    assertIs<AppResult.Failed>(
                            repository.observe(other, InboxCacheQuery.Page()).first()
                        )
                        .failure
                        .kind,
                )
            }
        } finally {
            keys.close()
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun sqliteContainsCiphertextAndDetailUsesTheSamePublishedGenerationAsTheList() = runTest {
        val directory = Files.createTempDirectory("inbox-encryption").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(access.sessionId, inboxReplicaScope(access).accountKey)
            val repository = EncryptedInboxCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(AppResult.Success(true), repository.begin(initial, null))
            val staged =
                initial.copy(
                    revision = 1,
                    phase = InboxSyncPhase.CHANGES,
                    cursor = "private-cursor",
                )
            assertEquals(
                AppResult.Success(true),
                repository.commit(
                    initial,
                    staged,
                    listOf(InboxReplicaEntry(item.id, item.version, item)),
                ),
            )
            assertNull(
                assertIs<AppResult.Success<InboxCachePage>>(
                        repository.observe(access, InboxCacheQuery.Item(item.id)).first()
                    )
                    .value
                    .checkpoint
            )
            assertEquals(
                InboxCachePage(staged, emptyList(), null),
                assertIs<AppResult.Success<InboxCachePage>>(
                        repository.observe(access, InboxCacheQuery.Checkpoint).first()
                    )
                    .value,
            )
            val visible = staged.copy(revision = 2, published = true, refreshedAt = now)
            assertEquals(AppResult.Success(true), repository.commit(staged, visible, emptyList()))
            val detail =
                assertIs<AppResult.Success<InboxCachePage>>(
                        repository.observe(access, InboxCacheQuery.Item(item.id)).first()
                    )
                    .value
            assertEquals(listOf(item), detail.items)
            assertEquals(visible, detail.checkpoint)
            assertEquals(
                InboxCachePage(visible, emptyList(), null),
                assertIs<AppResult.Success<InboxCachePage>>(
                        repository.observe(access, InboxCacheQuery.Checkpoint).first()
                    )
                    .value,
            )
            assertEquals(
                detail,
                assertIs<AppResult.Success<InboxCachePage>>(
                        repository.observe(access, InboxCacheQuery.Page()).first()
                    )
                    .value,
            )
            val raw =
                db.replicas
                    .observeDocument(inboxReplicaScope(access), inboxPartition(access), item.id)
                    .first()
            assertFalse(raw.documents.single().payload!!.contains("Private"))
            assertFalse(raw.checkpoint!!.payload.contains("private-cursor"))
            val restored = EncryptedInboxCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            assertEquals(
                detail,
                assertIs<AppResult.Success<InboxCachePage>>(
                        restored.observe(access, InboxCacheQuery.Item(item.id)).first()
                    )
                    .value,
            )
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun replacementBootstrapRetainsPublishedDataAcrossDatabaseReopenUntilAtomicPublication() =
        runTest {
            val directory = Files.createTempDirectory("inbox-replacement").toFile()
            var db = createAppDatabase(directory)
            val keys = MemoryCredentialStore()
            try {
                db.accounts.activate(access.sessionId, inboxReplicaScope(access).accountKey)
                var repository =
                    EncryptedInboxCacheRepository(db.replicas, AesGcmContentCipher(), keys)
                assertEquals(AppResult.Success(true), repository.begin(initial, null))
                val published =
                    initial.copy(
                        revision = 1,
                        phase = InboxSyncPhase.CHANGES,
                        cursor = "old-cursor",
                        published = true,
                        refreshedAt = now,
                    )
                assertEquals(
                    AppResult.Success(true),
                    repository.commit(
                        initial,
                        published,
                        listOf(InboxReplicaEntry(item.id, item.version, item)),
                    ),
                )
                val previous = InboxCachePage(published, listOf(item), null)
                val replacement =
                    initial.copy(generation = "replacement", refreshAfter = now + 73.hours)
                assertEquals(
                    AppResult.Success(true),
                    repository.begin(replacement, published.generation),
                )
                assertEquals(
                    AppResult.Success(previous),
                    repository.observe(access, InboxCacheQuery.Page()).first(),
                )
                assertEquals(AppResult.Success(replacement), repository.checkpoint(access))
                db.close()
                db = createAppDatabase(directory)
                repository = EncryptedInboxCacheRepository(db.replicas, AesGcmContentCipher(), keys)
                assertEquals(
                    AppResult.Success(previous),
                    repository.observe(access, InboxCacheQuery.Page()).first(),
                )
                assertEquals(AppResult.Success(replacement), repository.checkpoint(access))
                val updated = item.copy(title = "Updated notice", version = item.version + 1)
                val ready =
                    replacement.copy(
                        revision = 1,
                        phase = InboxSyncPhase.CHANGES,
                        cursor = "new-cursor",
                        published = true,
                        refreshedAt = now + 72.hours,
                    )
                assertEquals(
                    AppResult.Success(true),
                    repository.commit(
                        replacement,
                        ready,
                        listOf(InboxReplicaEntry(updated.id, updated.version, updated)),
                    ),
                )
                assertEquals(
                    AppResult.Success(InboxCachePage(ready, listOf(updated), null)),
                    repository.observe(access, InboxCacheQuery.Page()).first(),
                )
                assertEquals(
                    AppResult.Success(false),
                    repository.commit(
                        published,
                        published.copy(revision = 2),
                        listOf(InboxReplicaEntry(item.id, item.version, item)),
                    ),
                )
                assertEquals(
                    AppResult.Success(InboxCachePage(ready, listOf(updated), null)),
                    repository.observe(access, InboxCacheQuery.Item(item.id)).first(),
                )
            } finally {
                db.close()
                directory.deleteRecursively()
            }
        }

    @Test
    fun ciphertextCannotBeReadWithAnotherAccountAudienceOrKey() = runTest {
        val directory = Files.createTempDirectory("inbox-binding").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(access.sessionId, inboxReplicaScope(access).accountKey)
            val cipher = AesGcmContentCipher()
            val repository = EncryptedInboxCacheRepository(db.replicas, cipher, keys)
            repository.begin(initial, null)
            val visible =
                initial.copy(
                    revision = 1,
                    phase = InboxSyncPhase.CHANGES,
                    cursor = "cursor",
                    published = true,
                    refreshedAt = now,
                )
            repository.commit(initial, visible, listOf(InboxReplicaEntry(item.id, 1, item)))
            for (wrong in
                listOf(
                    access.copy(accountId = "another-account"),
                    access.copy(audience = "https://another.example.test/"),
                )) {
                assertEquals(
                    AppResult.Success(InboxCachePage(null, emptyList(), null)),
                    repository.observe(wrong, InboxCacheQuery.Page()).first(),
                )
            }
            val otherCompany =
                repository
                    .observe(access.copy(companyId = "other-company"), InboxCacheQuery.Page())
                    .first()
            assertTrue(
                assertIs<AppResult.Success<InboxCachePage>>(otherCompany).value.items.isEmpty()
            )
            keys.write(kotlin.io.encoding.Base64.encode(cipher.generateKey()))
            assertIs<AppResult.Failed>(repository.checkpoint(access))
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingContentKeysAreNotRegeneratedOverAnExistingEncryptedPartition() = runTest {
        val directory = Files.createTempDirectory("inbox-key-loss").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(access.sessionId, inboxReplicaScope(access).accountKey)
            val repository = EncryptedInboxCacheRepository(db.replicas, AesGcmContentCipher(), keys)
            repository.begin(initial, null)
            keys.write(null)
            assertIs<AppResult.Failed>(repository.checkpoint(access))
            assertIs<AppResult.Failed>(
                repository.begin(initial.copy(generation = "replacement"), initial.generation)
            )
            assertNull(keys.read())
            assertEquals(
                initial.generation,
                db.replicas
                    .checkpoint(inboxReplicaScope(access), inboxPartition(access))!!
                    .handle
                    .generation,
            )
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun aLateCommitCannotRecreateCacheAfterAccountReplacement() = runTest {
        val directory = Files.createTempDirectory("inbox-account-change").toFile()
        val db = createAppDatabase(directory)
        try {
            db.accounts.activate(access.sessionId, inboxReplicaScope(access).accountKey)
            val repository =
                EncryptedInboxCacheRepository(
                    db.replicas,
                    AesGcmContentCipher(),
                    MemoryCredentialStore(),
                )
            repository.begin(initial, null)
            db.accounts.activate("replacement-session")
            val visible =
                initial.copy(
                    revision = 1,
                    phase = InboxSyncPhase.CHANGES,
                    cursor = "cursor",
                    published = true,
                    refreshedAt = now,
                )
            assertEquals(
                AppResult.Success(false),
                repository.commit(initial, visible, listOf(InboxReplicaEntry(item.id, 1, item))),
            )
            assertTrue(
                assertIs<AppResult.Success<InboxCachePage>>(
                        repository.observe(access, InboxCacheQuery.Page()).first()
                    )
                    .value
                    .items
                    .isEmpty()
            )
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }
}
