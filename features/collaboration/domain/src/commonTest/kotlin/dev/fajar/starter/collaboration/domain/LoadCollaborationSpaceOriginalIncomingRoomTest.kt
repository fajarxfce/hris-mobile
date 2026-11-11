package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceRegistrationRecord
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class LoadCollaborationSpaceOriginalIncomingRoomTest {
    @Test
    fun exactOriginalCompanyAndPhysicalDeviceSurviveGroupRuntimeResolution() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        val before = f.snapshot
        val result =
            assertNotNull(
                assertIs<AppResult.Success<CollaborationSpaceOriginalIncomingRoom?>>(f.load()).value
            )
        assertEquals(f.captured, result.captured)
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
        assertNotEquals(f.runtime.deviceId, result.traversal.deviceId)
        assertEquals(before, f.snapshot)
        assertEquals(listOf("demand", "registration1", "metadata", "registration2"), f.calls)
        assertEquals(0, f.setup.inserts)
        assertTrue(f.setup.sent.isEmpty())
    }

    @Test
    fun publishedAndMalformedTargetsFailBeforeLocalOrRuntimeReads() = runTest {
        val seed = SpaceOriginalIncomingLoadFixture()
        for (target in
            listOf(
                CollaborationSpaceIncomingTarget.Published(seed.position.conversationId),
                CollaborationSpaceIncomingTarget.Original(
                    seed.position.copy(companyId = "00000000-0000-0000-0000-000000000000")
                ),
                CollaborationSpaceIncomingTarget.Original(
                    seed.position.copy(conversationId = "invalid")
                ),
            )) {
            val f = SpaceOriginalIncomingLoadFixture()
            f.captured = f.captured.copy(target = target)
            assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(f.load()).failure.kind)
            assertTrue(f.calls.isEmpty())
            assertEquals(0, f.sourceReads)
        }
    }

    @Test
    fun originalRequiresConcreteSourceAndValidCapturedGeneration() = runTest {
        val seed = SpaceOriginalIncomingLoadFixture()
        for (captured in
            listOf(
                seed.captured.copy(sourceId = null),
                seed.captured.copy(sourceId = "bad"),
                seed.captured.copy(generation = "bad"),
            )) {
            val f = SpaceOriginalIncomingLoadFixture()
            f.captured = captured
            assertEquals(FailureKind.Validation, assertIs<AppResult.Failed>(f.load()).failure.kind)
            assertTrue(f.calls.isEmpty())
            assertEquals(0, f.sourceReads)
        }
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
                assertIs<AppResult.Failed>(f.loader()(runtime, f.captured)).failure.kind,
            )
        }
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.sourceReads)
    }

    @Test
    fun missingOrDifferentInstalledStoreDoesNotStartSyncOrReadDemand() = runTest {
        for (missing in listOf(false, true)) {
            val f = SpaceOriginalIncomingLoadFixture()
            f.installed = if (missing) null else f.source.copy(storeId = "b".repeat(64))
            assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.load()).failure.kind)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test
    fun retainedSourceMustMatchFullMatrixIdentity() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        val demand = assertNotNull(f.snapshot).demand
        f.demand(
            demand.copy(
                sources =
                    demand.sources.map {
                        it.copy(source = it.source.copy(matrixDeviceId = "OTHER"))
                    }
            )
        )
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
        assertEquals(listOf("demand"), f.calls)
    }

    @Test
    fun absentTaskAndNewerGenerationReturnNoObsoleteCoordinates() = runTest {
        for (variant in 0..2) {
            val f = SpaceOriginalIncomingLoadFixture()
            val demand = assertNotNull(f.snapshot).demand
            when (variant) {
                0 -> f.snapshot = null
                1 -> f.demand(demand.copy(rooms = emptyList()))
                else ->
                    f.demand(demand.copy(rooms = listOf(f.captured.copy(generation = f.id(20)))))
            }
            assertEquals(AppResult.Success(null), f.load())
            assertEquals(listOf("demand"), f.calls)
        }
    }

    @Test
    fun sameConversationIdentifierCannotAliasPublishedOrAnotherCompanyTask() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        val demand = assertNotNull(f.snapshot).demand
        f.demand(
            demand.copy(
                nextPosition = 2,
                rooms =
                    listOf(
                        f.captured.copy(
                            target =
                                CollaborationSpaceIncomingTarget.Published(
                                    f.position.conversationId
                                )
                        ),
                        f.captured.copy(
                            target =
                                CollaborationSpaceIncomingTarget.Original(
                                    f.position.copy(companyId = f.id(21))
                                ),
                            position = 1,
                            generation = f.id(22),
                        ),
                    ),
            )
        )
        assertEquals(AppResult.Success(null), f.load())
        assertEquals(listOf("demand"), f.calls)
    }

    @Test
    fun changedTaskUnderSameGenerationIsNotTheCapturedInvocation() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.demand(
            assertNotNull(f.snapshot)
                .demand
                .copy(rooms = listOf(f.captured.copy(headEventId = "\$new-head")))
        )
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
        assertEquals(listOf("demand"), f.calls)
    }

    @Test
    fun damagedQueueDoesNotFallBackToMetadata() = runTest {
        for (variant in 0..2) {
            val f = SpaceOriginalIncomingLoadFixture()
            val snapshot = assertNotNull(f.snapshot)
            f.snapshot =
                when (variant) {
                    0 -> snapshot.copy(generation = "bad")
                    1 -> snapshot.copy(demand = snapshot.demand.copy(sources = emptyList()))
                    else ->
                        snapshot.copy(
                            demand = snapshot.demand.copy(rooms = listOf(f.captured, f.captured))
                        )
                }
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
            assertEquals(listOf("demand"), f.calls)
        }
    }

    @Test
    fun absentPendingOrUnconfirmedRegistrationCannotInventPhysicalDevice() = runTest {
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
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
            assertEquals(listOf("demand", "registration1"), f.calls)
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
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
        assertEquals(listOf("demand", "registration1"), f.calls)
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
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
            assertEquals(listOf("demand", "registration1"), f.calls)
        }
    }

    @Test
    fun platformMismatchCannotReuseAnotherNativeRegistration() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.registration =
            assertNotNull(f.registration)
                .copy(command = f.command.copy(platform = CollaborationNativePlatform.IOS))
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
        assertEquals(listOf("demand", "registration1"), f.calls)
    }

    @Test
    fun validOtherCompanyRegistrationCannotConsumeThisOriginalTask() = runTest {
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
        val registration = assertNotNull(f.registration).copy(command = command)
        assertTrue(validCollaborationSpaceRegistrationRecord(f.access, registration))
        f.registration = registration
        val installed = f.source.copy(matrixUserId = matrixUser)
        f.installed = installed
        val demand = assertNotNull(f.snapshot).demand
        f.demand(demand.copy(sources = demand.sources.map { it.copy(source = installed) }))
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
        assertEquals(listOf("demand", "registration1"), f.calls)
    }

    @Test
    fun missingForeignOrDamagedMetadataCannotBeReacquiredHere() = runTest {
        val seed = SpaceOriginalIncomingLoadFixture()
        val row = assertNotNull(seed.retained)
        for (metadata in
            listOf(
                null,
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
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
            assertEquals(listOf("demand", "registration1", "metadata"), f.calls)
        }
    }

    @Test
    fun oldCredentialsAndUnrelatedSelectedCompanyDoNotHideOwnedCoordinates() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.native.setup.now += 365.days
        f.native.setup.workspace = null
        assertIs<AppResult.Success<*>>(f.load())
        assertEquals(f.setup.company, f.position.companyId)
    }

    @Test
    fun changedRegistrationDuringMetadataReadCannotPublishAStaleDeviceMapping() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.hooks["metadata"] = { f.registration = assertNotNull(f.registration).copy(revision = 3) }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.load()).failure.kind)
        assertEquals(listOf("demand", "registration1", "metadata", "registration2"), f.calls)
    }

    @Test
    fun directoryDenialStopsBeforeSourceAndLocalReads() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.directory.deny()
        assertEquals(f.directory.denied, assertIs<AppResult.Failed>(f.load()).failure)
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.sourceReads)
    }

    @Test
    fun lateAccountNativeSourceAndAccessChangesFenceEverySuspendingRead() = runTest {
        for (stage in listOf("demand", "registration1", "metadata", "registration2")) {
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
                val failed = assertIs<AppResult.Failed>(f.load(), "$stage/$change")
                if (change == "source") assertEquals(FailureKind.Unavailable, failed.failure.kind)
                if (change == "native") assertEquals(FailureKind.Unauthorized, failed.failure.kind)
                if (change == "denial") assertEquals(f.directory.denied, failed.failure)
                assertEquals(stage, f.calls.last())
            }
        }
    }

    @Test
    fun localFailureKeepsItsClassificationWithoutRetryOrWrites() = runTest {
        for (stage in listOf("demand", "registration1", "metadata", "registration2")) {
            val f = SpaceOriginalIncomingLoadFixture()
            f.failures[stage] = f.directory.storageFailure
            assertEquals(f.directory.storageFailure, assertIs<AppResult.Failed>(f.load()).failure)
            assertEquals(1, f.calls.count { it == stage })
            assertEquals(stage, f.calls.last())
        }
    }

    @Test
    fun confirmedDenialStillWinsWhenTheLocalReadFails() = runTest {
        val f = SpaceOriginalIncomingLoadFixture()
        f.hooks["metadata"] = { f.directory.deny() }
        f.failures["metadata"] = f.directory.storageFailure
        assertEquals(f.directory.denied, assertIs<AppResult.Failed>(f.load()).failure)
        assertEquals("metadata", f.calls.last())
    }

    @Test
    fun eachStalledReadHasOneLocalDeadlineWithoutRetry() = runTest {
        for (stage in listOf("demand", "registration1", "metadata", "registration2")) {
            val f = SpaceOriginalIncomingLoadFixture()
            f.hooks[stage] = { awaitCancellation() }
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.load()).failure.kind)
            assertEquals(1, f.calls.count { it == stage })
            assertEquals(stage, f.calls.last())
        }
    }

    @Test
    fun callerCancellationPropagatesAtEveryReadAndCannotPublishLateCoordinates() = runTest {
        for (stage in listOf("demand", "registration1", "metadata", "registration2")) {
            val f = SpaceOriginalIncomingLoadFixture()
            val entered = CompletableDeferred<Unit>()
            f.hooks[stage] = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val job = async { f.load() }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(stage, f.calls.last())
        }
    }
}
