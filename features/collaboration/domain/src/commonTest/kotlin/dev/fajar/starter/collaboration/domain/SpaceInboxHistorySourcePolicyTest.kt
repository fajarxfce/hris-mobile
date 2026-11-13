package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Instant

class SpaceInboxHistorySourcePolicyTest {
    @Test
    fun canonicalSourceMatchesTheThreadForNativeAndLegacyRooms() {
        for (legacy in listOf(false, true)) {
            val f = Fixture(legacy)
            assertEquals(listOf(f.history), success(f.capture()))
            val value = assertIs<CollaborationSpaceInboxHistorySource.History>(success(f.resolve()))
            assertEquals(f.location.conversationId, value.source.conversationId)
            assertEquals(f.binding.room.roomId, value.source.roomId)
            assertEquals(f.history.primaryEventId, value.source.eventId)
            assertEquals(if (legacy) f.companyAccess.companyId else null, value.source.companyId)
        }
    }

    @Test
    fun groupRecoveryCopyOfALegacyRoomCannotBecomeTheVisibleInboxSource() {
        val f = Fixture()
        val copy =
            CollaborationSpaceHistoryLocation.Space(
                f.access,
                f.binding.conversation.id,
                f.room.roomId,
            )
        assertIs<AppResult.Failed>(f.capture(source = copy))
        assertIs<AppResult.Failed>(f.resolve(source = copy, group = f.group))
    }

    @Test
    fun foreignSourceCoordinatesAndSessionsFailBeforePublishingIdentity() {
        val f = Fixture()
        val source = assertIs<CollaborationSpaceHistoryLocation.Original>(f.location)
        for (wrong in
            listOf(
                source.copy(conversationId = f.otherId),
                source.copy(roomId = "!another:example.test"),
                source.copy(access = source.access.copy(companyId = f.otherId)),
                source.copy(access = source.access.copy(accountId = f.otherId)),
                source.copy(access = source.access.copy(sessionId = f.otherId)),
                source.copy(access = source.access.copy(audience = "https://other.example.test")),
            )) {
            assertIs<AppResult.Failed>(f.capture(source = wrong))
            assertIs<AppResult.Failed>(f.resolve(source = wrong))
        }
    }

    @Test
    fun snapshotMustContainExactlyTheCapturedBoundedRequestInOrder() {
        val f = Fixture()
        for (ids in
            listOf(
                emptyList(),
                List(17) { "\$event-$it" },
                listOf("\$event", "\$event"),
                listOf("invalid"),
            )) {
            assertIs<AppResult.Failed>(f.capture(ids = ids))
        }
        val two = listOf("\$event", "\$second")
        for (events in
            listOf(
                emptyList(),
                listOf(CollaborationSpaceHistorySnapshotEvent("\$another", f.history)),
                listOf(
                    CollaborationSpaceHistorySnapshotEvent("\$event", f.history),
                    CollaborationSpaceHistorySnapshotEvent("\$second", null),
                ),
            )) assertIs<AppResult.Failed>(f.capture(snapshot = f.snapshot.copy(events = events)))
        assertIs<AppResult.Failed>(
            f.capture(
                ids = two,
                snapshot =
                    f.snapshot.copy(
                        events =
                            listOf(
                                CollaborationSpaceHistorySnapshotEvent(two[1], null),
                                CollaborationSpaceHistorySnapshotEvent(two[0], f.history),
                            )
                    ),
            )
        )
    }

    @Test
    fun absenceOrTombstoneWithoutAnOriginalCannotInventAnActivity() {
        val f = Fixture()
        val empty =
            CollaborationSpaceHistorySnapshot(
                null,
                listOf(CollaborationSpaceHistorySnapshotEvent("\$event", null)),
            )
        assertEquals(emptyList(), success(f.capture(snapshot = empty)))
        assertEquals(
            emptyList(),
            success(f.capture(snapshot = empty.copy(revision = "retained:3"))),
        )
        assertIs<AppResult.Failed>(f.capture(snapshot = f.snapshot.copy(revision = null)))
    }

