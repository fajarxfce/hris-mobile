@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationMixedOriginalQueueTest {
    @Test
    fun mixedRecordsUseBothLedgersWithoutTransportOrDelivery() = runTest {
        val f = MixedOriginalQueueReadFixture()
        val result =
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(f.read()).value
        assertEquals(
            f.base.queue.record,
            assertIs<CollaborationMatchedQueueMessage.Company>(result.messages[0]).record,
        )
        assertEquals(
            f.record,
            assertIs<CollaborationMatchedQueueMessage.Space>(result.messages[1]).record,
        )
        assertEquals(2, f.base.count("get"))
        assertEquals(2, f.base.count("space-get"))
        assertEquals(2, f.base.count("inspect"))
        assertEquals(2, f.base.count("binding"))
        f.noDelivery()
    }

    @Test
    fun expiredCredentialsAndNoSelectedCompanyDoNotHideOwnedFacts() = runTest {
        val f = MixedOriginalQueueReadFixture()
        val session = requireNotNull(f.base.native.setup.session)
        f.base.native.setup.session =
            session.copy(
                tokens = session.tokens.copy(expiresAtEpochMillis = 1),
                binding = session.binding.copy(expiresAtEpochMillis = 1),
            )
        f.base.native.setup.workspace = null
        assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(f.read())
        f.noDelivery()
    }

    @Test
    fun emptyCaptureDoesNotQueryUncapturedMessagesOrInferAcknowledgements() = runTest {
        val f = MixedOriginalQueueReadFixture()
        f.base.snapshot =
            f.base.snapshot.copy(original = f.base.snapshot.original.copy(messages = emptyList()))
        assertTrue(
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(f.read())
                .value
                .messages
                .isEmpty()
        )
        assertEquals(0, f.base.count("get"))
        assertEquals(0, f.base.count("space-get"))
        assertEquals(1, f.records.size)
        f.noDelivery()
    }

    @Test
    fun missingOrAmbiguousLogicalIdsFailWithoutChoosingALedger() = runTest {
        for (ambiguous in listOf(false, true)) {
            val f = MixedOriginalQueueReadFixture()
            if (ambiguous) {
                f.records[f.base.queue.record.intent.id] =
                    f.record.copy(intent = f.record.intent.copy(id = f.base.queue.record.intent.id))
            } else f.records.clear()
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.read()).failure.kind)
            assertEquals(1, f.base.count("inspect"))
            f.noDelivery()
        }
    }

    @Test
    fun failureReadingEitherLedgerCannotBeTreatedAsAbsence() = runTest {
        for (stage in listOf("get", "space-get")) {
            val f = MixedOriginalQueueReadFixture()
            f.base.failures[stage] = f.base.directory.storageFailure
            assertEquals(
                f.base.directory.storageFailure,
                assertIs<AppResult.Failed>(f.read()).failure,
            )
            assertEquals(1, f.base.count(stage))
            f.noDelivery()
        }
    }

    @Test
    fun admittedDenialPreventsAnyBindingOrNativeRead() = runTest {
        val f = MixedOriginalQueueReadFixture()
        f.base.directory.deny()
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.read()).failure)
        assertTrue(f.base.calls.isEmpty())
    }

    @Test
    fun denialAfterStorageReadWinsOverItsTechnicalFailure() = runTest {
        val f = MixedOriginalQueueReadFixture()
        f.base.hooks["get"] = { f.base.directory.deny() }
        f.base.failures["get"] = f.base.directory.storageFailure
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.read()).failure)
        assertEquals(0, f.base.count("space-get"))
        f.noDelivery()
    }

    @Test
    fun localParentReplacementAtAnyIoBoundaryCannotPublishOldFacts() = runTest {
        for (stage in listOf("binding", "inspect", "get", "space-get", "open")) {
            val f = MixedOriginalQueueReadFixture()
            f.base.hooks[stage] = {
                f.base.native.setup.session =
                    requireNotNull(f.base.native.setup.session).copy(id = "replacement-login")
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.read()).failure.kind,
            )
            f.noDelivery()
        }
    }

    @Test
    fun missingBindingOrMalformedCaptureDoesNotFallBackToRemoteDiscovery() = runTest {
        for (missing in listOf(false, true)) {
            val f = MixedOriginalQueueReadFixture()
            if (missing) f.base.retained = null
            else
                f.base.snapshot =
                    f.base.snapshot.copy(
                        original =
                            f.base.snapshot.original.copy(messages = List(129) { f.original })
                    )
            assertIs<AppResult.Failed>(f.read())
            assertEquals(0, f.base.count("get"))
            f.noDelivery()
        }
    }

    @Test
    fun malformedProviderLogicalIdIsRejectedBeforeStorageLookup() = runTest {
        val f = MixedOriginalQueueReadFixture()
        f.base.snapshot =
            f.base.snapshot.copy(
                original =
                    f.base.snapshot.original.copy(
                        messages = listOf(f.original.copy(logicalId = null))
                    )
            )
        assertIs<AppResult.Failed>(f.read())
        assertEquals(0, f.base.count("get"))
        assertEquals(0, f.base.count("space-get"))
        f.noDelivery()
    }

    @Test
    fun sentMessagesRequireExactBoundedCompletionFromTheirOwnLedger() = runTest {
        val f = MixedOriginalQueueReadFixture()
        val sent = f.sent()
        f.records[sent.intent.id] = sent
        assertIs<AppResult.Failed>(f.read())
        val proof = CollaborationSpaceMessageCompletion(sent, listOf("\$space-accepted", "\$alias"))
        f.completed[sent.intent.id] = proof
        val result =
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(f.read()).value
        assertEquals(
            proof,
            assertIs<CollaborationMatchedQueueMessage.Space>(result.messages.last()).completion,
        )
        f.noDelivery()
    }

    @Test
    fun completionIsDetachedBeforeTheFollowingAdmissionSuspension() = runTest {
        val f = MixedOriginalQueueReadFixture()
        val sent = f.sent()
        val aliases = mutableListOf("\$space-accepted", "\$alias")
        f.records[sent.intent.id] = sent
        f.completed[sent.intent.id] = CollaborationSpaceMessageCompletion(sent, aliases)
        f.base.hooks["space-completion"] = {
            f.base.directory.beforeAdmission = { aliases.clear() }
        }
        val result =
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(f.read()).value
        assertEquals(
            listOf("\$space-accepted", "\$alias"),
            assertIs<CollaborationMatchedQueueMessage.Space>(result.messages.last())
                .completion!!
                .eventIds,
        )
        f.noDelivery()
    }

    @Test
    fun changedBindingOrQueueAfterMatchingRequiresAnotherExplicitRead() = runTest {
        for (stage in listOf("binding", "inspect")) {
            val f = MixedOriginalQueueReadFixture()
            f.base.hooks[stage] = {
                if (f.base.count(stage) == 2) {
                    if (stage == "binding") f.base.retained = null
                    else
                        f.base.snapshot =
                            f.base.snapshot.copy(
                                original = f.base.snapshot.original.copy(messages = emptyList())
                            )
                }
            }
            assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.read()).failure.kind)
            assertEquals(2, f.base.count(stage))
            f.noDelivery()
        }
    }

    @Test
    fun initialCaptureIsDetachedAndLaterMutationCannotShrinkTheRead() = runTest {
        val f = MixedOriginalQueueReadFixture()
        val originals = f.base.snapshot.original.messages.toMutableList()
        f.base.snapshot =
            f.base.snapshot.copy(original = f.base.snapshot.original.copy(messages = originals))
        f.base.hooks["get"] = { originals.clear() }
        assertIs<AppResult.Failed>(f.read())
        assertEquals(2, f.base.count("get"))
        assertEquals(2, f.base.count("space-get"))
        f.noDelivery()
    }

    @Test
    fun closedRuntimeCannotPublishTheMatchedInventory() = runTest {
        val f = MixedOriginalQueueReadFixture()
        f.base.open = false
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.read()).failure.kind)
        f.noDelivery()
    }

    @Test
    fun explicitCancellationStopsLookupAndNeverStartsDelivery() = runTest {
        val f = MixedOriginalQueueReadFixture()
        val entered = CompletableDeferred<Unit>()
        f.base.hooks["space-get"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.read() }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, f.base.count("open"))
        f.noDelivery()
    }

    @Test
    fun deadlineEndsABlockedReadWithoutRetryingIt() = runTest {
        val f = MixedOriginalQueueReadFixture()
        f.base.hooks["space-get"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.read()).failure.kind)
        assertEquals(1, f.base.count("space-get"))
        f.noDelivery()
    }
}
