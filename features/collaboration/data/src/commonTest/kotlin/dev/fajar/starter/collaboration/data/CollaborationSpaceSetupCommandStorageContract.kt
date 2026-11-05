package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceSetupCommandRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceSetupCommandStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private val parent = historyId(9801)
    private val operation = historyId(9802)
    private val device = historyId(9850)

    private fun CollaborationHistoryFixture.scope(owner: CollaborationLocalAccess = access) =
        CollaborationSpaceLocalAccess(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            historyId(9800),
        )

    private fun CollaborationHistoryFixture.repo(store: CommandStore = database.commands) =
        EncryptedCollaborationSpaceSetupCommandRepository(store, cipher, keys)

    private fun CollaborationHistoryFixture.command(
        isBootstrap: Boolean = false,
        owner: CollaborationLocalAccess = access,
    ): CollaborationSpaceSetupCommand {
        val binding =
            CollaborationSpaceDeviceBinding(
                owner.audience,
                owner.accountId,
                scope(owner).spaceId,
                parent,
            )
        val provider =
            CollaborationSpaceIdentity(
                binding.spaceId,
                binding.accountId,
                "matrix.hris.internal",
                "@hris_${owner.companyId.replace("-", "")}_${owner.accountId.replace("-", "")}:matrix.hris.internal",
                CollaborationSpaceIdentityOrigin.LEGACY,
                owner.companyId,
                0,
                Instant.parse("2020-01-01T00:00:00Z"),
            )
        val request =
            if (isBootstrap)
                CollaborationSpaceBootstrap(
                    operation,
                    binding,
                    CollaborationNativePlatform.ANDROID,
                    5,
                    device,
                    2,
                    null,
                    provider,
                    "HRIS_${historyId(9851).replace("-", "")}",
                )
            else CollaborationSpaceProvisioningRetry(operation, binding, device, 0, 1)
        return CollaborationSpaceSetupCommand(
            historyId(9803),
            CollaborationNativePlatform.ANDROID,
            5,
            Instant.parse("2020-01-01T00:00:00Z"),
            request,
        )
    }

    private fun gateway(command: CollaborationSpaceSetupCommand): CollaborationSpaceGateway {
        val request = command.request as CollaborationSpaceBootstrap
        return CollaborationSpaceGateway(
            command.binding,
            device,
            historyId(9860),
            0,
            request.provider.matrixUserId,
            request.matrixDeviceId,
            "hris_mxg_a_" + "a".repeat(43),
            "hris_mxg_r_" + "b".repeat(43),
            Instant.parse("2020-01-01T00:05:00Z").toEpochMilliseconds(),
            Instant.parse("2020-01-02T00:00:00Z").toEpochMilliseconds(),
        )
    }

    private suspend fun CollaborationHistoryFixture.insert(
        command: CollaborationSpaceSetupCommand = command()
    ) {
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.STORED),
            repo().insert(scope(), command),
        )
    }

    private suspend fun CollaborationHistoryFixture.readSetup(
        access: CollaborationSpaceLocalAccess = scope()
    ) =
        assertNotNull(
            assertIs<AppResult.Success<CollaborationSpaceSetupRecord?>>(
                    repo().current(access, parent)
                )
                .value
        )

    private suspend fun CollaborationHistoryFixture.rawSetup() =
        assertNotNull(
            database.commands.get(
                spaceSetupCommandScope(scope()),
                spaceSetupCommandPartition(scope()),
                operation,
            )
        )

    private suspend fun CollaborationHistoryFixture.sending() {
        assertEquals(
            AppResult.Success(true),
            repo().update(scope(), readSetup(), CollaborationSpaceSetupOutcome.Unconfirmed(), 1),
        )
    }

    private suspend fun CollaborationHistoryFixture.retried(): CollaborationSpaceSetupRecord {
        insert()
        sending()
        assertEquals(
            AppResult.Success(true),
            repo()
                .update(
                    scope(),
                    readSetup(),
                    CollaborationSpaceSetupOutcome.Retried(CollaborationSetupReceipt(device, 2)),
                    1,
                ),
        )
        return readSetup()
    }

    @Test
    fun encryptedGatewayReopensAfterNewLoginCompanyAndOrdinaryExpiry() = withDatabase { f ->
        assertEquals(AppResult.Success(null), f.repo().current(f.scope(), parent))
        assertEquals(AppResult.Success(null), f.repo().find(f.scope(), operation))
        assertEquals(AppResult.Success(emptyList()), f.repo().observeAll(f.scope()).first())
        assertNull(f.keys.read())
        val command = f.command(true)
        f.insert(command)
        f.sending()
        val outcome = CollaborationSpaceSetupOutcome.Bootstrapped(gateway(command))
        assertEquals(AppResult.Success(true), f.repo().update(f.scope(), f.readSetup(), outcome, 1))
        val raw = f.rawSetup()
        for (plain in
            listOf(
                outcome.gateway.accessToken,
                outcome.gateway.refreshToken,
                outcome.gateway.matrixUserId,
                command.binding.nativeSessionId,
            )) {
            assertFalse(raw.request.contains(plain))
            assertFalse(raw.state.contains(plain))
            assertFalse(outcome.toString().contains(plain))
            assertFalse(f.readSetup().toString().contains(plain))
        }
        val renewed = f.access.copy(sessionId = "new-login", companyId = historyId(9880))
        f.reopen(renewed)
        val access = f.scope(renewed)
        val saved = f.readSetup(access)
        assertEquals(command, saved.command)
        assertEquals(outcome, saved.outcome)
        assertEquals(AppResult.Success(saved), f.repo().find(access, operation))
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.STORED),
            f.repo().insert(access, command),
        )
        assertIs<AppResult.Failed>(f.repo().archiveRetried(access, saved))
        assertEquals(
            raw,
            f.database.commands.get(
                spaceSetupCommandScope(access),
                spaceSetupCommandPartition(access),
                operation,
            ),
        )
    }

    @Test
    fun archivedRetryRetainsExactReceiptAndIdWhileNextOperationCanProceed() = withDatabase { f ->
        val saved = f.retried()
        val raw = f.rawSetup()
        assertEquals(AppResult.Success(true), f.repo().archiveRetried(f.scope(), saved))
        assertEquals(AppResult.Success(true), f.repo().archiveRetried(f.scope(), saved))
        assertEquals(AppResult.Success(null), f.repo().current(f.scope(), parent))
        assertEquals(AppResult.Success(emptyList()), f.repo().observeAll(f.scope()).first())
        assertEquals(AppResult.Success(saved), f.repo().find(f.scope(), operation))
        val archive =
            assertNotNull(
                f.database.commands.archived(
                    spaceSetupCommandScope(f.scope()),
                    spaceSetupCommandPartition(f.scope()),
                    operation,
                )
            )
        assertEquals(raw, archive.record)
        assertEquals(raw.state, archive.evidence)
        f.insert()
        assertEquals(AppResult.Success(null), f.repo().current(f.scope(), parent))
        val request = f.command().request as CollaborationSpaceProvisioningRetry
        val next =
            f.command()
                .copy(
                    request = request.copy(operationId = historyId(9804), provisioningRevision = 2)
                )
        f.insert(next)
        assertEquals(next, f.readSetup().command)
        assertEquals(AppResult.Success(saved), f.repo().find(f.scope(), operation))
        assertIs<AppResult.Failed>(f.repo().insert(f.scope(), saved.command.copy(clientBuild = 6)))
        assertEquals(next, f.readSetup().command)
    }

    @Test
    fun lostArchiveResponseRecoversTheSameCompletionWithoutRepublishingPendingWork() =
        withDatabase { f ->
            val saved = f.retried()
            val store =
                object : CommandStore by f.database.commands {
                    override suspend fun archive(
                        scope: CommandScope,
                        expected: CommandRecord,
                        evidence: String,
                    ): Boolean {
                        val result = f.database.commands.archive(scope, expected, evidence)
                        currentCoroutineContext().cancel()
                        return result
                    }
                }
            supervisorScope {
                val task = async { f.repo(store).archiveRetried(f.scope(), saved) }
                assertFailsWith<CancellationException> { task.await() }
            }
            f.reopen()
            assertEquals(AppResult.Success(null), f.repo().current(f.scope(), parent))
            assertEquals(AppResult.Success(saved), f.repo().find(f.scope(), operation))
            assertEquals(AppResult.Success(true), f.repo().archiveRetried(f.scope(), saved))
            f.insert()
            assertEquals(AppResult.Success(null), f.repo().current(f.scope(), parent))
        }

    @Test
    fun serverAccountSpaceAndNativeResourcesStaySeparated() = withDatabase { f ->
        f.insert()
        val saved = f.readSetup()
        val otherSpace = f.scope().copy(spaceId = historyId(9890))
        assertEquals(AppResult.Success(null), f.repo().current(otherSpace, parent))
        assertEquals(AppResult.Success(null), f.repo().find(otherSpace, operation))
        assertIs<AppResult.Failed>(f.repo().insert(otherSpace, saved.command))
        assertEquals(AppResult.Success(null), f.repo().current(f.scope(), historyId(9891)))
        for (other in
            listOf(
                f.access.copy(sessionId = "other", accountId = historyId(9892)),
                f.access.copy(sessionId = "other", audience = "https://another.example/"),
            )) {
            f.activate(other)
            assertEquals(AppResult.Success(null), f.repo().find(f.scope(other), operation))
            assertEquals(AppResult.Success(emptyList()), f.repo().observeAll(f.scope()).first())
            assertIs<AppResult.Failed>(f.repo().insert(f.scope(other), saved.command))
        }
        f.activate()
        assertEquals(saved, f.readSetup())
    }

    @Test
    fun uncertaintyCommittedBeforeCancellationSurvivesRestartWithOriginalCiphertext() =
        withDatabase { f ->
            f.insert()
            val initial = f.readSetup()
            val request = f.rawSetup().request
            val store =
                object : CommandStore by f.database.commands {
                    override suspend fun update(
                        scope: CommandScope,
                        partition: String,
                        id: String,
                        expectedRevision: Long,
                        state: String,
                    ): Boolean {
                        val result =
                            f.database.commands.update(
                                scope,
                                partition,
                                id,
                                expectedRevision,
                                state,
                            )
                        currentCoroutineContext().cancel()
                        return result
                    }
                }
            supervisorScope {
                val task = async {
                    f.repo(store)
                        .update(f.scope(), initial, CollaborationSpaceSetupOutcome.Unconfirmed(), 1)
                }
                assertFailsWith<CancellationException> { task.await() }
            }
            f.reopen()
            val saved = f.readSetup()
            assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(saved.outcome)
            assertEquals(1, saved.attempts)
            assertEquals(initial.command, saved.command)
            f.insert()
            assertEquals(request, f.rawSetup().request)
            assertEquals(saved, f.readSetup())
        }

    @Test
    fun competingInsertAndCasKeepWinningOperationAndRevision() = withDatabase { f ->
        val initial = f.command()
        val request = initial.request as CollaborationSpaceProvisioningRetry
        val winner = initial.copy(request = request.copy(operationId = historyId(9805)))
        val store =
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
            f.repo(store).insert(f.scope(), initial),
        )
        assertEquals(winner, f.readSetup().command)
        val before = f.readSetup()
        val competing =
            object : CommandStore by f.database.commands {
                override suspend fun update(
                    scope: CommandScope,
                    partition: String,
                    id: String,
                    expectedRevision: Long,
                    state: String,
                ): Boolean {
                    f.sending()
                    return f.database.commands.update(scope, partition, id, expectedRevision, state)
                }
            }
        assertEquals(
            AppResult.Success(false),
            f.repo(competing)
                .update(f.scope(), before, CollaborationSpaceSetupOutcome.Unconfirmed(), 1),
        )
        assertEquals(1L, f.readSetup().revision)
        assertEquals(winner, f.readSetup().command)
    }

    @Test
    fun receiptKindsAndVersionsCannotReplaceUncertaintyOrReopenTerminalSuccess() =
        withDatabase { f ->
            f.insert()
            f.sending()
            val sending = f.readSetup()
            for (receipt in
                listOf(
                    CollaborationSetupReceipt(device, 0),
                    CollaborationSetupReceipt(historyId(9895), 2),
                )) assertIs<AppResult.Failed>(
                f.repo()
                    .update(f.scope(), sending, CollaborationSpaceSetupOutcome.Retried(receipt), 1)
            )
            assertIs<AppResult.Failed>(
                f.repo()
                    .update(
                        f.scope(),
                        sending,
                        CollaborationSpaceSetupOutcome.Bootstrapped(gateway(f.command(true))),
                        1,
                    )
            )
            assertIs<AppResult.Failed>(f.repo().archiveRetried(f.scope(), sending))
            val outcome =
                CollaborationSpaceSetupOutcome.Retried(CollaborationSetupReceipt(device, 2))
            assertEquals(AppResult.Success(true), f.repo().update(f.scope(), sending, outcome, 1))
            val done = f.readSetup()
            assertIs<AppResult.Failed>(
                f.repo().update(f.scope(), done, CollaborationSpaceSetupOutcome.Unconfirmed(), 2)
            )
            assertEquals(AppResult.Success(false), f.repo().update(f.scope(), sending, outcome, 1))
            assertEquals(done, f.readSetup())
        }

    @Test
    fun gatewayCannotChangeOriginalBindingProviderDeviceVersionOrTokenNamespace() =
        withDatabase { f ->
            val command = f.command(true)
            f.insert(command)
            f.sending()
            val before = f.readSetup()
            val original = gateway(command)
            for (changed in
                listOf(
                    original.copy(
                        binding = original.binding.copy(nativeSessionId = historyId(9896))
                    ),
                    original.copy(deviceId = historyId(9896)),
                    original.copy(matrixUserId = "@other:matrix.hris.internal"),
                    original.copy(matrixDeviceId = "HRISG_${device.replace("-", "")}"),
                    original.copy(version = 1),
                    original.copy(sessionId = "invalid"),
                    original.copy(accessToken = "hris_mx_a_" + "a".repeat(43)),
                    original.copy(refreshToken = "hris_mxg_r_" + "b".repeat(42)),
                    original.copy(accessExpiresAtEpochMillis = -1),
                    original.copy(expiresAtEpochMillis = 0),
                )) assertIs<AppResult.Failed>(
                f.repo()
                    .update(
                        f.scope(),
                        before,
                        CollaborationSpaceSetupOutcome.Bootstrapped(changed),
                        1,
                    )
            )
            assertEquals(before, f.readSetup())
            val outcome = CollaborationSpaceSetupOutcome.Bootstrapped(original)
            assertEquals(AppResult.Success(true), f.repo().update(f.scope(), before, outcome, 1))
            assertIs<AppResult.Failed>(f.repo().archiveRetried(f.scope(), f.readSetup()))
            assertEquals(outcome, f.readSetup().outcome)
        }

    @Test
    fun lostSharedKeyCannotRegenerateOverArchivedInactiveAccountFacts() = withDatabase { f ->
        val saved = f.retried()
        assertEquals(AppResult.Success(true), f.repo().archiveRetried(f.scope(), saved))
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.repo().find(f.scope(), operation))
        val other = f.access.copy(sessionId = "other-account", accountId = historyId(9897))
        f.activate(other)
        assertIs<AppResult.Failed>(f.repo().insert(f.scope(other), f.command(owner = other)))
        assertNull(f.keys.read())
        f.activate()
        assertNotNull(
            f.database.commands.archived(
                spaceSetupCommandScope(f.scope()),
                spaceSetupCommandPartition(f.scope()),
                operation,
            )
        )
    }

    @Test
    fun corruptCiphertextRoutingPurposeAndBoundsFailWithoutReplacingOriginal() = withDatabase { f ->
        f.insert()
        val original = f.readSetup()
        val raw = f.rawSetup()
        for (changed in
            listOf(
                raw.copy(partition = "foreign"),
                raw.copy(resourceId = "foreign"),
                raw.copy(id = historyId(9898)),
                raw.copy(revision = -1),
                raw.copy(sortKey = "other"),
                raw.copy(request = raw.state),
                raw.copy(state = raw.request),
                raw.copy(request = "x".repeat(8193)),
                raw.copy(state = "bad"),
            )) {
            val store =
                object : CommandStore by f.database.commands {
                    override fun observeAll(scope: CommandScope, partition: String) =
                        flowOf(listOf(changed))

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
            assertIs<AppResult.Failed>(f.repo(store).current(f.scope(), parent))
            assertIs<AppResult.Failed>(f.repo(store).find(f.scope(), operation))
            assertIs<AppResult.Failed>(f.repo(store).observeAll(f.scope()).first())
            assertIs<AppResult.Failed>(f.repo(store).insert(f.scope(), original.command))
            assertIs<AppResult.Failed>(
                f.repo(store)
                    .update(f.scope(), original, CollaborationSpaceSetupOutcome.Unconfirmed(), 1)
            )
        }
        assertEquals(raw, f.rawSetup())
    }

    @Test
    fun invalidInputCannotAllocateKeyOrPublishUnsupportedOriginalRequest() = withDatabase { f ->
        for (access in
            listOf(
                f.scope().copy(sessionId = "bad\n"),
                f.scope().copy(spaceId = "../space"),
                f.scope().copy(accountId = "invalid"),
            )) {
            assertIs<AppResult.Failed>(f.repo().current(access, parent))
            assertIs<AppResult.Failed>(f.repo().find(access, operation))
            assertIs<AppResult.Failed>(f.repo().observeAll(access).first())
            assertIs<AppResult.Failed>(f.repo().insert(access, f.command()))
        }
        val command = f.command(true)
        val request = command.request as CollaborationSpaceBootstrap
        for (invalid in
            listOf(
                command.copy(registrationId = "bad"),
                command.copy(clientBuild = 0),
                command.copy(clientBuild = 6),
                command.copy(platform = CollaborationNativePlatform.IOS),
                command.copy(request = request.copy(matrixDeviceId = "wrong")),
                command.copy(
                    request =
                        request.copy(
                            provider =
                                request.provider.copy(matrixUserId = "@other:matrix.hris.internal")
                        )
                ),
                command.copy(request = request.copy(gatewayVersion = 999_999)),
                command.copy(createdAt = Instant.parse("1960-01-01T00:00:00Z")),
            )) {
            assertIs<AppResult.Failed>(f.repo().insert(f.scope(), invalid))
        }
        assertNull(f.keys.read())
    }

    @Test
    fun confirmedDenialSurvivesUncertainRetryAndRestart() = withDatabase { f ->
        f.insert()
        val denial =
            Failure(FailureKind.AccessDenied, "Denied", code = "collaboration_access_denied")
        assertEquals(
            AppResult.Success(true),
            f.repo()
                .update(
                    f.scope(),
                    f.readSetup(),
                    CollaborationSpaceSetupOutcome.Unconfirmed(denial),
                    1,
                ),
        )
        val saved = f.readSetup()
        assertIs<AppResult.Failed>(
            f.repo()
                .update(
                    f.scope(),
                    saved,
                    CollaborationSpaceSetupOutcome.Unconfirmed(
                        Failure(FailureKind.Unavailable, "Offline")
                    ),
                    2,
                )
        )
        assertEquals(saved, f.readSetup())
        assertEquals(AppResult.Success(true), f.repo().update(f.scope(), saved, saved.outcome, 2))
        f.reopen()
        val retained =
            assertIs<CollaborationSpaceSetupOutcome.Unconfirmed>(f.readSetup().outcome).failure
        assertEquals(denial.code, retained?.code)
        assertEquals(denial.kind, retained?.kind)
    }

    @Test
    fun boundedActiveInventoryRejectsDuplicatesAndPreservesSeparateNativeParents() =
        withDatabase { f ->
            f.insert()
            val raw = f.rawSetup()
            for (rows in listOf(listOf(raw, raw), List(129) { raw })) {
                val store =
                    object : CommandStore by f.database.commands {
                        override fun observeAll(scope: CommandScope, partition: String) =
                            flowOf(rows)
                    }
                assertIs<AppResult.Failed>(f.repo(store).observeAll(f.scope()).first())
            }
            val initial = f.command()
            val request = initial.request as CollaborationSpaceProvisioningRetry
            val other =
                initial.copy(
                    request =
                        request.copy(
                            operationId = historyId(9806),
                            binding = request.binding.copy(nativeSessionId = historyId(9807)),
                        )
                )
            f.insert(other)
            val rows =
                assertIs<AppResult.Success<List<CollaborationSpaceSetupRecord>>>(
                        f.repo().observeAll(f.scope()).first()
                    )
                    .value
            assertEquals(setOf(initial.id, other.id), rows.map { it.command.id }.toSet())
            assertEquals(
                other,
                assertNotNull(
                        assertIs<AppResult.Success<CollaborationSpaceSetupRecord?>>(
                                f.repo().current(f.scope(), other.binding.nativeSessionId)
                            )
                            .value
                    )
                    .command,
            )
            assertEquals(initial, f.readSetup().command)
        }

    @Test
    fun accountReplacementAtInsertionPublishesNeitherAccount() = withDatabase { f ->
        val other = f.access.copy(sessionId = "replacement", accountId = historyId(9899))
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
        assertEquals(AppResult.Success(null), f.repo().find(f.scope(other), operation))
        f.activate()
        assertEquals(AppResult.Success(null), f.repo().find(f.scope(), operation))
    }

    @Test
    fun archiveEvidenceCannotSubstituteAnotherOriginalOrTerminalState() = withDatabase { f ->
        val saved = f.retried()
        val raw = f.rawSetup()
        assertEquals(AppResult.Success(true), f.repo().archiveRetried(f.scope(), saved))
        for (archive in
            listOf(
                CommandArchive(raw, raw.request),
                CommandArchive(raw.copy(id = historyId(9810)), raw.state),
                CommandArchive(raw.copy(state = raw.request), raw.request),
            )) {
            val store =
                object : CommandStore by f.database.commands {
                    override suspend fun archived(
                        scope: CommandScope,
                        partition: String,
                        id: String,
                    ) = archive
                }
            assertIs<AppResult.Failed>(f.repo(store).find(f.scope(), operation))
            assertIs<AppResult.Failed>(f.repo(store).archiveRetried(f.scope(), saved))
            assertIs<AppResult.Failed>(f.repo(store).insert(f.scope(), saved.command))
        }
        assertEquals(AppResult.Success(saved), f.repo().find(f.scope(), operation))
        assertEquals(AppResult.Success(null), f.repo().current(f.scope(), parent))
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
