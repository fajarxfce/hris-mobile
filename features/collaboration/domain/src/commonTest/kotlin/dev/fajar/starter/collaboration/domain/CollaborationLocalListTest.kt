package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationLocalListTest {
    @Test
    fun individualConversationPublishesDirectlyWithoutScanningTheDirectory() = runTest {
        val f = LocalListFixture()
        val result =
            assertIs<AppResult.Success<CollaborationRead<CollaborationDirectSummary>>>(
                f.refresh.conversation("selection", "direct")
            )
        assertEquals(f.summary, result.value.value)
        assertEquals(listOf("direct"), f.pointCalls)
        assertEquals(0, f.remoteCalls)
        assertEquals(listOf(listOf(f.summary)), f.published)
    }

    @Test
    fun pointAcquisitionSharesThePagePublicationMutex() = runTest {
        val f = LocalListFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeRemoteReturn = {
            entered.complete(Unit)
            release.await()
        }
        val page = async { f.refresh("selection") }
        entered.await()
        val point = async { f.refresh.conversation("selection", "direct") }
        runCurrent()
        assertTrue(f.pointCalls.isEmpty())
        release.complete(Unit)
        assertIs<AppResult.Success<*>>(page.await())
        assertIs<AppResult.Success<*>>(point.await())
        assertEquals(listOf("direct"), f.pointCalls)
        assertEquals(2, f.merges)
    }

    @Test
    fun failedPointCannotReopenAnEarlierDenialAndAdmittedPointCan() = runTest {
        val f = LocalListFixture()
        f.pointResult = AppResult.Failed(f.denied)
        assertEquals(
            f.denied,
            assertIs<AppResult.Failed>(f.refresh.conversation("selection", "direct")).failure,
        )
        f.pointResult = AppResult.Failed(f.network)
        assertEquals(
            f.denied,
            assertIs<AppResult.Failed>(f.refresh.conversation("selection", "direct")).failure,
        )
        assertEquals(0, f.merges)
        f.pointResult = AppResult.Success(f.summary)
        assertIs<AppResult.Success<*>>(f.refresh.conversation("selection", "direct"))
        assertNull(f.gate.value.denial)
        assertEquals(1, f.merges)
    }

    @Test
    fun aRejectedPointDoesNotHideOtherOwnedConversations() = runTest {
        val f = LocalListFixture()
        f.pointResult =
            AppResult.Failed(
                Failure(
                    FailureKind.AccessDenied,
                    "Fixture resource denial",
                    code = "fixture.resource_denied",
                )
            )
        assertIs<AppResult.Failed>(f.refresh.conversation("selection", "direct"))
        assertNull(f.gate.value.denial)
        assertEquals(0, f.restricts)
        assertEquals(listOf(f.summary), f.observe().items)
    }

    @Test
    fun mismatchedPointCannotPublishAnotherConversation() = runTest {
        val f = LocalListFixture()
        assertIs<AppResult.Failed>(f.refresh.conversation("selection", "other"))
        assertEquals(0, f.merges)
        assertEquals(0, f.admits)
    }

    @Test
    fun latePointSuccessCannotPublishAfterCancellationOrAccountReplacement() = runTest {
        for (cancel in listOf(false, true)) {
            val f = LocalListFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeRemoteReturn = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val request = async { f.refresh.conversation("selection", "direct") }
            entered.await()
            if (cancel) request.cancel() else f.setup.session = null
            release.complete(Unit)
            if (cancel) request.join() else assertIs<AppResult.Failed>(request.await())
            assertEquals(0, f.merges)
            assertEquals(0, f.admits)
        }
    }

    @Test
    fun cancelledPointStillRetainsReceivedCompanyDenial() = runTest {
        val f = LocalListFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.pointResult = AppResult.Failed(f.denied)
        f.beforeRemoteReturn = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val request = async { f.refresh.conversation("selection", "direct") }
        entered.await()
        request.cancel()
        release.complete(Unit)
        request.join()
        assertEquals(f.denied, f.gate.value.denial)
        assertEquals(0, f.merges)
    }

    @Test
    fun localAccessObservationDoesNotReadBodiesOrRequireLiveCredentials() = runTest {
        val f = LocalListFixture()
        f.setup.now += 365.days
        val result =
            assertIs<AppResult.Success<CollaborationLocalAccess>>(f.access("selection").first())
        assertEquals("account", result.value.accountId)
        assertEquals(0, f.localReads)
        assertEquals(0, f.remoteCalls)
    }

    @Test
    fun localAccessKeepsObservingTheCompanyGateWithoutReadingRows() = runTest {
        val f = LocalListFixture()
        val values = mutableListOf<AppResult<CollaborationLocalAccess>>()
        val observation =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.access("selection").toList(values)
            }
        runCurrent()
        assertIs<AppResult.Success<*>>(values.last())
        f.gate.value = CollaborationListAdmission("denied", f.denied)
        runCurrent()
        assertEquals(f.denied, assertIs<AppResult.Failed>(values.last()).failure)
        f.gate.value = CollaborationListAdmission("reopened", null)
        runCurrent()
        assertIs<AppResult.Success<*>>(values.last())
        observation.cancelAndJoin()
        assertEquals(0, f.localReads)
        assertEquals(0, f.remoteCalls)
    }

    @Test
    fun expiredAuthorityRetainsOwnedLocalFactsWithoutAnyRequest() = runTest {
        val f = LocalListFixture()
        f.setup.now += 365.days
        assertEquals(listOf(f.summary), f.observe().items)
        assertEquals(1, f.workspaceSubscriptions)
        assertIs<AppResult.Failed>(f.refresh("selection"))
        assertEquals(0, f.remoteCalls)
        assertEquals(0, f.restricts)
        assertEquals(listOf(f.summary), f.observe().items)
    }

    @Test
    fun signedOutObservationNeverSubscribesToAnEmptyWorkspaceOwner() = runTest {
        val f = LocalListFixture()
        f.setup.session = null
        val failed =
            assertIs<AppResult.Failed>(
                f.observer("selection", flowOf(CollaborationListSelection())).first()
            )
        assertEquals(FailureKind.Unauthorized, failed.failure.kind)
        assertEquals(0, f.workspaceSubscriptions)
        assertEquals(0, f.localReads)
    }

    @Test
    fun newlyReceivedMaintenanceDenialSurvivesFailedPostRequestRead() = runTest {
        val f = LocalListFixture()
        val denied = Failure(FailureKind.Service, "Unavailable", code = "company_maintenance")
        f.remoteResult = AppResult.Failed(denied)
        f.beforeRemoteReturn = { f.setup.sessionFailure = f.storage }
        assertEquals(denied, assertIs<AppResult.Failed>(f.refresh("selection")).failure)
        assertEquals(denied, f.gate.value.denial)
        assertEquals(0, f.merges)
    }

    @Test
    fun wrongAccountCompanyAudienceAndDisabledPolicyCannotReadLocalFacts() = runTest {
        for (change in 0..3) {
            val f = LocalListFixture()
            when (change) {
                0 -> f.setup.session = f.setup.initial.copy(id = "replaced")
                1 -> f.setup.workspace = f.setup.selected.copy(id = "other-selection")
                2 ->
                    f.setup.session =
                        f.setup.initial.copy(
                            binding =
                                f.setup.initial.binding.copy(
                                    audience = "https://other.example.test/"
                                )
                        )
                3 -> f.setup.workspace = f.setup.selected.copy(permissions = emptySet())
            }
            assertIs<AppResult.Failed>(
                f.observer("selection", flowOf(CollaborationListSelection())).first()
            )
            assertEquals(0, f.localReads)
        }
    }

    @Test
    fun failedRefreshRetainsDenialUntilFreshSuccessfulAcquisition() = runTest {
        val f = LocalListFixture()
        f.remoteResult = AppResult.Failed(f.denied)
        assertEquals(f.denied, assertIs<AppResult.Failed>(f.refresh("selection")).failure)
        assertIs<AppResult.Failed>(
            f.observer("selection", flowOf(CollaborationListSelection())).first()
        )
        f.remoteResult = AppResult.Failed(f.network)
        assertEquals(f.denied, assertIs<AppResult.Failed>(f.refresh("selection")).failure)
        assertEquals(0, f.merges)
        f.remoteResult = AppResult.Success(CollaborationPage(listOf(f.summary), null))
        assertIs<AppResult.Success<*>>(f.refresh("selection"))
        assertNull(f.gate.value.denial)
        assertEquals(listOf(f.summary), f.observe().items)
        assertEquals(3, f.remoteCalls)
    }

    @Test
    fun confirmedDenialSurvivesFailedDurableWriteAndPostRequestSessionRead() = runTest {
        val f = LocalListFixture()
        f.restrictResult = AppResult.Failed(f.storage)
        f.remoteResult = AppResult.Failed(f.denied)
        f.beforeRemoteReturn = { f.setup.sessionFailure = f.storage }
        assertEquals(f.denied, assertIs<AppResult.Failed>(f.refresh("selection")).failure)
        assertEquals(f.denied, f.gate.value.denial)
        assertEquals(1, f.restricts)
        assertEquals(0, f.merges)
    }

    @Test
    fun maintenanceDenialRemainsAuthoritativeWhenRecoveryFails() = runTest {
        val f = LocalListFixture()
        val denied = Failure(FailureKind.Service, "Unavailable", code = "company_maintenance")
        f.remoteResult = AppResult.Failed(denied)
        assertEquals(denied, assertIs<AppResult.Failed>(f.refresh("selection")).failure)
        f.remoteResult = AppResult.Failed(f.network)
        assertEquals(denied, assertIs<AppResult.Failed>(f.refresh("selection")).failure)
        assertEquals(denied, f.gate.value.denial)
        assertEquals(0, f.admits)
    }

    @Test
    fun cancelledCallerStillPublishesAReceivedDenial() = runTest {
        val f = LocalListFixture()
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        f.remoteResult = AppResult.Failed(f.denied)
        f.beforeRemoteReturn = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                released.await()
            }
        }
        val request = async { f.refresh("selection") }
        entered.await()
        request.cancel()
        released.complete(Unit)
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(f.denied, f.gate.value.denial)
        assertEquals(0, f.merges)
    }

    @Test
    fun cancelledSuccessAndReplacedOwnerCannotPublishOrReopen() = runTest {
        for (cancel in listOf(false, true)) {
            val f = LocalListFixture()
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            f.beforeRemoteReturn = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    released.await()
                }
            }
            val request = async { f.refresh("selection") }
            entered.await()
            if (cancel) request.cancel()
            else f.setup.session = f.setup.initial.copy(id = "new-session")
            released.complete(Unit)
            if (cancel) request.join() else assertIs<AppResult.Failed>(request.await())
            assertEquals(0, f.merges)
            assertEquals(0, f.admits)
        }
    }

    @Test
    fun lateLocalWriteAndFailedWriteNeverClearAnExistingDenial() = runTest {
        for (conflict in listOf(false, true)) {
            val f = LocalListFixture()
            f.gate.value = CollaborationListAdmission("old", f.denied)
            f.mergeResult = if (conflict) AppResult.Success(false) else AppResult.Failed(f.storage)
            assertEquals(f.denied, assertIs<AppResult.Failed>(f.refresh("selection")).failure)
            assertEquals(0, f.admits)
            assertEquals(f.denied, f.gate.value.denial)
        }
    }

    @Test
    fun laterDenialWinsAgainstAnOlderSuccessfulAcquisition() = runTest {
        val f = LocalListFixture()
        val later = f.denied.copy(code = "mfa_required")
        f.beforeMerge = { f.gate.value = CollaborationListAdmission("later", later) }
        assertEquals(later, assertIs<AppResult.Failed>(f.refresh("selection")).failure)
        assertEquals(later, f.gate.value.denial)
    }

    @Test
    fun refreshSerializesMutableSummaryAcquisitionsWithoutImplicitPagination() = runTest {
        val f = LocalListFixture()
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        f.beforeRemoteReturn = {
            if (f.remoteCalls == 1) {
                entered.complete(Unit)
                released.await()
            }
        }
        val first = async { f.refresh("selection", "first", 12) }
        entered.await()
        val second = async { f.refresh("selection", "next", 12) }
        runCurrent()
        assertEquals(1, f.remoteCalls)
        released.complete(Unit)
        assertIs<AppResult.Success<*>>(first.await())
        assertIs<AppResult.Success<*>>(second.await())
        assertEquals<List<String?>>(listOf("first", "next"), f.cursors)
        assertEquals(2, f.merges)
    }

    @Test
    fun denialDisposesPendingLocalObservationAndSelectionNeverRequestsNetwork() = runTest {
        val f = LocalListFixture()
        val selection = MutableStateFlow(CollaborationListSelection())
        val values = mutableListOf<AppResult<CollaborationLocalList>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observer("selection", selection).toList(values)
            }
        runCurrent()
        assertEquals(1, f.localCollectors)
        selection.value = CollaborationListSelection("next", 12)
        runCurrent()
        assertEquals(1, f.localCollectors)
        f.gate.value = CollaborationListAdmission("denial", f.denied)
        runCurrent()
        assertEquals(0, f.localCollectors)
        assertEquals(f.denied, assertIs<AppResult.Failed>(values.last()).failure)
        f.gate.value = CollaborationListAdmission(null, null)
        runCurrent()
        assertEquals(1, f.localCollectors)
        job.cancelAndJoin()
        assertEquals(0, f.localCollectors)
        assertEquals(0, f.remoteCalls)
    }
}

