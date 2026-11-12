package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.registeredCollaborationSpaceOriginalIncomingSdk
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceRegistrationRecord
import dev.fajar.starter.collaboration.domain.usecases.LoadCollaborationSpaceOriginalKeyHistory
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class LoadCollaborationSpaceOriginalKeyHistoryTest {
    @Test
    fun registeredCompanyAndExactRoomResolveWithoutReadingThePrimaryQueue() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        val before = f.snapshot
        f.failures["demand"] = f.directory.storageFailure
        val result =
            assertIs<CollaborationSpaceOriginalKeyHistoryResolution.Ready>(
                    assertIs<AppResult.Success<*>>(load(f)).value
                )
                .history
        assertEquals(f.source, result.source)
        assertEquals(
            CollaborationSpaceOriginalConversation(f.owner, f.original, f.room),
            result.conversation,
        )
        assertEquals(
            CollaborationHistoryTraversal(
                f.position.conversationId,
                f.room.roomId,
                f.setup.sdkDevice,
                CollaborationHistoryPurpose.Incoming,
            ),
            result.traversal,
        )
        assertEquals(listOf("registration1", "metadata", "registration2"), f.calls)
        assertEquals(before, f.snapshot)
        assertEquals(0, f.setup.inserts)
        assertTrue(f.setup.sent.isEmpty())
    }

    @Test
    fun historicalDeviceCoordinatesArePreservedSeparatelyFromTheInstalledNativeDevice() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        val historical = f.id(900)
        val value =
            loader(f)(
                f.runtime,
                f.source,
                f.position.companyId,
                locator(f).copy(deviceId = historical),
            )
        val result =
            assertIs<CollaborationSpaceOriginalKeyHistoryResolution.Ready>(
                    assertIs<AppResult.Success<*>>(value).value
                )
                .history
        assertEquals(historical, result.traversal.deviceId)
        assertNotEquals(f.runtime.deviceId, result.traversal.deviceId)
        assertNotEquals(f.setup.sdkDevice, result.traversal.deviceId)
        assertEquals(f.source, result.source)
    }

    @Test
    fun missingPrimaryQueueDoesNotHideRetainedHistoryCoordinates() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.snapshot = null
        assertIs<CollaborationSpaceOriginalKeyHistoryResolution.Ready>(
            assertIs<AppResult.Success<*>>(load(f)).value
        )
        assertFalse("demand" in f.calls)
        assertNull(f.snapshot)
    }

    @Test
    fun missingMetadataIsExplicitlyUnresolvedAndDoesNotStartAcquisition() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.retained = null
        assertEquals(
            AppResult.Success(CollaborationSpaceOriginalKeyHistoryResolution.MetadataUnavailable),
            load(f),
        )
        assertEquals(listOf("registration1", "metadata", "registration2"), f.calls)
        assertEquals(0, f.setup.inserts)
        assertTrue(f.setup.sent.isEmpty())
    }

    @Test
    fun missingMetadataStillRequiresAnUnchangedFinalRegistration() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.retained = null
        f.hooks["metadata"] = { f.registration = null }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(load(f)).failure.kind)
        assertEquals(listOf("registration1", "metadata", "registration2"), f.calls)
    }

    @Test
    fun invalidCompanyConversationAndHistoricalDeviceStopBeforeAnyRead() = runTest {
        val seed = SpaceOriginalIncomingLoadFixture()
        for ((company, key) in
            listOf(
                "invalid" to locator(seed),
                "00000000-0000-0000-0000-000000000000" to locator(seed),
                seed.position.companyId to locator(seed).copy(conversationId = "invalid"),
                seed.position.companyId to locator(seed).copy(deviceId = "invalid"),
                seed.position.companyId to
                    locator(seed).copy(deviceId = "00000000-0000-0000-0000-000000000000"),
            )) {
            val f = SpaceOriginalIncomingLoadFixture()
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(loader(f)(f.runtime, f.source, company, key))
                    .failure
                    .kind,
            )
            assertTrue(f.calls.isEmpty())
            assertEquals(0, f.sourceReads)
        }
    }

    @Test
    fun malformedSourceCannotReadRegistrationOrMetadata() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        for (source in
            listOf(
                f.source.copy(storeId = "bad"),
                f.source.copy(matrixUserId = "bad"),
                f.source.copy(matrixDeviceId = ""),
            )) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(
                        loader(f)(f.runtime, source, f.position.companyId, locator(f))
                    )
                    .failure
                    .kind,
            )
        }
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.sourceReads)
    }

    @Test
    fun anotherCompanyCannotBorrowTheInstalledOriginalIdentity() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        assertEquals(
            CollaborationSpaceOriginalKeyHistoryResolution.SourceUnavailable,
            assertIs<AppResult.Success<*>>(loader(f)(f.runtime, f.source, f.id(901), locator(f)))
                .value,
        )
        assertEquals(listOf("registration1", "registration2"), f.calls)
    }

    @Test
    fun validForeignRegistrationDoesNotAuthorizeThisCompanyHistory() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        val company = f.id(27)
        val matrixUser =
            "@hris_${company.replace("-", "")}_${f.access.accountId.replace("-", "")}:matrix.hris.internal"
        val legacy = assertNotNull(f.command.legacy)
        val command =
            f.command.copy(
                provider =
                    f.command.provider.copy(matrixUserId = matrixUser, legacyCompanyId = company),
                legacy =
                    legacy.copy(
                        binding = legacy.binding.copy(companyId = company),
                        identity = legacy.identity.copy(matrixUserId = matrixUser),
                    ),
            )
        f.registration = assertNotNull(f.registration).copy(command = command)
        assertTrue(
            validCollaborationSpaceRegistrationRecord(f.access, assertNotNull(f.registration))
        )
        val installed = f.source.copy(matrixUserId = matrixUser)
        assertNotNull(
            registeredCollaborationSpaceOriginalIncomingSdk(
                f.runtime,
                installed,
                assertNotNull(f.registration),
                CollaborationNativePlatform.ANDROID,
            )
        )
        f.installed = installed
        assertEquals(
            CollaborationSpaceOriginalKeyHistoryResolution.SourceUnavailable,
            assertIs<AppResult.Success<*>>(
                    loader(f)(f.runtime, installed, f.position.companyId, locator(f))
                )
                .value,
        )
        assertEquals(listOf("registration1", "registration2"), f.calls)
    }

    @Test
    fun malformedRuntimeCannotReadLocalMetadata() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
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
                assertIs<AppResult.Failed>(
                        loader(f)(runtime, f.source, f.position.companyId, locator(f))
                    )
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
            val f = SpaceOriginalIncomingLoadFixture()
            f.installed = if (missing) null else f.source.copy(storeId = "b".repeat(64))
            assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(load(f)).failure.kind)
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
            val f = SpaceOriginalIncomingLoadFixture()
            f.registration = row
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(load(f)).failure.kind)
            assertEquals(listOf("registration1"), f.calls)
        }
    }

    @Test
    fun changedRegisteredReceiptCannotAdmitTheOldRuntime() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.registration =
            assertNotNull(f.registration)
                .copy(
                    outcome =
                        CollaborationSpaceRegistrationOutcome.Registered(
                            f.setup.receipt.copy(id = f.id(23))
                        )
                )
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(load(f)).failure.kind)
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
            val f = SpaceOriginalIncomingLoadFixture()
            f.registration = assertNotNull(f.registration).copy(command = changed)
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(load(f)).failure.kind)
            assertEquals(listOf("registration1"), f.calls)
        }
    }

    @Test
    fun platformMismatchCannotReuseAnotherNativeRegistration() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.registration =
            assertNotNull(f.registration)
                .copy(command = f.command.copy(platform = CollaborationNativePlatform.IOS))
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(load(f)).failure.kind)
        assertEquals(listOf("registration1"), f.calls)
    }

    @Test
    fun foreignOrDamagedMetadataCannotBeReacquiredHere() = runTest {
        val seed = SpaceOriginalIncomingLoadFixture()
        val row = assertNotNull(seed.retained)
        for (metadata in
            listOf(
                row.copy(
                    conversation =
                        row.conversation.copy(
                            position = seed.position.copy(companyId = seed.id(25))
                        )
                ),
                row.copy(conversation = row.conversation.copy(firstAccountId = seed.id(25))),
                row.copy(
                    room =
                        row.room.copy(
                            serverName = "other.example",
                            roomId = "!original:other.example",
                        )
                ),
                row.copy(room = row.room.copy(boundAt = row.conversation.createdAt - 1.days)),
            )) {
            val f = SpaceOriginalIncomingLoadFixture()
            f.retained = metadata
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(load(f)).failure.kind)
            assertEquals(listOf("registration1", "metadata"), f.calls)
        }
    }

    @Test
    fun oldCredentialsAndUnrelatedSelectedCompanyDoNotHideOwnedCoordinates() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.native.setup.now += 365.days
        f.native.setup.workspace = null
        assertIs<AppResult.Success<*>>(load(f))
        assertEquals(f.setup.company, f.position.companyId)
    }

    @Test
    fun changedRegistrationDuringMetadataReadCannotPublishAStaleSourceMapping() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.hooks["metadata"] = { f.registration = assertNotNull(f.registration).copy(revision = 3) }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(load(f)).failure.kind)
        assertEquals(listOf("registration1", "metadata", "registration2"), f.calls)
    }

    @Test
    fun directoryDenialStopsBeforeSourceAndLocalReads() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.directory.deny()
        assertEquals(f.directory.denied, assertIs<AppResult.Failed>(load(f)).failure)
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.sourceReads)
    }

    @Test
    fun lateAccountNativeSourceAndAccessChangesFenceEverySuspendingRead() = runTest {
        for (stage in listOf("registration1", "metadata", "registration2")) {
            for (change in listOf("account", "native", "source", "denial")) {
                val f = SpaceOriginalIncomingLoadFixture()
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
                val failed = assertIs<AppResult.Failed>(load(f), "$stage/$change")
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
            val f = SpaceOriginalIncomingLoadFixture()
            f.failures[stage] = f.directory.storageFailure
            assertEquals(f.directory.storageFailure, assertIs<AppResult.Failed>(load(f)).failure)
            assertEquals(1, f.calls.count { it == stage })
            assertEquals(stage, f.calls.last())
        }
    }

    @Test
    fun confirmedDenialStillWinsWhenTheLocalReadFails() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.hooks["metadata"] = { f.directory.deny() }
        f.failures["metadata"] = f.directory.storageFailure
        assertEquals(f.directory.denied, assertIs<AppResult.Failed>(load(f)).failure)
        assertEquals("metadata", f.calls.last())
    }

    @Test
    fun eachStalledReadHasOneLocalDeadlineWithoutRetry() = runTest {
        for (stage in listOf("registration1", "metadata", "registration2")) {
            val f = SpaceOriginalIncomingLoadFixture()
            f.hooks[stage] = { awaitCancellation() }
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(load(f)).failure.kind)
            assertEquals(1, f.calls.count { it == stage })
            assertEquals(stage, f.calls.last())
        }
    }

    @Test
    fun callerCancellationPropagatesAtEveryReadAndCannotPublishLateCoordinates() = runTest {
        for (stage in listOf("registration1", "metadata", "registration2")) {
            val f = SpaceOriginalIncomingLoadFixture()
            val entered = CompletableDeferred<Unit>()
            f.hooks[stage] = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val job = async { load(f) }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(stage, f.calls.last())
        }
    }

    @Test
    fun unavailableCompanyDoesNotHideMissingRegistration() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.registration = null
        assertIs<AppResult.Failed>(loader(f)(f.runtime, f.source, f.id(901), locator(f)))
        assertEquals(listOf("registration1"), f.calls)
    }

    @Test
    fun unavailableCompanyRequiresUnchangedFinalRegistration() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.hooks["registration2"] = { f.registration = null }
        assertIs<AppResult.Failed>(loader(f)(f.runtime, f.source, f.id(901), locator(f)))
        assertEquals(listOf("registration1", "registration2"), f.calls)
    }

    @Test
    fun unavailableCompanyStillFencesLateOwnershipAndSourceChanges() = runTest {
        for (stage in listOf("registration1", "registration2")) {
            for (change in listOf("account", "native", "source", "denial")) {
                val f = SpaceOriginalIncomingLoadFixture()
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
                val failed =
                    assertIs<AppResult.Failed>(
                        loader(f)(f.runtime, f.source, f.id(901), locator(f))
                    )
                if (change == "denial") assertEquals(f.directory.denied, failed.failure)
                if (change == "source") assertEquals(FailureKind.Unavailable, failed.failure.kind)
                if (change == "native") assertEquals(FailureKind.Unauthorized, failed.failure.kind)
                assertEquals(stage, f.calls.last())
                assertFalse("metadata" in f.calls)
            }
        }
    }

    @Test
    fun unavailableCompanyDoesNotConvertStorageFailuresOrDenialsToWaiting() = runTest {
        for (stage in listOf("registration1", "registration2")) for (deny in listOf(false, true)) {
            val f = SpaceOriginalIncomingLoadFixture()
            f.failures[stage] = f.directory.storageFailure
            if (deny) f.hooks[stage] = { f.directory.deny() }
            val failed =
                assertIs<AppResult.Failed>(loader(f)(f.runtime, f.source, f.id(901), locator(f)))
            assertEquals(
                if (deny) f.directory.denied else f.directory.storageFailure,
                failed.failure,
            )
            assertEquals(1, f.calls.count { it == stage })
            assertFalse("metadata" in f.calls)
        }
    }

    @Test
    fun unavailableCompanyRegistrationHasOneBoundedDeadline() = runTest {
        for (stage in listOf("registration1", "registration2")) {
            val f = SpaceOriginalIncomingLoadFixture()
            f.hooks[stage] = { awaitCancellation() }
            assertEquals(
                FailureKind.Timeout,
                assertIs<AppResult.Failed>(loader(f)(f.runtime, f.source, f.id(901), locator(f)))
                    .failure
                    .kind,
            )
            assertEquals(1, f.calls.count { it == stage })
            assertFalse("metadata" in f.calls)
        }
    }

    @Test
    fun unavailableCompanyCallerCancellationCannotPublishAWaitingOutcome() = runTest {
        for (stage in listOf("registration1", "registration2")) {
            val f = SpaceOriginalIncomingLoadFixture()
            val entered = CompletableDeferred<Unit>()
            f.hooks[stage] = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val job = async { loader(f)(f.runtime, f.source, f.id(901), locator(f)) }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(stage, f.calls.last())
            assertFalse("metadata" in f.calls)
        }
    }

    private fun locator(f: SpaceOriginalIncomingLoadFixture) =
        CollaborationIncomingHistoryLocator(f.position.conversationId, f.setup.sdkDevice)

    private fun loader(f: SpaceOriginalIncomingLoadFixture) =
        LoadCollaborationSpaceOriginalKeyHistory(
            f.metadata,
            f.registrations,
            f.setup.build,
            f.directory.directory,
            f.native.setup.sessions,
            f.native.server,
            f.sync,
        )

    private suspend fun load(f: SpaceOriginalIncomingLoadFixture) =
        loader(f)(f.runtime, f.source, f.position.companyId, locator(f))
}
