package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationHistoryProgress
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CompleteCollaborationSpaceKeyReferencesTest {
    @Test
    fun originalAliasCompletesOnlyTheLogicalSpaceTraversal() = runTest {
        val f = fixture()
        f.reference.originalAlias()
        val result = success(f)
        assertEquals(f.reference.generation, result.generation)
        assertEquals(f.reference.conversationId, f.history.traversal.conversationId)
        assertNotEquals(f.base.position.conversationId, f.history.traversal.conversationId)
        assertNull(f.base.retained)
        assertEquals(1, f.attempts.size)
        assertFalse(f.provider.calls.contains("metadata"))
    }

    @Test
    fun retainedProviderCoordinatesDoNotBecomeTheCurrentOriginalSdk() = runTest {
        val f = fixture()
        val retained = CollaborationHistoryProviderIdentity(f.base.id(995), f.base.id(996))
        f.page = f.page.copy(progress = f.checkpoint.copy(provider = retained))
        assertNotEquals(f.base.position.companyId, retained.companyId)
        assertNotEquals(f.provider.traversal.deviceId, retained.deviceId)
        success(f)
        assertEquals(retained, f.attempts.single().progress?.provider)
    }

    @Test
    fun companyProgressCannotAuthorizeSpaceRecovery() = runTest {
        val f = fixture()
        f.page = f.page.copy(progress = f.checkpoint.copy(provider = null))
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.attempts.isEmpty())
        assertTrue(f.reference.calls.isEmpty())
        assertTrue(f.provider.calls.isEmpty())
    }

    private fun fixture(count: Int = 2) =
        SpaceKeyCompletionFixture().also { f ->
            val through = maxOf(1, count).toLong()
            f.page =
                f.page.copy(
                    progress =
                        f.checkpoint.copy(
                            references = through,
                            recovery = requireNotNull(f.checkpoint.recovery).copy(through = through),
                        ),
                    events = List(count, f.reference.original::reference),
                )
            f.items =
                f.page.events.map {
                    CollaborationIncomingHistoryResult(
                        it.event.eventId,
                        CollaborationIncomingHistoryOutcome.Retained(
                            listOf(it.event.eventId),
                            emptyList(),
                        ),
                    )
                }
        }

    private suspend fun success(f: SpaceKeyCompletionFixture) =
        assertIs<AppResult.Success<CollaborationHistoryRecoveryPageCompletion>>(f.run()).value

    @Test
    fun completesSixteenReferencesWithOneScopedAtomicCall() = runTest {
        val f = fixture(16)
        val result = success(f)
        assertEquals(16, result.resolved)
        assertEquals(0, result.unresolved)
        assertEquals(f.reference.generation, result.generation)
        assertNull(result.after)
        assertEquals(listOf(f.page), f.attempts)
        assertEquals(listOf(f.page.events), f.subsets)
        assertTrue(f.committed)
        assertNotEquals(f.provider.traversal.deviceId, f.history.traversal.deviceId)
        assertNull(f.base.snapshot)
        assertFalse(
            f.provider.calls.any {
                it in setOf("demand", "event", "append", "transport", "settle", "progress")
            }
        )
    }

    @Test
    fun retainedAndUnsupportedResolveWhileKeyAndTargetWaitsRemainPending() = runTest {
        val f = fixture(6)
        val outcomes =
            listOf(
                CollaborationIncomingHistoryOutcome.Retained(
                    listOf(f.items[0].sourceEventId),
                    emptyList(),
                ),
                CollaborationIncomingHistoryOutcome.Retained(
                    emptyList(),
                    listOf(f.items[1].sourceEventId),
                ),
                CollaborationIncomingHistoryOutcome.Unsupported,
                CollaborationIncomingHistoryOutcome.AwaitDecryption(
                    "\$key",
                    CollaborationEventDecryptionFailure.UnableToDecrypt,
                ),
                CollaborationIncomingHistoryOutcome.AwaitRedaction("\$redaction"),
                CollaborationIncomingHistoryOutcome.MissingOriginal("\$original"),
            )
        f.items = f.items.zip(outcomes) { item, outcome -> item.copy(outcome = outcome) }
        val result = success(f)
        assertEquals(3, result.resolved)
        assertEquals(3, result.unresolved)
        assertEquals(f.page.events.take(3), f.subsets.single())
        assertEquals(1, f.attempts.size)
    }

    @Test
    fun anAdmittedEmptyPageStillCompletesItsAttemptedBoundary() = runTest {
        val f = fixture(0)
        val result = success(f)
        assertEquals(0, result.resolved)
        assertEquals(0, result.unresolved)
        assertEquals(f.reference.generation, result.generation)
        assertTrue(f.subsets.single().isEmpty())
        assertTrue(f.reference.calls.contains("binding"))
    }

    @Test
    fun aSourceRedactionMayRetainItsTargetInsteadOfItsOwnEventId() = runTest {
        val f = fixture(1)
        f.items =
            listOf(
                f.items
                    .single()
                    .copy(
                        outcome =
                            CollaborationIncomingHistoryOutcome.Retained(
                                listOf("\$target"),
                                emptyList(),
                            )
                    )
            )
        assertEquals(1, success(f).resolved)
        assertEquals(f.page.events, f.subsets.single())
    }

    @Test
    fun aBatchFromAnotherRuntimeHistoryOrCapturedPageFailsBeforeIo() = runTest {
        for (mode in 0..4) {
            val f = fixture()
            val batch =
                when (mode) {
                    0 -> f.batch.copy(runtime = f.base.runtime.copy(deviceId = f.base.id(801)))
                    1 ->
                        f.batch.copy(
                            history =
                                f.history.copy(
                                    traversal = f.history.traversal.copy(deviceId = f.base.id(802))
                                )
                        )
                    2 ->
                        f.batch.copy(
                            history =
                                f.history.copy(
                                    source = f.history.source.copy(matrixDeviceId = "OTHER")
                                )
                        )
                    3 ->
                        f.batch.copy(
                            references =
                                f.page.copy(
                                    progress =
                                        requireNotNull(f.page.progress)
                                            .copy(
                                                recovery =
                                                    requireNotNull(f.page.progress?.recovery)
                                                        .copy(generation = f.base.id(803))
                                            )
                                )
                        )
                    else ->
                        f.batch.copy(
                            references =
                                f.page.copy(
                                    events =
                                        f.page.events.map {
                                            it.copy(
                                                event =
                                                    it.event.copy(
                                                        timestamp = it.event.timestamp + 1
                                                    )
                                            )
                                        }
                                )
                        )
                }
            assertIs<AppResult.Failed>(f.complete(f.base.runtime, f.history, f.page, batch))
            assertTrue(f.provider.calls.isEmpty())
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun absentCompletedOrPrimaryOnlyPositionsCannotBeCompletedAsRecovery() = runTest {
        val f = fixture()
        val progress = requireNotNull(f.page.progress)
        for (wrong in
            listOf(
                null,
                progress.copy(recovery = null),
                progress.copy(recovery = requireNotNull(progress.recovery).copy(complete = true)),
            )) {
            assertIs<AppResult.Failed>(
                f.complete(f.base.runtime, f.history, f.page.copy(progress = wrong), f.batch)
            )
            assertTrue(f.provider.calls.isEmpty())
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun wrongCountOrderDuplicatesAndForeignSourceIdsFailBeforeIo() = runTest {
        val f = fixture()
        for (items in
            listOf(
                emptyList(),
                f.items.take(1),
                f.items.reversed(),
                listOf(f.items[0], f.items[0]),
                listOf(f.items[0].copy(sourceEventId = "\$other"), f.items[1]),
            )) {
            assertIs<AppResult.Failed>(
                f.complete(f.base.runtime, f.history, f.page, f.batch.copy(items = items))
            )
            assertTrue(f.provider.calls.isEmpty())
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun emptyOversizedDuplicateAndMalformedRetentionIdsFailBeforeIo() = runTest {
        for (retained in
            listOf(
                CollaborationIncomingHistoryOutcome.Retained(emptyList(), emptyList()),
                CollaborationIncomingHistoryOutcome.Retained(
                    List(17) { "\$event$it" },
                    emptyList(),
                ),
                CollaborationIncomingHistoryOutcome.Retained(emptyList(), List(17) { "\$edit$it" }),
                CollaborationIncomingHistoryOutcome.Retained(listOf("\$one"), listOf("\$one")),
                CollaborationIncomingHistoryOutcome.Retained(listOf("bad"), emptyList()),
            )) {
            val f = fixture(1)
            f.items = listOf(f.items.single().copy(outcome = retained))
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.provider.calls.isEmpty())
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun malformedWaitingTargetsFailBeforeIo() = runTest {
        for (outcome in
            listOf(
                CollaborationIncomingHistoryOutcome.AwaitDecryption(
                    "bad",
                    CollaborationEventDecryptionFailure.UnableToDecrypt,
                ),
                CollaborationIncomingHistoryOutcome.AwaitRedaction("bad"),
                CollaborationIncomingHistoryOutcome.MissingOriginal("bad"),
            )) {
            val f = fixture(1)
            f.items = listOf(f.items.single().copy(outcome = outcome))
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.provider.calls.isEmpty())
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun moreThanSixteenSourcesOrResultsNeverReachStorage() = runTest {
        for (sources in listOf(true, false)) {
            val f = fixture(if (sources) 17 else 1)
            if (!sources) f.items = List(17) { f.items.single() }
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.provider.calls.isEmpty())
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun falseRepliesNeverBecomePageAcknowledgements() = runTest {
        for (committed in listOf(true, false)) {
            val f = fixture()
            f.commit = committed
            f.response = AppResult.Success(false)
            assertIs<AppResult.Failed>(f.run())
            assertEquals(committed, f.committed)
            assertEquals(1, f.attempts.size)
        }
    }

    @Test
    fun aLostReplyAfterCommitStaysExplicitWithoutAnotherWrite() = runTest {
        val f = fixture()
        f.response = AppResult.Failed(f.base.directory.storageFailure)
        assertEquals(f.base.directory.storageFailure, assertIs<AppResult.Failed>(f.run()).failure)
        assertTrue(f.committed)
        assertEquals(1, f.attempts.size)
    }

    @Test
    fun cancellationBeforeCommitCannotAcknowledgeOrRetry() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        f.reference.hooks["complete"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.run() }
        entered.await()
        job.cancelAndJoin()
        assertFailsWith<CancellationException> { job.await() }
        assertFalse(f.committed)
        assertEquals(1, f.attempts.size)
    }

    @Test
    fun cancellationAfterCommitPreservesTheUnknownOutcome() = runTest {
        val f = fixture()
        f.reference.hooks["completed"] = { currentCoroutineContext().cancel() }
        val job = async { f.run() }
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(f.committed)
        assertEquals(1, f.attempts.size)
    }

    @Test
    fun anUnavailableInstalledSourcePreventsCompletion() = runTest {
        val f = fixture()
        f.base.installed = null
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.attempts.isEmpty())
    }

    @Test
    fun absentMetadataOrRegistrationPreventsCompletion() = runTest {
        for (metadata in listOf(true, false)) {
            val f = fixture()
            if (metadata) f.reference.binding = null else f.base.registration = null
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun anUnregisteredPhysicalDeviceCannotCompleteThisHistory() = runTest {
        val f = fixture()
        val record = assertNotNull(f.base.registration)
        val legacy = assertNotNull(record.command.legacy)
        f.base.registration =
            record.copy(
                command =
                    record.command.copy(
                        legacy =
                            legacy.copy(identity = legacy.identity.copy(matrixDeviceId = "OTHER"))
                    )
            )
        assertIs<AppResult.Failed>(f.run())
        assertTrue(f.attempts.isEmpty())
    }

    @Test
    fun metadataAndRegistrationReadFailuresRemainStorageFailures() = runTest {
        for (stage in listOf("binding", "registration")) {
            val f = fixture()
            (if (stage == "binding") f.reference.failures else f.provider.failures)[stage] =
                f.base.directory.storageFailure
            assertEquals(
                f.base.directory.storageFailure,
                assertIs<AppResult.Failed>(f.run()).failure,
            )
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun accountNativeSessionSourceAndDenialChangesAfterCommitPreventAcknowledgement() = runTest {
        for (change in listOf("account", "session", "source", "denial")) {
            val f = fixture()
            f.reference.hooks["completed"] = {
                when (change) {
                    "account" -> f.base.native.setup.session = null
                    "session" -> {
                        val session = assertNotNull(f.base.native.setup.session)
                        f.base.native.setup.session =
                            session.copy(binding = session.binding.copy(serverId = f.base.id(998)))
                    }
                    "source" -> f.base.installed = f.base.source.copy(matrixDeviceId = "OTHER")
                    else -> f.base.directory.deny()
                }
            }
            val result = assertIs<AppResult.Failed>(f.run())
            if (change == "denial") assertEquals(f.base.directory.denied, result.failure)
            assertTrue(f.committed)
            assertEquals(1, f.attempts.size)
        }
    }

    @Test
    fun metadataOrRegistrationReplacementAfterCommitIsNotSuccess() = runTest {
        for (metadata in listOf(true, false)) {
            val f = fixture()
            f.reference.hooks["completed"] = {
                if (metadata) f.reference.binding = null else f.base.registration = null
            }
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.committed)
            assertEquals(1, f.attempts.size)
        }
    }

    @Test
    fun confirmedDenialWinsOverAnUnknownStorageReply() = runTest {
        val f = fixture()
        f.response = AppResult.Failed(f.base.directory.storageFailure)
        f.reference.hooks["completed"] = { f.base.directory.deny() }
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertTrue(f.committed)
        assertEquals(1, f.attempts.size)
    }

    @Test
    fun inputReferencesOutcomesAndRetentionIdsAreCapturedBeforeIo() = runTest {
        val f = fixture()
        val expected = f.page
        val rows = expected.events.toMutableList()
        val ids = mutableListOf(f.items[0].sourceEventId)
        val items = f.items.toMutableList()
        items[0] =
            items[0].copy(outcome = CollaborationIncomingHistoryOutcome.Retained(ids, emptyList()))
        f.page = expected.copy(events = rows)
        f.items = items
        f.reference.hooks["binding"] = {
            rows.clear()
            ids.clear()
            items.clear()
        }
        val result = success(f)
        assertEquals(2, result.resolved)
        assertEquals(expected, f.attempts.single())
        assertEquals(expected.events, f.subsets.single())
    }

    @Test
    fun expiredPreparationDoesNotBlockOwnedLocalCompletion() = runTest {
        val f = fixture()
        f.base.native.setup.now += 365.days
        assertEquals(2, success(f).resolved)
        assertEquals(1, f.attempts.size)
    }

    @Test
    fun primaryQueueFailureDoesNotBecomeHistoricalAuthority() = runTest {
        val f = fixture()
        f.provider.failures["demand"] = f.base.directory.storageFailure
        assertEquals(2, success(f).resolved)
        assertNull(f.base.snapshot)
        assertFalse(f.provider.calls.contains("demand"))
    }

    @Test
    fun stalledLocalAdmissionReadsRespectTheFiveSecondLifetime() = runTest {
        for (stage in listOf("binding", "registration")) {
            val f = fixture()
            (if (stage == "binding") f.reference.hooks else f.provider.hooks)[stage] = {
                awaitCancellation()
            }
            assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.run()).failure.kind)
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun anEmptyPageCannotBypassConfirmedDenial() = runTest {
        val f = fixture(0)
        f.base.directory.deny()
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.run()).failure)
        assertTrue(f.attempts.isEmpty())
    }

    @Test
    fun aLaterRequestedGenerationDoesNotBecomeThisPagesReceipt() = runTest {
        val f = fixture()
        val next = f.base.id(997)
        val progress = requireNotNull(f.page.progress)
        f.page =
            f.page.copy(
                progress =
                    progress.copy(
                        incoming =
                            requireNotNull(progress.incoming)
                                .copy(
                                    pendingAfter = "primary-cursor",
                                    pendingThrough = progress.references,
                                    pendingRetryRequired = true,
                                ),
                        recovery =
                            requireNotNull(progress.recovery).copy(requestedGeneration = next),
                    )
            )
        assertTrue(validCollaborationHistoryProgress(requireNotNull(f.page.progress)))
        val result = success(f)
        assertEquals(f.reference.generation, result.generation)
        assertNotEquals(next, result.generation)
        assertEquals(f.page, f.attempts.single())
        assertTrue(requireNotNull(f.attempts.single().progress?.incoming).pendingRetryRequired)
    }

    @Test
    fun aPartialPageReturnsOnlyItsCapturedRecoveryCursor() = runTest {
        val f = fixture(16)
        val progress = requireNotNull(f.page.progress)
        f.page =
            f.page.copy(
                progress =
                    progress.copy(
                        references = 20,
                        recovery = requireNotNull(progress.recovery).copy(through = 20),
                    ),
                after = "cursor-15",
            )
        val result = success(f)
        assertEquals("cursor-15", result.after)
        assertEquals(f.reference.generation, result.generation)
        assertEquals(16, result.resolved)
        assertEquals(1, f.attempts.size)
    }

    @Test
    fun invalidHistoricalCoordinatesFailEvenWithAMatchingBatch() = runTest {
        for (mode in 0..2) {
            val f = fixture()
            val history =
                when (mode) {
                    0 -> f.history.copy(traversal = f.history.traversal.copy(deviceId = "bad"))
                    1 ->
                        f.history.copy(
                            traversal = f.history.traversal.copy(roomId = "!foreign:example.test")
                        )
                    else ->
                        f.history.copy(
                            traversal =
                                f.history.traversal.copy(
                                    purpose = CollaborationHistoryPurpose.Browse
                                )
                        )
                }
            assertIs<AppResult.Failed>(
                f.complete(f.base.runtime, history, f.page, f.batch.copy(history = history))
            )
            assertTrue(f.provider.calls.isEmpty())
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun sourceOrRegistrationChangesDuringAdmissionPreventTheWrite() = runTest {
        for (source in listOf(true, false)) {
            val f = fixture()
            f.reference.hooks["binding"] = {
                if (source) f.base.installed = null else f.base.registration = null
            }
            assertIs<AppResult.Failed>(f.run())
            assertTrue(f.attempts.isEmpty())
        }
    }

    @Test
    fun cancellingAnAdmissionReadPropagatesWithoutAWrite() = runTest {
        val f = fixture()
        val entered = CompletableDeferred<Unit>()
        f.reference.hooks["binding"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.run() }
        entered.await()
        job.cancelAndJoin()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(f.attempts.isEmpty())
    }

    @Test
    fun allWaitingOutcomesStillCommitOneFiniteAttemptWithAnEmptyResolvedSubset() = runTest {
        val f = fixture()
        f.items =
            f.items.map {
                it.copy(
                    outcome =
                        CollaborationIncomingHistoryOutcome.AwaitDecryption(
                            it.sourceEventId,
                            CollaborationEventDecryptionFailure.UnableToDecrypt,
                        )
                )
            }
        val result = success(f)
        assertEquals(0, result.resolved)
        assertEquals(2, result.unresolved)
        assertTrue(f.subsets.single().isEmpty())
        assertTrue(f.committed)
        assertEquals(1, f.attempts.size)
    }
}
