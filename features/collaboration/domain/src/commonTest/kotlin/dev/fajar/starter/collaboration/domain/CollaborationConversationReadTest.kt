package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationConversationRepository
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationConversations
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationConversationReadTest {
    @Test
    fun allReadPathsRetainServerDenialAcrossFailedPostRequestSessionReads() = runTest {
        for (path in 0..3) for (remote in
            listOf(
                FailureKind.Unauthorized,
                FailureKind.AccessDenied,
                FailureKind.Network,
            )) for (local in listOf(FailureKind.Storage, FailureKind.Network)) {
            val f = ReadFixture()
            val received = Failure(remote, "Fixture response", code = "fixture.remote")
            val later = Failure(local, "Fixture read", code = "fixture.local")
            f.failure = received
            f.before = { f.setup.sessionFailure = later }
            val expected = if (remote == FailureKind.Network) later else received
            assertEquals(expected, assertIs<AppResult.Failed>(f.read(path)).failure)
            assertEquals(1, f.calls)
        }
    }

    @Test
    fun currentDenialStillWinsOnEveryDiscoveryPath() = runTest {
        for (path in 0..3) for (kind in
            listOf(FailureKind.Unauthorized, FailureKind.AccessDenied)) {
            val f = ReadFixture()
            val current = Failure(kind, "Fixture current denial", code = "fixture.current")
            f.failure = Failure(FailureKind.AccessDenied, "Fixture server denial")
            f.before = { f.setup.sessionFailure = current }
            assertEquals(current, assertIs<AppResult.Failed>(f.read(path)).failure)
            assertEquals(1, f.calls)
        }
    }

    @Test
    fun allReadPathsCaptureOwnerAndKeepOrdinaryTokenRotation() = runTest {
        for (kind in 0..3) {
            val f = ReadFixture()
            f.before = {
                f.setup.session =
                    f.setup.initial.copy(
                        tokens = f.setup.initial.tokens.copy(accessToken = "renewed")
                    )
            }
            val result = assertIs<AppResult.Success<*>>(f.read(kind))
            assertEquals(f.owner, (result.value as CollaborationRead<*>).owner)
            assertEquals(1, f.calls)
        }
    }

    @Test
    fun permissionLossOverridesBothSuccessfulAndFailedAcquisitionOnEveryPath() = runTest {
        for (kind in 0..3) for (failed in listOf(false, true)) {
            val f = ReadFixture()
            f.failure = if (failed) Failure(FailureKind.Network, "Network unavailable") else null
            f.before = { f.setup.workspace = f.setup.selected.copy(permissions = emptySet()) }
            assertEquals("permission_denied", assertIs<AppResult.Failed>(f.read(kind)).failure.code)
            assertEquals(1, f.calls)
        }
    }

    @Test
    fun selectionAccountAndNativeParentReplacementRejectLateDiscovery() = runTest {
        for (kind in 0..3) for (replacement in 0..2) {
            val f = ReadFixture()
            f.before = {
                when (replacement) {
                    0 -> f.setup.workspace = f.setup.selected.copy(id = "new-selection")
                    1 -> f.setup.session = f.setup.initial.copy(id = "new-session")
                    else -> {
                        f.setup.session =
                            f.setup.initial.copy(
                                binding = f.setup.initial.binding.copy(serverId = "new-native")
                            )
                        f.setup.workspace =
                            f.setup.selected.copy(
                                admission = f.setup.selected.admission.copy(familyId = "new-native")
                            )
                    }
                }
            }
            assertIs<AppResult.Failed>(f.read(kind))
            assertEquals(1, f.calls)
        }
    }

    @Test
    fun expirationDuringRequestClosesAdmissionWithoutReplacingLocalFacts() = runTest {
        val f = ReadFixture()
        f.before = { f.setup.now += 6.minutes }
        assertEquals(
            "client.company_policy_expired",
            assertIs<AppResult.Failed>(f.read(0)).failure.code,
        )
        assertEquals(f.setup.selected, f.setup.workspace)
    }

    @Test
    fun denialBeforeDiscoveryNeverReachesAnyRemoteRead() = runTest {
        for (kind in 0..3) {
            val f = ReadFixture()
            f.setup.workspace = f.setup.selected.copy(permissions = emptySet())
            assertIs<AppResult.Failed>(f.read(kind))
            assertEquals(0, f.calls)
        }
    }

    @Test
    fun cancellationDuringARequestReleasesItAndDoesNotPublishLateSuccess() = runTest {
        val f = ReadFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.before = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val result = async { f.read(2) }
        entered.await()
        result.cancel()
        release.complete(Unit)
        result.join()
        assertTrue(result.isCancelled)
        assertEquals(1, f.calls)
    }

    @Test
    fun transportFailureIsReturnedOnceWithoutImplicitRefreshOrProvisioning() = runTest {
        val f = ReadFixture()
        f.failure =
            Failure(FailureKind.Unavailable, "Try later", code = "collaboration_unavailable")
        assertEquals(AppResult.Failed(f.failure!!), f.read(3))
        assertEquals(1, f.calls)
    }
}

