@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceOriginalConversationRepository
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceOriginalConversations
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationOriginalConversationReadTest {
    @Test
    fun noCompanySelectionIsNeededAndTerminalPagesNeverScheduleMoreReads() = runTest {
        val f = Fixture()
        f.native.setup.workspace = null
        assertEquals(AppResult.Success(f.page), f.read().list(f.owner))
        assertEquals(AppResult.Success(f.item), f.read().get(f.owner, f.position))
        advanceTimeBy(60_000)
        assertEquals(2, f.calls)
        assertEquals(0, f.directory.admits)
        assertEquals(0, f.directory.begins)
    }

    @Test
    fun invalidOwnerCoordinatesAndLimitsNeverCallTheRepository() = runTest {
        val f = Fixture()
        val nil = "00000000-0000-0000-0000-000000000000"
        for (value in
            listOf(
                f.owner.copy(spaceId = nil),
                f.owner.copy(parent = f.owner.parent.copy(nativeSessionId = nil)),
            )) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.read().list(value)).failure.kind,
            )
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.read().get(value, f.position)).failure.kind,
            )
        }
        assertIs<AppResult.Failed>(f.read().get(f.owner, f.position.copy(companyId = nil)))
        assertIs<AppResult.Failed>(f.read().list(f.owner, f.position.copy(conversationId = "bad")))
        for (limit in listOf(0, 51)) assertIs<AppResult.Failed>(
            f.read().list(f.owner, limit = limit)
        )
        assertEquals(0, f.calls)
    }

    @Test
    fun repositoryValuesCannotChangeTheOriginalScopeOrContinueAnExhaustedPage() = runTest {
        val f = Fixture()
        f.item =
            f.item.copy(
                conversation =
                    f.item.conversation.copy(
                        position = f.position.copy(companyId = f.owner.spaceId)
                    )
            )
        assertEquals(
            "client.collaboration_invalid_response",
            assertIs<AppResult.Failed>(f.read().get(f.owner, f.position)).failure.code,
        )
        f.page = f.page.copy(next = f.position)
        assertEquals(
            "client.collaboration_invalid_response",
            assertIs<AppResult.Failed>(f.read().list(f.owner)).failure.code,
        )
        f.page = f.page.copy(items = List(31) { f.item }, next = null)
        assertIs<AppResult.Failed>(f.read().list(f.owner))
        f.page =
            f.page.copy(
                items = emptyList(),
                owner = f.owner.copy(spaceId = f.owner.parent.accountId),
            )
        assertIs<AppResult.Failed>(f.read().list(f.owner))
    }

    @Test
    fun pendingAccountReplacementSuppressesBothListAndExactResults() = runTest {
        for (list in listOf(false, true)) {
            val f = Fixture()
            f.native.setup.session = null
            assertIs<AppResult.Failed>(f.call(list))
            assertEquals(0, f.calls)
            f.native.setup.session = f.native.session
            f.beforeReply = { f.native.setup.session = null }
            assertIs<AppResult.Failed>(f.call(list))
            assertEquals(1, f.calls)
        }
    }

    @Test
    fun originalHardDeadlineWallRollbackAndMonotonicBudgetWinOverRenewedSession() = runTest {
        for (list in listOf(false, true)) for (variant in 0..2) {
            val f = Fixture()
            f.native.setup.session =
                f.native.session.copy(
                    binding =
                        f.native.session.binding.copy(
                            expiresAtEpochMillis =
                                (f.native.setup.now + 2.seconds).toEpochMilliseconds()
                        )
                )
            f.beforeReply = {
                when (variant) {
                    0 -> {
                        f.native.setup.now += 3.seconds
                        f.native.setup.session = f.native.session
                    }
                    1 -> f.native.setup.now -= 1.seconds
                    else -> f.native.time += 2.seconds
                }
            }
            assertEquals(
                "client.collaboration_revalidation_required",
                assertIs<AppResult.Failed>(f.call(list)).failure.code,
            )
        }
    }

    @Test
    fun cancellationDrainsPendingAcquisitionAndCannotPublishNonCooperativeLateSuccess() = runTest {
        for (list in listOf(false, true)) {
            val f = Fixture()
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            f.beforeReply = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
            val task = async { f.call(list) }
            entered.await()
            task.cancelAndJoin()
            stopped.await()
            assertTrue(task.isCancelled)
            f.beforeReply = { withContext(NonCancellable) { delay(20_000) } }
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.call(list)).failure.kind)
        }
    }

    @Test
    fun confirmedDenialSurvivesCancellationAndFailedPersistenceWithoutSuccessfulReadReopeningGate() =
        runTest {
            for (list in listOf(false, true)) {
                val f = Fixture()
                f.directory.afterRestrict = { AppResult.Failed(f.directory.storageFailure) }
                f.failure = f.directory.denied
                f.beforeReply = { currentCoroutineContext().cancel() }
                val task = async { f.call(list) }
                task.join()
                assertTrue(task.isCancelled)
                assertEquals(1, f.directory.restrictions)
                f.beforeReply = {}
                f.failure = f.directory.unavailable
                assertEquals(f.directory.denied, assertIs<AppResult.Failed>(f.call(list)).failure)
                f.failure = null
                assertIs<AppResult.Success<*>>(f.call(list))
                assertEquals(
                    f.directory.denied,
                    assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                            f.directory.gate.value
                        )
                        .value
                        .denial,
                )
                assertEquals(0, f.directory.admits)
                assertEquals(0, f.directory.begins)
            }
        }

    @Test
    fun finalAdmissionDenialAccountReplacementOrBudgetExpirySuppressesCompletedResult() = runTest {
        for (list in listOf(false, true)) for (variant in 0..2) {
            val f = Fixture()
            var reads = 0
            f.directory.beforeAdmission = {
                if (++reads == 2)
                    when (variant) {
                        0 -> f.directory.deny()
                        1 -> f.native.setup.session = null
                        else -> f.native.time += 15.seconds
                    }
            }
            assertIs<AppResult.Failed>(f.call(list))
            assertEquals(1, f.calls)
            assertEquals(0, f.directory.admits)
        }
    }

    @Test
    fun finalAdmissionCannotMutateTheCapturedRepositoryPage() = runTest {
        val f = Fixture()
        val shared = mutableListOf(f.item)
        f.page = f.page.copy(items = shared)
        var reads = 0
        f.directory.beforeAdmission = { if (++reads == 2) shared.clear() }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceOriginalConversationPage>>(
                    f.read().list(f.owner)
                )
                .value
        assertTrue(shared.isEmpty())
        assertEquals(listOf(f.item), result.items)
    }

    private class Fixture {
        val directory = SpaceDirectoryUseCaseFixture()
        val native = directory.native
        val owner = native.owner
        val position =
            CollaborationOriginalConversationPosition(
                "50000000-0000-4000-8000-000000000001",
                "60000000-0000-4000-8000-000000000001",
            )
        var item =
            CollaborationSpaceOriginalConversation(
                owner,
                CollaborationOriginalConversation(
                    position,
                    owner.parent.accountId,
                    "80000000-0000-4000-8000-000000000001",
                    owner.parent.accountId,
                    native.setup.now - 60.seconds,
                    0,
                ),
                null,
            )
        var page = CollaborationSpaceOriginalConversationPage(owner, listOf(item), null)
        var calls = 0
        var failure: Failure? = null
        var beforeReply: suspend () -> Unit = {}
        val originals =
            object : CollaborationSpaceOriginalConversationRepository {
                override suspend fun get(
                    owner: CollaborationSpaceOwner,
                    position: CollaborationOriginalConversationPosition,
                ): AppResult<CollaborationSpaceOriginalConversation> {
                    check(owner == this@Fixture.owner && position == this@Fixture.position)
                    calls++
                    beforeReply()
                    return failure?.let { AppResult.Failed(it) } ?: AppResult.Success(item)
                }

                override suspend fun list(
                    owner: CollaborationSpaceOwner,
                    after: CollaborationOriginalConversationPosition?,
                    limit: Int,
                ): AppResult<CollaborationSpaceOriginalConversationPage> {
                    check(owner == this@Fixture.owner && after == null && limit == 30)
                    calls++
                    beforeReply()
                    return failure?.let { AppResult.Failed(it) } ?: AppResult.Success(page)
                }
            }

        fun read() =
            ReadCollaborationSpaceOriginalConversations(
                originals,
                directory.directory,
                native.setup.sessions,
                native.server,
                AppBuild(AppPlatform.Android, 5, "test", null),
                native.clock,
                native.time,
            )

        suspend fun call(list: Boolean): AppResult<*> =
            if (list) read().list(owner) else read().get(owner, position)
    }
}