    @Test
    fun invalidRevisionAndMalformedOriginalRemainFailures() {
        val f = Fixture()
        for (revision in listOf("", "r".repeat(129))) {
            assertIs<AppResult.Failed>(f.capture(snapshot = f.snapshot.copy(revision = revision)))
        }
        for (history in
            listOf(
                f.history.copy(eventIds = emptyList()),
                f.history.copy(contentHash = "bad"),
                f.history.copy(timestamp = -1),
            )) {
            assertIs<AppResult.Failed>(
                f.capture(
                    snapshot =
                        f.snapshot.copy(
                            events =
                                listOf(CollaborationSpaceHistorySnapshotEvent("\$event", history))
                        )
                )
            )
            assertIs<AppResult.Failed>(f.resolve(original = history))
        }
    }

    @Test
    fun aliasesAndReplacementEventsShareOneOriginalWithoutTruncatingItsAliases() {
        val f = Fixture()
        val aliases = listOf("\$event") + List(127) { "\$alias-$it" }
        val original = f.history.copy(eventIds = aliases)
        val ids = listOf("\$alias-42", "\$older-replacement", "\$event")
        val snapshot =
            CollaborationSpaceHistorySnapshot(
                "source:4",
                ids.map { CollaborationSpaceHistorySnapshotEvent(it, original) },
            )
        val captured = success(f.capture(ids = ids, snapshot = snapshot))
        assertEquals(listOf(original), captured)
        assertEquals(128, captured.single().eventIds.size)
        assertEquals("\$event", captured.single().primaryEventId)
    }

    @Test
    fun boundedBatchKeepsFirstRequestedOriginalOrderAndPreservesHiddenOrEditedFacts() {
        val f = Fixture()
        val originals =
            List(16) { index ->
                f.history.copy(
                    logicalId = null,
                    primaryEventId = "\$item-$index",
                    eventIds = listOf("\$item-$index"),
                    locallyHidden = index == 2,
                )
            }
        val ids = originals.map { it.primaryEventId }
        val snapshot =
            CollaborationSpaceHistorySnapshot(
                "source:7",
                originals.map { CollaborationSpaceHistorySnapshotEvent(it.primaryEventId, it) },
            )
        assertEquals(originals, success(f.capture(ids = ids, snapshot = snapshot)))
        val edited = f.history.copy(latestEdit = f.edit)
        assertEquals(
            listOf(edited),
            success(
                f.capture(
                    snapshot =
                        f.snapshot.copy(
                            events =
                                listOf(CollaborationSpaceHistorySnapshotEvent("\$event", edited))
                        )
                )
            ),
        )
    }

    @Test
    fun conflictingCopiesOfOnePrimaryCannotBeCoalescedByTheirEventId() {
        val f = Fixture()
        for (other in
            listOf(
                f.history.copy(text = "Different"),
                f.history.copy(locallyHidden = true),
                f.history.copy(latestEdit = f.edit),
                f.history.copy(sender = "@other:example.test"),
            )) {
            val ids = listOf("\$event", "\$replacement")
            val snapshot =
                CollaborationSpaceHistorySnapshot(
                    "source:3",
                    listOf(
                        CollaborationSpaceHistorySnapshotEvent(ids[0], f.history),
                        CollaborationSpaceHistorySnapshotEvent(ids[1], other),
                    ),
                )
            assertIs<AppResult.Failed>(f.capture(ids = ids, snapshot = snapshot))
        }
    }

