package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceIdentityCommandRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceIdentityCommandStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private fun CollaborationHistoryFixture.scope(owner: CollaborationLocalAccess = access) =
        CollaborationSpaceLocalAccess(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            historyId(9700),
        )

    private fun CollaborationHistoryFixture.repo(store: CommandStore = database.commands) =
        EncryptedCollaborationSpaceIdentityCommandRepository(store, cipher, keys)

    private fun CollaborationHistoryFixture.command() =
        CollaborationSpaceIdentityCommand(
            historyId(9702),
            CollaborationSpaceIdentityBinding(
                access.audience,
                access.accountId,
                scope().spaceId,
                historyId(9701),
            ),
            CollaborationNativePlatform.ANDROID,
            5,
            Instant.parse("2026-11-01T00:00:00Z"),
        )

    private suspend fun CollaborationHistoryFixture.readIdentity(
        access: CollaborationSpaceLocalAccess = scope()
    ) =
        assertNotNull(
            assertIs<AppResult.Success<CollaborationSpaceIdentityCommandRecord?>>(
                    repo().current(access)
                )
                .value
        )

    private suspend fun CollaborationHistoryFixture.rawIdentity(
        access: CollaborationSpaceLocalAccess = scope()
    ) =
        assertNotNull(
            database.commands.get(
                spaceIdentityCommandScope(access),
                spaceIdentityCommandPartition(access),
                command().id,
            )
        )

    private suspend fun CollaborationHistoryFixture.insert() =
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.STORED),
            repo().insert(scope(), command()),
        )

    @Test
    fun emptyReadsAllocateNoKeyAndConfirmedReceiptReopensWithOriginalRequest() = withDatabase { f ->
        assertEquals(AppResult.Success(null), f.repo().current(f.scope()))
        assertEquals(AppResult.Success(null), f.repo().observe(f.scope()).first())
        assertNull(f.keys.read())
        f.insert()
        assertEquals(
            AppResult.Success(true),
            f.repo()
                .update(
                    f.scope(),
                    f.readIdentity(),
                    CollaborationSpaceIdentityOutcome.Unconfirmed(),
                    1,
                ),
        )
        val selected =
            CollaborationSpaceIdentityOutcome.Selected(
                CollaborationSetupReceipt(f.access.accountId, 0)
            )
        assertEquals(
            AppResult.Success(true),
            f.repo().update(f.scope(), f.readIdentity(), selected, 1),
        )
        val raw = f.rawIdentity()
        assertFalse(raw.request.contains(f.command().binding.nativeSessionId))
        assertFalse(raw.state.contains(f.access.accountId))
        assertFalse(raw.partition.contains(f.command().binding.nativeSessionId))
        val renewed = f.access.copy(sessionId = "new-local-login", companyId = historyId(9790))
        f.reopen(renewed)
        val access = f.scope(renewed)
        val saved = f.readIdentity(access)
        assertEquals(f.command(), saved.command)
        assertEquals(selected, saved.outcome)
        assertEquals(2L, saved.revision)
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.STORED),
            f.repo().insert(access, f.command()),
        )
        assertEquals(raw, f.rawIdentity(access))
    }

    @Test
    fun serverAccountAndSpaceBoundariesCannotSubstituteACommand() = withDatabase { f ->
        f.insert()
        val original = f.readIdentity()
        val foreignSpace = f.scope().copy(spaceId = historyId(9710))
        assertEquals(AppResult.Success(null), f.repo().current(foreignSpace))
        assertIs<AppResult.Failed>(f.repo().insert(foreignSpace, f.command()))
        assertIs<AppResult.Failed>(
            f.repo()
                .update(foreignSpace, original, CollaborationSpaceIdentityOutcome.Unconfirmed(), 1)
        )
        for (owner in
            listOf(
                f.access.copy(sessionId = "other", accountId = historyId(9711)),
                f.access.copy(sessionId = "server", audience = "https://other.example.test/"),
            )) {
            f.activate(owner)
            assertEquals(AppResult.Success(null), f.repo().current(f.scope(owner)))
            assertEquals(AppResult.Success(null), f.repo().current(f.scope()))
            assertIs<AppResult.Failed>(f.repo().insert(f.scope(owner), f.command()))
            f.activate()
            assertEquals(original, f.readIdentity())
        }
    }

    @Test
    fun lostUnconfirmedWriteResponseSurvivesRestartWithoutRewritingTheOperation() =
        withDatabase { f ->
            f.insert()
            val initial = f.readIdentity()
            val request = f.rawIdentity().request
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
                            f.database.commands.update(
                                scope,
                                partition,
                                id,
                                expectedRevision,
                                state,
                            )
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
                            CollaborationSpaceIdentityOutcome.Unconfirmed(),
                            1,
                        )
                }
                assertFailsWith<CancellationException> { pending.await() }
            }
            f.reopen()
            val saved = f.readIdentity()
            assertEquals(initial.command, saved.command)
            assertEquals(1, saved.attempts)
            assertIs<CollaborationSpaceIdentityOutcome.Unconfirmed>(saved.outcome)
            f.insert()
            assertEquals(request, f.rawIdentity().request)
            assertEquals(saved, f.readIdentity())
        }

    @Test
    fun competingFirstInsertionKeepsTheWinningOperation() = withDatabase { f ->
        val winner = f.command().copy(id = historyId(9712))
        val store =
            object : CommandStore by f.database.commands {
                override suspend fun insert(
                    scope: CommandScope,
                    record: CommandRecord,
                ): CommandInsertResult {
                    assertEquals(
                        AppResult.Success(CollaborationSetupInsertion.STORED),
                        f.repo().insert(f.scope(), winner),
                    )
                    return f.database.commands.insert(scope, record)
                }
            }
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.OCCUPIED),
            f.repo(store).insert(f.scope(), f.command()),
        )
        assertEquals(winner, f.readIdentity().command)
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.OCCUPIED),
            f.repo().insert(f.scope(), f.command()),
        )
        assertEquals(winner, f.readIdentity().command)
    }

    @Test
    fun competingStateWriteWinsAndSelectedReceiptCannotBeReopened() = withDatabase { f ->
        f.insert()
        val original = f.readIdentity()
        val uncertain = CollaborationSpaceIdentityOutcome.Unconfirmed()
        val store =
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
                        f.repo().update(f.scope(), original, uncertain, 1),
                    )
                    return f.database.commands.update(scope, partition, id, expectedRevision, state)
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repo(store).update(f.scope(), original, uncertain, 1),
        )
        val sending = f.readIdentity()
        assertEquals(1L, sending.revision)
        assertEquals(
            AppResult.Success(true),
            f.repo()
                .update(
                    f.scope(),
                    sending,
                    CollaborationSpaceIdentityOutcome.Selected(
                        CollaborationSetupReceipt(f.access.accountId, 0)
                    ),
                    1,
                ),
        )
        val selected = f.readIdentity()
        assertIs<AppResult.Failed>(f.repo().update(f.scope(), selected, uncertain, 2))
        assertEquals(selected, f.readIdentity())
        assertEquals(AppResult.Success(false), f.repo().update(f.scope(), original, uncertain, 1))
    }

    @Test
    fun lostSharedKeyNeverRegeneratesOverActiveOrInactiveCommands() = withDatabase { f ->
        f.insert()
        val raw = f.rawIdentity()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.repo().current(f.scope()))
        val other = f.access.copy(sessionId = "other", accountId = historyId(9720))
        f.activate(other)
        val command =
            f.command().copy(binding = f.command().binding.copy(accountId = other.accountId))
        assertIs<AppResult.Failed>(f.repo().insert(f.scope(other), command))
        assertNull(f.keys.read())
        f.activate()
        assertEquals(raw, f.rawIdentity())
    }

    @Test
    fun corruptRecordMetadataPurposeAndBoundsFailInReadFlowAndMutation() = withDatabase { f ->
        f.insert()
        val original = f.readIdentity()
        val raw = f.rawIdentity()
        for (changed in
            listOf(
                raw.copy(partition = "foreign"),
                raw.copy(resourceId = "foreign"),
                raw.copy(id = historyId(9730)),
                raw.copy(revision = -1),
                raw.copy(sortKey = "other"),
                raw.copy(request = raw.state),
                raw.copy(state = raw.request),
                raw.copy(request = "x".repeat(8193)),
                raw.copy(state = "bad"),
            )) {
            val store =
                object : CommandStore by f.database.commands {
                    override fun observe(
                        scope: CommandScope,
                        partition: String,
                        resourceId: String?,
                        after: String?,
                        limit: Int,
                    ) = flowOf(listOf(changed))

                    override suspend fun get(scope: CommandScope, partition: String, id: String) =
                        changed
                }
            assertIs<AppResult.Failed>(f.repo(store).current(f.scope()))
            assertIs<AppResult.Failed>(f.repo(store).observe(f.scope()).first())
            assertIs<AppResult.Failed>(f.repo(store).insert(f.scope(), f.command()))
            assertIs<AppResult.Failed>(
                f.repo(store)
                    .update(f.scope(), original, CollaborationSpaceIdentityOutcome.Unconfirmed(), 1)
            )
        }
        assertEquals(raw, f.rawIdentity())
    }

    @Test
    fun invalidInputFailsInsideSafeBoundariesWithoutAllocatingAKey() = withDatabase { f ->
        for (access in
            listOf(
                f.scope().copy(sessionId = "bad\n"),
                f.scope().copy(spaceId = "../space"),
                f.scope().copy(accountId = "invalid"),
            )) {
            assertIs<AppResult.Failed>(f.repo().current(access))
            assertIs<AppResult.Failed>(f.repo().observe(access).first())
            assertIs<AppResult.Failed>(f.repo().insert(access, f.command()))
        }
        assertIs<AppResult.Failed>(f.repo().insert(f.scope(), f.command().copy(clientBuild = 0)))
        assertNull(f.keys.read())
    }

    @Test
    fun accountReplacementAtInsertionPublishesNeitherAccount() = withDatabase { f ->
        val other = f.access.copy(sessionId = "replacement", accountId = historyId(9740))
        val store =
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
            f.repo(store).insert(f.scope(), f.command()),
        )
        assertEquals(AppResult.Success(null), f.repo().current(f.scope(other)))
        f.activate()
        assertEquals(AppResult.Success(null), f.repo().current(f.scope()))
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
