package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PublishSpaceInboxHistoryTest {
    @Test
    fun actualCanonicalGroupAndCompanySourcesPublishWithoutANetworkOrBackgroundReader() = runTest {
        for (legacy in listOf(false, true)) {
            val f = SpaceInboxHistoryAdmissionFixture(legacy)
            val value =
                assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result()).value
            assertEquals(listOf(f.base.original.primaryEventId), value.retainedEventIds)
            assertTrue(value.unresolvedEventIds.isEmpty())
            assertEquals(f.base.source, f.current.entry?.newest?.source)
            assertEquals(1, f.commits)
            assertEquals(listOf(emptyList()), f.snapshots)
            assertFalse(f.boundaries.any { it.endsWith("completion") })
        }
    }

    @Test
    fun missingOrTombstoneOnlyOriginalsRetainTheCapturedReferenceWithoutAnIndexWrite() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        f.originalByEvent.clear()
        val value =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result()).value
        assertTrue(value.retainedEventIds.isEmpty())
        assertEquals(listOf(f.base.original.primaryEventId), value.unresolvedEventIds)
        assertTrue(f.publications.isEmpty())
        assertFalse("index" in f.boundaries)
    }

    @Test
    fun partialOriginalAmbiguityMapsEveryAliasBackToDurableUnresolvedWork() = runTest {
        val pending = SpaceInboxEditOutcomeFixture(false)
        val f = SpaceInboxHistoryAdmissionFixture(base = pending.base)
        val originalId = "e9000000-0000-4000-8000-000000000055"
        val unproven =
            f.base.original.copy(
                logicalId = originalId,
                eventIds = listOf(f.base.original.primaryEventId, "\$alias"),
            )
        f.originalByEvent[unproven.primaryEventId] = unproven
        f.originalByEvent["\$alias"] = unproven
        val ready = f.base.original.copy(primaryEventId = "\$ready", eventIds = listOf("\$ready"))
        f.originalByEvent[ready.primaryEventId] = ready
        val intent =
            pending.prepared.intent.copy(
                id = originalId,
                text = f.base.original.text!!,
                editTarget = null,
                inboxOrigin = null,
            )
        f.base.group[originalId] =
            CollaborationSpaceMessageRecord(intent, 0, CollaborationMessageState.Queued)
        val ids = listOf(unproven.primaryEventId, "\$alias", ready.primaryEventId)
        val value =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result(ids)).value
        assertEquals(listOf(ready.primaryEventId), value.retainedEventIds)
        assertEquals(ids.take(2), value.unresolvedEventIds)
        assertEquals(1, f.publications.single().activities.size)
        assertEquals(1L, f.current.version?.lastOrdinal)
    }

    @Test
    fun aRetainedOriginalWithoutCommandProofCannotBecomeASecondHistoryRow() = runTest {
        for (company in listOf(false, true)) {
            val f = SpaceInboxHistoryAdmissionFixture(legacy = company)
            val originalId = "e9000000-0000-4000-8000-000000000055"
            val original =
                f.base.original.copy(
                    logicalId = originalId,
                    eventIds = listOf(f.base.original.primaryEventId, "\$alias"),
                )
            original.eventIds.forEach { f.originalByEvent[it] = original }
            val source =
                if (company) {
                    val location =
                        assertIs<CollaborationSpaceHistoryLocation.Original>(f.base.location)
                    CollaborationSpaceInboxSource.CompanyOriginal(
                        location.access.companyId,
                        location.conversationId,
                        originalId,
                    )
                } else CollaborationSpaceInboxSource.GroupOriginal(originalId)
            val retained =
                f.base.activity.copy(
                    source = source,
                    preview =
                        f.base.activity.preview!!.copy(
                            delivery = CollaborationMessageDelivery.PENDING
                        ),
                )
            f.retain(retained)
            val before = f.current
            val result =
                assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(
                        f.result(original.eventIds)
                    )
                    .value
            assertEquals(original.eventIds, result.unresolvedEventIds)
            assertTrue(result.retainedEventIds.isEmpty())
            assertEquals(before, f.current)
            assertEquals(listOf(retained), f.activityBySource.values.toList())
            assertTrue(f.publications.isEmpty())
            assertTrue(source in f.anchorQueries.flatten())
        }
    }

    @Test
    fun sixteenLegacyOriginalsCheckBothAnchorBatchesUnderOneIndexVersion() = runTest {
        for (competing in listOf(false, true)) {
            val f = SpaceInboxHistoryAdmissionFixture(legacy = true)
            f.originalByEvent.clear()
            val ids = (1..16).map { "\$original$it" }
            for ((index, eventId) in ids.withIndex()) {
                val logicalId =
                    "e9000000-0000-4000-8000-" + (index + 1).toString().padStart(12, '0')
                val original =
                    f.base.original.copy(
                        primaryEventId = eventId,
                        eventIds = listOf(eventId),
                        logicalId = logicalId,
                    )
                f.originalByEvent[eventId] = original
                val retained =
                    f.base.activity.copy(
                        source = CollaborationSpaceInboxSource.GroupOriginal(logicalId),
                        position = f.base.activity.position.copy(ordinal = index + 1L),
                        preview =
                            f.base.activity.preview!!.copy(
                                delivery = CollaborationMessageDelivery.PENDING
                            ),
                    )
                f.retain(retained)
            }
            val retained = f.activityBySource.toMap()
            val before = f.current
            if (competing)
                f.before = { boundary ->
                    if (boundary == "anchors" && f.anchorQueries.size % 2 == 0) {
                        val version = requireNotNull(f.current.version)
                        f.current =
                            f.current.copy(version = version.copy(revision = version.revision + 1))
                    }
                }
            val result = f.result(ids)
            if (competing) {
                assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(result).failure.kind)
                assertEquals(listOf(32, 16, 32, 16, 32, 16), f.anchorQueries.map { it.size })
                assertEquals(3, f.snapshots.size)
            } else {
                val value =
                    assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(result).value
                assertEquals(ids, value.unresolvedEventIds)
                assertTrue(value.retainedEventIds.isEmpty())
                assertEquals(listOf(32, 16), f.anchorQueries.map { it.size })
                assertEquals(48, f.anchorQueries.flatten().distinct().size)
                assertEquals(before, f.current)
            }
            assertEquals(retained, f.activityBySource)
            assertTrue(f.publications.isEmpty())
        }
    }

    @Test
    fun anExactSourceAndIndexReplayDoesNotAllocateOrRewriteAnything() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        val first = f.result()
        val retained = f.current
        assertEquals(first, f.result())
        assertEquals(retained, f.current)
        assertEquals(1, f.commits)
        assertEquals(1, f.noops)
    }

    @Test
    fun lostPublicationResponseKeepsWorkUntilAReentryConfirmsTheLocalNoop() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        f.lostResponse = f.failure
        assertEquals(AppResult.Failed(f.failure), f.result())
        val retained = f.current
        assertEquals(1, f.commits)
        f.lostResponse = null
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result())
        assertEquals(retained, f.current)
        assertEquals(1, f.commits)
        assertEquals(1, f.noops)
    }

    @Test
    fun observedGroupAndCompanyEditsClearOnlyTheirExactPublishedPointer() = runTest {
        for (company in listOf(false, true)) {
            val (f, pending) = pending(company)
            val original = f.base.original
            val fact = pending.evidence.single()
            val edit =
                CollaborationMessageEdit(
                    fact.eventId,
                    fact.sender,
                    fact.targetEventId,
                    fact.timestamp,
                    pending.prepared.intent.text,
                    fact.contentHash,
                    fact.operationId,
                    original.logicalId,
                    original.replyToEventId,
                    original.forwarded,
                )
            f.originalByEvent[original.primaryEventId] =
                original.copy(latestEdit = CollaborationMessageRevision(edit, original.timestamp))
            f.operations[CollaborationHistoryEditOperation(original.sender, pending.operation)] =
                pending.evidence
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result())
            val activity = assertNotNull(f.current.entry?.newest)
            assertNull(activity.pendingEdit)
            assertEquals(pending.activity.position, activity.position)
            assertEquals(pending.prepared.intent.text, activity.preview?.text)
            assertEquals(2, f.snapshots.size)
            assertEquals(
                listOf(CollaborationHistoryEditOperation(original.sender, pending.operation)),
                f.snapshots.last(),
            )
            assertFalse(f.boundaries.any { it.endsWith("completion") })
        }
    }

    @Test
    fun anAcknowledgementWithoutProviderEditEvidenceKeepsOptimisticTextAndProvenance() = runTest {
        val (f, pending) = pending(false)
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result())
        assertEquals(pending.activity, f.current.entry?.newest)
        assertEquals(0, f.commits)
        assertEquals(1, f.noops)
        assertEquals(2, f.snapshots.size)
    }

    @Test
    fun changingOperationSourceAndCompetingIndexWritesHaveFiniteIndependentBudgets() = runTest {
        val (source, _) = pending(false)
        source.before = {
            if (it == "operations") source.revision = "source:${source.snapshots.size}"
        }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(source.result()).failure.kind)
        assertEquals(6, source.snapshots.size)
        assertTrue(source.publications.isEmpty())
        val index = SpaceInboxHistoryAdmissionFixture()
        index.writeConflicts = 100
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(index.result()).failure.kind)
        assertEquals(3, index.publications.size)
        assertEquals(0, index.commits)
    }

    @Test
    fun ordinaryStorageFailuresDoNotEnterTheContentionRetryLoop() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        f.snapshotFailure = f.failure
        assertEquals(AppResult.Failed(f.failure), f.result())
        assertEquals(1, f.snapshots.size)
        assertTrue(f.publications.isEmpty())
    }

    @Test
    fun deniedAccessAfterAnyReadNeverPublishesOrReturnsRetainedReferences() = runTest {
        for (boundary in listOf("history", "index", "anchors", "operations")) {
            val (f, _) = pending(false)
            val denial = Failure(FailureKind.AccessDenied, "Fixture denial")
            f.before = { name ->
                if (name == boundary)
                    f.base.thread.currentAdmission = {
                        AppResult.Success(
                            CollaborationSpaceDirectoryAdmission(
                                "a9000000-0000-4000-8000-000000000002",
                                denial,
                            )
                        )
                    }
            }
            assertEquals(AppResult.Failed(denial), f.result())
            assertTrue(f.publications.isEmpty())
        }
    }

    @Test
    fun aReplacedAccountAfterReadingAnIndexCannotPublishItsFormerContent() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        f.before = {
            if (it == "index") f.base.thread.currentIdentity = { AppResult.Success(null) }
        }
        assertIs<AppResult.Failed>(f.result())
        assertTrue(f.publications.isEmpty())
    }

    @Test
    fun cancelledSourceReadsAreJoinedBeforeTheOwnerCanContinue() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        f.before = {
            if (it == "history")
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped = true
                }
        }
        val reading = launch { f.result() }
        entered.await()
        reading.cancelAndJoin()
        assertTrue(stopped)
        assertTrue(f.publications.isEmpty())
        assertEquals(1, f.snapshots.size)
    }

    @Test
    fun capturedReferenceInputsSurviveCallerMutationDuringAdmission() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        val ids = mutableListOf(f.base.original.primaryEventId)
        f.base.thread.currentIdentity = {
            ids.clear()
            AppResult.Success(f.base.thread.native.session)
        }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result(ids)).value
        assertEquals(listOf(f.base.original.primaryEventId), result.retainedEventIds)
        assertEquals(listOf(listOf(f.base.original.primaryEventId)), f.references)
    }

    @Test
    fun aLaterVisibleEditReadsBothCompletionNamespacesBeforeClearingAnyOlderPointer() = runTest {
        for (oldCompany in listOf(false, true)) for (newCompany in listOf(false, true)) {
            val (f, pending) = pending(oldCompany)
            successor(f, pending, newCompany)
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result())
            assertNull(f.current.entry?.newest?.pendingEdit)
            assertEquals("Newer edit", f.current.entry?.newest?.preview?.text)
            assertEquals(1, f.boundaries.count { it == "group-completion" })
            assertEquals(1, f.boundaries.count { it == "company-completion" })
        }
    }

    @Test
    fun twoMatchingSuccessorsRemainAmbiguousAndAReadFailureCannotStandInForAbsence() = runTest {
        val (f, pending) = pending(false)
        successor(f, pending, false)
        successor(f, pending, true)
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result())
        assertEquals(pending.activity, f.current.entry?.newest)
        assertEquals(0, f.commits)
        f.completionFailure = f.failure
        val attempts = f.publications.size
        assertEquals(AppResult.Failed(f.failure), f.result())
        assertEquals(attempts, f.publications.size)
    }

    @Test
    fun aMissingOldCommandCannotGrantClearanceEvenWhenANewerCompletionExists() = runTest {
        val (f, pending) = pending(false)
        successor(f, pending, false)
        f.base.group.clear()
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result())
        assertEquals(pending.activity, f.current.entry?.newest)
        assertFalse(f.boundaries.any { it.endsWith("completion") })
    }

    @Test
    fun sourceAliasListsAreDetachedBeforeTheNextAdmissionSuspends() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        val aliases = mutableListOf(f.base.original.primaryEventId, "\$alias")
        val original = f.base.original.copy(eventIds = aliases)
        aliases.forEach { f.originalByEvent[it] = original }
        f.base.thread.currentIdentity = {
            if (f.boundaries.lastOrNull() == "history") aliases.clear()
            AppResult.Success(f.base.thread.native.session)
        }
        val captured = listOf(f.base.original.primaryEventId, "\$alias")
        val result =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result(captured))
                .value
        assertEquals(captured, result.retainedEventIds)
        assertTrue(aliases.isEmpty())
        assertEquals(1, f.current.candidates.size)
    }

    @Test
    fun aNewOptimisticEditDuringEvidenceAcquisitionWinsByTheIndexRevision() = runTest {
        val (f, pending) = pending(false)
        val nextId = "e9000000-0000-4000-8000-000000000088"
        val nextPointer =
            assertNotNull(pending.activity.pendingEdit)
                .copy(
                    source = CollaborationSpaceInboxEditSource.Group(nextId),
                    createdAt = pending.prepared.intent.createdAt.toEpochMilliseconds() + 1,
                    publishedAtRevision = 8,
                )
        val next =
            pending.activity.copy(
                preview = assertNotNull(pending.activity.preview).copy(text = "Newest local edit"),
                pendingEdit = nextPointer,
            )
        var changed = false
        f.before = { boundary ->
            if (boundary == "operations" && !changed) {
                changed = true
                f.retain(next)
                f.current =
                    f.current.copy(version = assertNotNull(f.current.version).copy(revision = 8))
                val intent =
                    pending.prepared.intent.copy(
                        id = nextId,
                        createdAt = pending.prepared.intent.createdAt + 1.milliseconds,
                        text = "Newest local edit",
                        inboxOrigin = CollaborationSpaceEditInboxOrigin(next.source, 8),
                    )
                f.base.group[nextId] =
                    CollaborationSpaceMessageRecord(intent, 0, CollaborationMessageState.Queued)
            }
        }
        assertIs<AppResult.Success<CollaborationSpaceInboxHistoryCommit>>(f.result())
        assertEquals(next, f.current.entry?.newest)
        assertEquals(2, f.publications.size)
        assertEquals(0, f.commits)
        assertEquals(1, f.noops)
    }

    @Test
    fun invalidReferenceBatchesFailBeforeAnyRepositoryAcquisition() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        for (ids in
            listOf(
                emptyList(),
                List(17) { "\$event-$it" },
                listOf("\$same", "\$same"),
                listOf("invalid"),
            )) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.result(ids)).failure.kind,
            )
        }
        assertIs<AppResult.Failed>(f.usecase()(f.access, "invalid", listOf("\$event")))
        assertTrue(f.boundaries.isEmpty())
        assertTrue(f.references.isEmpty())
    }

    @Test
    fun malformedAndOversizedRepositorySnapshotsCannotPublishOrResolveReferences() = runTest {
        val f = SpaceInboxHistoryAdmissionFixture()
        val event =
            CollaborationSpaceHistorySnapshotEvent(f.base.original.primaryEventId, f.base.original)
        val valid = CollaborationSpaceHistorySnapshot(f.revision, listOf(event))
        val operation =
            CollaborationHistoryEditOperation(
                f.base.original.sender,
                "e9000000-0000-4000-8000-000000000044",
            )
        for (snapshot in
            listOf(
                valid.copy(revision = ""),
                valid.copy(events = List(17) { event }),
                valid.copy(
                    events =
                        listOf(
                            event.copy(
                                original =
                                    f.base.original.copy(
                                        eventIds =
                                            listOf(f.base.original.primaryEventId) +
                                                List(128) { "\$alias-$it" }
                                    )
                            )
                        )
                ),
                valid.copy(
                    editOperations =
                        listOf(CollaborationHistoryEditOperationEvidence(operation, emptyList()))
                ),
            )) {
            f.rawSnapshot = snapshot
            assertIs<AppResult.Failed>(f.result())
            assertTrue(f.publications.isEmpty())
        }
    }

    private fun successor(
        f: SpaceInboxHistoryAdmissionFixture,
        pending: SpaceInboxEditOutcomeFixture,
        company: Boolean,
    ) {
        val id = "e9000000-0000-4000-8000-000000000077"
        val origin = pending.origin.copy(contentHash = "c".repeat(64))
        val sent = CollaborationMessageState.Sent(pending.device, "newer", "\$newer", origin)
        if (company) {
            val record = assertNotNull(pending.companyRecord)
            f.originalCompletions[id] =
                CollaborationMessageCompletion(
                    record.copy(
                        intent =
                            record.intent.copy(
                                id = id,
                                createdAt = record.intent.createdAt + 1.milliseconds,
                                text = "Newer edit",
                            ),
                        state = sent,
                    ),
                    listOf(sent.eventId),
                )
        } else {
            val record = assertNotNull(pending.group)
            f.groupCompletions[id] =
                CollaborationSpaceMessageCompletion(
                    record.copy(
                        intent =
                            record.intent.copy(
                                id = id,
                                createdAt = record.intent.createdAt + 1.milliseconds,
                                text = "Newer edit",
                                inboxOrigin = null,
                            ),
                        state = sent,
                    ),
                    listOf(sent.eventId),
                )
        }
        val original = f.base.original
        val edit =
            CollaborationMessageEdit(
                sent.eventId,
                original.sender,
                original.primaryEventId,
                original.timestamp + 1,
                "Newer edit",
                origin.contentHash,
                id,
                original.logicalId,
                original.replyToEventId,
                original.forwarded,
            )
        f.originalByEvent[original.primaryEventId] =
            original.copy(latestEdit = CollaborationMessageRevision(edit, original.timestamp))
    }

    private fun pending(
        company: Boolean
    ): Pair<SpaceInboxHistoryAdmissionFixture, SpaceInboxEditOutcomeFixture> {
        val pending = SpaceInboxEditOutcomeFixture(company)
        val f = SpaceInboxHistoryAdmissionFixture(base = pending.base)
        f.retain(pending.activity)
        if (company) f.base.company[pending.operation] = assertNotNull(pending.companyRecord)
        else f.base.group[pending.operation] = assertNotNull(pending.group)
        return f to pending
    }
}
