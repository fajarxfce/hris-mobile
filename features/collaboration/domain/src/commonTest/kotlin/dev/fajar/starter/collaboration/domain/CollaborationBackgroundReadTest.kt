package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationBackgroundReadTest {
    @Test
    fun admittedBackgroundPagesAndPointsReuseExistingPublicationWithoutWorkspaceSelection() =
        runTest {
            val f = Fixture()
            // Subsequent operations can use the remaining grant; they do not acquire a new15s
            // grant.
            f.base.setup.now += 40.seconds
            assertIs<AppResult.Success<*>>(f.refresh(f.scope))
            assertIs<AppResult.Success<*>>(f.refresh.conversation(f.scope, "direct"))
            assertIs<AppResult.Success<*>>(f.read.summaries(f.scope))
            assertIs<AppResult.Success<*>>(f.read.summary(f.scope, "direct"))
            assertEquals(2, f.base.merges)
            assertEquals(listOf(listOf(f.summary), listOf(f.summary)), f.base.published)
            assertEquals(2, f.base.remoteCalls)
            assertEquals(listOf("direct", "direct"), f.base.pointCalls)
            assertNull(f.base.setup.workspace)
            assertEquals(f.owner, f.observedOwner)
        }

    @Test
    fun expiredBackwardMalformedOrForeignGrantsNeverReadRemoteData() = runTest {
        for (mode in 0..7) {
            val f = Fixture()
            when (mode) {
                0 -> f.base.setup.now += 60.seconds
                1 -> f.base.setup.now -= 1.seconds
                2 -> f.grant = f.grant.copy(expiresAt = f.grant.expiresAt + 1.seconds)
                3 ->
                    f.grant =
                        f.grant.copy(observation = f.grant.observation.copy(deviceVersion = -1))
                4 ->
                    f.grant =
                        f.grant.copy(
                            observation =
                                f.grant.observation.copy(
                                    owner = f.owner.copy(accountId = f.owner.companyId)
                                )
                        )
                5 -> f.base.setup.session = f.session.copy(id = "replacement")
                6 -> f.base.setup.session = null
                else ->
                    f.base.setup.session =
                        f.session.copy(
                            binding = f.session.binding.copy(serverId = f.owner.companyId)
                        )
            }
            assertIs<AppResult.Failed>(f.refresh(f.scope))
            assertIs<AppResult.Failed>(f.read.summary(f.scope, "direct"))
            assertEquals(0, f.base.remoteCalls)
            assertTrue(f.base.pointCalls.isEmpty())
            assertEquals(0, f.base.merges)
            assertEquals(0, f.base.admits)
        }
    }

    @Test
    fun expiryOrParentReplacementDuringIoCannotPublishOrReopenTheProjection() = runTest {
        for (expire in listOf(false, true)) for (readOnly in listOf(false, true)) {
            val f = Fixture()
            f.base.beforeRemoteReturn = {
                if (expire) f.base.setup.now += 60.seconds
                else f.base.setup.session = f.session.copy(id = "replacement")
            }
            assertIs<AppResult.Failed>(
                if (readOnly) f.read.summary(f.scope, "direct") else f.refresh(f.scope)
            )
            assertEquals(0, f.base.merges)
            assertEquals(0, f.base.admits)
        }
    }

    @Test
    fun olderDenialSurvivesFailedAcquisitionAndFreshContentAcquisitionCanReopenIt() = runTest {
        val f = Fixture()
        f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
        f.base.remoteResult = AppResult.Failed(f.base.network)
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.refresh(f.scope)).failure)
        assertEquals(f.base.denied, f.base.gate.value.denial)
        f.base.remoteResult = AppResult.Success(CollaborationPage(listOf(f.summary), null))
        assertIs<AppResult.Success<*>>(f.refresh(f.scope))
        assertNull(f.base.gate.value.denial)
        assertEquals(1, f.base.merges)
    }

    @Test
    fun concurrentDenialDuringPublicationWinsOverAnEarlierAdmittedPage() = runTest {
        val f = Fixture()
        f.base.beforeMerge = {
            f.base.gate.value = CollaborationListAdmission("newer-denial", f.base.denied)
        }
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.refresh(f.scope)).failure)
        assertEquals(f.base.denied, f.base.gate.value.denial)
        assertEquals(1, f.base.merges)
    }

    @Test
    fun receivedDenialStillPersistsAfterCancellationAndFailedCleanup() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.base.pointResult = AppResult.Failed(f.base.denied)
        f.base.restrictResult = AppResult.Failed(f.base.storage)
        f.base.beforeRemoteReturn = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val pending = async { f.refresh.conversation(f.scope, "direct") }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertEquals(f.base.denied, f.base.gate.value.denial)
        assertEquals(0, f.base.merges)
        assertEquals(0, f.base.admits)
    }

    @Test
    fun aLateMergeDoesNotAuthorizeAReadAfterTheCapturedGrantExpires() = runTest {
        val f = Fixture()
        f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
        f.base.beforeMerge = { f.base.setup.now += 60.seconds }
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.refresh(f.scope)).failure)
        assertEquals(1, f.base.merges)
        assertEquals(0, f.base.admits)
        assertEquals(f.base.denied, f.base.gate.value.denial)
    }

    private class Fixture {
        val base = LocalListFixture()
        val owner =
            CollaborationOwner(
                "local",
                base.setup.initial.binding.audience,
                "10000000-0000-4000-8000-000000000001",
                "20000000-0000-4000-8000-000000000001",
                "30000000-0000-4000-8000-000000000001",
            )
        val session =
            base.setup.initial.copy(
                user = base.setup.initial.user.copy(id = owner.accountId),
                binding = base.setup.initial.binding.copy(serverId = owner.nativeSessionId),
            )
        var grant =
            CollaborationBackgroundAdmission(
                CollaborationAdmissionObservation(
                    owner,
                    "40000000-0000-4000-8000-000000000001",
                    1,
                    0,
                    0,
                    base.setup.now,
                    base.setup.now + 60.seconds,
                ),
                base.setup.now,
                base.setup.now + 60.seconds,
            )
        val scope
            get() = CollaborationOperationScope.Background(grant)

        val summary =
            base.summary.copy(
                conversation =
                    base.summary.conversation.copy(
                        companyId = owner.companyId,
                        firstAccountId = owner.accountId,
                        createdBy = owner.accountId,
                    )
            )
        var observedOwner: CollaborationOwner? = null

        init {
            base.setup.session = session
            base.setup.workspace = null
            base.setup.beforeWorkspace = {
                error("Background work cannot read foreground selection")
            }
            base.remoteResult = AppResult.Success(CollaborationPage(listOf(summary), null))
            base.pointResult = AppResult.Success(summary)
        }

        private val remote =
            object : CollaborationConversationRepository by base.remote {
                override suspend fun summaries(
                    owner: CollaborationOwner,
                    after: String?,
                    limit: Int,
                ): AppResult<CollaborationPage<CollaborationDirectSummary>> {
                    assertEquals(this@Fixture.owner, owner)
                    observedOwner = owner
                    return base.remote.summaries(owner, after, limit)
                }

                override suspend fun summary(
                    owner: CollaborationOwner,
                    conversationId: String,
                ): AppResult<CollaborationDirectSummary> {
                    assertEquals(this@Fixture.owner, owner)
                    observedOwner = owner
                    return base.remote.summary(owner, conversationId)
                }
            }
        private val server = IdentityServer(owner.audience, "Fictional Android")
        private val build = AppBuild(AppPlatform.Android, 5, "test", null)
        private val clock =
            object : Clock {
                override fun now() = base.setup.now
            }
        val refresh =
            RefreshCollaborationList(
                base.replica,
                remote,
                base.setup.sessions,
                base.setup.workspaces,
                server,
                build,
                clock,
            )
        val read =
            ReadCollaborationConversations(
                base.setup.sessions,
                base.setup.workspaces,
                remote,
                server,
                build,
                clock,
                base.replica,
            )
    }
}
