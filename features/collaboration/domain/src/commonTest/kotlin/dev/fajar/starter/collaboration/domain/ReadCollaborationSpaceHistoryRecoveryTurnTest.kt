package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.nextCollaborationSpaceIncomingRetry
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationSpaceHistoryRecoveryTurnTest {
    @Test
    fun oneAccountRowRetainsHistoricalCompanyAndDeviceWithoutWrites() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        val before = f.snapshot
        val result = ready(f)
        assertEquals(f.runtime, result.runtime)
        assertEquals(f.base.source, result.source)
        assertEquals(f.base.registration, result.registration)
        assertEquals(f.page, result.page)
        assertEquals(f.recovery, result.recovery)
        assertEquals(listOf<Pair<String?, Int>>(null to 1), f.requests)
        assertEquals(3, f.calls.count { it.startsWith("registration") })
        assertEquals(before, f.snapshot)
        assertEquals(0, f.base.setup.inserts)
        assertTrue(f.base.setup.sent.isEmpty())
    }

    @Test
    fun anotherSpaceKeepsItsActualAccountMetadataOwner() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        val locator =
            CollaborationAccountIncomingHistoryLocator.Space(f.base.id(952), f.company.history)
        f.page = f.page.copy(locators = listOf(locator))
        assertEquals(listOf(locator), ready(f).page.locators)
        assertNotEquals(f.access.spaceId, locator.spaceId)
    }

    @Test
    fun internalMetadataCanAdvanceAnEmptyBoundedPage() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        f.history(f.recovery.copy(revision = 1, afterPartition = "history-before"))
        f.page = f.page.copy(locators = emptyList())
        val turn = ready(f)
        assertTrue(turn.page.locators.isEmpty())
        assertEquals("history-next", turn.page.next)
        assertEquals(listOf<Pair<String?, Int>>("history-before" to 1), f.requests)
    }

    @Test
    fun emptyEndPageIsCapturedWithoutAcknowledgingTheGlobalPass() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        f.page = CollaborationAccountIncomingHistoryLocatorPage(emptyList(), null)
        assertNull(ready(f).page.next)
        assertFalse(assertNotNull(f.snapshot?.demand?.historyRecovery).complete)
    }

    @Test
    fun absentRecoveryCreatesNeitherRegistrationReadNorMetadataWork() = runTest {
        for (absentSnapshot in listOf(false, true)) {
            val f = SpaceHistoryRecoveryTurnFixture()
            if (absentSnapshot) f.snapshot = null else f.history(null)
            assertEquals(
                AppResult.Success(CollaborationSpaceHistoryRecoverySelection.Idle),
                f.load(),
            )
            assertTrue(f.requests.isEmpty())
            assertFalse(f.calls.any { it.startsWith("registration") })
            assertEquals(0, f.base.sourceReads)
        }
    }

    @Test
    fun completedWaitingPassSleepsWithoutRestartingItsGeneration() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        f.history(f.recovery.copy(revision = 1, complete = true, unresolved = true))
        val before = f.snapshot
        assertEquals(AppResult.Success(CollaborationSpaceHistoryRecoverySelection.Idle), f.load())
        assertEquals(before, f.snapshot)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun futureOperationalRetryDoesNotReadMetadataOrRegistration() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        val retry =
            assertNotNull(
                nextCollaborationSpaceIncomingRetry(
                    null,
                    f.base.native.setup.now.toEpochMilliseconds(),
                )
            )
        f.history(f.recovery.copy(revision = 1, retry = retry))
        assertEquals(
            AppResult.Success(CollaborationSpaceHistoryRecoverySelection.Deferred(retry)),
            f.load(),
        )
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.base.sourceReads)
    }

    @Test
    fun dueRetryAndBackwardsClockAllowOneTurn() = runTest {
        for (offset in listOf(-1L, 30_000L)) {
            val f = SpaceHistoryRecoveryTurnFixture()
            val retry =
                assertNotNull(
                    nextCollaborationSpaceIncomingRetry(
                        null,
                        f.base.native.setup.now.toEpochMilliseconds(),
                    )
                )
            f.history(f.recovery.copy(revision = 1, retry = retry))
            f.base.native.setup.now += offset.milliseconds
            assertEquals(retry, ready(f).recovery.retry)
            assertEquals(1, f.requests.size)
        }
    }

    @Test
    fun invalidRuntimeStopsBeforeLocalOrNativeReads() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        for (runtime in
            listOf(
                f.runtime.copy(deviceId = "invalid"),
                f.runtime.copy(operationId = "00000000-0000-0000-0000-000000000000"),
            )) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.reader(runtime)).failure.kind,
            )
        }
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.base.sourceReads)
    }

    @Test
    fun unavailableLocalOwnerOrDeniedAccessCannotReadAccountRootOrWork() = runTest {
        for (change in listOf("account", "native", "denial")) {
            val f = SpaceHistoryRecoveryTurnFixture()
            when (change) {
                "account" -> f.base.native.setup.session = null
                "native" -> {
                    val old = assertNotNull(f.base.native.setup.session)
                    f.base.native.setup.session =
                        old.copy(binding = old.binding.copy(serverId = f.base.id(965)))
                }
                else -> f.base.directory.deny()
            }
            val failed = assertIs<AppResult.Failed>(f.load())
            if (change == "denial") assertEquals(f.base.directory.denied, failed.failure)
            assertTrue(f.calls.isEmpty())
            assertTrue(f.requests.isEmpty())
            assertEquals(0, f.base.sourceReads)
        }
    }

    @Test
    fun malformedRecoveryIsAnErrorRatherThanIdleOrEndOfHistory() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        f.history(f.recovery.copy(revision = 0, afterPartition = "bad"))
        assertIs<AppResult.Failed>(f.load())
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun malformedAndOversizedMetadataCannotEscapeTheReadBoundary() = runTest {
        val seed = SpaceHistoryRecoveryTurnFixture()
        val invalid =
            listOf(
                listOf(seed.company, seed.company),
                listOf(seed.company.copy(companyId = "invalid")),
                listOf(
                    seed.company.copy(
                        history =
                            seed.company.history.copy(
                                deviceId = "00000000-0000-0000-0000-000000000000"
                            )
                    )
                ),
                listOf(
                    CollaborationAccountIncomingHistoryLocator.Space(
                        "invalid",
                        seed.company.history,
                    )
                ),
                listOf(
                    seed.company.copy(
                        history = seed.company.history.copy(conversationId = "invalid")
                    )
                ),
            )
        for (locators in invalid) {
            val f = SpaceHistoryRecoveryTurnFixture()
            f.page = f.page.copy(locators = locators)
            assertIs<AppResult.Failed>(f.load())
            assertEquals(1, f.requests.size)
        }
    }

    @Test
    fun nonAdvancingAndInvalidCursorsAreNotExhaustion() = runTest {
        for (next in listOf("history-before", "aaa", "", "x".repeat(161), "bad\n")) {
            val f = SpaceHistoryRecoveryTurnFixture()
            f.history(f.recovery.copy(revision = 1, afterPartition = "history-before"))
            f.page = f.page.copy(next = next)
            assertIs<AppResult.Failed>(f.load())
        }
    }

    @Test
    fun returnedLocatorListIsCopiedBeforeAnyLaterSuspension() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        val original = f.page.locators.toMutableList()
        f.page = f.page.copy(locators = original)
        f.hooks["registration2"] = { original.clear() }
        assertEquals(listOf(f.company), ready(f).page.locators)
        assertTrue(original.isEmpty())
    }

    @Test
    fun concurrentPrimaryWorkDoesNotInvalidateAnUnchangedRecoveryTurn() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        f.hooks["locators1"] = {
            val old = assertNotNull(f.snapshot)
            f.snapshot =
                old.copy(generation = f.base.id(959), demand = old.demand.copy(rooms = emptyList()))
        }
        assertEquals(f.recovery, ready(f).recovery)
        assertTrue(assertNotNull(f.snapshot).demand.rooms.isEmpty())
    }

    @Test
    fun newerRequestedGenerationIsPreservedWithoutRestartingTheTurn() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        f.hooks["locators1"] = { f.history(f.recovery.copy(requestedGeneration = f.base.id(960))) }
        val captured = ready(f).recovery
        assertEquals(f.generation, captured.generation)
        assertEquals(f.base.id(960), captured.requestedGeneration)
    }

    @Test
    fun changedCursorRevisionGenerationOrCompletionInvalidatesTheCapturedRead() = runTest {
        val seed = SpaceHistoryRecoveryTurnFixture()
        for (next in
            listOf(
                seed.recovery.copy(revision = 2),
                seed.recovery.copy(revision = 1, afterPartition = "later"),
                seed.recovery.copy(generation = seed.base.id(961)),
                seed.recovery.copy(revision = 1, complete = true),
            )) {
            val f = SpaceHistoryRecoveryTurnFixture()
            f.hooks["locators1"] = { f.history(next) }
            assertIs<AppResult.Failed>(f.load())
        }
    }

    @Test
    fun disappearingRequestedGenerationIsNotACompatibleConcurrentWrite() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        f.history(f.recovery.copy(requestedGeneration = f.base.id(962)))
        f.hooks["locators1"] = { f.history(f.recovery) }
        assertIs<AppResult.Failed>(f.load())
    }

    @Test
    fun missingInstalledSourceOrRegistrationCannotPublishMetadata() = runTest {
        for (missing in listOf("source", "registration")) {
            val f = SpaceHistoryRecoveryTurnFixture()
            if (missing == "source") f.base.installed = null else f.base.registration = null
            assertIs<AppResult.Failed>(f.load())
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test
    fun lateRootAccountNativeSourceRegistrationAndDenialInvalidateEveryContentRead() = runTest {
        for (stage in
            listOf("registration1", "locators1", "registration2", "demand2", "registration3")) {
            for (change in
                listOf("root", "account", "native", "source", "registration", "denial")) {
                val f = SpaceHistoryRecoveryTurnFixture()
                f.hooks[stage] = {
                    when (change) {
                        "root" -> f.root = CollaborationSpaceRoot(f.base.id(963))
                        "account" -> f.base.native.setup.session = null
                        "native" -> {
                            val old = assertNotNull(f.base.native.setup.session)
                            f.base.native.setup.session =
                                old.copy(binding = old.binding.copy(serverId = f.base.id(964)))
                        }
                        "source" -> f.base.installed = f.base.source.copy(matrixDeviceId = "OTHER")
                        "registration" -> f.base.registration = null
                        else -> f.base.directory.deny()
                    }
                }
                val failed = assertIs<AppResult.Failed>(f.load())
                if (change == "denial") assertEquals(f.base.directory.denied, failed.failure)
                assertEquals(1, f.calls.count { it == stage })
            }
        }
    }

    @Test
    fun localStorageFailuresRemainFailuresAndConfirmedDenialWins() = runTest {
        for (stage in
            listOf(
                "root1",
                "demand1",
                "registration1",
                "locators1",
                "registration2",
                "demand2",
                "registration3",
            )) {
            for (deny in listOf(false, true)) {
                val f = SpaceHistoryRecoveryTurnFixture()
                f.failures[stage] = f.base.directory.storageFailure
                if (deny) f.hooks[stage] = { f.base.directory.deny() }
                val failed = assertIs<AppResult.Failed>(f.load())
                assertEquals(
                    if (deny) f.base.directory.denied else f.base.directory.storageFailure,
                    failed.failure,
                )
            }
        }
    }

    @Test
    fun allSuspendingReadsShareOneBoundedDeadlineAndDoNotRetry() = runTest {
        val seed = SpaceHistoryRecoveryTurnFixture()
        ready(seed)
        for (stage in seed.calls) {
            val f = SpaceHistoryRecoveryTurnFixture()
            f.hooks[stage] = { awaitCancellation() }
            val before = testScheduler.currentTime
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.load()).failure.kind)
            assertEquals(5_000L, testScheduler.currentTime - before)
            assertEquals(1, f.calls.count { it == stage })
        }
    }

    @Test
    fun callerCancellationCannotPublishAReadAfterDisposal() = runTest {
        val seed = SpaceHistoryRecoveryTurnFixture()
        ready(seed)
        for (stage in seed.calls) {
            val f = SpaceHistoryRecoveryTurnFixture()
            val entered = CompletableDeferred<Unit>()
            f.hooks[stage] = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val task = async { f.load() }
            entered.await()
            task.cancelAndJoin()
            assertTrue(task.isCancelled)
            assertEquals(stage, f.calls.last())
        }
    }

    @Test
    fun expiredTransportCredentialsDoNotHideOwnedLocalRecovery() = runTest {
        val f = SpaceHistoryRecoveryTurnFixture()
        f.base.native.setup.now += 90.days
        assertEquals(f.page, ready(f).page)
        assertEquals(0, f.base.setup.inserts)
    }

    private suspend fun ready(
        f: SpaceHistoryRecoveryTurnFixture
    ): CollaborationSpaceHistoryRecoveryTurn =
        assertIs<CollaborationSpaceHistoryRecoverySelection.Ready>(
                assertIs<AppResult.Success<*>>(f.load()).value
            )
            .turn
}
