package dev.fajar.starter.inbox.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.inbox.data.mappers.inboxPartition
import dev.fajar.starter.inbox.data.repositories.EncryptedInboxCacheRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.securestorage.*
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

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
    fun sqliteContainsCiphertextAndDetailUsesTheSamePublishedGenerationAsTheList() = runTest {
        val directory = Files.createTempDirectory("inbox-encryption").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(access.sessionId)
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
                    .observeDocument(access.sessionId, inboxPartition(access), item.id)
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
    fun ciphertextCannotBeReadWithAnotherAccountAudienceOrKey() = runTest {
        val directory = Files.createTempDirectory("inbox-binding").toFile()
        val db = createAppDatabase(directory)
        val keys = MemoryCredentialStore()
        try {
            db.accounts.activate(access.sessionId)
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
                    FailureKind.Storage,
                    assertIs<AppResult.Failed>(
                            repository.observe(wrong, InboxCacheQuery.Page()).first()
                        )
                        .failure
                        .kind,
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
            db.accounts.activate(access.sessionId)
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
                db.replicas.checkpoint(access.sessionId, inboxPartition(access))!!.handle.generation,
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
            db.accounts.activate(access.sessionId)
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