    @Test
    fun overlappingAliasesAndSplitLogicalOriginalsAreRejected() {
        val f = Fixture()
        val ids = listOf("\$event", "\$second")
        for (other in
            listOf(
                f.history.copy(
                    logicalId = null,
                    primaryEventId = "\$second",
                    eventIds = listOf("\$second", "\$event"),
                ),
                f.history.copy(primaryEventId = "\$second", eventIds = listOf("\$second")),
            )) {
            val snapshot =
                CollaborationSpaceHistorySnapshot(
                    "source:3",
                    listOf(
                        CollaborationSpaceHistorySnapshotEvent(ids[0], f.history),
                        CollaborationSpaceHistorySnapshotEvent(ids[1], other),
                    ),
                )
            assertIs<AppResult.Failed>(f.capture(ids = ids, snapshot = snapshot))
        }
    }

    @Test
    fun sameLogicalIdFromDifferentSendersRemainsTwoIndependentOriginals() {
        val f = Fixture()
        val other =
            f.history.copy(
                sender = "@other:example.test",
                primaryEventId = "\$second",
                eventIds = listOf("\$second"),
            )
        val ids = listOf("\$event", "\$second")
        val snapshot =
            CollaborationSpaceHistorySnapshot(
                "source:3",
                listOf(
                    CollaborationSpaceHistorySnapshotEvent(ids[0], f.history),
                    CollaborationSpaceHistorySnapshotEvent(ids[1], other),
                ),
            )
        assertEquals(listOf(f.history, other), success(f.capture(ids = ids, snapshot = snapshot)))
    }

    @Test
    fun requestedAliasCannotSimultaneouslyBeAbsentOrPointToAnotherOriginal() {
        val f = Fixture()
        val first = f.history.copy(eventIds = listOf("\$event", "\$alias"))
        val other =
            f.history.copy(
                logicalId = null,
                primaryEventId = "\$second",
                eventIds = listOf("\$second"),
            )
        val ids = listOf("\$event", "\$alias")
        for (value in listOf(null, other)) {
            val snapshot =
                CollaborationSpaceHistorySnapshot(
                    "source:3",
                    listOf(
                        CollaborationSpaceHistorySnapshotEvent(ids[0], first),
                        CollaborationSpaceHistorySnapshotEvent(ids[1], value),
                    ),
                )
            assertIs<AppResult.Failed>(f.capture(ids = ids, snapshot = snapshot))
        }
    }

    @Test
    fun capturedOriginalsDoNotRetainMutableAliasLists() {
        val f = Fixture()
        val aliases = mutableListOf("\$event", "\$alias")
        val events =
            mutableListOf(
                CollaborationSpaceHistorySnapshotEvent(
                    "\$event",
                    f.history.copy(eventIds = aliases),
                )
            )
        val captured =
            success(f.capture(snapshot = CollaborationSpaceHistorySnapshot("source:3", events)))
        aliases.clear()
        events.clear()
        assertEquals(listOf("\$event", "\$alias"), captured.single().eventIds)
    }

    @Test
    fun matchedGroupAndCompanyCommandsKeepTheirActualNamespaces() {
        val f = Fixture()
        val group =
            assertIs<CollaborationSpaceInboxHistorySource.Original>(
                success(f.resolve(group = f.group))
            )
        val company =
            assertIs<CollaborationSpaceInboxHistorySource.Original>(
                success(f.resolve(company = f.company))
            )
        assertEquals(CollaborationSpaceInboxSource.GroupOriginal(f.id), group.association.source)
        assertEquals(
            CollaborationSpaceInboxSource.CompanyOriginal(
                f.companyAccess.companyId,
                f.room.position.conversationId,
                f.id,
            ),
            company.association.source,
        )
        assertEquals(CollaborationMessageDelivery.PENDING, group.association.delivery)
        assertEquals(CollaborationMessageDelivery.PENDING, company.association.delivery)
        assertNotEquals(group.association.source, company.association.source)
    }

    @Test
    fun twoProvenOriginalsAreAmbiguousInsteadOfUsingANamespacePriority() {
        val f = Fixture()
        assertEquals(
            CollaborationSpaceInboxHistorySourceReason.MULTIPLE_ORIGINALS,
            assertIs<CollaborationSpaceInboxHistorySource.Unresolved>(
                    success(f.resolve(group = f.group, company = f.company))
                )
                .reason,
        )
    }