private class ReadFixture {
    val setup = CollaborationSetupFixture()
    var before: suspend () -> Unit = {}
    var failure: Failure? = null
    var calls = 0
    var owner: CollaborationOwner? = null
    val conversations =
        object : CollaborationConversationRepository {
            private suspend fun <T> acquire(value: T, scope: CollaborationOwner): AppResult<T> {
                calls++
                owner = scope
                before()
                return failure?.let { AppResult.Failed(it) } ?: AppResult.Success(value)
            }

            override suspend fun participants(
                owner: CollaborationOwner,
                query: String,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationPage<CollaborationParticipant>> {
                assertEquals("Fictional", query)
                assertEquals("cursor", after)
                assertEquals(12, limit)
                return acquire(
                    CollaborationPage(
                        listOf(CollaborationParticipant("peer", "Fictional coworker")),
                        null,
                    ),
                    owner,
                )
            }

            override suspend fun list(owner: CollaborationOwner, after: String?, limit: Int) =
                acquire(CollaborationPage(listOf(conversation), null), owner)

            override suspend fun get(owner: CollaborationOwner, conversationId: String) =
                acquire(conversation, owner)

            override suspend fun room(owner: CollaborationOwner, conversationId: String) =
                acquire(
                    CollaborationDirectRoom(
                        "conversation",
                        null,
                        CollaborationDirectRoomState.NOT_REQUESTED,
                        null,
                        null,
                        false,
                        null,
                    ),
                    owner,
                )

            override suspend fun open(
                owner: CollaborationOwner,
                command: CollaborationOpenDirect,
            ): AppResult<CollaborationSetupReceipt> = error("Reads cannot open conversations")

            override suspend fun requestRoom(
                owner: CollaborationOwner,
                command: CollaborationRequestDirectRoom,
            ): AppResult<CollaborationSetupReceipt> = error("Reads cannot provision rooms")
        }
    private val conversation
        get() =
            CollaborationDirectConversation(
                "conversation",
                "company",
                "account",
                "peer",
                "account",
                0,
                0,
            )

    suspend fun read(kind: Int): AppResult<*> {
        val reader =
            ReadCollaborationConversations(
                setup.sessions,
                setup.workspaces,
                conversations,
                IdentityServer(setup.initial.binding.audience, "Fictional Android"),
                AppBuild(AppPlatform.Android, 5, "test", null),
                object : Clock {
                    override fun now() = setup.now
                },
            )
        return when (kind) {
            0 -> reader.participants("selection", "Fictional", "cursor", 12)
            1 -> reader.list("selection")
            2 -> reader.get("selection", "conversation")
            else -> reader.room("selection", "conversation")
        }
    }
}
