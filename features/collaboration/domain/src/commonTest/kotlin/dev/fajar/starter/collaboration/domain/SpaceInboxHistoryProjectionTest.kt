package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxHistoryProjectionTest {
    @Test
    fun newHistoryKeepsItsActualPhysicalNamespaceAndOriginalTime() {
        for (legacy in listOf(false, true)) {
            val f = Fixture(legacy)
            val result = f.projection()
            val publication = assertNotNull(result.publication)
            val activity = publication.activities.single()
            assertEquals(f.base.source, activity.source)
            assertEquals(f.base.original.timestamp, activity.preview?.timestamp)
            assertEquals(maxOf(f.createdAt, f.base.original.timestamp), activity.position.timestamp)
            assertEquals(1L, activity.position.ordinal)
            assertNull(activity.preview?.delivery)
            assertEquals(activity, publication.entry.newest)
            assertTrue(result.unresolvedEventIds.isEmpty())
        }
    }

    @Test
    fun retainedPhysicalAnchorSurvivesLaterProvenAndUnprovenCommands() {
        for (legacy in listOf(false, true)) for (proven in listOf(false, true)) {
            val f = Fixture(legacy)
            f.retain(f.base.activity)
            f.original = f.original.copy(logicalId = f.originalId)
            val identity = if (proven) f.association() else f.unresolved
            val publication =
                assertNotNull(f.projection(listOf(f.update(identity = identity))).publication)
            val activity = publication.activities.single()
            assertEquals(f.base.activity.source, activity.source)
            assertEquals(f.base.activity.position, activity.position)
            assertEquals(f.version.lastOrdinal, publication.lastOrdinal)
            assertNull(activity.preview?.delivery)
        }
    }

    @Test
    fun groupAndCompanyAcknowledgementsRetainActualCommandCoordinates() {
        for (company in listOf(false, true)) {
            val f = Fixture(true)
            f.original = f.original.copy(logicalId = f.originalId)
            val identity = f.association(company, CollaborationMessageDelivery.SENT)
            val result =
                assertNotNull(f.projection(listOf(f.update(identity = identity))).publication)
            assertEquals(identity.association.source, result.activities.single().source)
            assertEquals(CollaborationMessageDelivery.SENT, result.entry.newest?.preview?.delivery)
        }
    }

    @Test
    fun pendingAssociationCannotDowngradeSentOrFailedOriginals() {
        for (company in listOf(false, true)) for (delivery in
            listOf(CollaborationMessageDelivery.SENT, CollaborationMessageDelivery.FAILED)) {
            val f = Fixture(true)
            f.original = f.original.copy(logicalId = f.originalId)
            val identity = f.association(company)
            val old =
                f.base.activity.copy(
                    source = identity.association.source,
                    preview = f.base.activity.preview!!.copy(delivery = delivery),
                )
            f.retain(old)
            val result =
                assertNotNull(f.projection(listOf(f.update(identity = identity))).publication)
            assertEquals(delivery, result.entry.newest?.preview?.delivery)
            assertEquals(old.position, result.entry.newest?.position)
        }
    }

    @Test
    fun aConfirmedAckCanUpgradeOriginalAndPhysicalHistoryDeliveryWithoutMovingEither() {
        for (physical in listOf(false, true)) {
            val f = Fixture()
            f.original = f.original.copy(logicalId = f.originalId)
            val identity = f.association(delivery = CollaborationMessageDelivery.SENT)
            val old =
                if (physical) f.base.activity
                else
                    f.base.activity.copy(
                        source = identity.association.source,
                        preview =
                            f.base.activity.preview!!.copy(
                                delivery = CollaborationMessageDelivery.FAILED
                            ),
                    )
            f.retain(old)
            val activity =
                assertNotNull(f.projection(listOf(f.update(identity = identity))).publication)
                    .activities
                    .single()
            assertEquals(old.source, activity.source)
            assertEquals(old.position, activity.position)
            assertEquals(CollaborationMessageDelivery.SENT, activity.preview?.delivery)
        }
    }

    @Test
    fun unresolvedNewOriginalsRetainWorkWithoutAllocatingAnAnchor() {
        val f = Fixture()
        val result = f.projection(listOf(f.update(identity = f.unresolved)))
        assertNull(result.publication)
        assertEquals(listOf(f.base.original.primaryEventId), result.unresolvedEventIds)
        val other =
            f.base.original.copy(
                logicalId = null,
                primaryEventId = "\$another",
                eventIds = listOf("\$another"),
            )
        val mixed = f.projection(listOf(f.update(identity = f.unresolved), f.update(other)))
        assertEquals(listOf(f.base.original.primaryEventId), mixed.unresolvedEventIds)
        assertEquals(1L, mixed.publication?.lastOrdinal)
        assertEquals(
            other.primaryEventId,
            (mixed.publication?.entry?.newest?.source as CollaborationSpaceInboxSource.History)
                .eventId,
        )
    }

    @Test
    fun optimisticTextRemainsUntilTheExactPendingPointerIsResolved() {
        for (company in listOf(false, true)) {
            val pending = SpaceInboxEditOutcomeFixture(company)
            val f = Fixture(base = pending.base)
            f.retain(pending.activity)
            val unchanged = assertNotNull(f.projection().publication).activities.single()
            assertEquals(pending.activity, unchanged)
            val resolved =
                assertNotNull(
                        f.projection(listOf(f.update(resolved = pending.activity.pendingEdit)))
                            .publication
                    )
                    .activities
                    .single()
            assertEquals(f.base.original.text, resolved.preview?.text)
            assertNull(resolved.pendingEdit)
            assertEquals(pending.activity.position, resolved.position)
        }
    }

    @Test
    fun observedProviderTextReplacesOnlyItsExactOptimisticEditWithoutMovingTheOriginal() {
        val pending = SpaceInboxEditOutcomeFixture(false)
        val f = Fixture(base = pending.base)
        f.retain(pending.activity)
        val original = f.editedOriginal()
        val update = f.update(original, resolved = pending.activity.pendingEdit)
        val activity = assertNotNull(f.projection(listOf(update)).publication).activities.single()
        assertEquals("Provider edit", activity.preview?.text)
        assertEquals(f.base.original.timestamp, activity.preview?.timestamp)
        assertEquals(pending.activity.position, activity.position)
        assertNull(activity.pendingEdit)
        val pointer = assertNotNull(update.resolvedEdit)
        val stale =
            update.copy(
                resolvedEdit = pointer.copy(publishedAtRevision = pointer.publishedAtRevision + 1)
            )
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.result(listOf(stale))).failure.kind,
        )
    }

    @Test
    fun hidingAndDeletionDoNotResurrectContentOrErasePendingProvenance() {
        for (hidden in listOf(false, true)) for (resolved in listOf(false, true)) {
            val pending = SpaceInboxEditOutcomeFixture(false)
            val f = Fixture(base = pending.base)
            f.retain(pending.activity)
            val original =
                if (hidden) f.base.original.copy(locallyHidden = true)
                else f.base.original.copy(redacted = true, text = null)
            val activity =
                assertNotNull(
                        f.projection(
                                listOf(
                                    f.update(
                                        original,
                                        resolved = pending.activity.pendingEdit.takeIf { resolved },
                                    )
                                )
                            )
                            .publication
                    )
                    .activities
                    .single()
            if (hidden) assertNull(activity.preview)
            else assertEquals(CollaborationSpaceInboxContent.DELETED, activity.preview?.kind)
            assertEquals(pending.activity.pendingEdit.takeUnless { resolved }, activity.pendingEdit)
            f.retain(activity)
            val replay = assertNotNull(f.projection().publication).activities.single()
            assertEquals(activity, replay)
        }
    }

    @Test
    fun independentDraftAndTheFirstUnaffectedCandidateSurviveHidingTheNewest() {
        val f = Fixture()
        val old = f.base.activity
        val next =
            old.copy(
                source =
                    (old.source as CollaborationSpaceInboxSource.History).copy(eventId = "\$older"),
                position = old.position.copy(timestamp = old.position.timestamp - 1, ordinal = 2),
            )
        val draft =
            CollaborationSpaceInboxDraft(
                CollaborationDraft(
                    f.base.conversationId,
                    "e9000000-0000-4000-8000-000000000077",
                    "Draft remains",
                    f.base.now,
                ),
                CollaborationSpaceInboxPosition(f.base.now.toEpochMilliseconds(), 3),
            )
        f.version = f.version.copy(lastOrdinal = 3)
        f.current =
            CollaborationSpaceInboxConversation(
                f.version,
                collaborationSpaceInboxEntry(f.base.conversationId, f.createdAt, draft, old),
                draft,
                listOf(old, next),
            )
        f.anchors = listOf(old)
        val publication =
            assertNotNull(
                f.projection(listOf(f.update(f.base.original.copy(locallyHidden = true))))
                    .publication
            )
        assertEquals(next, publication.entry.newest)
        assertEquals(draft, publication.entry.draft)
        assertNull(publication.draft)
        assertEquals(3L, publication.lastOrdinal)
    }

    @Test
    fun duplicatesForeignAnchorsAndDifferentIndexRevisionsFail() {
        val f = Fixture()
        assertIs<AppResult.Failed>(f.result(listOf(f.update(), f.update())))
        f.retain(f.base.activity)
        f.anchors = listOf(f.base.activity, f.base.activity)
        assertIs<AppResult.Failed>(f.result())
        f.anchors =
            listOf(f.base.activity.copy(source = (f.base.source).copy(eventId = "\$foreign")))
        assertIs<AppResult.Failed>(f.result())
        f.anchors = listOf(f.base.activity)
        f.anchorVersion = f.version.copy(revision = f.version.revision + 1)
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.result()).failure.kind)
    }

    @Test
    fun twoPhysicalAndCommandAnchorsCannotBeMergedByPriority() {
        val f = Fixture(true)
        f.original = f.original.copy(logicalId = f.originalId)
        val identity = f.association()
        val command =
            f.base.activity.copy(
                source = identity.association.source,
                position = f.base.activity.position.copy(ordinal = 2),
                preview =
                    f.base.activity.preview!!.copy(delivery = CollaborationMessageDelivery.PENDING),
            )
        f.version = f.version.copy(lastOrdinal = 2)
        f.retain(f.base.activity)
        f.anchors = listOf(f.base.activity, command)
        assertIs<AppResult.Failed>(f.result(listOf(f.update(identity = identity))))
    }

    @Test
    fun malformedUnresolvedHistoryAndWrongPhysicalMappingsNeverBecomeSuccessfulAbsence() {
        val f = Fixture(true)
        assertIs<AppResult.Failed>(
            f.result(listOf(f.update(f.base.original.copy(contentHash = "bad"), f.unresolved)))
        )
        val wrong =
            CollaborationSpaceInboxHistorySource.History(f.base.source.copy(companyId = null))
        assertIs<AppResult.Failed>(f.result(listOf(f.update(identity = wrong))))
        f.original = f.original.copy(logicalId = f.originalId)
        val identity = f.association(true)
        val source = identity.association.source as CollaborationSpaceInboxSource.CompanyOriginal
        val foreign =
            identity.copy(
                association =
                    identity.association.copy(
                        source =
                            source.copy(conversationId = "e9000000-0000-4000-8000-000000000099")
                    )
            )
        assertIs<AppResult.Failed>(f.result(listOf(f.update(identity = foreign))))
    }

    @Test
    fun pendingProvenanceMustStillMatchTheOriginalAndPhysicalRoom() {
        val pending = SpaceInboxEditOutcomeFixture(false)
        val f = Fixture(base = pending.base)
        f.retain(pending.activity)
        val changed = f.base.original.copy(contentHash = "c".repeat(64))
        assertIs<AppResult.Failed>(f.result(listOf(f.update(changed))))
        val pointer = assertNotNull(pending.activity.pendingEdit)
        val foreign = pointer.copy(target = pointer.target.copy(roomId = "!foreign:example.test"))
        f.anchors = listOf(pending.activity.copy(pendingEdit = foreign))
        assertIs<AppResult.Failed>(f.result())
        val empty = Fixture(base = pending.base)
        assertIs<AppResult.Failed>(empty.result(listOf(empty.update(resolved = pointer))))
    }

    @Test
    fun allSixteenHiddenChangesKeepTheSeventeenthVisibleCandidateAndDetachedLists() {
        val f = Fixture()
        val originals =
            List(16) { index ->
                f.base.original.copy(
                    logicalId = null,
                    primaryEventId = "\$hidden-$index",
                    eventIds = listOf("\$hidden-$index"),
                    locallyHidden = true,
                )
            }
        val activities =
            originals.mapIndexed { index, original ->
                f.base.activity.copy(
                    source = f.base.source.copy(eventId = original.primaryEventId),
                    position =
                        CollaborationSpaceInboxPosition(
                            f.base.original.timestamp - index,
                            (index + 1).toLong(),
                        ),
                )
            }
        val fallback =
            f.base.activity.copy(
                source = f.base.source.copy(eventId = "\$fallback"),
                position = CollaborationSpaceInboxPosition(f.base.original.timestamp - 16, 17),
            )
        f.version = f.version.copy(lastOrdinal = 17)
        val candidates = (activities + fallback).toMutableList()
        f.current =
            CollaborationSpaceInboxConversation(
                f.version,
                collaborationSpaceInboxEntry(
                    f.base.conversationId,
                    f.createdAt,
                    null,
                    candidates.first(),
                ),
                null,
                candidates,
            )
        val retained = activities.toMutableList()
        f.anchors = retained
        val updates = originals.map { f.update(it) }.toMutableList()
        val result = f.projection(updates)
        updates.clear()
        retained.clear()
        candidates.clear()
        val publication = assertNotNull(result.publication)
        assertEquals(fallback, publication.entry.newest)
        assertEquals(17L, publication.lastOrdinal)
        assertEquals(16, publication.activities.size)
        assertTrue(publication.activities.all { it.preview == null })
    }

    @Test
    fun maximumBatchAllocatesOnlyNewOriginalsAndRejectsOverflow() {
        val f = Fixture()
        val updates =
            List(16) { index ->
                f.update(
                    f.base.original.copy(
                        logicalId = null,
                        primaryEventId = "\$item-$index",
                        eventIds = listOf("\$item-$index"),
                    )
                )
            }
        val publication = assertNotNull(f.projection(updates).publication)
        assertEquals(16, publication.activities.size)
        assertEquals((1L..16L).toList(), publication.activities.map { it.position.ordinal })
        assertIs<AppResult.Failed>(f.result(updates + f.update()))
        assertIs<AppResult.Failed>(f.result(emptyList()))
        f.version = f.version.copy(lastOrdinal = COLLABORATION_SPACE_INBOX_MAX_ORDER)
        f.current = CollaborationSpaceInboxConversation(f.version, null, null, emptyList())
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.result()).failure.kind)
    }

    private class Fixture(
        legacy: Boolean = false,
        val base: SpaceEditInboxAdmissionFixture = SpaceEditInboxAdmissionFixture(legacy),
    ) {
        val originalId = "e9000000-0000-4000-8000-000000000066"
        var original = base.original
        val createdAt = base.thread.room.binding.conversation.createdAt.toEpochMilliseconds()
        var version = CollaborationSpaceInboxVersion(base.generation, 7, 1)
        var current = CollaborationSpaceInboxConversation(null, null, null, emptyList())
        var anchors = emptyList<CollaborationSpaceInboxActivity>()
        var anchorVersion: CollaborationSpaceInboxVersion? = null
        val unresolved =
            CollaborationSpaceInboxHistorySource.Unresolved(
                CollaborationSpaceInboxHistorySourceReason.ORIGINAL_NOT_PROVEN
            )

        fun retain(activity: CollaborationSpaceInboxActivity) {
            current =
                CollaborationSpaceInboxConversation(
                    version,
                    collaborationSpaceInboxEntry(
                        base.conversationId,
                        createdAt,
                        null,
                        activity.takeIf { it.preview != null },
                    ),
                    null,
                    listOfNotNull(activity.takeIf { it.preview != null }),
                )
            anchors = listOf(activity)
        }

        fun update(
            original: CollaborationHistoryMessage = this.original,
            identity: CollaborationSpaceInboxHistorySource =
                CollaborationSpaceInboxHistorySource.History(
                    base.source.copy(sender = original.sender, eventId = original.primaryEventId)
                ),
            resolved: CollaborationSpaceInboxPendingEdit? = null,
        ) = CollaborationSpaceInboxHistoryUpdate(original, identity, resolved)

        fun association(
            company: Boolean = false,
            delivery: CollaborationMessageDelivery = CollaborationMessageDelivery.PENDING,
        ) =
            CollaborationSpaceInboxHistorySource.Original(
                CollaborationSpaceInboxOriginalAssociation(
                    if (company)
                        CollaborationSpaceInboxSource.CompanyOriginal(
                            (base.location as CollaborationSpaceHistoryLocation.Original)
                                .access
                                .companyId,
                            base.location.conversationId,
                            originalId,
                        )
                    else CollaborationSpaceInboxSource.GroupOriginal(originalId),
                    base.original.timestamp,
                    delivery,
                )
            )

        fun editedOriginal(): CollaborationHistoryMessage {
            val original = base.original
            val edit =
                CollaborationMessageEdit(
                    "\$edit",
                    original.sender,
                    original.primaryEventId,
                    original.timestamp + 1,
                    "Provider edit",
                    "b".repeat(64),
                    "e9000000-0000-4000-8000-000000000099",
                    original.logicalId,
                    original.replyToEventId,
                    original.forwarded,
                )
            return original.copy(
                latestEdit = CollaborationMessageRevision(edit, original.timestamp)
            )
        }

        fun result(updates: List<CollaborationSpaceInboxHistoryUpdate> = listOf(update())) =
            collaborationSpaceInboxHistoryProjection(
                base.access,
                base.thread.room.binding,
                base.location,
                current,
                CollaborationSpaceInboxActivities(anchorVersion ?: current.version, anchors),
                updates,
            )

        fun projection(updates: List<CollaborationSpaceInboxHistoryUpdate> = listOf(update())) =
            assertIs<AppResult.Success<CollaborationSpaceInboxHistoryProjection>>(result(updates))
                .value
    }
}
