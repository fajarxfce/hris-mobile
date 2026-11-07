package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceConversationCommandRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

abstract class CollaborationSpaceConversationCommandStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private fun CollaborationHistoryFixture.scope(owner: CollaborationLocalAccess = access) =
        CollaborationSpaceLocalAccess(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            historyId(9800),
        )

    private fun CollaborationHistoryFixture.repo(store: CommandStore = database.commands) =
        EncryptedCollaborationSpaceConversationCommandRepository(store, cipher, keys)

    private fun CollaborationHistoryFixture.open() =
        CollaborationSpaceConversationCommand.Open(
            historyId(9801),
            CollaborationSpaceIdentityBinding(
                access.audience,
                access.accountId,
                scope().spaceId,
                historyId(9802),
            ),
            historyId(9803),
            Instant.parse("2026-11-07T00:00:00Z"),
        )

    private fun CollaborationHistoryFixture.room() =
        CollaborationSpaceConversationCommand.Room(
            CollaborationSpaceRoomRequestIntent(
                historyId(9804),
                open().binding,
                conversation,
                CollaborationSpaceRoomRequest(
                    historyId(9805),
                    7,
                    CollaborationSpaceRoomAttempt(open().peerAccountId, 9),
                ),
            ),
            open().peerAccountId,
            open().createdAt,
        )

    private suspend fun CollaborationHistoryFixture.readCommand(
        command: CollaborationSpaceConversationCommand = open(),
        access: CollaborationSpaceLocalAccess = scope(),
    ) =
        assertNotNull(
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord?>>(
                    repo().find(access, command.id)
                )
                .value
        )

    private suspend fun CollaborationHistoryFixture.rawCommand(
        command: CollaborationSpaceConversationCommand = open()
    ) =
        assertNotNull(
            database.commands.get(
                spaceConversationCommandScope(scope()),
                spaceConversationCommandPartition(scope()),
                command.id,
            )
        )

    private suspend fun CollaborationHistoryFixture.insert(
        command: CollaborationSpaceConversationCommand = open()
    ) {
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.STORED),
            repo().insert(scope(), command),
        )
    }

    private suspend fun CollaborationHistoryFixture.record(
        command: CollaborationSpaceConversationCommand = open()
    ): CollaborationSpaceConversationCommandRecord {
        insert(command)
        assertEquals(
            AppResult.Success(true),
            repo()
                .update(
                    scope(),
                    readCommand(command),
                    CollaborationSpaceConversationOutcome.Unconfirmed(),
                    1,
                ),
        )
        val receipt =
            CollaborationSetupReceipt(
                conversation,
                if (command is CollaborationSpaceConversationCommand.Room) 10 else 0,
            )
        assertEquals(
            AppResult.Success(true),
            repo()
                .update(
                    scope(),
                    readCommand(command),
                    CollaborationSpaceConversationOutcome.Recorded(receipt),
                    1,
                ),
        )
        return readCommand(command)
    }

    private fun CollaborationHistoryFixture.fence(
        command: CollaborationSpaceConversationCommand.Room = room()
    ) =
        CollaborationSpaceConversationOutcome.Fenced(
            CollaborationSpaceRoomRequestFence(
                command.intent,
                historyId(9870),
                Instant.parse("2026-11-07T00:00:01.123456Z"),
            )
        )

    private suspend fun CollaborationHistoryFixture.markRoom():
        CollaborationSpaceConversationCommandRecord {
        insert(room())
        assertEquals(
            AppResult.Success(true),
            repo()
                .update(
                    scope(),
                    readCommand(room()),
                    CollaborationSpaceConversationOutcome.Unconfirmed(),
                    1,
                ),
        )
        return readCommand(room())
    }

    // Pin the original v1 authenticated envelope, independently of the current codec.
    private fun CollaborationHistoryFixture.envelopeAad(
        raw: CommandRecord,
        purpose: String = "outcome",
        revision: Long = raw.revision,
    ) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-space-conversation-v1",
                    access.audience,
                    access.accountId,
                    scope().spaceId,
                    raw.id,
                    "conversation-setup",
                    purpose,
                    revision.toString(),
                )
            )
            .encodeToByteArray()

    private suspend fun CollaborationHistoryFixture.envelopeJson(
        raw: CommandRecord,
        payload: String = raw.state,
        purpose: String = "outcome",
        revision: Long = raw.revision,
    ): JsonObject {
        val key = Base64.decode(assertNotNull(keys.read()))
        return try {
            val bytes =
                cipher.decrypt(key, envelopeAad(raw, purpose, revision), Base64.decode(payload))
            try {
                Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            } finally {
                bytes.fill(0)
            }
        } finally {
            key.fill(0)
        }
    }

    private suspend fun CollaborationHistoryFixture.sealEnvelope(
        raw: CommandRecord,
        value: JsonObject,
        purpose: String = "outcome",
        revision: Long = raw.revision,
    ): String {
        val key = Base64.decode(assertNotNull(keys.read()))
        val bytes = value.toString().encodeToByteArray()
        return try {
            Base64.encode(cipher.encrypt(key, envelopeAad(raw, purpose, revision), bytes))
        } finally {
            key.fill(0)
            bytes.fill(0)
        }
    }

    private fun CollaborationHistoryFixture.replacement(
        original: CollaborationSpaceConversationCommand.Room = room(),
        id: String = historyId(9860),
    ) =
        original.copy(
            intent =
                original.intent.copy(
                    operationId = id,
                    binding = original.binding.copy(nativeSessionId = historyId(9862)),
                    request =
                        original.intent.request.copy(
                            deviceId = historyId(9863),
                            expectedDeviceVersion = 0,
                        ),
                ),
            predecessorId = original.id,
        )

    private suspend fun CollaborationHistoryFixture.retainFence(
        command: CollaborationSpaceConversationCommand.Room = room()
    ): CollaborationSpaceConversationCommandRecord {
        assertEquals(
            AppResult.Success(true),
            repo()
                .update(
                    scope(),
                    readCommand(command),
                    CollaborationSpaceConversationOutcome.Unconfirmed(),
                    1,
                ),
        )
        assertEquals(
            AppResult.Success(true),
            repo().update(scope(), readCommand(command), fence(command), 1),
        )
        return readCommand(command)
    }

    private suspend fun CollaborationHistoryFixture.archivedCommand(
        command: CollaborationSpaceConversationCommand.Room = room(),
        owner: CollaborationSpaceLocalAccess = scope(),
    ) =
        assertNotNull(
            database.commands.archived(
                spaceConversationCommandScope(owner),
                spaceConversationCommandPartition(owner),
                command.id,
            )
        )

    @Test
    fun fencedHandoffKeepsBothReferencesAndExactOriginalCiphertextAcrossRestart() =
        withDatabase { f ->
            f.insert(f.room())
            val original = f.retainFence()
            val raw = f.rawCommand(f.room())
            val key = f.keys.read()
            val next = f.replacement()
            assertEquals(
                AppResult.Success(true),
                f.repo().prepareReplacement(f.scope(), original, next),
            )
            val linked = original.copy(successorId = next.id)
            assertEquals(linked, f.readCommand(f.room()))
            val saved = f.readCommand(next)
            assertEquals(next, saved.command)
            assertEquals(CollaborationSpaceConversationOutcome.Pending, saved.outcome)
            assertEquals(0, saved.attempts)
            assertEquals(0L, saved.revision)
            val archive = f.archivedCommand()
            assertEquals(raw, archive.record)
            assertNotEquals(raw.state, archive.evidence)
            assertEquals(
                1,
                f.envelopeJson(raw, raw.request, "request", 0)["schema"]?.jsonPrimitive?.int,
            )
            val nextRaw = f.rawCommand(next)
            val request = f.envelopeJson(nextRaw, nextRaw.request, "request", 0)
            assertEquals(2, request["schema"]?.jsonPrimitive?.int)
            assertEquals(f.room().id, request["predecessorId"]?.jsonPrimitive?.content)
            assertEquals(
                listOf(saved),
                assertIs<AppResult.Success<List<CollaborationSpaceConversationCommandRecord>>>(
                        f.repo().observeAll(f.scope()).first()
                    )
                    .value,
            )
            val login = f.access.copy(sessionId = "new-handoff-login", companyId = historyId(9899))
            f.reopen(login)
            assertEquals(linked, f.readCommand(f.room(), f.scope(login)))
            assertEquals(saved, f.readCommand(next, f.scope(login)))
            assertEquals(archive, f.archivedCommand(owner = f.scope(login)))
            assertEquals(key, f.keys.read())
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.STORED),
                f.repo().insert(f.scope(login), f.room()),
            )
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.STORED),
                f.repo().insert(f.scope(login), next),
            )
        }

    @Test
    fun replacementCannotBeIndependentlyInsertedOrPreparedWithoutAnExactFence() =
        withDatabase { f ->
            val next = f.replacement()
            assertIs<AppResult.Failed>(f.repo().insert(f.scope(), next))
            assertNull(f.keys.read())
            assertEquals(AppResult.Success(emptyList()), f.repo().observeAll(f.scope()).first())
            f.insert(f.room())
            val pending = f.readCommand(f.room())
            assertIs<AppResult.Failed>(f.repo().prepareReplacement(f.scope(), pending, next))
            val fenced = f.retainFence()
            val raw = f.rawCommand(f.room())
            for (wrong in
                listOf(
                    next.copy(predecessorId = null),
                    next.copy(predecessorId = historyId(9859)),
                    next.copy(intent = next.intent.copy(conversationId = historyId(9858))),
                )) {
                assertIs<AppResult.Failed>(f.repo().prepareReplacement(f.scope(), fenced, wrong))
            }
            assertEquals(raw, f.rawCommand(f.room()))
            assertEquals(AppResult.Success(null), f.repo().find(f.scope(), next.id))
        }

    @Test
    fun competingHandoffsPublishExactlyOneSuccessorAndOneImmutableOriginal() = withDatabase { f ->
        f.insert(f.room())
        val original = f.retainFence()
        val raw = f.rawCommand(f.room())
        val next = listOf(f.replacement(), f.replacement(id = historyId(9861)))
        val results = coroutineScope {
            next.map { async { f.repo().prepareReplacement(f.scope(), original, it) } }.awaitAll()
        }
        assertEquals(1, results.count { it == AppResult.Success(true) })
        assertEquals(1, results.count { it == AppResult.Success(false) })
        val linked = f.readCommand(f.room())
        val winner = next.single { it.id == linked.successorId }
        val loser = next.single { it.id != linked.successorId }
        assertEquals(winner, f.readCommand(winner).command)
        assertEquals(AppResult.Success(null), f.repo().find(f.scope(), loser.id))
        assertEquals(raw, f.archivedCommand().record)
        assertEquals(
            listOf(winner),
            assertIs<AppResult.Success<List<CollaborationSpaceConversationCommandRecord>>>(
                    f.repo().observeAll(f.scope()).first()
                )
                .value
                .map { it.command },
        )
    }

    @Test
    fun committedHandoffSurvivesLostReturnAndReopensWithoutAnotherReplacement() =
        withDatabase { f ->
            f.insert(f.room())
            val original = f.retainFence()
            val raw = f.rawCommand(f.room())
            val next = f.replacement()
            val store =
                object : CommandStore by f.database.commands {
                    override suspend fun archiveAndInsert(
                        scope: CommandScope,
                        expected: CommandRecord,
                        evidence: String,
                        successor: CommandRecord,
                    ): Boolean {
                        val saved =
                            f.database.commands.archiveAndInsert(
                                scope,
                                expected,
                                evidence,
                                successor,
                            )
                        currentCoroutineContext().cancel()
                        return saved
                    }
                }
            supervisorScope {
                val work = async { f.repo(store).prepareReplacement(f.scope(), original, next) }
                assertFailsWith<CancellationException> { work.await() }
            }
            val archive = f.archivedCommand()
            val successor = f.rawCommand(next)
            f.reopen()
            assertEquals(original.copy(successorId = next.id), f.readCommand(f.room()))
            assertEquals(next, f.readCommand(next).command)
            assertEquals(raw, archive.record)
            assertEquals(archive, f.archivedCommand())
            assertEquals(successor, f.rawCommand(next))
            assertEquals(
                AppResult.Success(false),
                f.repo().prepareReplacement(f.scope(), original, next),
            )
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.STORED),
                f.repo().insert(f.scope(), next),
            )
        }

    @Test
    fun failedOrCancelledHandoffKeepsTheOriginalAndCreatesNoGhost() = withDatabase { f ->
        f.insert(f.room())
        val original = f.retainFence()
        val raw = f.rawCommand(f.room())
        val next = f.replacement()
        for (cancel in listOf(false, true)) {
            val store =
                object : CommandStore by f.database.commands {
                    override suspend fun archiveAndInsert(
                        scope: CommandScope,
                        expected: CommandRecord,
                        evidence: String,
                        successor: CommandRecord,
                    ): Boolean {
                        if (cancel) throw CancellationException("Cancelled before handoff")
                        error("Unavailable handoff transaction")
                    }
                }
            if (cancel)
                assertFailsWith<CancellationException> {
                    f.repo(store).prepareReplacement(f.scope(), original, next)
                }
            else
                assertIs<AppResult.Failed>(
                    f.repo(store).prepareReplacement(f.scope(), original, next)
                )
            assertEquals(original, f.readCommand(f.room()))
            assertEquals(raw, f.rawCommand(f.room()))
            assertEquals(AppResult.Success(null), f.repo().find(f.scope(), next.id))
            assertNull(
                f.database.commands.archived(
                    spaceConversationCommandScope(f.scope()),
                    raw.partition,
                    raw.id,
                )
            )
        }
    }

    @Test
    fun authenticatedMalformedArchiveLinksAndReplacementSchemasAreRejected() = withDatabase { f ->
        f.insert(f.room())
        val original = f.retainFence()
        val next = f.replacement()
        assertEquals(
            AppResult.Success(true),
            f.repo().prepareReplacement(f.scope(), original, next),
        )
        val archived = f.archivedCommand()
        val link = f.envelopeJson(archived.record, archived.evidence, "successor")
        assertEquals(next.id, link["successorId"]?.jsonPrimitive?.content)
        val invalidLinks =
            listOf(
                JsonObject(link + ("schema" to JsonPrimitive(2))),
                JsonObject(link + ("kind" to JsonPrimitive("RECORDED"))),
                JsonObject(link + ("successorId" to JsonPrimitive(f.room().id))),
                JsonObject(
                    link + ("successorId" to JsonPrimitive("00000000-0000-0000-0000-000000000000"))
                ),
            )
        for (value in invalidLinks) {
            val changed =
                archived.copy(evidence = f.sealEnvelope(archived.record, value, "successor"))
            val store =
                object : CommandStore by f.database.commands {
                    override suspend fun archived(
                        scope: CommandScope,
                        partition: String,
                        id: String,
                    ) = changed
                }
            assertIs<AppResult.Failed>(f.repo(store).find(f.scope(), f.room().id))
        }
        val rawNext = f.rawCommand(next)
        val request = f.envelopeJson(rawNext, rawNext.request, "request", 0)
        val invalidRequests =
            listOf(
                JsonObject(request + ("schema" to JsonPrimitive(1))),
                JsonObject(request + ("predecessorId" to JsonNull)),
                JsonObject(request + ("predecessorId" to JsonPrimitive(next.id))),
                JsonObject(
                    request +
                        ("predecessorId" to JsonPrimitive("00000000-0000-0000-0000-000000000000"))
                ),
            )
        for (value in invalidRequests) {
            val changed = rawNext.copy(request = f.sealEnvelope(rawNext, value, "request", 0))
            val store =
                object : CommandStore by f.database.commands {
                    override suspend fun get(scope: CommandScope, partition: String, id: String) =
                        changed
                }
            assertIs<AppResult.Failed>(f.repo(store).find(f.scope(), next.id))
        }
        assertEquals(archived, f.archivedCommand())
        assertEquals(rawNext, f.rawCommand(next))
    }

    @Test
    fun handoffAtSharedCapacityKeepsAllIndependentCommands() = withDatabase { f ->
        f.insert(f.room())
        val original = f.retainFence()
        for (index in 0 until 127) {
            f.insert(
                f.open()
                    .copy(id = historyId(15000 + index), peerAccountId = historyId(16000 + index))
            )
        }
        val before =
            assertIs<AppResult.Success<List<CollaborationSpaceConversationCommandRecord>>>(
                    f.repo().observeAll(f.scope()).first()
                )
                .value
        assertEquals(128, before.size)
        val next = f.replacement()
        assertEquals(
            AppResult.Success(true),
            f.repo().prepareReplacement(f.scope(), original, next),
        )
        val after =
            assertIs<AppResult.Success<List<CollaborationSpaceConversationCommandRecord>>>(
                    f.repo().observeAll(f.scope()).first()
                )
                .value
        assertEquals(128, after.size)
        assertEquals(
            before.filter { it.command.id != original.command.id }.toSet(),
            after.filter { it.command.id != next.id }.toSet(),
        )
        assertEquals(original.copy(successorId = next.id), f.readCommand(f.room()))
    }

    @Test
    fun handoffCannotCrossAccountsOrRegenerateALostOriginalKey() = withDatabase { f ->
        f.insert(f.room())
        val original = f.retainFence()
        val raw = f.rawCommand(f.room())
        val next = f.replacement()
        val other = f.access.copy(sessionId = "foreign-handoff", accountId = historyId(9898))
        f.activate(other)
        assertIs<AppResult.Failed>(f.repo().prepareReplacement(f.scope(other), original, next))
        f.activate()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.repo().prepareReplacement(f.scope(), original, next))
        assertNull(f.keys.read())
        assertEquals(raw, f.rawCommand(f.room()))
        assertNull(
            f.database.commands.get(
                spaceConversationCommandScope(f.scope()),
                raw.partition,
                next.id,
            )
        )
    }

    @Test
    fun aSecondHandoffRetainsImmediateOriginsAndNeverChangesTheEarlierArchive() =
        withDatabase { f ->
            f.insert(f.room())
            val first = f.retainFence()
            val second = f.replacement()
            assertEquals(
                AppResult.Success(true),
                f.repo().prepareReplacement(f.scope(), first, second),
            )
            val firstArchive = f.archivedCommand()
            val nextOriginal = f.retainFence(second)
            val third = f.replacement(second, historyId(9864))
            assertEquals(
                AppResult.Success(true),
                f.repo().prepareReplacement(f.scope(), nextOriginal, third),
            )
            assertEquals(first.copy(successorId = second.id), f.readCommand(f.room()))
            assertEquals(nextOriginal.copy(successorId = third.id), f.readCommand(second))
            assertEquals(third, f.readCommand(third).command)
            assertEquals(firstArchive, f.archivedCommand())
            assertIs<AppResult.Failed>(
                f.repo()
                    .prepareReplacement(f.scope(), nextOriginal, f.replacement(second, f.room().id))
            )
            assertEquals(
                listOf(third),
                assertIs<AppResult.Success<List<CollaborationSpaceConversationCommandRecord>>>(
                        f.repo().observeAll(f.scope()).first()
                    )
                    .value
                    .map { it.command },
            )
        }

    @Test
    fun fencedRoomReopensWithExactEvidenceAndStillOccupiesItsOriginalResource() =
        withDatabase { f ->
            val before = f.markRoom()
            val request = f.rawCommand(f.room()).request
            val key = f.keys.read()
            val evidence = f.fence()
            assertEquals(AppResult.Success(true), f.repo().update(f.scope(), before, evidence, 1))
            val saved = f.readCommand(f.room())
            val raw = f.rawCommand(f.room())
            assertEquals(evidence, saved.outcome)
            assertEquals(request, raw.request)
            assertFalse(raw.state.contains(evidence.evidence.id))
            assertEquals(2, f.envelopeJson(raw)["schema"]?.jsonPrimitive?.int)
            val login = f.access.copy(sessionId = "replacement", companyId = historyId(9899))
            f.reopen(login)
            assertEquals(saved, f.readCommand(f.room(), f.scope(login)))
            assertEquals(key, f.keys.read())
            assertEquals(
                listOf(saved),
                assertIs<AppResult.Success<List<CollaborationSpaceConversationCommandRecord>>>(
                        f.repo().observeAll(f.scope(login)).first()
                    )
                    .value,
            )
            assertIs<AppResult.Failed>(f.repo().archiveRecorded(f.scope(login), saved))
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.OCCUPIED),
                f.repo()
                    .insert(
                        f.scope(login),
                        f.room().copy(intent = f.room().intent.copy(operationId = historyId(9871))),
                    ),
            )
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.STORED),
                f.repo().insert(f.scope(login), f.room()),
            )
            f.activate()
            assertEquals(raw, f.rawCommand(f.room()))
        }

    @Test
    fun lostFenceSaveResponseSurvivesDatabaseRestartWithoutAnotherAttempt() = withDatabase { f ->
        val before = f.markRoom()
        val request = f.rawCommand(f.room()).request
        val evidence = f.fence()
        val store =
            object : CommandStore by f.database.commands {
                override suspend fun update(
                    scope: CommandScope,
                    partition: String,
                    id: String,
                    expectedRevision: Long,
                    state: String,
                ): Boolean {
                    val saved =
                        f.database.commands.update(scope, partition, id, expectedRevision, state)
                    currentCoroutineContext().cancel()
                    return saved
                }
            }
        supervisorScope {
            val work = async { f.repo(store).update(f.scope(), before, evidence, 1) }
            assertFailsWith<CancellationException> { work.await() }
        }
        val committed = f.rawCommand(f.room())
        f.reopen()
        val saved = f.readCommand(f.room())
        assertEquals(evidence, saved.outcome)
        assertEquals(1, saved.attempts)
        assertEquals(2L, saved.revision)
        assertEquals(request, committed.request)
        assertEquals(committed, f.rawCommand(f.room()))
        assertEquals(AppResult.Success(false), f.repo().update(f.scope(), before, evidence, 1))
        assertIs<AppResult.Failed>(
            f.repo()
                .update(f.scope(), saved, CollaborationSpaceConversationOutcome.Unconfirmed(), 2)
        )
        assertEquals(committed, f.rawCommand(f.room()))
    }

    @Test
    fun competingFenceAndReceiptCaptureKeepsExactlyOneTerminalOutcome() = withDatabase { f ->
        val before = f.markRoom()
        val fence = f.fence()
        val receipt =
            CollaborationSpaceConversationOutcome.Recorded(
                CollaborationSetupReceipt(f.conversation, 10)
            )
        val results = coroutineScope {
            listOf(
                    async { f.repo().update(f.scope(), before, fence, 1) },
                    async { f.repo().update(f.scope(), before, receipt, 1) },
                )
                .awaitAll()
        }
        assertEquals(1, results.count { it == AppResult.Success(true) })
        assertEquals(1, results.count { it == AppResult.Success(false) })
        val saved = f.readCommand(f.room())
        assertTrue(saved.outcome == fence || saved.outcome == receipt)
        assertEquals(2L, saved.revision)
        assertIs<AppResult.Failed>(
            f.repo().update(f.scope(), saved, if (saved.outcome == fence) receipt else fence, 1)
        )
        assertEquals(saved, f.readCommand(f.room()))
    }

    @Test
    fun legacyOutcomeEnvelopesRemainVersionOneBesideRetainedFences() = withDatabase { f ->
        val open = f.record()
        val original = f.rawCommand()
        val legacy = f.envelopeJson(original)
        assertEquals(1, legacy["schema"]?.jsonPrimitive?.int)
        assertFalse(legacy.containsKey("fence"))
        val before = f.markRoom()
        assertEquals(1, f.envelopeJson(f.rawCommand(f.room()))["schema"]?.jsonPrimitive?.int)
        assertEquals(AppResult.Success(true), f.repo().update(f.scope(), before, f.fence(), 1))
        f.reopen()
        assertEquals(open, f.readCommand())
        assertEquals(original, f.rawCommand())
        assertEquals(AppResult.Success(true), f.repo().archiveRecorded(f.scope(), open))
        assertEquals(AppResult.Success(open), f.repo().find(f.scope(), f.open().id))
        assertEquals(
            listOf(f.readCommand(f.room())),
            assertIs<AppResult.Success<List<CollaborationSpaceConversationCommandRecord>>>(
                    f.repo().observeAll(f.scope()).first()
                )
                .value,
        )
    }

    @Test
    fun authenticatedButConflictingFenceDescriptorsAndSchemasAreRejected() = withDatabase { f ->
        val before = f.markRoom()
        assertEquals(AppResult.Success(true), f.repo().update(f.scope(), before, f.fence(), 1))
        val saved = f.readCommand(f.room())
        val raw = f.rawCommand(f.room())
        val payload = f.envelopeJson(raw)
        val evidence = payload.getValue("fence").jsonObject
        val original = evidence.getValue("original").jsonObject
        fun withEvidence(proof: JsonObject) = JsonObject(payload + ("fence" to proof))
        val invalid = mutableListOf<JsonObject>()
        for (field in
            listOf(
                "operationId",
                "spaceId",
                "conversationId",
                "deviceId",
                "expectedRequestAccountId",
            )) {
            val changed = JsonObject(original + (field to JsonPrimitive(historyId(9879))))
            invalid += withEvidence(JsonObject(evidence + ("original" to changed)))
        }
        for (field in listOf("expectedDeviceVersion", "expectedRequestRevision")) {
            val changed = JsonObject(original + (field to JsonPrimitive(999999)))
            invalid += withEvidence(JsonObject(evidence + ("original" to changed)))
        }
        invalid +=
            withEvidence(
                JsonObject(evidence + ("accountId" to JsonPrimitive(f.open().peerAccountId)))
            )
        invalid +=
            withEvidence(
                JsonObject(
                    evidence + ("id" to JsonPrimitive("00000000-0000-0000-0000-000000000000"))
                )
            )
        invalid +=
            withEvidence(
                JsonObject(
                    evidence + ("createdAt" to JsonPrimitive("2026-11-07T00:00:01.123456789Z"))
                )
            )
        invalid += JsonObject(payload + ("schema" to JsonPrimitive(1)))
        invalid += JsonObject(payload + ("schema" to JsonPrimitive(3)))
        invalid += JsonObject(payload + ("attempts" to JsonPrimitive(0)))
        invalid += JsonObject(payload + ("fence" to JsonNull))
        invalid +=
            JsonObject(
                payload +
                    ("receipt" to
                        buildJsonObject {
                            put("id", f.conversation)
                            put("version", 10)
                        })
            )
        invalid +=
            JsonObject(
                payload +
                    ("failure" to
                        buildJsonObject {
                            put("kind", "Network")
                            put("code", JsonNull)
                            put("retryAfterSeconds", JsonNull)
                        })
            )
        for (changed in invalid) {
            val altered = raw.copy(state = f.sealEnvelope(raw, changed))
            val store =
                object : CommandStore by f.database.commands {
                    override suspend fun get(scope: CommandScope, partition: String, id: String) =
                        altered

                    override fun observeAll(scope: CommandScope, partition: String) =
                        flowOf(listOf(altered))
                }
            assertIs<AppResult.Failed>(f.repo(store).find(f.scope(), f.room().id))
            assertIs<AppResult.Failed>(f.repo(store).observeAll(f.scope()).first())
        }
        assertEquals(saved, f.readCommand(f.room()))
        assertEquals(raw, f.rawCommand(f.room()))
    }

    @Test
    fun fencedEvidenceCannotBeUsedToReplaceALostKeyOrReadAnotherOwner() = withDatabase { f ->
        val before = f.markRoom()
        assertEquals(AppResult.Success(true), f.repo().update(f.scope(), before, f.fence(), 1))
        val original = f.rawCommand(f.room())
        val other = f.access.copy(sessionId = "other", accountId = historyId(9879))
        f.activate(other)
        assertEquals(AppResult.Success(null), f.repo().find(f.scope(other), f.room().id))
        f.activate()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.repo().find(f.scope(), f.room().id))
        assertIs<AppResult.Failed>(f.repo().insert(f.scope(), f.open()))
        assertNull(f.keys.read())
        assertEquals(original, f.rawCommand(f.room()))
    }

    @Test
    fun emptyReadsAllocateNoKeyAndBothKindsReopenUnderAnotherLocalLogin() = withDatabase { f ->
        assertEquals(AppResult.Success(null), f.repo().find(f.scope(), f.open().id))
        assertEquals(AppResult.Success(emptyList()), f.repo().observeAll(f.scope()).first())
        assertNull(f.keys.read())
        f.insert()
        f.insert(f.room())
        val retained = listOf(f.readCommand(), f.readCommand(f.room()))
        val original = f.rawCommand(f.room())
        assertFalse(original.request.contains(f.room().intent.request.deviceId))
        assertFalse(original.request.contains(f.open().binding.nativeSessionId))
        val login = f.access.copy(sessionId = "new-local-login", companyId = historyId(9899))
        f.reopen(login)
        assertEquals(
            retained,
            assertIs<AppResult.Success<List<CollaborationSpaceConversationCommandRecord>>>(
                    f.repo().observeAll(f.scope(login)).first()
                )
                .value,
        )
        for (command in listOf(f.open(), f.room())) {
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.STORED),
                f.repo().insert(f.scope(login), command),
            )
            assertEquals(command, f.readCommand(command, f.scope(login)).command)
        }
        f.activate()
        assertEquals(original, f.rawCommand(f.room()))
    }

    @Test
    fun lostInsertResponseRetainsTheOriginalOperationAndCiphertext() = withDatabase { f ->
        val store =
            object : CommandStore by f.database.commands {
                override suspend fun insert(
                    scope: CommandScope,
                    record: CommandRecord,
                ): CommandInsertResult {
                    val saved = f.database.commands.insert(scope, record)
                    currentCoroutineContext().cancel()
                    return saved
                }
            }
        supervisorScope {
            val pending = async { f.repo(store).insert(f.scope(), f.room()) }
            assertFailsWith<CancellationException> { pending.await() }
        }
        val original = f.rawCommand(f.room())
        f.reopen()
        f.insert(f.room())
        assertEquals(original, f.rawCommand(f.room()))
        assertEquals(f.room(), f.readCommand(f.room()).command)
    }

    @Test
    fun lostUnconfirmedResponseSurvivesRestartWithoutReturningToPending() = withDatabase { f ->
        f.insert()
        val initial = f.readCommand()
        val request = f.rawCommand().request
        val store =
            object : CommandStore by f.database.commands {
                override suspend fun update(
                    scope: CommandScope,
                    partition: String,
                    id: String,
                    expectedRevision: Long,
                    state: String,
                ): Boolean {
                    val saved =
                        f.database.commands.update(scope, partition, id, expectedRevision, state)
                    currentCoroutineContext().cancel()
                    return saved
                }
            }
        supervisorScope {
            val pending = async {
                f.repo(store)
                    .update(
                        f.scope(),
                        initial,
                        CollaborationSpaceConversationOutcome.Unconfirmed(),
                        1,
                    )
            }
            assertFailsWith<CancellationException> { pending.await() }
        }
        f.reopen()
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.readCommand().outcome)
        assertEquals(1, f.readCommand().attempts)
        f.insert()
        assertEquals(request, f.rawCommand().request)
        assertEquals(
            AppResult.Success(false),
            f.repo()
                .update(f.scope(), initial, CollaborationSpaceConversationOutcome.Unconfirmed(), 1),
        )
        assertIs<AppResult.Failed>(f.repo().archiveRecorded(f.scope(), f.readCommand()))
    }

    @Test
    fun lostArchiveResponseKeepsAnExactImmutableResultAndFreesOnlyTheActiveResource() =
        withDatabase { f ->
            val recorded = f.record(f.room())
            val original = f.rawCommand(f.room())
            val store =
                object : CommandStore by f.database.commands {
                    override suspend fun archive(
                        scope: CommandScope,
                        expected: CommandRecord,
                        evidence: String,
                    ): Boolean {
                        val saved = f.database.commands.archive(scope, expected, evidence)
                        currentCoroutineContext().cancel()
                        return saved
                    }
                }
            supervisorScope {
                val pending = async { f.repo(store).archiveRecorded(f.scope(), recorded) }
                assertFailsWith<CancellationException> { pending.await() }
            }
            f.reopen()
            assertEquals(recorded, f.readCommand(f.room()))
            assertEquals(AppResult.Success(emptyList()), f.repo().observeAll(f.scope()).first())
            f.insert(f.room())
            assertEquals(AppResult.Success(true), f.repo().archiveRecorded(f.scope(), recorded))
            assertEquals(
                CommandArchive(original, original.state),
                f.database.commands.archived(
                    spaceConversationCommandScope(f.scope()),
                    original.partition,
                    original.id,
                ),
            )
            assertIs<AppResult.Failed>(
                f.repo()
                    .update(
                        f.scope(),
                        recorded,
                        CollaborationSpaceConversationOutcome.Unconfirmed(),
                        2,
                    )
            )
            val next = f.room().copy(intent = f.room().intent.copy(operationId = historyId(9810)))
            f.insert(next)
            assertEquals(
                listOf(next),
                assertIs<AppResult.Success<List<CollaborationSpaceConversationCommandRecord>>>(
                        f.repo().observeAll(f.scope()).first()
                    )
                    .value
                    .map { it.command },
            )
            assertEquals(recorded, f.readCommand(f.room()))
            assertIs<AppResult.Failed>(
                f.repo().insert(f.scope(), f.room().copy(peerAccountId = historyId(9811)))
            )
        }

    @Test
    fun competingResourceInsertAndStateUpdateKeepTheWinner() = withDatabase { f ->
        val winner = f.open().copy(id = historyId(9812))
        val inserting =
            object : CommandStore by f.database.commands {
                override suspend fun insert(
                    scope: CommandScope,
                    record: CommandRecord,
                ): CommandInsertResult {
                    f.insert(winner)
                    return f.database.commands.insert(scope, record)
                }
            }
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.OCCUPIED),
            f.repo(inserting).insert(f.scope(), f.open()),
        )
        val initial = f.readCommand(winner)
        val updating =
            object : CommandStore by f.database.commands {
                override suspend fun update(
                    scope: CommandScope,
                    partition: String,
                    id: String,
                    expectedRevision: Long,
                    state: String,
                ): Boolean {
                    assertEquals(
                        AppResult.Success(true),
                        f.repo()
                            .update(
                                f.scope(),
                                initial,
                                CollaborationSpaceConversationOutcome.Unconfirmed(),
                                1,
                            ),
                    )
                    return f.database.commands.update(scope, partition, id, expectedRevision, state)
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repo(updating)
                .update(f.scope(), initial, CollaborationSpaceConversationOutcome.Unconfirmed(), 1),
        )
        assertEquals(1L, f.readCommand(winner).revision)
        assertEquals(winner, f.readCommand(winner).command)
    }

    @Test
    fun concurrentSameIdentityRecoversTheStoredRequestAfterAConflict() = withDatabase { f ->
        val store =
            object : CommandStore by f.database.commands {
                override suspend fun insert(
                    scope: CommandScope,
                    record: CommandRecord,
                ): CommandInsertResult {
                    f.insert()
                    return f.database.commands.insert(scope, record)
                }
            }
        // Independent encryption produces a raw immutable-ciphertext conflict. A known-ID read
        // recovers the winning request; it must not allocate another operation or overwrite it.
        assertIs<AppResult.Failed>(f.repo(store).insert(f.scope(), f.open()))
        val raw = f.rawCommand()
        f.insert()
        assertEquals(raw, f.rawCommand())
        assertEquals(f.open(), f.readCommand().command)
    }

    @Test
    fun sharedAccountCapacityIsReleasedByRecordedArchival() = withDatabase { f ->
        val recorded = f.record()
        val filler =
            (1..127).map {
                CommandRecord(
                    "other-workflow",
                    "filler-$it",
                    "resource-$it",
                    "order-$it",
                    "opaque-request",
                    "opaque-state",
                )
            }
        assertEquals(
            CommandInsertResult.Stored,
            f.database.commands.insertBatch(spaceConversationCommandScope(f.scope()), filler),
        )
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.CAPACITY_EXCEEDED),
            f.repo().insert(f.scope(), f.room()),
        )
        assertEquals(AppResult.Success(true), f.repo().archiveRecorded(f.scope(), recorded))
        f.insert(f.room())
        assertEquals(recorded, f.readCommand())
    }

    @Test
    fun anotherAccountServerOrSpaceCannotReadOrSubstituteRetainedCommands() = withDatabase { f ->
        f.insert()
        val original = f.readCommand()
        val foreign = f.scope().copy(spaceId = historyId(9820))
        assertEquals(AppResult.Success(null), f.repo().find(foreign, f.open().id))
        assertIs<AppResult.Failed>(f.repo().insert(foreign, f.open()))
        for (owner in
            listOf(
                f.access.copy(sessionId = "other", accountId = historyId(9821)),
                f.access.copy(sessionId = "other-server", audience = "https://other.example.test/"),
            )) {
            f.activate(owner)
            assertEquals(AppResult.Success(null), f.repo().find(f.scope(owner), f.open().id))
            assertEquals(AppResult.Success(null), f.repo().find(f.scope(), f.open().id))
            assertIs<AppResult.Failed>(f.repo().insert(f.scope(owner), f.open()))
            f.activate()
            assertEquals(original, f.readCommand())
        }
    }

    @Test
    fun scopeReplacementAtInsertionAndArchivalCannotMutateTheOriginalAccount() = withDatabase { f ->
        val other = f.access.copy(sessionId = "replacement", accountId = historyId(9822))
        val inserting =
            object : CommandStore by f.database.commands {
                override suspend fun insert(
                    scope: CommandScope,
                    record: CommandRecord,
                ): CommandInsertResult {
                    f.activate(other)
                    return f.database.commands.insert(scope, record)
                }
            }
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.SCOPE_CHANGED),
            f.repo(inserting).insert(f.scope(), f.open()),
        )
        f.activate()
        assertEquals(AppResult.Success(null), f.repo().find(f.scope(), f.open().id))
        val recorded = f.record()
        val archiving =
            object : CommandStore by f.database.commands {
                override suspend fun archive(
                    scope: CommandScope,
                    expected: CommandRecord,
                    evidence: String,
                ): Boolean {
                    f.activate(other)
                    return f.database.commands.archive(scope, expected, evidence)
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repo(archiving).archiveRecorded(f.scope(), recorded),
        )
        f.activate()
        assertEquals(recorded, f.readCommand())
        assertEquals(
            1,
            assertIs<AppResult.Success<List<CollaborationSpaceConversationCommandRecord>>>(
                    f.repo().observeAll(f.scope()).first()
                )
                .value
                .size,
        )
    }

    @Test
    fun missingKeyOverAnArchiveCannotCreateNewKeysOrPendingWork() = withDatabase { f ->
        val recorded = f.record()
        assertEquals(AppResult.Success(true), f.repo().archiveRecorded(f.scope(), recorded))
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.repo().find(f.scope(), f.open().id))
        assertIs<AppResult.Failed>(f.repo().insert(f.scope(), f.room()))
        val other = f.access.copy(sessionId = "other", accountId = historyId(9823))
        f.activate(other)
        assertIs<AppResult.Failed>(
            f.repo()
                .insert(
                    f.scope(other),
                    f.open().copy(binding = f.open().binding.copy(accountId = other.accountId)),
                )
        )
        assertNull(f.keys.read())
    }

    @Test
    fun tamperedMetadataPurposeAndArchiveEvidenceNeverBecomeOwnedFacts() = withDatabase { f ->
        f.insert()
        val raw = f.rawCommand()
        val original = f.readCommand()
        for (changed in
            listOf(
                raw.copy(partition = "foreign"),
                raw.copy(resourceId = "room/" + f.conversation),
                raw.copy(id = historyId(9830)),
                raw.copy(sortKey = "other"),
                raw.copy(revision = 1),
                raw.copy(request = raw.state),
                raw.copy(state = raw.request),
                raw.copy(request = "x".repeat(8193)),
                raw.copy(state = "bad"),
            )) {
            val store =
                object : CommandStore by f.database.commands {
                    override suspend fun get(scope: CommandScope, partition: String, id: String) =
                        changed

                    override fun observeAll(scope: CommandScope, partition: String) =
                        flowOf(listOf(changed))
                }
            assertIs<AppResult.Failed>(f.repo(store).find(f.scope(), f.open().id))
            assertIs<AppResult.Failed>(f.repo(store).observeAll(f.scope()).first())
            assertIs<AppResult.Failed>(f.repo(store).insert(f.scope(), f.open()))
            assertIs<AppResult.Failed>(
                f.repo(store)
                    .update(
                        f.scope(),
                        original,
                        CollaborationSpaceConversationOutcome.Unconfirmed(),
                        1,
                    )
            )
        }
        val pendingArchive =
            object : CommandStore by f.database.commands {
                override suspend fun get(
                    scope: CommandScope,
                    partition: String,
                    id: String,
                ): CommandRecord? = null

                override suspend fun archived(scope: CommandScope, partition: String, id: String) =
                    CommandArchive(raw, raw.state)
            }
        assertIs<AppResult.Failed>(f.repo(pendingArchive).find(f.scope(), f.open().id))
        val recorded = f.record()
        val accepted = f.rawCommand()
        val wrongArchive =
            object : CommandStore by f.database.commands {
                override suspend fun get(
                    scope: CommandScope,
                    partition: String,
                    id: String,
                ): CommandRecord? = null

                override suspend fun archived(scope: CommandScope, partition: String, id: String) =
                    CommandArchive(accepted, raw.state)
            }
        assertIs<AppResult.Failed>(f.repo(wrongArchive).archiveRecorded(f.scope(), recorded))
        assertEquals(recorded, f.readCommand())
    }

    @Test
    fun denialIsRetainedThroughFailuresAndSuccessCannotBeReopened() = withDatabase { f ->
        f.insert(f.room())
        val denied =
            Failure(
                FailureKind.AccessDenied,
                "Conversation setup could not be confirmed.",
                code = "access_denied",
            )
        val unavailable = Failure(FailureKind.Network, "Unavailable")
        assertEquals(
            AppResult.Success(true),
            f.repo()
                .update(
                    f.scope(),
                    f.readCommand(f.room()),
                    CollaborationSpaceConversationOutcome.Unconfirmed(denied),
                    1,
                ),
        )
        val retained = f.readCommand(f.room())
        for (failure in listOf(null, unavailable)) {
            assertIs<AppResult.Failed>(
                f.repo()
                    .update(
                        f.scope(),
                        retained,
                        CollaborationSpaceConversationOutcome.Unconfirmed(failure),
                        2,
                    )
            )
        }
        for (receipt in
            listOf(
                CollaborationSetupReceipt(historyId(9840), 10),
                CollaborationSetupReceipt(f.conversation, 1000000),
            )) {
            assertIs<AppResult.Failed>(
                f.repo()
                    .update(
                        f.scope(),
                        retained,
                        CollaborationSpaceConversationOutcome.Recorded(receipt),
                        1,
                    )
            )
        }
        assertEquals(retained, f.readCommand(f.room()))
        val outcome =
            CollaborationSpaceConversationOutcome.Recorded(
                CollaborationSetupReceipt(f.conversation, 10)
            )
        assertEquals(AppResult.Success(true), f.repo().update(f.scope(), retained, outcome, 1))
        assertIs<AppResult.Failed>(
            f.repo()
                .update(
                    f.scope(),
                    f.readCommand(f.room()),
                    CollaborationSpaceConversationOutcome.Unconfirmed(),
                    2,
                )
        )
    }

    @Test
    fun invalidInputsAndDuplicateInventoryFailWithoutAKeyOrPartialPublication() =
        withDatabase { f ->
            for (access in
                listOf(
                    f.scope().copy(sessionId = "bad\n"),
                    f.scope().copy(spaceId = "../space"),
                    f.scope().copy(accountId = "invalid"),
                )) {
                assertIs<AppResult.Failed>(f.repo().find(access, f.open().id))
                assertIs<AppResult.Failed>(f.repo().observeAll(access).first())
                assertIs<AppResult.Failed>(f.repo().insert(access, f.open()))
            }
            assertIs<AppResult.Failed>(
                f.repo().insert(f.scope(), f.open().copy(peerAccountId = f.access.accountId))
            )
            assertIs<AppResult.Failed>(
                f.repo()
                    .insert(
                        f.scope(),
                        f.room()
                            .copy(
                                intent =
                                    f.room()
                                        .intent
                                        .copy(
                                            request =
                                                f.room()
                                                    .intent
                                                    .request
                                                    .copy(expectedDeviceVersion = -1)
                                        )
                            ),
                    )
            )
            assertNull(f.keys.read())
            f.insert()
            val raw = f.rawCommand()
            for (rows in listOf(listOf(raw, raw), List(129) { raw })) {
                val store =
                    object : CommandStore by f.database.commands {
                        override fun observeAll(scope: CommandScope, partition: String) =
                            flowOf(rows)
                    }
                assertIs<AppResult.Failed>(f.repo(store).observeAll(f.scope()).first())
            }
        }

    @Test
    fun sameRawInsertionFindingAnArchiveRecoversTheRecordedResult() = withDatabase { f ->
        val store =
            object : CommandStore by f.database.commands {
                override suspend fun insert(
                    scope: CommandScope,
                    record: CommandRecord,
                ): CommandInsertResult {
                    assertEquals(
                        CommandInsertResult.Stored,
                        f.database.commands.insert(scope, record),
                    )
                    val saved = f.record()
                    assertEquals(
                        AppResult.Success(true),
                        f.repo().archiveRecorded(f.scope(), saved),
                    )
                    return f.database.commands.insert(scope, record)
                }
            }
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.STORED),
            f.repo(store).insert(f.scope(), f.open()),
        )
        assertIs<CollaborationSpaceConversationOutcome.Recorded>(f.readCommand().outcome)
        assertEquals(AppResult.Success(emptyList()), f.repo().observeAll(f.scope()).first())
    }

    @Test
    fun lateCancelledReadCannotPublishItsDecryptedCommand() = withDatabase { f ->
        f.insert()
        val raw = f.rawCommand()
        val store =
            object : CommandStore by f.database.commands {
                override suspend fun get(
                    scope: CommandScope,
                    partition: String,
                    id: String,
                ): CommandRecord? {
                    currentCoroutineContext().cancel()
                    return raw
                }
            }
        supervisorScope {
            val pending = async { f.repo(store).find(f.scope(), f.open().id) }
            assertFailsWith<CancellationException> { pending.await() }
        }
        assertEquals(f.open(), f.readCommand().command)
    }

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