private class LocalListFixture {
    val setup = CollaborationSetupFixture()
    val denied = Failure(FailureKind.AccessDenied, "Denied", code = "permission_denied")
    val network = Failure(FailureKind.Network, "Offline", code = "fixture.network")
    val storage = Failure(FailureKind.Storage, "Unavailable", code = "fixture.storage")
    val summary =
        CollaborationDirectSummary(
            CollaborationDirectConversation(
                "direct",
                "company",
                "account",
                "peer",
                "account",
                0,
                0,
            ),
            CollaborationParticipant("peer", "Fictional colleague"),
        )
    val gate = MutableStateFlow(CollaborationListAdmission(null, null))
    var remoteResult: AppResult<CollaborationPage<CollaborationDirectSummary>> =
        AppResult.Success(CollaborationPage(listOf(summary), null))
    var pointResult: AppResult<CollaborationDirectSummary> = AppResult.Success(summary)
    val pointCalls = mutableListOf<String>()
    val published = mutableListOf<List<CollaborationDirectSummary>>()
    var restrictResult: AppResult<Boolean> = AppResult.Success(true)
    var mergeResult: AppResult<Boolean> = AppResult.Success(true)
    var beforeRemoteReturn: suspend () -> Unit = {}
    var beforeMerge: suspend () -> Unit = {}
    var remoteCalls = 0
    var localReads = 0
    var localCollectors = 0
    var merges = 0
    var restricts = 0
    var admits = 0
    val cursors = mutableListOf<String?>()
    val replica =
        object : CollaborationListRepository {
            override suspend fun get(
                access: CollaborationLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationDirectSummary?> = error("No individual read")

            override fun observe(
                access: CollaborationLocalAccess,
                selection: CollaborationListSelection,
            ) = flow {
                localReads++
                localCollectors++
                try {
                    emit(AppResult.Success(CollaborationLocalList(null, listOf(summary), null)))
                    awaitCancellation()
                } finally {
                    localCollectors--
                }
            }

            override suspend fun checkpoint(access: CollaborationLocalAccess) =
                AppResult.Success(null)

            override suspend fun merge(
                access: CollaborationLocalAccess,
                expected: CollaborationListCheckpoint?,
                items: List<CollaborationDirectSummary>,
                acquiredAt: Instant,
            ): AppResult<Boolean> {
                merges++
                published += items.toList()
                beforeMerge()
                return mergeResult
            }

            override fun observeAdmission(access: CollaborationLocalAccess) =
                gate.map { AppResult.Success(it) }

            override suspend fun admission(access: CollaborationLocalAccess) =
                AppResult.Success(gate.value)

            override suspend fun restrict(
                access: CollaborationLocalAccess,
                failure: Failure,
            ): AppResult<Boolean> {
                restricts++
                gate.value = CollaborationListAdmission("denial-$restricts", failure)
                return restrictResult
            }

            override suspend fun admit(
                access: CollaborationLocalAccess,
                expectedRevision: String?,
            ): AppResult<Boolean> {
                admits++
                if (gate.value.revision != expectedRevision) return AppResult.Success(false)
                gate.value = CollaborationListAdmission(null, null)
                return AppResult.Success(true)
            }
        }
    val remote =
        object : CollaborationConversationRepository {
            override suspend fun summaries(
                owner: CollaborationOwner,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationPage<CollaborationDirectSummary>> {
                remoteCalls++
                cursors += after
                beforeRemoteReturn()
                return remoteResult
            }

            override suspend fun participants(
                owner: CollaborationOwner,
                query: String,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationPage<CollaborationParticipant>> = error("No discovery")

            override suspend fun list(
                owner: CollaborationOwner,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationPage<CollaborationDirectConversation>> = error("No raw list")

            override suspend fun get(
                owner: CollaborationOwner,
                conversationId: String,
            ): AppResult<CollaborationDirectConversation> = error("No detail")

            override suspend fun summary(
                owner: CollaborationOwner,
                conversationId: String,
            ): AppResult<CollaborationDirectSummary> {
                pointCalls += conversationId
                beforeRemoteReturn()
                return pointResult
            }

            override suspend fun room(
                owner: CollaborationOwner,
                conversationId: String,
            ): AppResult<CollaborationDirectRoom> = error("No room")

            override suspend fun open(
                owner: CollaborationOwner,
                command: CollaborationOpenDirect,
            ): AppResult<CollaborationSetupReceipt> = error("No command")

            override suspend fun requestRoom(
                owner: CollaborationOwner,
                command: CollaborationRequestDirectRoom,
            ): AppResult<CollaborationSetupReceipt> = error("No command")
        }
    var workspaceSubscriptions = 0
    private val workspaces =
        object : WorkspaceRepository by setup.workspaces {
            override fun observe(sessionId: String) =
                setup.workspaces.observe(sessionId).also {
                    require(sessionId.isNotBlank())
                    workspaceSubscriptions++
                }
        }
    private val server = IdentityServer(setup.initial.binding.audience, "Fictional Android")
    private val build = AppBuild(AppPlatform.Android, 5, "test", null)
    private val clock =
        object : Clock {
            override fun now() = setup.now
        }
    val refresh =
        RefreshCollaborationList(replica, remote, setup.sessions, workspaces, server, build, clock)
    val access =
        ObserveCollaborationAccess(replica, setup.sessions, workspaces, server, build, clock)
    val observer =
        ObserveCollaborationList(replica, setup.sessions, workspaces, server, build, clock)

    suspend fun observe() =
        assertIs<AppResult.Success<CollaborationLocalList>>(
                observer("selection", flowOf(CollaborationListSelection())).first()
            )
            .value
}
