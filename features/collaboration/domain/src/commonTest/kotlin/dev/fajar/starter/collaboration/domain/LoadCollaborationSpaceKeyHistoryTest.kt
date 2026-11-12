package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRoomBindingRepository
import dev.fajar.starter.collaboration.domain.usecases.LoadCollaborationSpaceKeyHistory
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

class LoadCollaborationSpaceKeyHistoryTest {
    @Test
    fun exactSpaceBindingResolvesWithoutPrimaryQueueOrCompanyMetadataReads() = runTest {
        val f = Fixture()
        val before = f.base.snapshot
        f.base.retained = null
        f.base.failures["demand"] = f.base.directory.storageFailure
        val result = f.ready()
        assertEquals(f.base.source, result.source)
        assertEquals(f.binding, result.binding)
        assertEquals(
            CollaborationHistoryTraversal(
                f.conversationId,
                f.base.room.roomId,
                f.base.runtime.deviceId,
                CollaborationHistoryPurpose.Incoming,
            ),
            result.traversal,
        )
        assertEquals(listOf("registration1", "metadata", "registration2"), f.base.calls)
        assertEquals(before, f.base.snapshot)
        assertEquals(0, f.base.setup.inserts)
        assertTrue(f.base.setup.sent.isEmpty())
    }

    @Test
    fun oldSpaceDeviceIsNotReplacedByTheCurrentOriginalOrGroupDevice() = runTest {
        val f = Fixture()
        val device = f.base.id(950)
        val result =
            assertIs<CollaborationSpaceKeyHistoryResolution.Ready>(
                    assertIs<AppResult.Success<*>>(
                            f.loader()(
                                f.base.runtime,
                                f.base.source,
                                f.locator().copy(deviceId = device),
                            )
                        )
                        .value
                )
                .history
        assertEquals(device, result.traversal.deviceId)
        assertNotEquals(f.base.runtime.deviceId, result.traversal.deviceId)
        assertNotEquals(f.base.setup.sdkDevice, result.traversal.deviceId)
    }

    @Test
    fun originalAliasKeepsSpaceTraversalAndCompleteOriginalProvenance() = runTest {
        val f = Fixture()
        f.binding = f.originalAlias()
        val result = f.ready()
        assertEquals(f.binding, result.binding)
        assertEquals(f.conversationId, result.traversal.conversationId)
        assertNotEquals(f.base.position.conversationId, result.traversal.conversationId)
        assertEquals(f.base.runtime.deviceId, result.traversal.deviceId)
        assertEquals(f.base.position, assertNotNull(result.binding.original).conversation.position)
        assertEquals(CollaborationSpaceRoomOrigin.LEGACY, result.binding.room.origin)
    }

    @Test
    fun retainedForeignCompanyAliasIsMetadataRatherThanNativeContentAuthority() = runTest {
        val f = Fixture()
        val alias = f.originalAlias()
        val original = assertNotNull(alias.original)
        val reference = assertNotNull(alias.room.original)
        val position = f.base.position.copy(companyId = f.base.id(951))
        f.binding =
            alias.copy(
                room = alias.room.copy(original = reference.copy(position = position)),
                original =
                    original.copy(conversation = original.conversation.copy(position = position)),
            )
        val result = f.ready()
        assertEquals(f.conversationId, result.traversal.conversationId)
        assertEquals(position, assertNotNull(result.binding.original).conversation.position)
        assertNotEquals(f.base.setup.company, position.companyId)
        assertEquals(f.base.source, result.source)
        assertEquals(listOf("registration1", "metadata", "registration2"), f.base.calls)
        assertEquals(0, f.base.setup.inserts)
        assertTrue(f.base.setup.sent.isEmpty())
    }

    @Test
    fun anAbsentPrimaryQueueCannotHideOwnedSpaceHistory() = runTest {
        val f = Fixture()
        f.base.snapshot = null
        f.ready()
        assertNull(f.base.snapshot)
        assertFalse("demand" in f.base.calls)
    }