    @Test
    fun queuedOriginalWaitsForProofWithoutAllocatingAHistoryAnchor() {
        val f = Fixture()
        val queued = f.group.copy(revision = 0, state = CollaborationMessageState.Queued)
        assertEquals(
            CollaborationSpaceInboxHistorySourceReason.ORIGINAL_NOT_PROVEN,
            assertIs<CollaborationSpaceInboxHistorySource.Unresolved>(
                    success(f.resolve(group = queued))
                )
                .reason,
        )
        val staging =
            f.group.copy(state = CollaborationMessageState.Staging(f.device, emptySet(), f.origin))
        val selected =
            assertIs<CollaborationSpaceInboxHistorySource.Original>(
                success(f.resolve(group = staging))
            )
        assertEquals(CollaborationSpaceInboxSource.GroupOriginal(f.id), selected.association.source)
        assertEquals(CollaborationMessageDelivery.PENDING, selected.association.delivery)
        assertEquals(CollaborationMessageState.Queued, queued.state)
    }

    @Test
    fun missingLegacyOriginRemainsUnresolvedEvenWithAnAcknowledgedEventId() {
        val f = Fixture()
        for (state in
            listOf(
                CollaborationMessageState.Queued,
                CollaborationMessageState.Staging(f.device, emptySet()),
                CollaborationMessageState.Staged(f.device, "txn"),
                CollaborationMessageState.Sent(f.device, "txn", "\$event"),
            )) {
            val record =
                f.company.copy(
                    revision = if (state == CollaborationMessageState.Queued) 0 else 2,
                    state = state,
                )
            assertEquals(
                CollaborationSpaceInboxHistorySourceReason.ORIGINAL_NOT_PROVEN,
                assertIs<CollaborationSpaceInboxHistorySource.Unresolved>(
                        success(f.resolve(company = record))
                    )
                    .reason,
            )
        }
    }

    @Test
    fun unprovenOtherNamespaceCannotBeIgnoredBecauseOneOriginalAlreadyMatches() {
        val f = Fixture()
        val queued = f.group.copy(revision = 0, state = CollaborationMessageState.Queued)
        val legacy = f.company.copy(state = CollaborationMessageState.Staged(f.device, "txn"))
        assertIs<CollaborationSpaceInboxHistorySource.Unresolved>(
            success(f.resolve(group = queued, company = f.company))
        )
        assertIs<CollaborationSpaceInboxHistorySource.Unresolved>(
            success(f.resolve(group = f.group, company = legacy))
        )
    }

    @Test
    fun immutableNonmatchingFactsDoNotParkAnUnrelatedHistoryMessage() {
        val f = Fixture()
        for (record in
            listOf(
                f.company.copy(intent = f.company.intent.copy(conversationId = f.otherId)),
                f.company.copy(
                    intent = f.company.intent.copy(text = "Different"),
                    state = CollaborationMessageState.Staged(f.device, "txn"),
                ),
                f.company.copy(
                    state =
                        CollaborationMessageState.Staged(
                            f.device.copy(roomId = "!other:example.test"),
                            "txn",
                        )
                ),
                f.company.copy(
                    state =
                        CollaborationMessageState.Staged(
                            f.device,
                            "txn",
                            f.origin.copy(sender = "@other:example.test"),
                        )
                ),
                f.company.copy(
                    state =
                        CollaborationMessageState.Staged(
                            f.device,
                            "txn",
                            f.origin.copy(contentHash = "b".repeat(64)),
                        )
                ),
                f.company.copy(revision = 0, state = CollaborationMessageState.Cancelled),
            )) assertIs<CollaborationSpaceInboxHistorySource.History>(
            success(f.resolve(company = record))
        )
        val queued =
            f.group.copy(
                intent = f.group.intent.copy(text = "Different"),
                revision = 0,
                state = CollaborationMessageState.Queued,
            )
        assertIs<CollaborationSpaceInboxHistorySource.History>(success(f.resolve(group = queued)))
    }

