package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.MemoryCollaborationSpaceDirectoryAdmissionSource
import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReplayCollaborationSpaceConversationOpen
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

abstract class CollaborationSpaceOpenReplayStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun normalizedDirectoryFailureCanBeMarkedConfirmedAndArchivedAcrossRealCodecs() =
        withDatabase { f ->
            val denied =
                Failure(
                    FailureKind.AccessDenied,
                    "Original server detail",
                    code = "collaboration_access_denied",
                )
            assertEquals(AppResult.Success(true), f.directory().restrict(f.access, denied))
            val gate =
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                        f.directory().admission(f.access)
                    )
                    .value
            assertNotEquals(denied.message, gate.denial?.message)
            val result =
                assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                        f.replay()(f.owner, f.command.id)
                    )
                    .value
            assertIs<CollaborationSpaceConversationOutcome.Recorded>(result.outcome)
            f.assertArchived(result)
            assertEquals(
                denied.code,
                assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                        f.directory().admission(f.access)
                    )
                    .value
                    .denial
                    ?.code,
            )
            val archive = f.rawArchive()
            val keys = listOf(f.commandKey.read(), f.directoryKey.read(), f.history.keys.read())
            f.reopen()
            assertEquals(AppResult.Success(result), f.replay()(f.owner, f.command.id))
            assertEquals(archive, f.rawArchive())
            assertEquals(
                keys,
                listOf(f.commandKey.read(), f.directoryKey.read(), f.history.keys.read()),
            )
            assertEquals(1, f.opens)
            assertEquals(1, f.gets)
        }

    @Test
    fun pairDenialAndLostArchiveResponseKeepOriginalIntentAndCiphertext() = withDatabase { f ->
        val denied =
            Failure(
                FailureKind.AccessDenied,
                "Original pair detail",
                code = "collaboration_peer_unavailable",
                retryAfterSeconds = 7,
            )
        f.openResult = AppResult.Failed(denied)
        assertIs<AppResult.Failed>(f.replay()(f.owner, f.command.id))
        val retained = f.retained()
        val storedFailure =
            assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(retained.outcome).failure
        assertEquals(denied.code, storedFailure?.code)
        assertEquals(denied.retryAfterSeconds, storedFailure?.retryAfterSeconds)
        assertNotEquals(denied.message, storedFailure?.message)
        assertNull(
            assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                    f.directory().admission(f.access)
                )
                .value
                .denial
        )
        f.openResult = AppResult.Success(f.receipt)
        val store =
            object : CommandStore by f.history.database.commands {
                override suspend fun archive(
                    scope: CommandScope,
                    expected: CommandRecord,
                    evidence: String,
                ): Boolean {
                    check(f.history.database.commands.archive(scope, expected, evidence))
                    throw CancellationException("Lost real archive reply")
                }
            }
        assertFailsWith<CancellationException> {
            f.replay(commands = f.commands(store))(f.owner, f.command.id)
        }
        val archive = f.rawArchive()
        f.reopen()
        val result =
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                    f.replay()(f.owner, f.command.id)
                )
                .value
        f.assertArchived(result)
        assertEquals(f.command, result.command)
        assertEquals(archive, f.rawArchive())
        assertEquals(2, f.opens)
        assertEquals(1, f.gets)
    }

    @Test
    fun lostRealMetadataPublicationIsVerifiedBeforeOriginalArchive() = withDatabase { f ->
        var interrupted = false
        val store =
            object : ReplicaStore by f.history.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    val result =
                        f.history.database.replicas.replace(
                            scope,
                            partition,
                            expected,
                            snapshot,
                            wake,
                        )
                    if (result && partition == spaceConversationMetadataPartition(f.access)) {
                        interrupted = true
                        error("Lost real metadata publication reply")
                    }
                    return result
                }
            }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                    f.replay(metadata = f.metadata(store))(f.owner, f.command.id)
                )
                .value
        assertTrue(interrupted)
        f.assertArchived(result)
        assertEquals(1, f.opens)
        assertEquals(1, f.gets)
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
            CollaborationSpaceConversationCommand.Open(
                historyId(13003),
                CollaborationSpaceIdentityBinding(
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    owner.parent.nativeSessionId,
                ),
                peer,
                now,
            )
        val receipt = CollaborationSetupReceipt(historyId(13004), 0)
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
        var opens = 0
        var gets = 0
        var openResult: AppResult<CollaborationSetupReceipt> = AppResult.Success(receipt)
        val remote =
            object : CollaborationSpaceConversationRepository {
                override suspend fun list(
                    owner: CollaborationSpaceOwner,
                    after: String?,
                    limit: Int,
                ): AppResult<CollaborationSpaceConversationPage> = error("No remote scan")

                override suspend fun open(
                    owner: CollaborationSpaceOwner,
                    operation: String,
                    peerAccountId: String,
                ): AppResult<CollaborationSetupReceipt> {
                    assertEquals(this@Scenario.owner, owner)
                    assertEquals(command.id, operation)
                    assertEquals(peer, peerAccountId)
                    assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(retained().outcome)
                    opens++
                    return openResult
                }

                override suspend fun get(
                    owner: CollaborationSpaceOwner,
                    conversationId: String,
                ): AppResult<CollaborationSpaceConversation> {
                    assertEquals(this@Scenario.owner, owner)
                    assertEquals(receipt.id, conversationId)
                    gets++
                    return AppResult.Success(conversation())
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

        fun metadata(store: ReplicaStore = history.database.replicas) =
            EncryptedCollaborationSpaceConversationMetadataRepository(
                store,
                history.cipher,
                history.keys,
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

        fun replay(
            commands: CollaborationSpaceConversationCommandRepository = commands(),
            metadata: CollaborationSpaceConversationMetadataRepository = metadata(),
        ) =
            ReplayCollaborationSpaceConversationOpen(
                commands,
                remote,
                metadata,
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

        suspend fun rawArchive() =
            assertNotNull(
                history.database.commands.archived(
                    spaceConversationCommandScope(access),
                    spaceConversationCommandPartition(access),
                    command.id,
                )
            )

        suspend fun assertArchived(record: CollaborationSpaceConversationCommandRecord) {
            assertNull(
                history.database.commands.get(
                    spaceConversationCommandScope(access),
                    spaceConversationCommandPartition(access),
                    command.id,
                )
            )
            assertEquals(record, retained())
            assertNotNull(rawArchive())
            assertEquals(
                AppResult.Success(conversation().localMetadata()),
                metadata().getById(access, receipt.id),
            )
        }

        suspend fun reopen() {
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
                            expiresAtEpochMillis = (now - 1.seconds).toEpochMilliseconds(),
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
