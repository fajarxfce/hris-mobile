package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*

class CollaborationSpaceOriginalProviderHistoryPolicyTest {
    @Test
    fun validOriginalPageAndReceiptKeepCompanyAndGroupDeviceIdentitiesSeparate() {
        val f = SpaceOriginalProviderHistoryFixture()
        assertTrue(page(f))
        val result = receipt(f)
        assertTrue(validCollaborationSpaceOriginalProviderAcquisition(result))
        assertNull(result.progress.provider)
        assertNotEquals(result.runtime.deviceId, result.room.traversal.deviceId)
    }

    @Test
    fun foreignPhysicalOwnerFieldsAreRejectedWithoutChangingTheProviderPage() {
        val f = SpaceOriginalProviderHistoryFixture()
        val original = f.page.physical
        val parent = original.owner
        for (bad in
            listOf(
                parent.copy(sessionId = "foreign"),
                parent.copy(audience = "https://other.example/"),
                parent.copy(accountId = f.base.id(201)),
                parent.copy(companyId = f.base.id(201)),
                parent.copy(nativeSessionId = f.base.id(201)),
            )) {
            f.page = f.page.copy(physical = original.copy(owner = bad))
            assertFalse(page(f))
            assertEquals(bad, f.page.physical.owner)
        }
    }

    @Test
    fun anotherRoomOrPhysicalDeviceCannotReuseOriginalTraversal() {
        val f = SpaceOriginalProviderHistoryFixture()
        val original = f.page.physical
        for (bad in
            listOf(
                original.copy(roomId = "!foreign:matrix.hris.internal"),
                original.copy(deviceId = f.runtime.deviceId),
            )) {
            f.page = f.page.copy(physical = bad)
            assertFalse(page(f))
        }
    }

    @Test
    fun sourceStoreUserAndDeviceMustAllMatch() {
        val f = SpaceOriginalProviderHistoryFixture()
        for (source in
            listOf(
                f.base.source.copy(storeId = "b".repeat(64)),
                f.base.source.copy(matrixUserId = "@other:matrix.hris.internal"),
                f.base.source.copy(matrixDeviceId = "OTHER"),
            )) {
            assertFalse(
                validCollaborationSpaceOriginalProviderPage(
                    f.runtime,
                    f.request,
                    source,
                    f.page,
                    null,
                    CollaborationHistoryPageOrigin.Latest,
                )
            )
        }
    }

    @Test
    fun fullRuntimeIdentityCannotBeReplacedByAnEquivalentLookingPage() {
        val f = SpaceOriginalProviderHistoryFixture()
        val original = f.page
        for (runtime in
            listOf(
                f.runtime.copy(operationId = f.base.id(202)),
                f.runtime.copy(deviceId = f.base.id(202)),
                f.runtime.copy(owner = f.runtime.owner.copy(spaceId = f.base.id(202))),
            )) {
            f.page = original.copy(runtime = runtime)
            assertFalse(page(f))
        }
    }

    @Test
    fun missingRoomPublishedTargetAndWrongCompanyAreNotOriginalCaptures() {
        val f = SpaceOriginalProviderHistoryFixture()
        val original = f.request
        for (request in
            listOf(
                original.copy(conversation = original.conversation.copy(room = null)),
                original.copy(
                    captured =
                        original.captured.copy(
                            target =
                                CollaborationSpaceIncomingTarget.Published(
                                    f.base.position.conversationId
                                )
                        )
                ),
                original.copy(
                    captured =
                        original.captured.copy(
                            target =
                                CollaborationSpaceIncomingTarget.Original(
                                    f.base.position.copy(companyId = f.base.id(203))
                                )
                        )
                ),
            )) assertFalse(validCollaborationSpaceOriginalIncomingRoom(f.runtime, request))
    }

    @Test
    fun wrongRequestPositionOrUntilBoundaryCannotAdvanceHistory() {
        val f = SpaceOriginalProviderHistoryFixture()
        val original = f.page.physical
        for (bad in
            listOf(
                original.copy(requestedPosition = "foreign"),
                original.copy(requestedUntilPosition = "foreign"),
            )) {
            f.page = f.page.copy(physical = bad)
            assertFalse(page(f))
        }
    }

    @Test
    fun oversizedOrDuplicateProviderReferencesAreNotAValidPage() {
        val f = SpaceOriginalProviderHistoryFixture()
        val original = f.page.physical
        for (events in
            listOf(
                List(21) { original.events.first().copy(eventId = "\$large-$it") },
                listOf(original.events.first(), original.events.first()),
            )) {
            f.page = f.page.copy(physical = original.copy(events = events))
            assertFalse(page(f))
        }
    }

    @Test
    fun originalReceiptRequiresCompanyProgressAndItsIncomingPhase() {
        val f = SpaceOriginalProviderHistoryFixture()
        val value = receipt(f)
        for (changed in
            listOf(
                value.copy(
                    progress =
                        value.progress.copy(
                            provider =
                                CollaborationHistoryProviderIdentity(
                                    f.base.setup.company,
                                    f.traversal.deviceId,
                                )
                        )
                ),
                value.copy(
                    room =
                        value.room.copy(
                            captured =
                                value.room.captured.copy(
                                    phase = CollaborationSpaceIncomingPhase.Pending
                                )
                        )
                ),
                value.copy(origin = CollaborationHistoryPageOrigin.SavedPosition),
                value.copy(progress = value.progress.copy(incoming = null)),
            )) assertFalse(validCollaborationSpaceOriginalProviderAcquisition(changed))
    }

