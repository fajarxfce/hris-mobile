package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.identity.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceRegistrationCommandRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceRegistrationCommandStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    private fun CollaborationHistoryFixture.scope(owner: CollaborationLocalAccess = access) =
        CollaborationSpaceLocalAccess(
            owner.sessionId,
            owner.audience,
            owner.accountId,
            historyId(9700),
        )

    private fun CollaborationHistoryFixture.repo(store: CommandStore = database.commands) =
        EncryptedCollaborationSpaceRegistrationCommandRepository(store, cipher, keys)

    private fun CollaborationHistoryFixture.command(
        original: CollaborationLocalAccess = access
    ): CollaborationSpaceRegistrationCommand {
        val company = original.companyId
        val user =
            "@hris_${company.replace("-", "")}_${original.accountId.replace("-", "")}:matrix.hris.internal"
        val deviceId = historyId(9751)
        return CollaborationSpaceRegistrationCommand(
            historyId(9702),
            CollaborationSpaceDeviceBinding(
                original.audience,
                original.accountId,
                scope().spaceId,
                historyId(9701),
            ),
            CollaborationNativePlatform.ANDROID,
            5,
            Instant.parse("2026-11-01T00:00:00Z"),
            CollaborationSpaceIdentity(
                scope().spaceId,
                original.accountId,
                "matrix.hris.internal",
                user,
                CollaborationSpaceIdentityOrigin.LEGACY,
                company,
                0,
                Instant.parse("2020-01-01T00:00:00Z"),
            ),
            "Employee phone",
            CollaborationSpaceDeviceOrigin.LEGACY,
            CollaborationSpaceLegacySdk(
                CollaborationSetupBinding(
                    original.audience,
                    original.accountId,
                    company,
                    historyId(9680),
                ),
                deviceId,
                CollaborationLocalDeviceIdentity(
                    user,
                    "HRIS_${deviceId.replace("-", "")}",
                    "a".repeat(64),
                ),
            ),
        )
    }

    private suspend fun CollaborationHistoryFixture.readRegistration(
        access: CollaborationSpaceLocalAccess = scope()
    ) =
        assertNotNull(
            assertIs<AppResult.Success<CollaborationSpaceRegistrationRecord?>>(
                    repo().current(access, historyId(9701))
                )
                .value
        )

    private suspend fun CollaborationHistoryFixture.rawRegistration(
        access: CollaborationSpaceLocalAccess = scope()
    ) =
        assertNotNull(
            database.commands.get(
                spaceRegistrationCommandScope(access),
                spaceRegistrationCommandPartition(access),
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
        assertEquals(AppResult.Success(null), f.repo().current(f.scope(), historyId(9701)))
        assertEquals(AppResult.Success(emptyList()), f.repo().observeAll(f.scope()).first())
        assertEquals(AppResult.Success(null), f.repo().observe(f.scope(), historyId(9701)).first())
        assertNull(f.keys.read())
        f.insert()
        assertEquals(
            AppResult.Success(true),
            f.repo()
                .update(
                    f.scope(),
                    f.readRegistration(),
                    CollaborationSpaceRegistrationOutcome.Unconfirmed(),
                    1,
                ),
        )
        val selected =
            CollaborationSpaceRegistrationOutcome.Registered(
                CollaborationSetupReceipt(historyId(9750), 2)
            )
        assertEquals(
            AppResult.Success(true),
            f.repo().update(f.scope(), f.readRegistration(), selected, 1),
        )
        val raw = f.rawRegistration()
        assertFalse(raw.request.contains(f.command().binding.nativeSessionId))
        assertFalse(raw.state.contains(f.access.accountId))
        assertFalse(raw.partition.contains(f.command().binding.nativeSessionId))
        val renewed = f.access.copy(sessionId = "new-local-login", companyId = historyId(9790))
        f.reopen(renewed)
        val access = f.scope(renewed)
        val saved = f.readRegistration(access)
        assertEquals(f.command(), saved.command)
        assertEquals(
            listOf(saved),
            assertIs<AppResult.Success<List<CollaborationSpaceRegistrationRecord>>>(
                    f.repo().observeAll(access).first()
                )
                .value,
        )
        assertEquals(selected, saved.outcome)
        assertEquals(2L, saved.revision)
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.STORED),
            f.repo().insert(access, f.command()),
        )
        assertEquals(raw, f.rawRegistration(access))
    }

    @Test
    fun serverAccountAndSpaceBoundariesCannotSubstituteACommand() = withDatabase { f ->
        f.insert()
        val original = f.readRegistration()
        val foreignSpace = f.scope().copy(spaceId = historyId(9710))
        assertEquals(AppResult.Success(null), f.repo().current(foreignSpace, historyId(9701)))
        assertIs<AppResult.Failed>(f.repo().insert(foreignSpace, f.command()))
        assertIs<AppResult.Failed>(
            f.repo()
                .update(
                    foreignSpace,
                    original,
                    CollaborationSpaceRegistrationOutcome.Unconfirmed(),
                    1,
                )
        )
        for (owner in
            listOf(
                f.access.copy(sessionId = "other", accountId = historyId(9711)),
                f.access.copy(sessionId = "server", audience = "https://other.example.test/"),
            )) {
            f.activate(owner)
            assertEquals(AppResult.Success(null), f.repo().current(f.scope(owner), historyId(9701)))
            assertEquals(AppResult.Success(null), f.repo().current(f.scope(), historyId(9701)))
            assertIs<AppResult.Failed>(f.repo().insert(f.scope(owner), f.command()))
            f.activate()
            assertEquals(original, f.readRegistration())
        }
    }

    @Test
    fun lostUnconfirmedWriteResponseSurvivesRestartWithoutRewritingTheOperation() =
        withDatabase { f ->
            f.insert()
            val initial = f.readRegistration()
            val request = f.rawRegistration().request
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
                            CollaborationSpaceRegistrationOutcome.Unconfirmed(),
                            1,
                        )
                }
                assertFailsWith<CancellationException> { pending.await() }
            }
            f.reopen()
            val saved = f.readRegistration()
            assertEquals(initial.command, saved.command)
            assertEquals(1, saved.attempts)
            assertIs<CollaborationSpaceRegistrationOutcome.Unconfirmed>(saved.outcome)
            f.insert()
            assertEquals(request, f.rawRegistration().request)
            assertEquals(saved, f.readRegistration())
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
        assertEquals(winner, f.readRegistration().command)
        assertEquals(
            AppResult.Success(CollaborationSetupInsertion.OCCUPIED),
            f.repo().insert(f.scope(), f.command()),
        )
        assertEquals(winner, f.readRegistration().command)
    }

    @Test
    fun competingStateWriteWinsAndRegisteredReceiptCannotBeReopened() = withDatabase { f ->
        f.insert()
        val original = f.readRegistration()
        val uncertain = CollaborationSpaceRegistrationOutcome.Unconfirmed()
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
        val sending = f.readRegistration()
        assertEquals(1L, sending.revision)
        assertEquals(
            AppResult.Success(true),
            f.repo()
                .update(
                    f.scope(),
                    sending,
                    CollaborationSpaceRegistrationOutcome.Registered(
                        CollaborationSetupReceipt(historyId(9750), 2)
                    ),
                    1,
                ),
        )
        val selected = f.readRegistration()
        assertIs<AppResult.Failed>(f.repo().update(f.scope(), selected, uncertain, 2))
        assertEquals(selected, f.readRegistration())
        assertEquals(AppResult.Success(false), f.repo().update(f.scope(), original, uncertain, 1))
    }

    @Test
    fun lostSharedKeyNeverRegeneratesOverActiveOrInactiveCommands() = withDatabase { f ->
        f.insert()
        val raw = f.rawRegistration()
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.repo().current(f.scope(), historyId(9701)))
        val other = f.access.copy(sessionId = "other", accountId = historyId(9720))
        f.activate(other)
        val command = f.command(other)
        assertIs<AppResult.Failed>(f.repo().insert(f.scope(other), command))
        assertNull(f.keys.read())
        f.activate()
        assertEquals(raw, f.rawRegistration())
    }

    @Test
    fun corruptRecordMetadataPurposeAndBoundsFailInReadFlowAndMutation() = withDatabase { f ->
        f.insert()
        val original = f.readRegistration()
        val raw = f.rawRegistration()
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
            assertIs<AppResult.Failed>(f.repo(store).current(f.scope(), historyId(9701)))
            assertIs<AppResult.Failed>(f.repo(store).observeAll(f.scope()).first())
            assertIs<AppResult.Failed>(f.repo(store).observe(f.scope(), historyId(9701)).first())
            assertIs<AppResult.Failed>(f.repo(store).insert(f.scope(), f.command()))
            assertIs<AppResult.Failed>(
                f.repo(store)
                    .update(
                        f.scope(),
                        original,
                        CollaborationSpaceRegistrationOutcome.Unconfirmed(),
                        1,
                    )
            )
        }
        assertEquals(raw, f.rawRegistration())
    }

    @Test
    fun invalidInputFailsInsideSafeBoundariesWithoutAllocatingAKey() = withDatabase { f ->
        for (access in
            listOf(
                f.scope().copy(sessionId = "bad\n"),
                f.scope().copy(spaceId = "../space"),
                f.scope().copy(accountId = "invalid"),
            )) {
            assertIs<AppResult.Failed>(f.repo().current(access, historyId(9701)))
            assertIs<AppResult.Failed>(f.repo().observe(access, historyId(9701)).first())
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
        assertEquals(AppResult.Success(null), f.repo().current(f.scope(other), historyId(9701)))
        f.activate()
        assertEquals(AppResult.Success(null), f.repo().current(f.scope(), historyId(9701)))
    }

    @Test
    fun originalSdkProvenanceCannotBeRewrittenOrReplacedBySelectedPreference() = withDatabase { f ->
        val command = f.command()
        val original = assertNotNull(command.legacy)
        for (invalid in
            listOf(
                command.copy(
                    legacy =
                        original.copy(identity = original.identity.copy(fingerprint = "invalid"))
                ),
                command.copy(legacy = original.copy(deviceId = historyId(9780))),
                command.copy(
                    legacy =
                        original.copy(
                            identity =
                                original.identity.copy(matrixUserId = "@other:matrix.hris.internal")
                        )
                ),
                command.copy(
                    legacy =
                        original.copy(binding = original.binding.copy(companyId = historyId(9781)))
                ),
                command.copy(label = "Phone\nname"),
                command.copy(label = "x".repeat(65)),
            )) assertIs<AppResult.Failed>(f.repo().insert(f.scope(), invalid))
        assertNull(f.keys.read())
        f.insert()
        val raw = f.rawRegistration()
        assertIs<AppResult.Failed>(
            f.repo().insert(f.scope(), command.copy(label = "Another label"))
        )
        assertEquals(raw, f.rawRegistration())
        assertEquals(original, f.readRegistration().command.legacy)
        assertNotEquals(command.binding.nativeSessionId, original.binding.nativeSessionId)
    }

    @Test
    fun originalParentResourceAndCiphertextRemainDistinctAcrossNewNativeIntents() =
        withDatabase { f ->
            f.insert()
            val before = f.rawRegistration()
            val other =
                f.command()
                    .copy(
                        id = historyId(9770),
                        binding = f.command().binding.copy(nativeSessionId = historyId(9771)),
                    )
            assertEquals(
                AppResult.Success(null),
                f.repo().current(f.scope(), other.binding.nativeSessionId),
            )
            assertEquals(
                AppResult.Success(CollaborationSetupInsertion.STORED),
                f.repo().insert(f.scope(), other),
            )
            assertEquals(
                other,
                assertNotNull(
                        assertIs<AppResult.Success<CollaborationSpaceRegistrationRecord?>>(
                                f.repo().current(f.scope(), other.binding.nativeSessionId)
                            )
                            .value
                    )
                    .command,
            )
            assertEquals(before, f.rawRegistration())
            val all =
                assertIs<AppResult.Success<List<CollaborationSpaceRegistrationRecord>>>(
                        f.repo().observeAll(f.scope()).first()
                    )
                    .value
            assertEquals(setOf(f.command(), other), all.map { it.command }.toSet())
            val fresh = f.access.copy(sessionId = "new-native-login")
            f.reopen(fresh)
            assertEquals(
                all,
                assertIs<AppResult.Success<List<CollaborationSpaceRegistrationRecord>>>(
                        f.repo().observeAll(f.scope(fresh)).first()
                    )
                    .value,
            )
            f.activate()

            val wrong =
                object : CommandStore by f.database.commands {
                    override fun observe(
                        scope: CommandScope,
                        partition: String,
                        resourceId: String?,
                        after: String?,
                        limit: Int,
                    ) = flowOf(listOf(before))
                }
            assertIs<AppResult.Failed>(
                f.repo(wrong).current(f.scope(), other.binding.nativeSessionId)
            )
            for (invalid in listOf("bad", "../parent", "")) {
                assertIs<AppResult.Failed>(f.repo().current(f.scope(), invalid))
                assertIs<AppResult.Failed>(f.repo().observe(f.scope(), invalid).first())
            }
        }

    @Test
    fun newDeviceOriginCanRetainLegacyOrSpaceUserWithoutInventingOldSdkProvenance() =
        withDatabase { f ->
            for (spaceUser in listOf(false, true)) {
                val original = f.command()
                val selected =
                    if (spaceUser)
                        original.provider.copy(
                            origin = CollaborationSpaceIdentityOrigin.SPACE,
                            legacyCompanyId = null,
                            matrixUserId =
                                "@hrisg_${original.binding.spaceId.replace("-", "")}_${original.binding.accountId.replace("-", "")}:matrix.hris.internal",
                        )
                    else original.provider
                val command =
                    original.copy(
                        id = historyId(if (spaceUser) 9782 else 9783),
                        binding =
                            original.binding.copy(
                                nativeSessionId = historyId(if (spaceUser) 9784 else 9785)
                            ),
                        provider = selected,
                        origin = CollaborationSpaceDeviceOrigin.SPACE,
                        legacy = null,
                    )
                assertEquals(
                    AppResult.Success(CollaborationSetupInsertion.STORED),
                    f.repo().insert(f.scope(), command),
                )
                val saved =
                    assertNotNull(
                        assertIs<AppResult.Success<CollaborationSpaceRegistrationRecord?>>(
                                f.repo().current(f.scope(), command.binding.nativeSessionId)
                            )
                            .value
                    )
                assertEquals(command, saved.command)
                assertNull(saved.command.legacy)
            }
        }

    @Test
    fun confirmedAccessFailureCannotBeDowngradedByAnotherUncertainAttempt() = withDatabase { f ->
        f.insert()
        val denied =
            Failure(FailureKind.AccessDenied, "Denied", code = "collaboration_access_denied")
        assertEquals(
            AppResult.Success(true),
            f.repo()
                .update(
                    f.scope(),
                    f.readRegistration(),
                    CollaborationSpaceRegistrationOutcome.Unconfirmed(denied),
                    1,
                ),
        )
        val saved = f.readRegistration()
        assertIs<AppResult.Failed>(
            f.repo()
                .update(
                    f.scope(),
                    saved,
                    CollaborationSpaceRegistrationOutcome.Unconfirmed(
                        Failure(FailureKind.Unavailable, "Offline")
                    ),
                    2,
                )
        )
        assertEquals(saved, f.readRegistration())
        assertEquals(AppResult.Success(true), f.repo().update(f.scope(), saved, saved.outcome, 2))
        f.reopen()
        val retained =
            assertIs<CollaborationSpaceRegistrationOutcome.Unconfirmed>(
                f.readRegistration().outcome
            )
        assertEquals(denied.code, retained.failure?.code)
        assertEquals(denied.kind, retained.failure?.kind)
    }

    @Test
    fun allParentInventoryRejectsDuplicateAndOversizedRowsWithoutDroppingOriginals() =
        withDatabase { f ->
            f.insert()
            val raw = f.rawRegistration()
            for (rows in listOf(listOf(raw, raw), List(129) { raw })) {
                val store =
                    object : CommandStore by f.database.commands {
                        override fun observeAll(scope: CommandScope, partition: String) =
                            flowOf(rows)
                    }
                assertIs<AppResult.Failed>(f.repo(store).observeAll(f.scope()).first())
            }
            assertEquals(raw, f.rawRegistration())
            val other = f.access.copy(sessionId = "other-account", accountId = historyId(9792))
            f.activate(other)
            assertEquals(AppResult.Success(emptyList()), f.repo().observeAll(f.scope()).first())
            assertEquals(
                AppResult.Success(emptyList()),
                f.repo().observeAll(f.scope(other)).first(),
            )
            f.activate()
            assertEquals(
                listOf(f.readRegistration()),
                assertIs<AppResult.Success<List<CollaborationSpaceRegistrationRecord>>>(
                        f.repo().observeAll(f.scope()).first()
                    )
                    .value,
            )
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
