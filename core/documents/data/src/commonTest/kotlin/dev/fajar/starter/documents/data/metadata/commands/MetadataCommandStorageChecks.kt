package dev.fajar.starter.documents.data.metadata.commands

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.metadata.commands.mappers.*
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.securestorage.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

suspend fun checkMetadataCommandRestartAndOriginalReceipt(f: MetadataCommandStorageFixture) {
    assertNull(f.keys.read())
    assertEquals(
        AppResult.Success(DocumentMetadataCommandAdmission.STORED),
        f.repository.insert(f.access, f.command),
    )
    val original = f.stored()
    assertEquals(
        AppResult.Success(true),
        f.repository.update(f.access, original, DocumentMetadataCommandOutcome.Unconfirmed(), 1),
    )
    f.reopen()
    val sending = f.stored()
    assertEquals(f.command, sending.command)
    assertIs<DocumentMetadataCommandOutcome.Unconfirmed>(sending.outcome)
    val renewed =
        f.access.copy(
            sessionId = "renewed",
            sessionExpiresAt = f.access.sessionExpiresAt + 365.days,
        )
    f.reopen(renewed)
    val restored = f.stored(renewed)
    assertEquals(sending, restored)
    assertEquals(
        AppResult.Success(true),
        f.repository.update(
            renewed,
            restored,
            DocumentMetadataCommandOutcome.Confirmed(f.command.change.expectedVersion + 1),
        ),
    )
    f.reopen(renewed)
    val receipt = f.stored(renewed)
    assertEquals(DocumentMetadataCommandOutcome.Confirmed(4), receipt.outcome)
    assertEquals(f.command, receipt.command)
    assertEquals(
        AppResult.Success(false),
        f.repository.update(renewed, receipt, DocumentMetadataCommandOutcome.Unconfirmed(), 2),
    )
    assertEquals(AppResult.Success(false), f.repository.remove(renewed, restored))
    assertEquals(AppResult.Success(true), f.repository.remove(renewed, receipt))
    assertNull(
        assertIs<AppResult.Success<DocumentMetadataCommandRecord?>>(
                f.repository.find(renewed, f.command.change.operationId)
            )
            .value
    )
}

suspend fun checkMetadataCommandExactWritesAndResourceSlot(f: MetadataCommandStorageFixture) {
    f.repository.insert(f.access, f.command)
    assertEquals(
        AppResult.Success(DocumentMetadataCommandAdmission.STORED),
        f.repository.insert(f.access, f.command),
    )
    val other = f.command.copy(change = f.command.change.copy(operationId = metadataCommandId(2)))
    assertEquals(
        AppResult.Success(DocumentMetadataCommandAdmission.OCCUPIED),
        f.repository.insert(f.access, other),
    )
    val original = f.stored()
    val forged =
        original.copy(
            command = f.command.copy(change = f.command.change.copy(reason = "Different"))
        )
    assertEquals(AppResult.Success(false), f.repository.remove(f.access, forged))
    assertEquals(
        AppResult.Success(false),
        f.repository.update(f.access, forged, DocumentMetadataCommandOutcome.Unconfirmed(), 1),
    )
    assertEquals(
        AppResult.Success(true),
        f.repository.update(f.access, original, DocumentMetadataCommandOutcome.Unconfirmed(), 1),
    )
    assertEquals(
        AppResult.Success(false),
        f.repository.update(f.access, original, DocumentMetadataCommandOutcome.Unconfirmed(), 1),
    )
    val stored = f.stored()
    assertIs<AppResult.Failed>(
        f.repository.update(f.access, stored, DocumentMetadataCommandOutcome.Confirmed(99))
    )
    assertEquals(AppResult.Success(false), f.repository.remove(f.access, original))
    assertEquals(
        AppResult.Success(true),
        f.repository.update(
            f.access,
            stored,
            DocumentMetadataCommandOutcome.Rejected(
                Failure(FailureKind.Conflict, "PRIVATE diagnostic", code = "stale_version")
            ),
        ),
    )
    val rejected = f.stored()
    assertIs<DocumentMetadataCommandOutcome.Rejected>(rejected.outcome)
    assertFalse(rejected.outcome.toString().contains("PRIVATE diagnostic"))
    assertEquals(AppResult.Success(true), f.repository.remove(f.access, rejected))
    assertEquals(
        AppResult.Success(DocumentMetadataCommandAdmission.STORED),
        f.repository.insert(f.access, other),
    )
}

