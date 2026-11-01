package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationConversations
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class CollaborationConversationReadTest {
    @Test
    fun failedDirectoryRetryKeepsCompanyDenialUntilFreshAdmittedDiscovery() = runTest {
        for (path in listOf(0, 1, 4)) {
            val f = ReadFixture()
            val denied =
                Failure(FailureKind.AccessDenied, "Fixture denial", code = "permission_denied")
            f.failure = denied
            assertEquals(denied, assertIs<AppResult.Failed>(f.read(path)).failure)
            assertEquals(denied, f.gate.denial)
            f.failure = Failure(FailureKind.Network, "Fixture offline")
            assertEquals(denied, assertIs<AppResult.Failed>(f.read(path)).failure)
            assertEquals(0, f.admissions)
            f.failure = null
            assertIs<AppResult.Success<*>>(f.read(path))
            assertNull(f.gate.denial)
            assertEquals(1, f.admissions)
            assertEquals(3, f.calls)
        }
    }

    @Test
    fun rejectedIndividualResourceDoesNotCloseCompanyContent() = runTest {
        for (path in listOf(2, 3, 5)) {
            val f = ReadFixture()
            f.failure =
                Failure(
                    FailureKind.AccessDenied,
                    "Fixture resource denial",
                    code = "fixture.resource_denied",
                )
            assertIs<AppResult.Failed>(f.read(path))
            assertNull(f.gate.denial)
            assertEquals(0, f.denialWrites)
        }
    }

    @Test
    fun cancelledReceivedCompanyDenialStillClosesAllDiscoveryPaths() = runTest {
        for (path in 0..5) {
            val f = ReadFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val denied =
                Failure(FailureKind.Service, "Fixture maintenance", code = "company_maintenance")
            f.failure = denied
            f.before = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val request = async { f.read(path) }
            entered.await()
            request.cancel()
            release.complete(Unit)
            request.join()
            assertEquals(denied, f.gate.denial)
            assertEquals(0, f.admissions)
        }
    }

    @Test
    fun successfulOlderReadCannotReopenANewerCompanyDenial() = runTest {
        val f = ReadFixture()
        val denied =
            Failure(FailureKind.AccessDenied, "Fixture later denial", code = "permission_denied")
        f.beforeAdmission = { f.gate = CollaborationListAdmission("newer", denied) }
        assertEquals(denied, assertIs<AppResult.Failed>(f.read(0)).failure)
        assertEquals(denied, f.gate.denial)
    }

    @Test
    fun lateDiscoveryAdmissionCannotPublishToAReplacedAccount() = runTest {
        val f = ReadFixture()
        f.beforeAdmission = { f.setup.session = null }
        assertIs<AppResult.Failed>(f.read(0))
        assertEquals(1, f.calls)
    }

    @Test
    fun failedDenialPersistenceDoesNotEraseItsProcessGateOrRetryFailure() = runTest {
        val f = ReadFixture()
        val denied = Failure(FailureKind.AccessDenied, "Fixture denial", code = "permission_denied")
        f.failure = denied
        f.persistenceFailure = Failure(FailureKind.Storage, "Fixture write failure")
        assertEquals(denied, assertIs<AppResult.Failed>(f.read(0)).failure)
        f.failure = Failure(FailureKind.Network, "Fixture offline")
        assertEquals(denied, assertIs<AppResult.Failed>(f.read(0)).failure)
        assertEquals(denied, f.gate.denial)
        assertEquals(0, f.admissions)
    }

    @Test
    fun summariesAcquireTheCounterpartAlongsideOwnedMetadataInOneRead() = runTest {
        for (kind in 4..5) {
            val f = ReadFixture()
            val read =
                assertIs<CollaborationRead<*>>(assertIs<AppResult.Success<*>>(f.read(kind)).value)
            val summary =
                if (kind == 4)
                    assertIs<CollaborationDirectSummary>(
                        assertIs<CollaborationPage<*>>(read.value).items.single()
                    )
                else assertIs<CollaborationDirectSummary>(read.value)
            assertEquals("conversation", summary.conversation.id)
            assertEquals(CollaborationParticipant("peer", "Fictional counterpart"), summary.peer)
            assertEquals(f.owner, read.owner)
            assertEquals(1, f.calls)
        }
    }

    @Test
    fun allReadPathsRetainServerDenialAcrossFailedPostRequestSessionReads() = runTest {
        for (path in 0..5) for (remote in
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
        for (path in 0..5) for (kind in
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
        for (kind in 0..5) {
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
        for (kind in 0..5) for (failed in listOf(false, true)) {
            val f = ReadFixture()
            f.failure = if (failed) Failure(FailureKind.Network, "Network unavailable") else null
            f.before = { f.setup.workspace = f.setup.selected.copy(permissions = emptySet()) }
            assertEquals("permission_denied", assertIs<AppResult.Failed>(f.read(kind)).failure.code)
            assertEquals(1, f.calls)
        }
    }

    @Test
    fun selectionAccountAndNativeParentReplacementRejectLateDiscovery() = runTest {
        for (kind in 0..5) for (replacement in 0..2) {
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
        for (kind in 0..5) {
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
    var gate = CollaborationListAdmission(null, null)
    var denialWrites = 0
    var admissions = 0
    var persistenceFailure: Failure? = null
    var beforeAdmission: suspend () -> Unit = {}
    val replicas =
        object : CollaborationListRepository {
            override suspend fun get(
                access: CollaborationLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationDirectSummary?> = error("No cached bodies")

            override fun observe(
                access: CollaborationLocalAccess,
                selection: CollaborationListSelection,
            ): Flow<AppResult<CollaborationLocalList>> = error("No cached bodies")

            override suspend fun checkpoint(
                access: CollaborationLocalAccess
            ): AppResult<CollaborationListCheckpoint?> = error("No list refresh")

            override suspend fun merge(
                access: CollaborationLocalAccess,
                expected: CollaborationListCheckpoint?,
                items: List<CollaborationDirectSummary>,
                acquiredAt: Instant,
            ): AppResult<Boolean> = error("No list refresh")

            override fun observeAdmission(access: CollaborationLocalAccess) =
                flowOf(AppResult.Success(gate))

            override suspend fun admission(access: CollaborationLocalAccess) =
                AppResult.Success(gate)

            override suspend fun restrict(
                access: CollaborationLocalAccess,
                failure: Failure,
            ): AppResult<Boolean> {
                denialWrites++
                gate = CollaborationListAdmission("denial-$denialWrites", failure)
                return persistenceFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }

            override suspend fun admit(
                access: CollaborationLocalAccess,
                expectedRevision: String?,
            ): AppResult<Boolean> {
                admissions++
                beforeAdmission()
                if (gate.revision != expectedRevision) return AppResult.Success(false)
                gate = CollaborationListAdmission(expectedRevision, null)
                return AppResult.Success(true)
            }
        }
    val conversations =
        object : CollaborationConversationRepository {
            private suspend fun <T> acquire(value: T, scope: CollaborationOwner): AppResult<T> {
                calls++
                owner = scope
                before()
                return failure?.let { AppResult.Failed(it) } ?: AppResult.Success(value)
            }

            override suspend fun summaries(owner: CollaborationOwner, after: String?, limit: Int) =
                acquire(
                    CollaborationPage(
                        listOf(
                            CollaborationDirectSummary(
                                conversation,
                                CollaborationParticipant("peer", "Fictional counterpart"),
                            )
                        ),
                        null,
                    ),
                    owner,
                )

            override suspend fun summary(owner: CollaborationOwner, conversationId: String) =
                acquire(
                    CollaborationDirectSummary(
                        conversation,
                        CollaborationParticipant("peer", "Fictional counterpart"),
                    ),
                    owner,
                )

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
                replicas,
            )
        return when (kind) {
            0 -> reader.participants("selection", "Fictional", "cursor", 12)
            1 -> reader.list("selection")
            2 -> reader.get("selection", "conversation")
            3 -> reader.room("selection", "conversation")
            4 -> reader.summaries("selection")
            else -> reader.summary("selection", "conversation")
        }
    }
}
