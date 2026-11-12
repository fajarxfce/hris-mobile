package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationSpaceKeyReferencesTest {
    @Test
    fun spaceAndOriginalAliasReadSixteenRowsWithoutCompanyOrPrimaryFallback() = runTest {
        for (alias in listOf(false, true)) {
            val f = SpaceKeyReferenceReadFixture()
            if (alias) f.originalAlias()
            f.progress = requireNotNull(f.progress).copy(references = 20)
            f.events = List(16, f.original::reference)
            f.after = "cursor-15"
            val page = f.pending()
            assertEquals(16, page.events.size)
            assertEquals("cursor-15", page.after)
            assertEquals(listOf<Pair<String?, Int>>(null to 16), f.requests)
            assertEquals(1, f.writes)
            assertNull(f.base.retained)
            assertNull(f.base.snapshot)
            assertFalse(
                f.provider.calls.any {
                    it in setOf("metadata", "demand", "transport", "page", "event")
                }
            )
        }
    }

    @Test
    fun historicalSpaceProviderAndCurrentSdkDevicesRemainDistinct() = runTest {
        val f = SpaceKeyReferenceReadFixture()
        val initial = requireNotNull(f.progress)
        f.progress =
            initial.copy(
                provider = requireNotNull(initial.provider).copy(companyId = f.base.id(952))
            )
        val before = requireNotNull(f.progress).provider
        val page = f.pending()
        assertEquals(before, page.progress?.provider)
        assertEquals(f.base.id(950), f.keyHistory.traversal.deviceId)
        assertEquals(f.base.id(951), page.progress?.provider?.deviceId)
        assertNotEquals(f.base.setup.sdkDevice, f.keyHistory.traversal.deviceId)
        assertNotEquals(f.base.setup.sdkDevice, page.progress?.provider?.deviceId)
    }

    @Test
    fun absentHistoryDoesNotCreateProgressOrReadPendingRows() = runTest {
        val f = SpaceKeyReferenceReadFixture()
        f.progress = null
        assertIs<CollaborationHistoryRecoveryRead.Absent>(
            assertIs<AppResult.Success<*>>(f.run()).value
        )
        assertEquals(0, f.writes)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun theSameCompletedGenerationReturnsWithoutAnotherWriteOrPageRead() = runTest {
        val f = SpaceKeyReferenceReadFixture()
        f.progress =
            requireNotNull(f.progress)
                .copy(
                    recovery =
                        CollaborationHistoryRecoveryPosition(f.generation, 2, complete = true)
                )
        assertIs<CollaborationHistoryRecoveryRead.Complete>(
            assertIs<AppResult.Success<*>>(f.run()).value
        )
        assertEquals(0, f.writes)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun replayOfAnActiveRequestDoesNotResetItsPageOrWriteAgain() = runTest {
        val f = SpaceKeyReferenceReadFixture()
        val first = f.pending()
        assertEquals(first, f.pending())
        assertEquals(1, f.writes)
        assertEquals(2, f.requests.size)
    }

    @Test
    fun lostAndFalseSaveRepliesRequireExactReadback() = runTest {
        for (lost in listOf(true, false)) for (committed in listOf(true, false)) {
            val f = SpaceKeyReferenceReadFixture()
            f.commit = committed
            f.response =
                if (lost) AppResult.Failed(f.base.directory.storageFailure)
                else AppResult.Success(false)
            if (committed) assertEquals(f.generation, f.pending().progress?.recovery?.generation)
            else assertIs<AppResult.Failed>(f.run())
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun providerReplacementDuringRequestConfirmationIsRejected() = runTest {
        val f = SpaceKeyReferenceReadFixture()
        f.hooks["replaced"] = {
            f.progress =
                requireNotNull(f.progress)
                    .copy(
                        provider =
                            CollaborationHistoryProviderIdentity(
                                f.base.position.companyId,
                                f.base.id(999),
                            )
                    )
        }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(1, f.writes)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun aPageFromAnotherProviderCannotBorrowTheConfirmedCheckpoint() = runTest {
        val f = SpaceKeyReferenceReadFixture()
        f.hooks["pending"] = {
            f.page =
                CollaborationHistoryReferences(
                    requireNotNull(f.progress)
                        .copy(
                            provider =
                                CollaborationHistoryProviderIdentity(
                                    f.base.position.companyId,
                                    f.base.id(999),
                                )
                        ),
                    f.events,
                    null,
                )
        }
        assertIs<AppResult.Failed>(f.run())
        assertEquals(1, f.requests.size)
    }

    @Test
    fun companyProgressWithoutASpaceProviderIsRejected() = runTest {
        val f = SpaceKeyReferenceReadFixture()
        f.progress = requireNotNull(f.progress).copy(provider = null)
        assertIs<AppResult.Failed>(f.run())
        assertEquals(0, f.writes)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun primaryAppendsPreserveTheirProviderAndTheCapturedFiniteBoundary() = runTest {
        val f = SpaceKeyReferenceReadFixture()
        val before = requireNotNull(f.progress).provider
        f.hooks["replaced"] = {
            val current = requireNotNull(f.progress)
            f.progress = current.copy(revision = current.revision + 4, references = 5)
        }
        val page = f.pending()
        assertEquals(5L, page.progress?.references)
        assertEquals(2L, page.progress?.recovery?.through)
        assertEquals(before, page.progress?.provider)
    }

    @Test
    fun anEmptyFlowIsFailureRatherThanAnEmptyOrCompletedHistory() = runTest {
        val f = SpaceKeyReferenceReadFixture()
        f.emptyFlow = true
        assertIs<AppResult.Failed>(f.run())
        assertEquals(1, f.requests.size)
    }

    @Test
    fun expiredPreparationDoesNotPreventReadingOwnedLocalReferences() = runTest {
        val f = SpaceKeyReferenceReadFixture()
        f.base.native.setup.now += 365.days
        assertEquals(2, f.pending().events.size)
    }

    @Test
    fun denialStopsBeforeBindingAndHistoryReads() = runTest {
        val f = SpaceKeyReferenceReadFixture()
        f.base.directory.deny()
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertTrue(f.calls.isEmpty())
        assertTrue(f.provider.calls.isEmpty())
    }

    @Test
    fun sourceAccountSessionAndDenialChangesFenceEachSuspendingStage() = runTest {
        for (stage in listOf("binding", "progress", "replace", "replaced", "pending")) {
            for (change in listOf("source", "account", "session", "denial")) {
                val f = SpaceKeyReferenceReadFixture()
                f.hooks[stage] = {
                    when (change) {
                        "source" -> f.base.installed = null
                        "account" -> f.base.native.setup.session = null
                        "session" -> {
                            val value = requireNotNull(f.base.native.setup.session)
                            f.base.native.setup.session =
                                value.copy(binding = value.binding.copy(serverId = f.base.id(998)))
                        }
                        else -> f.base.directory.deny()
                    }
                }
                val result = assertIs<AppResult.Failed>(f.run(), "$stage/$change")
                if (change == "denial") assertEquals(f.base.directory.denied, result.failure)
                assertTrue(f.writes <= 1)
            }
        }
    }

    @Test
    fun cancellationAndStalledReadsCannotAcknowledgeOrLoop() = runTest {
        for (stage in listOf("binding", "progress", "replace", "pending")) {
            val f = SpaceKeyReferenceReadFixture()
            f.hooks[stage] = { awaitCancellation() }
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertEquals(1, f.calls.count { it == stage })
        }
        val f = SpaceKeyReferenceReadFixture()
        val entered = CompletableDeferred<Unit>()
        f.hooks["pending"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.run() }
        entered.await()
        job.cancelAndJoin()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(1, f.requests.size)
    }

    @Test
    fun missingBindingOrRegistrationPreventsHistoryReads() = runTest {
        for (binding in listOf(true, false)) {
            val f = SpaceKeyReferenceReadFixture()
            if (binding) f.binding = null else f.base.registration = null
            assertIs<AppResult.Failed>(f.run())
            assertEquals(0, f.progressReads)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun bindingAndRegistrationReplacementCannotPublishAStalePage() = runTest {
        for (stage in listOf("progress", "replaced", "pending")) for (binding in
            listOf(true, false)) {
            val f = SpaceKeyReferenceReadFixture()
            f.hooks[stage] = {
                if (binding) {
                    val before = requireNotNull(f.binding)
                    f.binding =
                        before.copy(
                            conversation =
                                before.conversation.copy(version = before.conversation.version + 1)
                        )
                } else {
                    val before = requireNotNull(f.base.registration)
                    f.base.registration = before.copy(revision = before.revision + 1)
                }
            }
            assertIs<AppResult.Failed>(f.run(), "$stage/$binding")
            assertTrue(f.writes <= 1)
        }
    }

    @Test
    fun localReadFailuresKeepTheirClassificationAndDoNotRetry() = runTest {
        for (stage in listOf("binding", "progress", "pending")) {
            val f = SpaceKeyReferenceReadFixture()
            f.failures[stage] = f.base.directory.storageFailure
            assertEquals(
                f.base.directory.storageFailure,
                assertIs<AppResult.Failed>(f.run()).failure,
            )
            assertEquals(1, f.calls.count { it == stage })
        }
    }

    @Test
    fun anUnrelatedSpaceOrHistoricalRoomFailsBeforeAnyRead() = runTest {
        for (mode in 0..2) {
            val f = SpaceKeyReferenceReadFixture()
            val wrong =
                when (mode) {
                    0 ->
                        f.keyHistory.copy(
                            traversal = f.keyHistory.traversal.copy(conversationId = f.base.id(999))
                        )
                    1 ->
                        f.keyHistory.copy(
                            traversal = f.keyHistory.traversal.copy(roomId = "!other:example.test")
                        )
                    else ->
                        f.keyHistory.copy(
                            binding =
                                f.keyHistory.binding.copy(
                                    conversation =
                                        f.keyHistory.binding.conversation.copy(
                                            spaceId = f.base.id(999)
                                        )
                                )
                        )
                }
            assertIs<AppResult.Failed>(f.read(f.base.runtime, wrong, f.generation))
            assertTrue(f.calls.isEmpty())
            assertTrue(f.provider.calls.isEmpty())
        }
    }
}
