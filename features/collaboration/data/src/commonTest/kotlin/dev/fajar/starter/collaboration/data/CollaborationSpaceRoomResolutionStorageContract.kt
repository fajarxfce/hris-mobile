package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.MemoryCollaborationSpaceDirectoryAdmissionSource
import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.PrepareCollaborationSpaceRoomRequest
import dev.fajar.starter.collaboration.domain.usecases.ResolveCollaborationSpaceRoomRequest
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.securestorage.MemoryCredentialStore
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceRoomResolutionStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun normalizedDirectoryMarkCapturesExactFenceAndReopensWithExpiredReplacementParent() =
        withDatabase { f ->
            val denied =
                Failure(
                    FailureKind.AccessDenied,
                    "Original server detail",
                    code = "collaboration_access_denied",
                )
            assertEquals(AppResult.Success(true), f.directory().restrict(f.access, denied))
            val rawOriginal = f.raw()
            val result =
                assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                        f.resolve()(f.conversation(), f.command.id)
                    )
                    .value
            assertEquals(CollaborationSpaceConversationOutcome.Fenced(f.evidence), result.outcome)
            assertEquals(f.command, result.command)
            assertEquals(1, result.attempts)
            val saved = f.raw()
            assertEquals(rawOriginal.request, saved.request)
            val keys = listOf(f.commandKey.read(), f.directoryKey.read())
            f.reopen()
            assertEquals(AppResult.Success(result), f.resolve()(f.conversation(), f.command.id))
            assertEquals(saved, f.raw())
            assertEquals(keys, listOf(f.commandKey.read(), f.directoryKey.read()))
            assertEquals(
                denied.code,
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                        f.directory().admission(f.access)
                    )
                    .value
                    .denial
                    ?.code,
            )
            assertEquals(1, f.resolutions)
            assertIs<AppResult.Failed>(f.commands().archiveRecorded(f.access, result))
        }

    @Test
    fun committedFenceWithLostStorageReplyIsFoundThroughTheActualEncryptedCodec() =
        withDatabase { f ->
            var writes = 0
            val store =
                object : CommandStore by f.history.database.commands {
                    override suspend fun update(
                        scope: CommandScope,
                        partition: String,
                        id: String,
                        expectedRevision: Long,
                        state: String,
                    ): Boolean {
                        val committed =
                            f.history.database.commands.update(
                                scope,
                                partition,
                                id,
                                expectedRevision,
                                state,
                            )
                        writes++
                        if (writes == 2) error("Lost response after actual outcome commit")
                        return committed
                    }
                }
            val result =
                assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                        f.resolve(f.commands(store))(f.conversation(), f.command.id)
                    )
                    .value
            assertEquals(CollaborationSpaceConversationOutcome.Fenced(f.evidence), result.outcome)
            assertEquals(2, writes)
            val saved = f.raw()
            f.reopen()
            assertEquals(result, f.retained())
            assertEquals(saved, f.raw())
            assertEquals(1, f.resolutions)
        }

    @Test
    fun observedReceiptPersistsThroughCancellationWithoutBecomingFenceOrArchive() =
        withDatabase { f ->
            f.onResolve = {
                currentCoroutineContext().cancel()
                AppResult.Success(
                    CollaborationSpaceRoomRequestResolution.Recorded(f.command.intent, f.receipt)
                )
            }
            supervisorScope {
                val work = async { f.resolve()(f.conversation(), f.command.id) }
                assertFailsWith<CancellationException> { work.await() }
            }
            val record = f.retained()
            assertEquals(CollaborationSpaceConversationOutcome.Recorded(f.receipt), record.outcome)
            val saved = f.raw()
            f.reopen()
            assertEquals(AppResult.Success(record), f.resolve()(f.conversation(), f.command.id))
            assertEquals(saved, f.raw())
            assertEquals(1, f.resolutions)
        }

    @Test
    fun normalizedPeerFailureRetriesSameOriginalAfterRealDatabaseAndParentReplacement() =
        withDatabase { f ->
            val denied =
                Failure(
                    FailureKind.AccessDenied,
                    "Original peer detail",
                    code = "collaboration_peer_unavailable",
                    retryAfterSeconds = 7,
                )
            f.onResolve = { AppResult.Failed(denied) }
            assertIs<AppResult.Failed>(f.resolve()(f.conversation(), f.command.id))
            val retained = f.retained()
            val failure =
                assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(retained.outcome)
                    .failure
            assertEquals(denied.code, failure?.code)
            assertNotEquals(denied.message, failure?.message)
            val request = f.raw().request
            f.reopen(expired = false)
            f.onResolve = {
                AppResult.Success(CollaborationSpaceRoomRequestResolution.Fenced(f.evidence))
            }
            val result =
                assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                        f.resolve()(f.conversation(), f.command.id)
                    )
                    .value
            assertEquals(CollaborationSpaceConversationOutcome.Fenced(f.evidence), result.outcome)
            assertEquals(f.command, result.command)
            assertEquals(request, f.raw().request)
            assertEquals(1, result.attempts)
            assertEquals(2, f.resolutions)
            assertNull(
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                        f.directory().admission(f.access)
                    )
                    .value
                    .denial
            )
        }

    @Test
    fun owningPreparationPreservesActualFencedCiphertextAndReopensSuccessorOffline() =
        withDatabase { f ->
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                f.resolve()(f.conversation(), f.command.id)
            )
            val original = f.retained()
            val rawOriginal = f.raw()
            val result =
                assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                        f.prepare()(f.conversation())
                    )
                    .value
            val successor = assertIs<CollaborationSpaceConversationCommand.Room>(result.command)
            assertEquals(original.command.id, successor.predecessorId)
            assertEquals(original.copy(successorId = successor.id), f.retained())
            val archive =
                assertNotNull(
                    f.history.database.commands.archived(
                        spaceConversationCommandScope(f.access),
                        spaceConversationCommandPartition(f.access),
                        f.command.id,
                    )
                )
            assertEquals(rawOriginal, archive.record)
            val rawSuccessor =
                assertNotNull(
                    f.history.database.commands.get(
                        spaceConversationCommandScope(f.access),
                        spaceConversationCommandPartition(f.access),
                        successor.id,
                    )
                )
            val key = f.commandKey.read()
            f.reopen()
            assertEquals(AppResult.Success(result), f.prepare()(f.conversation()))
            assertEquals(
                rawSuccessor,
                f.history.database.commands.get(
                    spaceConversationCommandScope(f.access),
                    spaceConversationCommandPartition(f.access),
                    successor.id,
                ),
            )
            assertEquals(key, f.commandKey.read())
            assertEquals(original.copy(successorId = successor.id), f.retained())
            assertEquals(1, f.roomReads)
            assertEquals(1, f.deviceReads)
        }

    @Test
    fun owningPreparationFindsCommittedSuccessorAfterActualTransactionReplyIsLost() =
        withDatabase { f ->
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                f.resolve()(f.conversation(), f.command.id)
            )
            var handoffs = 0
            val store =
                object : CommandStore by f.history.database.commands {
                    override suspend fun archiveAndInsert(
                        scope: CommandScope,
                        expected: CommandRecord,
                        evidence: String,
                        successor: CommandRecord,
                    ): Boolean {
                        assertTrue(
                            f.history.database.commands.archiveAndInsert(
                                scope,
                                expected,
                                evidence,
                                successor,
                            )
                        )
                        handoffs++
                        error("Lost response after actual atomic handoff")
                    }
                }
            val result =
                assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                        f.prepare(f.commands(store))(f.conversation())
                    )
                    .value
            assertEquals(result.command.id, f.retained().successorId)
            f.reopen()
            assertEquals(AppResult.Success(result), f.prepare()(f.conversation()))
            assertEquals(1, handoffs)
            assertEquals(1, f.roomReads)
        }

    @Test
    fun cancelledActualHandoffRecoversOriginalSuccessorAfterDatabaseAndParentReplacement() =
        withDatabase { f ->
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                f.resolve()(f.conversation(), f.command.id)
            )
            var successorId: String? = null
            val store =
                object : CommandStore by f.history.database.commands {
                    override suspend fun archiveAndInsert(
                        scope: CommandScope,
                        expected: CommandRecord,
                        evidence: String,
                        successor: CommandRecord,
                    ): Boolean {
                        assertTrue(
                            f.history.database.commands.archiveAndInsert(
                                scope,
                                expected,
                                evidence,
                                successor,
                            )
                        )
                        successorId = successor.id
                        throw CancellationException("Cancelled after actual handoff commit")
                    }
                }
            assertFailsWith<CancellationException> {
                f.prepare(f.commands(store))(f.conversation())
            }
            val link = f.retained()
            assertEquals(successorId, link.successorId)
            f.reopen()
            val recovered =
                assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                        f.prepare()(f.conversation())
                    )
                    .value
            assertEquals(successorId, recovered.command.id)
            assertEquals(
                f.command.id,
                assertIs<CollaborationSpaceConversationCommand.Room>(recovered.command)
                    .predecessorId,
            )
            assertEquals(link, f.retained())
            assertEquals(1, f.roomReads)
            assertEquals(1, f.deviceReads)
        }

    private fun withDatabase(block: suspend (Scenario) -> Unit) = runTest {
        // Real database/crypto dispatchers use their own operation timers on every target.
        withContext(Dispatchers.Default) {
            val fixture = Scenario(CollaborationHistoryFixture(databaseOwner()))
            try {
                fixture.history.activate()
                assertEquals(
                    AppResult.Success(CollaborationSetupInsertion.STORED),
                    fixture.commands().insert(fixture.access, fixture.command),
                )
                assertEquals(
                    AppResult.Success(true),
                    fixture
                        .commands()
                        .update(
                            fixture.access,
                            fixture.retained(),
                            CollaborationSpaceConversationOutcome.Unconfirmed(),
                            1,
                        ),
                )
                block(fixture)
            } finally {
                fixture.close()
            }
        }
    }

    private class Scenario(val history: CollaborationHistoryFixture) {
        private val now = Instant.parse("2026-11-07T00:00:00Z")
        var owner =
            CollaborationSpaceOwner(
                CollaborationNativeParent(
                    history.access.sessionId,
                    history.access.audience,
                    history.access.accountId,
                    historyId(13001),
                ),
                historyId(13000),
            )
        val access
            get() = owner.localDirectoryAccess()

        val peer = historyId(13002)
        val command =
            CollaborationSpaceConversationCommand.Room(
                CollaborationSpaceRoomRequestIntent(
                    historyId(13003),
                    CollaborationSpaceIdentityBinding(
                        access.audience,
                        access.accountId,
                        access.spaceId,
                        owner.parent.nativeSessionId,
                    ),
                    historyId(13004),
                    CollaborationSpaceRoomRequest(
                        historyId(13006),
                        7,
                        CollaborationSpaceRoomAttempt(peer, 9),
                    ),
                ),
                peer,
                now,
            )
        val receipt = CollaborationSetupReceipt(historyId(13004), 10)
        val evidence = CollaborationSpaceRoomRequestFence(command.intent, historyId(13007), now)
        val commandKey = MemoryCredentialStore()
        val directoryKey = MemoryCredentialStore()
        var admissions = MemoryCollaborationSpaceDirectoryAdmissionSource()
        var session =
            Session(
                owner.parent.sessionId,
                User(access.accountId, "Fictional coworker", "fixture@example.test"),
                SessionTokens(
                    "fictional-access",
                    "fictional-refresh",
                    (now + 1.hours).toEpochMilliseconds(),
                ),
                SessionBinding(
                    owner.parent.nativeSessionId,
                    access.audience,
                    (now + 2.hours).toEpochMilliseconds(),
                ),
            )
        var resolutions = 0
        var onResolve: suspend () -> AppResult<CollaborationSpaceRoomRequestResolution> = {
            AppResult.Success(CollaborationSpaceRoomRequestResolution.Fenced(evidence))
        }
        val remote =
            object : CollaborationSpaceRoomRepository {
                override suspend fun get(
                    conversation: CollaborationSpaceConversation
                ): AppResult<CollaborationSpaceRoomView> = error("No room polling")

                override suspend fun request(
                    conversation: CollaborationSpaceConversation,
                    operation: String,
                    request: CollaborationSpaceRoomRequest,
                ): AppResult<CollaborationSetupReceipt> = error("No new room execution")

                override suspend fun readReceipt(
                    conversation: CollaborationSpaceConversation,
                    original: CollaborationSpaceRoomOperation,
                ): AppResult<CollaborationSpaceRoomReceiptObservation> =
                    error("No old-parent impersonation")

                override suspend fun readIntentReceipt(
                    conversation: CollaborationSpaceConversation,
                    original: CollaborationSpaceRoomRequestIntent,
                ): AppResult<CollaborationSpaceRoomIntentReceiptObservation> =
                    error("Absence is not a fence")

                override suspend fun fenceRequest(
                    conversation: CollaborationSpaceConversation,
                    original: CollaborationSpaceRoomRequestIntent,
                ): AppResult<CollaborationSpaceRoomRequestResolution> {
                    assertEquals(this@Scenario.conversation(), conversation)
                    assertEquals(command.intent, original)
                    assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(retained().outcome)
                    assertEquals(1, retained().attempts)
                    resolutions++
                    return onResolve()
                }
            }
        val sessions =
            object : SessionRepository {
                override val persistent = true

                override fun observe() = flowOf(AppResult.Success(session))

                override suspend fun current(): AppResult<Session?> = AppResult.Success(session)

                override suspend fun compareAndSet(
                    expected: Session?,
                    updated: Session?,
                ): AppResult<Boolean> = error("No credential changes")

                override suspend fun clear(sessionId: String): AppResult<Boolean> =
                    error("No logout")
            }
        val clock =
            object : Clock {
                override fun now() = this@Scenario.now
            }

        fun commands(store: CommandStore = history.database.commands) =
            EncryptedCollaborationSpaceConversationCommandRepository(
                store,
                history.cipher,
                commandKey,
            )

        fun directory() =
            EncryptedCollaborationSpaceDirectoryRepository(
                history.database.replicas,
                history.cipher,
                directoryKey,
                admissions,
            )

        fun conversation() =
            CollaborationSpaceConversation(owner, receipt.id, access.accountId, peer, peer, now, 0)

        var roomReads = 0
        var deviceReads = 0

        fun prepare(commands: CollaborationSpaceConversationCommandRepository = commands()) =
            PrepareCollaborationSpaceRoomRequest(
                commands,
                object : CollaborationSpaceRoomRepository by remote {
                    override suspend fun get(
                        conversation: CollaborationSpaceConversation
                    ): AppResult<CollaborationSpaceRoomView> {
                        roomReads++
                        assertEquals(this@Scenario.conversation(), conversation)
                        return AppResult.Success(
                            CollaborationSpaceRoomView(conversation, null, null)
                        )
                    }
                },
                object : CollaborationSpaceDeviceRepository {
                    override suspend fun register(
                        owner: CollaborationSpaceOwner,
                        command: CollaborationSpaceRegistrationCommand,
                    ): AppResult<CollaborationSetupReceipt> = error("No device registration")

                    override suspend fun status(
                        owner: CollaborationSpaceOwner
                    ): AppResult<CollaborationSpaceDeviceObservation> {
                        deviceReads++
                        assertEquals(this@Scenario.owner, owner)
                        return AppResult.Success(
                            CollaborationSpaceDeviceObservation(
                                owner,
                                CollaborationSpaceDeviceStatus(
                                    historyId(13008),
                                    12,
                                    CollaborationDeviceState.ACTIVE,
                                    CollaborationNativePlatform.ANDROID,
                                    CollaborationSpaceDeviceOrigin.LEGACY,
                                    "Fixture",
                                    CollaborationSpaceProvisioningStatus(
                                        0,
                                        CollaborationProvisioningState.SUCCEEDED,
                                        1,
                                        now - 60.seconds,
                                        CollaborationSpaceProvisioningOutcome(
                                            0,
                                            CollaborationProvisioningState.SUCCEEDED,
                                            now - 120.seconds,
                                            null,
                                        ),
                                        null,
                                    ),
                                    now,
                                    null,
                                ),
                            )
                        )
                    }
                },
                directory(),
                sessions,
                IdentityServer(access.audience, "Fictional server"),
                AppBuild(AppPlatform.Android, 5, "test", null),
                clock,
            )

        fun resolve(commands: CollaborationSpaceConversationCommandRepository = commands()) =
            ResolveCollaborationSpaceRoomRequest(
                commands,
                remote,
                directory(),
                sessions,
                IdentityServer(access.audience, "Fictional server"),
                AppBuild(AppPlatform.Android, 5, "test", null),
                clock,
            )

        suspend fun retained() =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord?>>(
                        commands().find(access, command.id)
                    )
                    .value
            )

        suspend fun raw() =
            assertNotNull(
                history.database.commands.get(
                    spaceConversationCommandScope(access),
                    spaceConversationCommandPartition(access),
                    command.id,
                )
            )

        suspend fun reopen(expired: Boolean = true) {
            val renewed = history.access.copy(sessionId = "new-local-login")
            history.reopen(renewed)
            owner =
                owner.copy(
                    parent =
                        owner.parent.copy(
                            sessionId = renewed.sessionId,
                            nativeSessionId = historyId(13005),
                        )
                )
            session =
                session.copy(
                    id = renewed.sessionId,
                    binding =
                        session.binding.copy(
                            serverId = owner.parent.nativeSessionId,
                            expiresAtEpochMillis =
                                (if (expired) now - 1.seconds else now + 2.hours)
                                    .toEpochMilliseconds(),
                        ),
                )
            admissions = MemoryCollaborationSpaceDirectoryAdmissionSource()
        }

        fun close() {
            commandKey.close()
            directoryKey.close()
            history.close()
        }
    }
}