suspend fun checkMetadataCommandAccountIsolationAndSharedCapacity(
    f: MetadataCommandStorageFixture
) {
    val scope = metadataCommandScope(f.access)
    assertEquals(
        AppResult.Success(DocumentMetadataCommandAdmission.STORED),
        f.repository.insert(f.access, f.command),
    )
    // Occupied capacity in a different feature still counts toward this account's shared budget.
    repeat(127) { index ->
        val id = metadataCommandId(index)
        assertEquals(
            CommandInsertResult.Stored,
            f.db.commands.insert(
                scope,
                CommandRecord("other-feature", id, id, id, "sealed-request", "sealed-state"),
            ),
        )
    }
    val next =
        f.command.copy(
            change =
                f.command.change.copy(
                    operationId = metadataCommandId(128),
                    documentId = metadataCommandId(128),
                )
        )
    assertEquals(
        AppResult.Success(DocumentMetadataCommandAdmission.CAPACITY_EXCEEDED),
        f.repository.insert(f.access, next),
    )
    assertEquals(
        1,
        assertIs<AppResult.Success<List<DocumentMetadataCommandRecord>>>(
                f.repository.observeAll(f.access).first()
            )
            .value
            .size,
    )
    val company = f.access.copy(companyId = metadataCommandId(300))
    assertNull(
        assertIs<AppResult.Success<DocumentMetadataCommandRecord?>>(
                f.repository.observe(company, f.command.change.documentId).first()
            )
            .value
    )
    val replacement = f.access.copy(sessionId = "other-session", accountId = metadataCommandId(301))
    f.activate(replacement)
    assertNull(
        assertIs<AppResult.Success<DocumentMetadataCommandRecord?>>(
                f.repository.find(replacement, f.command.change.operationId)
            )
            .value
    )
    assertNull(
        assertIs<AppResult.Success<DocumentMetadataCommandRecord?>>(
                f.repository.find(f.access, f.command.change.operationId)
            )
            .value
    )
    f.activate()
    assertEquals(f.command, f.stored().command)
}

suspend fun checkMetadataCommandEncryptionKeyLossAndUnicode(f: MetadataCommandStorageFixture) {
    val command =
        f.command.copy(
            documentTitle = "漢".repeat(160),
            change = f.command.change.copy(reason = "漢".repeat(1000)),
        )
    f.repository.insert(f.access, command)
    val raw =
        assertNotNull(
            f.db.commands.get(
                metadataCommandScope(f.access),
                metadataCommandPartition(f.access),
                command.change.operationId,
            )
        )
    assertFalse(raw.request.contains("FIXTURE-IDENTITY"))
    assertFalse(raw.request.contains("漢"))
    assertEquals(command, f.stored().command)
    val key = assertNotNull(f.keys.read())
    f.keys.write(null)
    assertIs<AppResult.Failed>(f.repository.find(f.access, command.change.operationId))
    val other =
        command.copy(
            change =
                command.change.copy(
                    operationId = metadataCommandId(99),
                    documentId = metadataCommandId(99),
                )
        )
    assertIs<AppResult.Failed>(f.repository.insert(f.access, other))
    assertNull(f.keys.read())
    f.keys.write(key)
    assertEquals(command, f.stored().command)
}

suspend fun checkMetadataCommandTamperedCiphertextAndBounds(f: MetadataCommandStorageFixture) {
    f.repository.insert(f.access, f.command)
    val raw =
        assertNotNull(
            f.db.commands.get(
                metadataCommandScope(f.access),
                metadataCommandPartition(f.access),
                f.command.change.operationId,
            )
        )
    for (changed in
        listOf(
            raw.copy(resourceId = metadataCommandId(33)),
            raw.copy(revision = 1),
            raw.copy(request = raw.request.dropLast(5) + "AAAAA"),
            raw.copy(sortKey = "changed"),
        )) {
        val source =
            object : CommandStore by f.db.commands {
                override suspend fun get(scope: CommandScope, partition: String, id: String) =
                    changed
            }
        assertIs<AppResult.Failed>(
            f.repository(source).find(f.access, f.command.change.operationId)
        )
    }
    var decrypts = 0
    val cipher =
        object : ContentCipher by f.cipher {
            override suspend fun decrypt(
                key: ByteArray,
                associatedData: ByteArray,
                ciphertext: ByteArray,
            ): ByteArray {
                decrypts++
                error("Oversized envelope must not reach the cipher")
            }
        }
    for (changed in
        listOf(raw.copy(request = "x".repeat(24_577)), raw.copy(state = "x".repeat(8_193)))) {
        val source =
            object : CommandStore by f.db.commands {
                override suspend fun get(scope: CommandScope, partition: String, id: String) =
                    changed
            }
        assertIs<AppResult.Failed>(
            f.repository(source, cipher).find(f.access, f.command.change.operationId)
        )
    }
    assertEquals(0, decrypts)
}

suspend fun checkMetadataCommandCancellationAndLateAccount(f: MetadataCommandStorageFixture) =
    coroutineScope {
        val entered = CompletableDeferred<Unit>()
        val waiting =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        val job = launch { f.repository(content = waiting).insert(f.access, f.command) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(
            assertIs<AppResult.Success<List<DocumentMetadataCommandRecord>>>(
                    f.repository.observeAll(f.access).first()
                )
                .value
                .isEmpty()
        )
        val changed =
            object : ContentCipher by f.cipher {
                override suspend fun encrypt(
                    key: ByteArray,
                    associatedData: ByteArray,
                    plaintext: ByteArray,
                ): ByteArray {
                    f.activate(f.access.copy(sessionId = "replacement"))
                    return f.cipher.encrypt(key, associatedData, plaintext)
                }
            }
        assertEquals(
            AppResult.Success(DocumentMetadataCommandAdmission.SCOPE_CHANGED),
            f.repository(content = changed).insert(f.access, f.command),
        )
        f.activate()
        assertTrue(
            assertIs<AppResult.Success<List<DocumentMetadataCommandRecord>>>(
                    f.repository.observeAll(f.access).first()
                )
                .value
                .isEmpty()
        )
    }

private fun metadataCommandId(index: Int) =
    "30000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