    @Test
    fun redactionDoesNotUseMissingTextToDismissAnUnstampedOriginal() {
        val f = Fixture()
        val deleted = f.history.copy(redacted = true, text = null)
        val unknown =
            f.company.copy(
                intent = f.company.intent.copy(text = "Different"),
                state = CollaborationMessageState.Staged(f.device, "txn"),
            )
        assertIs<CollaborationSpaceInboxHistorySource.Unresolved>(
            success(f.resolve(original = deleted, company = unknown))
        )
        assertIs<CollaborationSpaceInboxHistorySource.Original>(
            success(f.resolve(original = deleted, group = f.group))
        )
    }

    @Test
    fun hiddenEditedAndConfirmedMessagesPreserveIdentityWithoutSettlingAnotherCommand() {
        val f = Fixture()
        val sent =
            f.group.copy(
                state = CollaborationMessageState.Sent(f.device, "txn", "\$other-alias", f.origin)
            )
        for (original in
            listOf(
                f.history.copy(locallyHidden = true),
                f.history.copy(latestEdit = f.edit),
                f.history.copy(redacted = true, text = null),
            )) {
            val value =
                assertIs<CollaborationSpaceInboxHistorySource.Original>(
                    success(f.resolve(original = original, group = sent))
                )
            assertEquals(CollaborationMessageDelivery.SENT, value.association.delivery)
            assertEquals(f.time.toEpochMilliseconds(), value.association.timestamp)
            assertEquals(
                CollaborationSpaceInboxSource.GroupOriginal(f.id),
                value.association.source,
            )
        }
        assertIs<CollaborationMessageState.Staged>(f.group.state)
    }

    @Test
    fun commandsMustBeExactLookupResultsAndCannotCrossAnAccountBoundary() {
        val f = Fixture()
        assertIs<AppResult.Failed>(
            f.resolve(group = f.group.copy(intent = f.group.intent.copy(id = f.otherId)))
        )
        assertIs<AppResult.Failed>(
            f.resolve(company = f.company.copy(intent = f.company.intent.copy(id = f.otherId)))
        )
        assertIs<AppResult.Failed>(
            f.resolve(
                group =
                    f.group.copy(
                        intent =
                            f.group.intent.copy(
                                owner = f.group.intent.owner.copy(accountId = f.otherId)
                            )
                    )
            )
        )
        assertIs<AppResult.Failed>(
            f.resolve(
                company =
                    f.company.copy(
                        intent =
                            f.company.intent.copy(
                                owner = f.company.intent.owner.copy(companyId = f.otherId)
                            )
                    )
            )
        )
        assertIs<AppResult.Failed>(f.resolve(group = f.group.copy(revision = -1)))
        assertIs<AppResult.Failed>(f.resolve(company = f.company.copy(revision = -1)))
    }

    @Test
    fun anOriginalWithoutALogicalIdRequiresNoCommandLookupAndKeepsItsPrimaryIdentity() {
        val f = Fixture()
        val original = f.history.copy(logicalId = null)
        val value =
            assertIs<CollaborationSpaceInboxHistorySource.History>(
                success(f.resolve(original = original))
            )
        assertEquals(original.primaryEventId, value.source.eventId)
        assertEquals(original.sender, value.source.sender)
        assertIs<AppResult.Failed>(f.resolve(original = original, group = f.group))
    }

    @Test
    fun nativeGroupBindingCannotAuthorizeACompanyCommandRead() {
        val f = Fixture(false)
        assertIs<CollaborationSpaceInboxHistorySource.Original>(success(f.resolve(group = f.group)))
        assertIs<AppResult.Failed>(f.resolve(company = f.company))
    }

