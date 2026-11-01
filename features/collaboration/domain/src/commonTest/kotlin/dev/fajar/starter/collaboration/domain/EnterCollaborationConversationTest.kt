package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.EnterCollaborationConversation
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class EnterCollaborationConversationTest {
    @Test
    fun aReceivedDenialSurvivesAFailedPostRequestSessionRead() = runTest {
        for (step in listOf("setup", "get", "room", "sdk")) {
            val f = EntryFixture()
            val denial =
                AppResult.Failed(
                    Failure(FailureKind.AccessDenied, "Rejected", code = "permission_denied")
                )
            val failCapture = {
                f.captureFailure =
                    AppResult.Failed(Failure(FailureKind.Storage, "Local read failed"))
            }
            when (step) {
                "setup" -> f.access.onRead = {
                        failCapture()
                        denial
                    }
                "sdk" -> f.result = {
                        failCapture()
                        denial
                    }
                else -> f.conversations.beforeRemote = {
                        if (it == step) {
                            failCapture()
                            f.conversations.remoteFailure = denial.failure
                        }
                    }
            }
            assertEquals(denial, f.enter())
            assertEquals(0, f.closes)
        }
    }

    @Test
    fun nativeParentExpiryDuringEntryClosesTheLateChildWithoutDeletingItsStore() = runTest {
        val f = EntryFixture()
        f.result = {
            f.access.now += 3.hours
            AppResult.Success(f.lease)
        }
        assertIs<AppResult.Failed>(f.enter())
        assertEquals(1, f.closes)
        assertTrue(f.conversations.sent.isEmpty())
    }

    @Test
    fun entersOnlyTheObservedOwnedRoomUsingTheExistingRuntime() = runTest {
        val f = EntryFixture()
        assertSame(
            f.lease,
            assertIs<AppResult.Success<CollaborationTimelineLease>>(f.enter()).value,
        )
        assertEquals(1, f.entries)
        assertEquals(listOf("get", "room"), f.reads)
        assertEquals(0, f.closes)
        assertTrue(f.conversations.sent.isEmpty())
        assertTrue(f.conversations.values.isEmpty())
        f.lease.close()
    }

    @Test
    fun invalidInputAndDeniedWorkspaceNeverTouchSetupOrSdk() = runTest {
        val f = EntryFixture()
        assertIs<AppResult.Failed>(f.enter(deviceId = "invalid"))
        assertIs<AppResult.Failed>(f.enter(conversationId = "invalid"))
        f.access.workspace = f.access.selected.copy(permissions = emptySet())
        assertIs<AppResult.Failed>(f.enter())
        assertEquals(0, f.access.calls)
        assertEquals(0, f.entries)
    }

    @Test
    fun inactiveMissingForeignAndOtherPlatformDevicesCannotEnter() = runTest {
        repeat(4) { scenario ->
            val f = EntryFixture()
            f.access.onRead = {
                AppResult.Success(
                    when (scenario) {
                        0 -> null
                        1 -> f.device.copy(state = CollaborationDeviceState.REVOKED)
                        2 -> f.device.copy(id = COMMAND_PEER)
                        else -> f.device.copy(platform = CollaborationNativePlatform.IOS)
                    }
                )
            }
            assertIs<AppResult.Failed>(f.enter())
            assertEquals(0, f.entries)
            assertTrue(f.reads.isEmpty())
        }
    }

    @Test
    fun scopeIsRecheckedAfterEveryPendingMetadataReadEvenIfTheReadFails() = runTest {
        for (step in listOf("setup", "get", "room")) repeat(2) { fail ->
            val f = EntryFixture()
            if (step == "setup")
                f.access.onRead = {
                    f.access.session = null
                    if (fail == 1) f.failed else AppResult.Success(f.device)
                }
            else
                f.conversations.beforeRemote = {
                    f.reads += it
                    if (it == step) {
                        f.access.session = null
                        if (fail == 1) f.conversations.remoteFailure = f.failed.failure
                    }
                }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.enter()).failure.kind,
            )
            assertEquals(0, f.entries)
        }
    }

    @Test
    fun foreignConversationOrRoomMetadataCannotBecomeANativeRoomCapability() = runTest {
        repeat(5) { scenario ->
            val f = EntryFixture()
            when (scenario) {
                0 ->
                    f.conversations.conversation =
                        f.conversations.conversation.copy(id = COMMAND_PEER)
                1 ->
                    f.conversations.conversation =
                        f.conversations.conversation.copy(companyId = "another")
                2 ->
                    f.conversations.conversation =
                        f.conversations.conversation.copy(firstAccountId = "another")
                3 -> f.conversations.room = f.conversations.room.copy(conversationId = COMMAND_PEER)
                else ->
                    f.conversations.room =
                        f.conversations.room.copy(
                            state = CollaborationDirectRoomState.RUNNING,
                            matrixRoomId = null,
                        )
            }
            assertIs<AppResult.Failed>(f.enter())
            assertEquals(0, f.entries)
        }
    }

    @Test
    fun lateAccountCompanyAndPermissionReplacementCloseTheChild() = runTest {
        repeat(3) { scenario ->
            val f = EntryFixture()
            f.result = {
                when (scenario) {
                    0 -> f.access.session = null
                    1 -> f.access.workspace = f.access.selected.copy(id = "other-selection")
                    else -> f.access.workspace = f.access.selected.copy(permissions = emptySet())
                }
                AppResult.Success(f.lease)
            }
            assertIs<AppResult.Failed>(f.enter())
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun confirmedDenialWinsOverSdkAndCleanupFailures() = runTest {
        repeat(2) { scenario ->
            val f = EntryFixture()
            f.closeFailure = true
            f.result = {
                f.access.session = null
                if (scenario == 0) f.failed else AppResult.Success(f.lease)
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.enter()).failure.kind,
            )
            assertEquals(scenario, f.closes)
        }
    }

    @Test
    fun foreignNativeOwnerDeviceAndRoomAreClosedBeforePublication() = runTest {
        repeat(3) { scenario ->
            val f = EntryFixture()
            when (scenario) {
                0 -> f.leaseOwner = f.owner.copy(nativeSessionId = "other-parent")
                1 -> f.leaseDevice = COMMAND_PEER
                else -> f.leaseRoom = "!other-room"
            }
            assertIs<AppResult.Failed>(f.enter())
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun cancelledEntryStillClosesAnUncooperativeLateNativeResult() = runTest {
        val f = EntryFixture()
        val started = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        f.result = {
            started.complete(Unit)
            withContext(NonCancellable) { complete.await() }
            AppResult.Success(f.lease)
        }
        val caller = async { f.enter() }
        started.await()
        caller.cancel()
        complete.complete(Unit)
        caller.join()
        assertTrue(caller.isCancelled)
        assertEquals(1, f.closes)
    }

    @Test
    fun rotatingTheTokenKeepsTheSameLocalOwnerAndSdkRejectionIsNotRetried() = runTest {
        val f = EntryFixture()
        f.result = {
            f.access.session =
                f.access.initial.copy(
                    tokens = f.access.initial.tokens.copy(accessToken = "rotated")
                )
            AppResult.Success(f.lease)
        }
        assertSame(
            f.lease,
            assertIs<AppResult.Success<CollaborationTimelineLease>>(f.enter()).value,
        )
        assertEquals(0, f.closes)
        f.lease.close()
        val denial =
            AppResult.Failed(
                Failure(
                    FailureKind.AccessDenied,
                    "Rejected",
                    code = "client.collaboration_sdk_access_denied",
                )
            )
        f.result = { denial }
        assertEquals(denial, f.enter())
        assertEquals(2, f.entries)
    }
}

private class EntryFixture {
    val conversations = ConversationCommandFixture()
    val access = conversations.setup
    var captureFailure: AppResult.Failed? = null
    private val sessions =
        object : SessionRepository by access.sessions {
            override suspend fun current(): AppResult<Session?> =
                captureFailure ?: access.sessions.current()
        }
    val device = access.device.copy(id = COMMAND_DEVICE)
    val owner =
        CollaborationOwner(
            "local",
            access.initial.binding.audience,
            "account",
            "company",
            "native-family",
        )
    var leaseOwner = owner
    var leaseDevice = COMMAND_DEVICE
    var leaseRoom = "!fixture-room"
    var entries = 0
    var closes = 0
    var closeFailure = false
    val reads = mutableListOf<String>()
    val failed = AppResult.Failed(Failure(FailureKind.Network, "Fictional interruption"))
    val lease =
        object : CollaborationTimelineLease {
            override val owner
                get() = leaseOwner

            override val deviceId
                get() = leaseDevice

            override val matrixRoomId
                get() = leaseRoom

            override val updates = flowOf(AppResult.Success(emptyList<CollaborationTimelineItem>()))

            override suspend fun close(): AppResult<Unit> {
                assertTrue(currentCoroutineContext().isActive)
                closes++
                return if (closeFailure) failed else AppResult.Success(Unit)
            }
        }
    var result: suspend () -> AppResult<CollaborationTimelineLease> = { AppResult.Success(lease) }
    private val runtime =
        object : CollaborationRuntimeRepository {
            override suspend fun enterTimeline(
                owner: CollaborationOwner,
                deviceId: String,
                matrixRoomId: String,
            ): AppResult<CollaborationTimelineLease> {
                assertEquals(this@EntryFixture.owner, owner)
                assertEquals(COMMAND_DEVICE, deviceId)
                assertEquals("!fixture-room", matrixRoomId)
                entries++
                return result()
            }

            override suspend fun install(
                owner: CollaborationOwner,
                command: CollaborationSetupCommandRecord,
            ): AppResult<CollaborationRuntimeLease> = error("No implicit install")

            override suspend fun restore(
                owner: CollaborationOwner,
                deviceId: String,
            ): AppResult<CollaborationRuntimeLease?> = error("No implicit restore")

            override suspend fun closeOtherOwners(
                retainedOwner: CollaborationOwner?
            ): AppResult<Unit> = error("No parent disposal")
        }

    init {
        conversations.room =
            conversations.room.copy(
                version = 0,
                state = CollaborationDirectRoomState.READY,
                matrixRoomId = "!fixture-room",
                creatorAccountId = "account",
            )
        conversations.beforeRemote = { reads += it }
    }

    suspend fun enter(deviceId: String = COMMAND_DEVICE, conversationId: String = COMMAND_ROOM) =
        EnterCollaborationConversation(
            runtime,
            conversations.remote,
            access.remote,
            sessions,
            access.workspaces,
            IdentityServer(access.initial.binding.audience, "Fictional Android"),
            conversations.build,
            object : Clock {
                override fun now() = access.now
            },
        )("selection", deviceId, conversationId)
}