    @Test
    fun sharedTransitionRejectsRevisionGenerationReferenceAndCursorConflicts() {
        val f = SpaceOriginalProviderHistoryFixture()
        f.phase(CollaborationSpaceIncomingPhase.Provider)
        val previous =
            CollaborationHistoryProgress(
                f.base.id(204),
                2,
                "before",
                3,
                CollaborationIncomingHistoryPosition("head", "until"),
            )
        val after = previous.copy(revision = 3, next = "after", references = 5)
        val value =
            receipt(f)
                .copy(
                    origin = CollaborationHistoryPageOrigin.SavedPosition,
                    previous = previous,
                    progress = after,
                )
        assertTrue(validCollaborationSpaceOriginalProviderAcquisition(value))
        for (bad in
            listOf(
                after.copy(revision = 2),
                after.copy(generation = f.base.id(205)),
                after.copy(references = 2),
                after.copy(references = 6),
                after.copy(next = previous.next),
            )) {
            assertFalse(
                validCollaborationSpaceOriginalProviderAcquisition(value.copy(progress = bad))
            )
        }
    }

    @Test
    fun pendingSeekAndOldWaveBoundaryCannotBeRewrittenByAPageReceipt() {
        val f = SpaceOriginalProviderHistoryFixture()
        f.phase(CollaborationSpaceIncomingPhase.Provider)
        val incoming =
            CollaborationIncomingHistoryPosition(
                "head",
                "until",
                pendingAfter = "pending-cursor",
                pendingThrough = 3,
                pendingRetryRequired = true,
            )
        val previous = CollaborationHistoryProgress(f.base.id(204), 2, "before", 3, incoming)
        assertTrue(validCollaborationHistoryProgress(previous))
        val after = previous.copy(revision = 3, next = "after", references = 5)
        val value =
            receipt(f)
                .copy(
                    origin = CollaborationHistoryPageOrigin.SavedPosition,
                    previous = previous,
                    progress = after,
                )
        assertTrue(validCollaborationSpaceOriginalProviderAcquisition(value))
        for (bad in
            listOf(
                incoming.copy(head = "new-head"),
                incoming.copy(until = "new-until"),
                incoming.copy(pendingAfter = "foreign-cursor"),
                incoming.copy(pendingThrough = 4),
                incoming.copy(pendingRetryRequired = false),
            )) {
            assertFalse(
                validCollaborationSpaceOriginalProviderAcquisition(
                    value.copy(progress = after.copy(incoming = bad))
                )
            )
        }
    }

    @Test
    fun exhaustedSavedCheckpointOnlyAcceptsAnUnchangedZeroReferenceReceipt() {
        val f = SpaceOriginalProviderHistoryFixture()
        f.phase(CollaborationSpaceIncomingPhase.Provider)
        val previous = receipt(f).progress.copy(next = null)
        val value =
            receipt(f)
                .copy(
                    origin = CollaborationHistoryPageOrigin.SavedPosition,
                    previous = previous,
                    progress = previous,
                    references = 0,
                )
        assertTrue(validCollaborationSpaceOriginalProviderAcquisition(value))
        assertFalse(validCollaborationSpaceOriginalProviderAcquisition(value.copy(references = 1)))
        assertFalse(
            validCollaborationSpaceOriginalProviderAcquisition(
                value.copy(progress = previous.copy(revision = previous.revision + 1))
            )
        )
    }

    @Test
    fun publishedPolicyStillRequiresGroupDeviceAndExplicitProviderIdentity() {
        val f = SpaceProviderHistoryFixture()
        val value =
            CollaborationSpaceProviderHistoryAcquisition(
                f.runtime,
                f.traversal,
                CollaborationHistoryPageOrigin.SavedPosition,
                null,
                f.afterCommit,
                20,
            )
        assertTrue(validCollaborationSpaceProviderAcquisition(value))
        assertFalse(
            validCollaborationSpaceProviderAcquisition(
                value.copy(progress = value.progress.copy(provider = null))
            )
        )
        assertFalse(
            validCollaborationSpaceProviderAcquisition(
                value.copy(traversal = value.traversal.copy(deviceId = f.queue.id(206)))
            )
        )
    }

    @Test
    fun groupReceiptCannotBeSubstitutedEvenWhenTheTraversalIsOtherwiseWellFormed() {
        val f = SpaceOriginalProviderHistoryFixture()
        f.request = f.request.copy(traversal = f.traversal.copy(deviceId = f.runtime.deviceId))
        assertFalse(page(f))
    }

    private fun page(f: SpaceOriginalProviderHistoryFixture) =
        validCollaborationSpaceOriginalProviderPage(
            f.runtime,
            f.request,
            f.base.source,
            f.page,
            null,
            CollaborationHistoryPageOrigin.Latest,
        )

    private fun receipt(f: SpaceOriginalProviderHistoryFixture) =
        CollaborationSpaceOriginalProviderHistoryAcquisition(
            f.runtime,
            f.request,
            CollaborationHistoryPageOrigin.Latest,
            null,
            CollaborationHistoryProgress(
                f.base.id(200),
                0,
                f.page.physical.next,
                f.page.physical.events.size.toLong(),
                collaborationIncomingHistoryPosition(
                    f.traversal,
                    null,
                    f.page.physical,
                    CollaborationHistoryPageOrigin.Latest,
                ),
            ),
            f.page.physical.events.size,
        )
}