    @Test
    fun replacementCommandsNeverOwnTheOriginalInboxAnchor() {
        val f = Fixture()
        val target =
            CollaborationEditTarget(
                f.room.roomId,
                "\$target",
                f.otherId,
                f.origin.sender,
                "d".repeat(64),
                500,
                500,
            )
        val group = f.group.copy(intent = f.group.intent.copy(editTarget = target))
        val company = f.company.copy(intent = f.company.intent.copy(editTarget = target))
        assertIs<CollaborationSpaceInboxHistorySource.History>(
            success(f.resolve(group = group, company = company))
        )
    }

    @Test
    fun sourceDiagnosticsContainNeitherMessageTextNorProviderIdentifiers() {
        val f = Fixture()
        for (value in listOf(success(f.resolve()), success(f.resolve(group = f.group)))) {
            assertTrue(value.toString().contains("[redacted]"))
            for (identifier in
                listOf(
                    f.id,
                    f.history.text!!,
                    f.room.roomId,
                    f.origin.sender,
                    f.history.primaryEventId,
                )) assertFalse(value.toString().contains(identifier))
        }
    }

    private fun <T> success(value: AppResult<T>) = assertIs<AppResult.Success<T>>(value).value

    private class Fixture(legacy: Boolean = true) {
        val room = SpaceRoomBindingPreparationFixture(legacy)
        val access = room.access
        val binding = room.binding
        val id = "ce000000-0000-4000-8000-000000000001"
        val otherId = "ce000000-0000-4000-8000-000000000002"
        val time = Instant.fromEpochMilliseconds(1000)
        val origin = CollaborationMessageOrigin("@author:example.test", "a".repeat(64))
        val device =
            CollaborationMessageDevice(
                id,
                otherId,
                "ce000000-0000-4000-8000-000000000003",
                room.roomId,
            )
        val state = CollaborationMessageState.Staged(device, "txn", origin)
        val group =
            CollaborationSpaceMessageRecord(
                CollaborationSpaceMessageIntent(
                    id,
                    access.messageOwner(),
                    binding.conversation.id,
                    time,
                    "Original text",
                ),
                2,
                state,
            )
        val companyAccess =
            CollaborationLocalAccess(
                access.sessionId,
                access.audience,
                access.accountId,
                room.position.companyId,
            )
        val company =
            CollaborationMessageRecord(
                CollaborationMessageIntent(
                    id,
                    companyAccess.messageOwner(),
                    room.position.conversationId,
                    time,
                    "Original text",
                ),
                2,
                state,
            )
        val history =
            CollaborationHistoryMessage(
                origin.sender,
                id,
                "\$event",
                2000,
                origin.contentHash,
                "Original text",
                listOf("\$event"),
                false,
            )
        val location =
            assertIs<AppResult.Success<CollaborationSpaceHistoryLocation>>(
                    resolveCollaborationSpaceHistoryLocation(
                        access,
                        binding.conversation.id,
                        binding,
                    )
                )
                .value
        val snapshot =
            CollaborationSpaceHistorySnapshot(
                "source:1",
                listOf(CollaborationSpaceHistorySnapshotEvent("\$event", history)),
            )
        val edit =
            CollaborationMessageRevision(
                CollaborationMessageEdit(
                    "\$edit",
                    origin.sender,
                    history.primaryEventId,
                    2001,
                    "Edited text",
                    "c".repeat(64),
                    originalLogicalId = id,
                ),
                history.timestamp,
            )

        fun capture(
            source: CollaborationSpaceHistoryLocation = location,
            ids: List<String> = listOf("\$event"),
            snapshot: CollaborationSpaceHistorySnapshot = this.snapshot,
        ) = captureCollaborationSpaceInboxHistory(access, binding, source, ids, snapshot)

        fun resolve(
            source: CollaborationSpaceHistoryLocation = location,
            original: CollaborationHistoryMessage = history,
            group: CollaborationSpaceMessageRecord? = null,
            company: CollaborationMessageRecord? = null,
        ) =
            resolveCollaborationSpaceInboxHistorySource(
                access,
                binding,
                source,
                original,
                group,
                company,
            )
    }
}
