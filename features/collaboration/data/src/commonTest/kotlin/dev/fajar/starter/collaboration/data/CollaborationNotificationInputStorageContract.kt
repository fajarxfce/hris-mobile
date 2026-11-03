package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.notifications.NOTIFICATION_INPUT_NAMESPACE
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationNotificationInputRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

/** Real authenticated encryption with actual SQLite/IndexedDB, before any account is activated. */
abstract class CollaborationNotificationInputStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun protectsUnverifiedInputBeforeLoginAndReopensWithoutPlaintextMetadata() = withDatabase { f ->
        val reply = reply()
        val saved = retained(f.repo().retain(reply))
        assertEquals(reply, saved.reply)
        val raw = f.raw().single()
        assertEquals(saved.reference.id, raw.id)
        for (sensitive in listOf(reply.text, reply.token.encoded)) {
            assertFalse(raw.payload.contains(sensitive))
            assertFalse(raw.id.contains(sensitive))
            assertFalse(saved.toString().contains(sensitive))
            assertFalse(saved.reply.toString().contains(sensitive))
        }
        f.reopen()
        assertEquals(saved, expect(f.repo().get(saved.reference)))
        f.database.accounts.activate("first", "first-account")
        f.database.accounts.activate(null)
        f.database.accounts.activate("second", "second-account")
        assertEquals(saved, expect(f.repo().get(saved.reference)))
        assertEquals(listOf(raw), f.raw())
    }

    @Test
    fun duplicateCallbacksReuseOriginalCiphertextAndGenerationButDifferentTextAndTokenDoNot() =
        withDatabase { f ->
            val reply = reply()
            val first = retained(f.repo().retain(reply))
            val before = f.raw()
            assertEquals(first, retained(f.repo().retain(reply)))
            f.reopen()
            assertEquals(first, retained(f.repo().retain(reply)))
            assertEquals(before, f.raw())
            val edited = retained(f.repo().retain(reply.copy(text = reply.text + " changed")))
            val otherToken = retained(f.repo().retain(reply.copy(token = reply(2).token)))
            assertEquals(
                3,
                setOf(first.reference.id, edited.reference.id, otherToken.reference.id).size,
            )
        }

    @Test
    fun simultaneousRepositoryAndDatabaseOwnersAgreeOnOneProtectedKeyAndOriginal() =
        withDatabase { f ->
            val second = f.owner.open()
            try {
                coroutineScope {
                    val ready = CompletableDeferred<Unit>()
                    val repositories = listOf(f.repo(), f.repo(second.deviceRecords))
                    val attempts =
                        (0 until 8).map { index ->
                            async {
                                ready.await()
                                retained(repositories[index % 2].retain(reply()))
                            }
                        }
                    ready.complete(Unit)
                    val results = attempts.awaitAll()
                    assertTrue(results.all { it == results.first() })
                    assertEquals(1, f.raw().size)
                    f.reopen()
                    assertEquals(results.first(), expect(f.repo().get(results.first().reference)))
                }
            } finally {
                second.close()
            }
        }

    @Test
    fun capacityNeverEvictsExistingInputAndReplaysStillSucceed() = withDatabase { f ->
        val originals = (0 until 64).map { retained(f.repo().retain(reply(it))) }
        val before = f.raw()
        assertEquals(
            CollaborationNotificationInputRetention.CapacityExceeded,
            expect(f.repo().retain(reply(999))),
        )
        assertEquals(originals.first(), retained(f.repo().retain(reply(0))))
        assertEquals(before, f.raw())
        assertTrue(expect(f.repo().remove(originals.first())))
        assertIs<CollaborationNotificationInputRetention.Retained>(
            expect(f.repo().retain(reply(999)))
        )
        assertEquals(64, f.raw().size)
    }

    @Test
    fun metadataPagesAreBoundedAndReadableWithoutUnlockingTheInputKey() = withDatabase { f ->
        val inputs =
            (0 until 23).map { retained(f.repo().retain(reply(it))) }.sortedBy { it.reference.id }
        val unavailable =
            object : AtomicCredentialStore by f.keys {
                override suspend fun read(): String? =
                    error("Key must not be read for opaque references")
            }
        val repository = f.repo(keys = unavailable)
        assertEquals(inputs.take(20).map { it.reference }, expect(repository.page(limit = 20)))
        assertEquals(
            inputs.takeLast(3).map { it.reference },
            expect(repository.page(inputs[19].reference.id, 20)),
        )
        assertTrue(expect(repository.page(inputs.last().reference.id)).isEmpty())
        assertIs<AppResult.Failed>(repository.get(inputs.first().reference))
        for (limit in listOf(0, 21)) assertIs<AppResult.Failed>(repository.page(limit = limit))
        assertIs<AppResult.Failed>(repository.page("invalid"))
        assertEquals(23, f.raw().size)
    }

    @Test
    fun missingMalformedAndWrongKeysCannotReplaceKeyOrDiscardCiphertext() = withDatabase { f ->
        val first = retained(f.repo().retain(reply()))
        val originalKey = requireNotNull(f.keys.read())
        val before = f.raw()
        for (replacement in listOf(null, "malformed", Base64.encode(ByteArray(64) { 7 }))) {
            f.keys.write(replacement)
            assertIs<AppResult.Failed>(f.repo().get(first.reference))
            // A wrong but well-formed key must not mint new records over this namespace either.
            assertIs<AppResult.Failed>(f.repo().retain(reply(2)))
            assertIs<AppResult.Failed>(f.repo().remove(first))
            assertEquals(replacement, f.keys.read())
            assertEquals(before, f.raw())
        }
        f.keys.write(originalKey)
        assertEquals(first, expect(f.repo().get(first.reference)))
    }

    @Test
    fun relabelledAndChangedCiphertextCannotBeReadOrRemovedAsTheOriginal() = withDatabase { f ->
        val first = retained(f.repo().retain(reply()))
        val raw = f.raw().single()
        val alternatives =
            listOf(
                raw.copy(payload = Base64.encode(ByteArray(64))),
                raw.copy(generation = "00000000-0000-4000-8000-000000000077"),
                raw.copy(id = "q-" + "b".repeat(64)),
            )
        for (changed in alternatives) {
            assertTrue(f.database.deviceRecords.remove(raw))
            assertEquals(changed, f.database.deviceRecords.retain(changed, 64))
            assertIs<AppResult.Failed>(
                f.repo()
                    .get(CollaborationNotificationInputReference(changed.id, changed.generation))
            )
            assertEquals(listOf(changed), f.raw())
            assertTrue(f.database.deviceRecords.remove(changed))
            assertEquals(raw, f.database.deviceRecords.retain(raw, 64))
        }
        assertEquals(first, expect(f.repo().get(first.reference)))
    }

    @Test
    fun authenticatedUnknownSchemaOrChangedInputCannotReuseAnOriginalIndex() = withDatabase { f ->
        val first = retained(f.repo().retain(reply()))
        val raw = f.raw().single()
        val dto = buildJsonObject {
            put("token", first.reply.token.encoded)
            put("text", first.reply.text)
            put("schema", 1)
        }
        for (modified in
            listOf(
                JsonObject(dto + ("schema" to JsonPrimitive(2))),
                JsonObject(dto + ("text" to JsonPrimitive("Changed private reply"))),
                JsonObject(dto + ("token" to JsonPrimitive("v2.Zh=="))),
            )) {
            val forged = f.forge(raw, modified.toString())
            assertTrue(f.database.deviceRecords.remove(raw))
            assertNotNull(f.database.deviceRecords.retain(forged, 64))
            assertIs<AppResult.Failed>(f.repo().get(first.reference))
            assertIs<AppResult.Failed>(f.repo().remove(first))
            assertTrue(f.database.deviceRecords.remove(forged))
            assertNotNull(f.database.deviceRecords.retain(raw, 64))
        }
        assertEquals(first, expect(f.repo().get(first.reference)))
    }

    @Test
    fun staleCleanupCannotDeleteRecreatedInputEvenWithIdenticalTokenAndText() = withDatabase { f ->
        val first = retained(f.repo().retain(reply()))
        assertIs<AppResult.Failed>(f.repo().remove(first.copy(reply = reply(2))))
        assertTrue(expect(f.repo().remove(first)))
        assertFalse(expect(f.repo().remove(first)))
        val next = retained(f.repo().retain(reply()))
        assertEquals(first.reference.id, next.reference.id)
        assertNotEquals(first.reference.generation, next.reference.generation)
        assertNull(expect(f.repo().get(first.reference)))
        assertFalse(expect(f.repo().remove(first)))
        assertEquals(next, expect(f.repo().get(next.reference)))
    }

    @Test
    fun lostWriteResponseLeavesOneRecoverableOriginalWithoutHiddenRetry() = withDatabase { f ->
        var writes = 0
        val actual = f.database.deviceRecords
        val lost =
            object : DeviceRecordStore by actual {
                override suspend fun retain(record: DeviceRecord, maxItems: Int): DeviceRecord? {
                    writes++
                    actual.retain(record, maxItems)
                    error("Controlled response loss after durable commit")
                }
            }
        assertIs<AppResult.Failed>(f.repo(lost).retain(reply()))
        assertEquals(1, writes)
        val before = f.raw().single()
        f.reopen()
        val recovered = retained(f.repo().retain(reply()))
        assertEquals(before.id, recovered.reference.id)
        assertEquals(before.generation, recovered.reference.generation)
        assertEquals(listOf(before), f.raw())
    }

    @Test
    fun cancellationDuringKeyReadJoinsTheOwnedReadWithoutSavingAnything() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        var joined = false
        val pending =
            object : AtomicCredentialStore by f.keys {
                override suspend fun read(): String? {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        joined = true
                    }
                }
            }
        var published = false
        coroutineScope {
            val job = launch {
                f.repo(keys = pending).retain(reply())
                published = true
            }
            select {
                entered.onAwait {}
                job.onJoin { error("Operation exited before the read barrier") }
            }
            job.cancelAndJoin()
        }
        assertTrue(joined)
        assertFalse(published)
        assertTrue(f.raw().isEmpty())
        assertNull(f.keys.read())
    }

    @Test
    fun cancellationAfterCommitKeepsInputAndDoesNotReportSaved() = withDatabase { f ->
        val entered = CompletableDeferred<Unit>()
        var joined = false
        var published = false
        val actual = f.database.deviceRecords
        val pending =
            object : DeviceRecordStore by actual {
                override suspend fun retain(record: DeviceRecord, maxItems: Int): DeviceRecord? {
                    actual.retain(record, maxItems)
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        joined = true
                    }
                }
            }
        coroutineScope {
            val job = launch {
                f.repo(pending).retain(reply())
                published = true
            }
            select {
                entered.onAwait {}
                job.onJoin { error("Operation exited before the write barrier") }
            }
            job.cancelAndJoin()
        }
        assertTrue(joined)
        assertFalse(published)
        val raw = f.raw().single()
        f.reopen()
        val recovered = retained(f.repo().retain(reply()))
        assertEquals(raw.generation, recovered.reference.generation)
        assertEquals(listOf(raw), f.raw())
    }

    @Test
    fun uncertainReadbackDoesNotReportSavedAndPreservesTheCommittedInput() = withDatabase { f ->
        val actual = f.database.deviceRecords
        val uncertain =
            object : DeviceRecordStore by actual {
                override suspend fun get(namespace: String, id: String): DeviceRecord? = null
            }
        assertEquals(
            CollaborationNotificationInputRetention.Contended,
            expect(f.repo(uncertain).retain(reply())),
        )
        val raw = f.raw().single()
        assertEquals(raw.generation, retained(f.repo().retain(reply())).reference.generation)
    }

    @Test
    fun malformedAndNoncanonicalInputRejectsBeforeKeyCreationAndMaximumTextReopens() =
        withDatabase { f ->
            val valid = reply()
            val invalid =
                listOf(
                    valid.copy(text = ""),
                    valid.copy(text = " "),
                    valid.copy(text = "a".repeat(16385)),
                    valid.copy(text = "\uD800"),
                    valid.copy(text = "\u0000"),
                    valid.copy(token = CollaborationNotificationActionToken("v2.Zh==")),
                    valid.copy(token = CollaborationNotificationActionToken("v3.Zg==")),
                    valid.copy(token = CollaborationNotificationActionToken("v2.no-padding")),
                )
            for (input in invalid) assertIs<AppResult.Failed>(f.repo().retain(input))
            assertTrue(f.raw().isEmpty())
            assertNull(f.keys.read())
            val maximum = valid.copy(text = "é".repeat(16384))
            val saved = retained(f.repo().retain(maximum))
            f.reopen()
            assertEquals(saved, expect(f.repo().get(saved.reference)))
        }

    private fun reply(index: Int = 1) =
        CollaborationUnverifiedNotificationReply(
            CollaborationNotificationActionToken(
                "v2." +
                    Base64.UrlSafe.encode(
                        "unverified-fixture-descriptor-$index".encodeToByteArray()
                    )
            ),
            " Private unsent input $index\nSecond line\t ",
        )

    private fun retained(result: AppResult<CollaborationNotificationInputRetention>) =
        assertIs<CollaborationNotificationInputRetention.Retained>(expect(result)).input

    private fun <T> expect(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

    private fun withDatabase(block: suspend (InputFixture) -> Unit) = runTest {
        val fixture = InputFixture(databaseOwner())
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}

private class InputFixture(val owner: CollaborationHistoryDatabaseOwner) {
    var database = owner.open()
    val keys = MemoryCredentialStore()
    val cipher = AesGcmContentCipher()

    fun repo(
        store: DeviceRecordStore = database.deviceRecords,
        keys: AtomicCredentialStore = this.keys,
    ) = EncryptedCollaborationNotificationInputRepository(store, cipher, keys)

    suspend fun raw(): List<DeviceRecord> {
        val result = mutableListOf<DeviceRecord>()
        var after: String? = null
        do {
            val page = database.deviceRecords.page(NOTIFICATION_INPUT_NAMESPACE, after, 32)
            result.addAll(page)
            after = page.lastOrNull()?.id
        } while (page.size == 32)
        return result
    }

    suspend fun forge(raw: DeviceRecord, json: String): DeviceRecord {
        val full = Base64.decode(requireNotNull(keys.read()))
        val key = full.copyOfRange(0, 32)
        val plain = json.encodeToByteArray()
        val aad =
            Json.encodeToString(listOf(NOTIFICATION_INPUT_NAMESPACE, raw.id, raw.generation))
                .encodeToByteArray()
        return try {
            raw.copy(payload = Base64.encode(cipher.encrypt(key, aad, plain)))
        } finally {
            full.fill(0)
            key.fill(0)
            plain.fill(0)
        }
    }

    fun reopen() {
        database.close()
        database = owner.open()
    }

    fun close() {
        database.close()
        owner.dispose()
    }
}