    @Test
    fun missingBindingReturnsUnresolvedWithoutFallbackAcquisition() = runTest {
        val f = Fixture()
        f.binding = null
        assertEquals(
            AppResult.Success(CollaborationSpaceKeyHistoryResolution.MetadataUnavailable),
            f.load(),
        )
        assertEquals(listOf("registration1", "metadata", "registration2"), f.base.calls)
        assertEquals(0, f.base.setup.inserts)
        assertTrue(f.base.setup.sent.isEmpty())
    }

    @Test
    fun missingBindingStillRequiresTheFinalRegistrationFence() = runTest {
        val f = Fixture()
        f.binding = null
        f.base.hooks["metadata"] = { f.base.registration = null }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
        assertEquals("registration2", f.base.calls.last())
    }

    @Test
    fun invalidConversationOrDeviceStopsBeforeAnyRead() = runTest {
        val seed = Fixture()
        for (locator in
            listOf(
                seed.locator().copy(conversationId = "invalid"),
                seed.locator().copy(deviceId = "invalid"),
                seed.locator().copy(deviceId = "00000000-0000-0000-0000-000000000000"),
            )) {
            val f = Fixture()
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.loader()(f.base.runtime, f.base.source, locator))
                    .failure
                    .kind,
            )
            assertTrue(f.base.calls.isEmpty())
            assertEquals(0, f.base.sourceReads)
        }
    }

    @Test
    fun malformedSourceCannotReadTheRegistrationOrBinding() = runTest {
        val f = Fixture()
        for (source in
            listOf(
                f.base.source.copy(storeId = "bad"),
                f.base.source.copy(matrixUserId = "bad"),
                f.base.source.copy(matrixDeviceId = ""),
            )) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.loader()(f.base.runtime, source, f.locator()))
                    .failure
                    .kind,
            )
        }
        assertTrue(f.base.calls.isEmpty())
        assertEquals(0, f.base.sourceReads)
    }

    @Test
    fun anotherSpaceConversationOrAccountCannotAliasThisLocator() = runTest {
        val seed = Fixture()
        val original = assertNotNull(seed.binding)
        for (metadata in
            listOf(
                original.conversation.copy(spaceId = seed.base.id(960)),
                original.conversation.copy(id = seed.base.id(961)),
                original.conversation.copy(firstAccountId = seed.base.id(962)),
            )) {
            val f = Fixture()
            f.binding = original.copy(conversation = metadata)
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
            assertEquals("metadata", f.base.calls.last())
        }
    }

    @Test
    fun aDifferentProviderServerCannotResolveThroughTheInstalledSource() = runTest {
        val f = Fixture()
        val binding = assertNotNull(f.binding)
        f.binding =
            binding.copy(
                room =
                    binding.room.copy(
                        serverName = "other.example",
                        roomId = "!original:other.example",
                    )
            )
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
        assertEquals("metadata", f.base.calls.last())
    }

    @Test
    fun incompleteOrConflictingOriginalAliasesRemainErrors() = runTest {
        val seed = Fixture()
        val alias = seed.originalAlias()
        val original = assertNotNull(alias.original)
        for (binding in
            listOf(
                alias.copy(original = null),
                alias.copy(room = alias.room.copy(original = null)),
                alias.copy(
                    original =
                        original.copy(
                            conversation =
                                original.conversation.copy(
                                    position =
                                        seed.base.position.copy(companyId = seed.base.id(970))
                                )
                        )
                ),
            )) {
            val f = Fixture()
            f.binding = binding
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
            assertEquals("metadata", f.base.calls.last())
        }
    }

    @Test
    fun malformedRuntimeCannotReadLocalMetadata() = runTest {
        val keys = Fixture()
        val f = keys.base
        for (runtime in
            listOf(
                f.runtime.copy(deviceId = "bad"),
                f.runtime.copy(operationId = "00000000-0000-0000-0000-000000000000"),
                f.runtime.copy(
                    owner =
                        f.owner.copy(
                            parent =
                                f.owner.parent.copy(
                                    nativeSessionId = "00000000-0000-0000-0000-000000000000"
                                )
                        )
                ),
            )) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(keys.loader()(runtime, f.source, keys.locator()))
                    .failure
                    .kind,
            )
        }
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.sourceReads)
    }

    @Test
    fun missingOrDifferentInstalledStoreDoesNotReadMetadata() = runTest {
        for (missing in listOf(false, true)) {
            val keys = Fixture()
            val f = keys.base
            f.installed = if (missing) null else f.source.copy(storeId = "b".repeat(64))
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(keys.load()).failure.kind,
            )
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test
    fun absentPendingOrUnconfirmedRegistrationCannotResolveKeyHistory() = runTest {
        val seed = SpaceOriginalIncomingLoadFixture()
        for (row in
            listOf(
                null,
                CollaborationSpaceRegistrationRecord(
                    seed.command,
                    0,
                    0,
                    CollaborationSpaceRegistrationOutcome.Pending,
                ),
                CollaborationSpaceRegistrationRecord(
                    seed.command,
                    1,
                    1,
                    CollaborationSpaceRegistrationOutcome.Unconfirmed(),
                ),
            )) {
            val keys = Fixture()
            val f = keys.base
            f.registration = row
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(keys.load()).failure.kind)
            assertEquals(listOf("registration1"), f.calls)
        }
    }

    @Test
    fun changedRegisteredReceiptCannotAdmitTheOldRuntime() = runTest {
        val keys = Fixture()
        val f = keys.base
        f.registration =
            assertNotNull(f.registration)
                .copy(
                    outcome =
                        CollaborationSpaceRegistrationOutcome.Registered(
                            f.setup.receipt.copy(id = f.id(23))
                        )
                )
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(keys.load()).failure.kind)
        assertEquals(listOf("registration1"), f.calls)
    }

    @Test
    fun registrationMustKeepNativeFamilyOriginalCompanyAndExactSdkIdentity() = runTest {
        val seed = SpaceOriginalIncomingLoadFixture()
        val command = seed.command
        val legacy = assertNotNull(command.legacy)
        for (changed in
            listOf(
                command.copy(binding = command.binding.copy(nativeSessionId = seed.id(24))),
                command.copy(
                    legacy =
                        legacy.copy(binding = legacy.binding.copy(nativeSessionId = seed.id(24)))
                ),
                command.copy(
                    legacy = legacy.copy(binding = legacy.binding.copy(companyId = seed.id(24)))
                ),
                command.copy(
                    legacy = legacy.copy(identity = legacy.identity.copy(matrixDeviceId = "OTHER"))
                ),
                command.copy(legacy = null, origin = CollaborationSpaceDeviceOrigin.SPACE),
            )) {
            val keys = Fixture()
            val f = keys.base
            f.registration = assertNotNull(f.registration).copy(command = changed)
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(keys.load()).failure.kind)
            assertEquals(listOf("registration1"), f.calls)
        }
    }

    @Test
    fun platformMismatchCannotReuseAnotherNativeRegistration() = runTest {
        val keys = Fixture()
        val f = keys.base
        f.registration =
            assertNotNull(f.registration)
                .copy(command = f.command.copy(platform = CollaborationNativePlatform.IOS))
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(keys.load()).failure.kind)
        assertEquals(listOf("registration1"), f.calls)
    }

    @Test
    fun oldCredentialsAndUnrelatedSelectedCompanyDoNotHideOwnedCoordinates() = runTest {
        val keys = Fixture()
        val f = keys.base
        f.native.setup.now += 365.days
        f.native.setup.workspace = null
        assertIs<AppResult.Success<*>>(keys.load())
        assertEquals(f.setup.company, f.position.companyId)
    }

    @Test
    fun changedRegistrationDuringMetadataReadCannotPublishAStaleSourceMapping() = runTest {
        val keys = Fixture()
        val f = keys.base
        f.hooks["metadata"] = { f.registration = assertNotNull(f.registration).copy(revision = 3) }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(keys.load()).failure.kind)
        assertEquals(listOf("registration1", "metadata", "registration2"), f.calls)
    }

    @Test
    fun directoryDenialStopsBeforeSourceAndLocalReads() = runTest {
        val keys = Fixture()
        val f = keys.base
        f.directory.deny()
        assertEquals(f.directory.denied, assertIs<AppResult.Failed>(keys.load()).failure)
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.sourceReads)
    }

    @Test
    fun lateAccountNativeSourceAndAccessChangesFenceEverySuspendingRead() = runTest {
        for (stage in listOf("registration1", "metadata", "registration2")) {
            for (change in listOf("account", "native", "source", "denial")) {
                val keys = Fixture()
                val f = keys.base
                f.hooks[stage] = {
                    when (change) {
                        "account" -> f.native.setup.session = null
                        "native" -> {
                            val old = assertNotNull(f.native.setup.session)
                            f.native.setup.session =
                                old.copy(binding = old.binding.copy(serverId = f.id(26)))
                        }
                        "source" -> f.installed = f.source.copy(matrixDeviceId = "OTHER")
                        else -> f.directory.deny()
                    }
                }
                val failed = assertIs<AppResult.Failed>(keys.load(), "$stage/$change")
                if (change == "source") assertEquals(FailureKind.Unavailable, failed.failure.kind)
                if (change == "native") assertEquals(FailureKind.Unauthorized, failed.failure.kind)
                if (change == "denial") assertEquals(f.directory.denied, failed.failure)
                assertEquals(stage, f.calls.last())
            }
        }
    }

    @Test
    fun localFailureKeepsItsClassificationWithoutRetryOrWrites() = runTest {
        for (stage in listOf("registration1", "metadata", "registration2")) {
            val keys = Fixture()
            val f = keys.base
            f.failures[stage] = f.directory.storageFailure
            assertEquals(
                f.directory.storageFailure,
                assertIs<AppResult.Failed>(keys.load()).failure,
            )
            assertEquals(1, f.calls.count { it == stage })
            assertEquals(stage, f.calls.last())
        }
    }

    @Test
    fun confirmedDenialStillWinsWhenTheLocalReadFails() = runTest {
        val keys = Fixture()
        val f = keys.base
        f.hooks["metadata"] = { f.directory.deny() }
        f.failures["metadata"] = f.directory.storageFailure
        assertEquals(f.directory.denied, assertIs<AppResult.Failed>(keys.load()).failure)
        assertEquals("metadata", f.calls.last())
    }

    @Test
    fun eachStalledReadHasOneLocalDeadlineWithoutRetry() = runTest {
        for (stage in listOf("registration1", "metadata", "registration2")) {
            val keys = Fixture()
            val f = keys.base
            f.hooks[stage] = { awaitCancellation() }
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(keys.load()).failure.kind)
            assertEquals(1, f.calls.count { it == stage })
            assertEquals(stage, f.calls.last())
        }
    }

    @Test
    fun callerCancellationPropagatesAtEveryReadAndCannotPublishLateCoordinates() = runTest {
        for (stage in listOf("registration1", "metadata", "registration2")) {
            val keys = Fixture()
            val f = keys.base
            val entered = CompletableDeferred<Unit>()
            f.hooks[stage] = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val job = async { keys.load() }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(stage, f.calls.last())
        }
    }

    @Test
    fun accountLocatorRetainsItsCurrentSpaceAndOriginalAliasScope() = runTest {
        for (alias in listOf(false, true)) {
            val f = Fixture()
            if (alias) f.binding = f.originalAlias()
            val locator =
                CollaborationAccountIncomingHistoryLocator.Space(f.base.access.spaceId, f.locator())
            val result =
                assertIs<AppResult.Success<*>>(f.loader()(f.base.runtime, f.base.source, locator))
                    .value
            val ready = assertIs<CollaborationSpaceKeyHistoryResolution.Ready>(result).history
            assertEquals(f.binding, ready.binding)
            assertEquals(locator.history.conversationId, ready.traversal.conversationId)
            assertEquals(listOf("registration1", "metadata", "registration2"), f.base.calls)
        }
    }

    @Test
    fun anotherAccountOwnedSpaceDoesNotLookUpTheSameConversationInTheCurrentRoot() = runTest {
        val f = Fixture()
        f.base.native.setup.now += 365.days
        val locator = CollaborationAccountIncomingHistoryLocator.Space(f.base.id(901), f.locator())
        assertEquals(
            CollaborationSpaceKeyHistoryResolution.SourceUnavailable,
            assertIs<AppResult.Success<*>>(f.loader()(f.base.runtime, f.base.source, locator)).value,
        )
        assertEquals(listOf("registration1", "registration2"), f.base.calls)
    }

    @Test
    fun malformedAccountSpaceCannotReachAdmissionOrLocalReads() = runTest {
        for (spaceId in listOf("bad", "00000000-0000-0000-0000-000000000000")) {
            val f = Fixture()
            val locator = CollaborationAccountIncomingHistoryLocator.Space(spaceId, f.locator())
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.loader()(f.base.runtime, f.base.source, locator))
                    .failure
                    .kind,
            )
            assertTrue(f.base.calls.isEmpty())
            assertEquals(0, f.base.sourceReads)
        }
    }

    @Test
    fun unavailableSpaceCannotHideMissingRegistrationOrInstalledSource() = runTest {
        for (missing in listOf("registration", "source")) {
            val f = Fixture()
            if (missing == "registration") f.base.registration = null else f.base.installed = null
            val locator =
                CollaborationAccountIncomingHistoryLocator.Space(f.base.id(901), f.locator())
            assertIs<AppResult.Failed>(f.loader()(f.base.runtime, f.base.source, locator))
            assertFalse("metadata" in f.base.calls)
        }
    }

    @Test
    fun unavailableSpaceRequiresAnUnchangedFinalRegistration() = runTest {
        val f = Fixture()
        f.base.hooks["registration2"] = { f.base.registration = null }
        val locator = CollaborationAccountIncomingHistoryLocator.Space(f.base.id(901), f.locator())
        assertIs<AppResult.Failed>(f.loader()(f.base.runtime, f.base.source, locator))
        assertEquals(listOf("registration1", "registration2"), f.base.calls)
    }

    @Test
    fun unavailableSpaceFencesOwnershipSourceAndAccessAfterEachRead() = runTest {
        for (stage in listOf("registration1", "registration2")) {
            for (change in listOf("account", "native", "source", "denial")) {
                val f = Fixture()
                f.base.hooks[stage] = {
                    when (change) {
                        "account" -> f.base.native.setup.session = null
                        "native" -> {
                            val old = assertNotNull(f.base.native.setup.session)
                            f.base.native.setup.session =
                                old.copy(binding = old.binding.copy(serverId = f.base.id(26)))
                        }
                        "source" -> f.base.installed = f.base.source.copy(matrixDeviceId = "OTHER")
                        else -> f.base.directory.deny()
                    }
                }
                val locator =
                    CollaborationAccountIncomingHistoryLocator.Space(f.base.id(901), f.locator())
                val failed =
                    assertIs<AppResult.Failed>(f.loader()(f.base.runtime, f.base.source, locator))
                if (change == "denial") assertEquals(f.base.directory.denied, failed.failure)
                if (change == "source") assertEquals(FailureKind.Unavailable, failed.failure.kind)
                if (change == "native") assertEquals(FailureKind.Unauthorized, failed.failure.kind)
                assertEquals(stage, f.base.calls.last())
                assertFalse("metadata" in f.base.calls)
            }
        }
    }

    @Test
    fun unavailableSpaceReadFailuresAndDeadlinesRemainOperationalFailures() = runTest {
        for (stage in listOf("registration1", "registration2")) for (stall in listOf(false, true)) {
            val f = Fixture()
            if (stall) f.base.hooks[stage] = { awaitCancellation() }
            else f.base.failures[stage] = f.base.directory.storageFailure
            val locator =
                CollaborationAccountIncomingHistoryLocator.Space(f.base.id(901), f.locator())
            val failed =
                assertIs<AppResult.Failed>(f.loader()(f.base.runtime, f.base.source, locator))
            if (stall) assertEquals(FailureKind.Timeout, failed.failure.kind)
            else assertEquals(f.base.directory.storageFailure, failed.failure)
            assertEquals(1, f.base.calls.count { it == stage })
            assertFalse("metadata" in f.base.calls)
        }
    }

    @Test
    fun unavailableSpaceCallerCancellationCannotPublishAWaitingOutcome() = runTest {
        for (stage in listOf("registration1", "registration2")) {
            val f = Fixture()
            val entered = CompletableDeferred<Unit>()
            f.base.hooks[stage] = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val locator =
                CollaborationAccountIncomingHistoryLocator.Space(f.base.id(901), f.locator())
            val job = async { f.loader()(f.base.runtime, f.base.source, locator) }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(stage, f.base.calls.last())
            assertFalse("metadata" in f.base.calls)
        }
    }

    private class Fixture {
        val base = SpaceOriginalIncomingLoadFixture()
        val conversationId = base.id(940)
        var binding: CollaborationSpaceRoomBinding? =
            CollaborationSpaceRoomBinding(
                CollaborationSpaceConversationMetadata(
                    base.access.spaceId,
                    conversationId,
                    base.original.firstAccountId,
                    base.original.secondAccountId,
                    base.original.createdBy,
                    base.original.createdAt,
                    0,
                ),
                CollaborationSpacePublishedRoom(
                    base.room.roomId,
                    base.room.serverName,
                    CollaborationSpaceRoomOrigin.SPACE,
                    base.room.boundAt + 1.days,
                ),
            )
        val bindings =
            object : CollaborationSpaceRoomBindingRepository {
                override fun observeByConversation(
                    access: CollaborationSpaceLocalAccess,
                    conversationId: String,
                ): Flow<AppResult<CollaborationSpaceRoomBinding?>> =
                    error("Binding observation is not part of this fixture")

                override suspend fun getByConversation(
                    access: CollaborationSpaceLocalAccess,
                    conversationId: String,
                ): AppResult<CollaborationSpaceRoomBinding?> {
                    assertEquals(base.access, access)
                    assertEquals(this@Fixture.conversationId, conversationId)
                    base.calls += "metadata"
                    base.hooks["metadata"]?.invoke()
                    return base.failures["metadata"]?.let { AppResult.Failed(it) }
                        ?: AppResult.Success(binding)
                }

                override suspend fun findByRoom(
                    access: CollaborationSpaceLocalAccess,
                    serverName: String,
                    roomId: String,
                ): AppResult<CollaborationSpaceRoomBinding?> =
                    error("No room scan or Company fallback")

                override suspend fun retain(
                    access: CollaborationSpaceLocalAccess,
                    binding: CollaborationSpaceRoomBinding,
                ): AppResult<Boolean> = error("Key-history resolution does not write or schedule")
            }

        fun originalAlias(): CollaborationSpaceRoomBinding {
            val current = assertNotNull(binding)
            return current.copy(
                room =
                    current.room.copy(
                        origin = CollaborationSpaceRoomOrigin.LEGACY,
                        original =
                            CollaborationSpaceOriginalRoom(
                                base.position,
                                base.room.roomId,
                                base.room.boundAt,
                            ),
                    ),
                original = CollaborationSpaceOriginalRoomMetadata(base.original, base.room),
            )
        }

        fun locator() = CollaborationIncomingHistoryLocator(conversationId, base.runtime.deviceId)

        fun loader() =
            LoadCollaborationSpaceKeyHistory(
                bindings,
                base.registrations,
                base.setup.build,
                base.directory.directory,
                base.native.setup.sessions,
                base.native.server,
                base.sync,
            )

        suspend fun load() = loader()(base.runtime, base.source, locator())

        suspend fun ready() =
            assertIs<CollaborationSpaceKeyHistoryResolution.Ready>(
                    assertIs<AppResult.Success<*>>(load()).value
                )
                .history
    }
}
