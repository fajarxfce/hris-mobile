package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationSpaceNewRoomQueueTest {
    @Test
    fun newRoomUsesSpaceCommandsAndRetainsActualPhysicalIdentity() {
        val f = Fixture()
        assertNull(assertIs<AppResult.Success<CollaborationLocalAccess?>>(f.access()).value)
        val match =
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(f.match()).value
        assertEquals(f.snapshot.original.binding, match.binding)
        assertEquals(f.snapshot.original.device, match.device)
        assertEquals(
            f.record,
            assertIs<CollaborationMatchedQueueMessage.Space>(match.messages.single()).record,
        )
        assertNotEquals(f.record.intent.owner.spaceId, match.binding.companyId)
        assertNotEquals(f.record.intent.conversationId, f.model.queue.record.intent.conversationId)
    }

    @Test
    fun emptyNewRoomPreservesPhysicalScopeWithoutInventingAnOldConversation() {
        val f = Fixture()
        val empty = f.snapshot.copy(original = f.snapshot.original.copy(messages = emptyList()))
        assertNull(
            assertIs<AppResult.Success<CollaborationLocalAccess?>>(f.access(snapshot = empty)).value
        )
        assertTrue(
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(
                    f.match(snapshot = empty, records = emptyList())
                )
                .value
                .messages
                .isEmpty()
        )
        assertIs<AppResult.Failed>(f.match(snapshot = empty))
    }

    @Test
    fun oldCompanyCommandCannotBeRelabeledAsANewRoomCommand() {
        val f = Fixture()
        val old = f.model.queue.record
        assertIs<AppResult.Failed>(
            matchCollaborationMixedOriginalQueue(
                f.model.queue.runtime,
                f.model.conversation,
                f.binding,
                f.snapshot,
                listOf(old),
                emptyList(),
                listOf(f.record),
                emptyList(),
            )
        )
        assertIs<AppResult.Failed>(
            f.match(
                records =
                    listOf(
                        f.record.copy(
                            intent =
                                f.record.intent.copy(conversationId = old.intent.conversationId)
                        )
                    )
            )
        )
    }

    @Test
    fun newRoomCannotCarryForgedLegacyMetadata() {
        val f = Fixture()
        assertIs<AppResult.Failed>(
            f.access(binding = f.binding.copy(original = f.model.binding.original))
        )
        assertIs<AppResult.Failed>(
            f.access(
                binding =
                    f.binding.copy(room = f.binding.room.copy(original = f.model.room.original))
            )
        )
        assertIs<AppResult.Failed>(
            f.access(
                binding =
                    f.binding.copy(
                        room = f.binding.room.copy(origin = CollaborationSpaceRoomOrigin.LEGACY)
                    )
            )
        )
    }

    @Test
    fun parentDeviceAndRoomCoordinatesAreCheckedEvenForAnEmptyQueue() {
        val f = Fixture()
        val empty = f.snapshot.copy(original = f.snapshot.original.copy(messages = emptyList()))
        val physical = empty.original
        for (changed in
            listOf(
                empty.copy(runtime = empty.runtime.copy(operationId = f.model.queue.id(999))),
                empty.copy(
                    original =
                        physical.copy(
                            binding = physical.binding.copy(accountId = f.model.queue.id(999))
                        )
                ),
                empty.copy(
                    original =
                        physical.copy(
                            binding = physical.binding.copy(audience = "https://foreign.test/")
                        )
                ),
                empty.copy(
                    original =
                        physical.copy(
                            binding =
                                physical.binding.copy(
                                    companyId = "00000000-0000-0000-0000-000000000000"
                                )
                        )
                ),
                empty.copy(
                    original =
                        physical.copy(
                            device = physical.device.copy(nativeSessionId = f.model.queue.id(999))
                        )
                ),
                empty.copy(
                    original =
                        physical.copy(
                            device = physical.device.copy(roomId = f.model.queue.device.roomId)
                        )
                ),
                empty.copy(
                    original =
                        physical.copy(
                            device = physical.device.copy(bootstrapOperationId = "invalid")
                        )
                ),
            )) assertIs<AppResult.Failed>(f.access(snapshot = changed))
    }

    @Test
    fun malformedDuplicateForeignAndOversizedCapturesFailBeforeLookup() {
        val f = Fixture()
        val original = f.snapshot.original.messages.single()
        for (originals in
            listOf(
                listOf(original.copy(logicalId = null)),
                listOf(original.copy(logicalId = "00000000-0000-0000-0000-000000000000")),
                listOf(original, original),
                listOf(
                    original.copy(origin = original.origin.copy(sender = "@foreign:example.test"))
                ),
                listOf(original.copy(serializedContent = "")),
                List(129) { original },
            )) assertIs<AppResult.Failed>(
            f.access(
                snapshot =
                    f.snapshot.copy(original = f.snapshot.original.copy(messages = originals))
            )
        )
    }

    @Test
    fun sentNewRoomOriginalStillNeedsExactImmutableCompletion() {
        val f = Fixture()
        val staged = f.record.state as CollaborationMessageState.Staged
        val sent =
            f.record.copy(
                revision = 3,
                state =
                    CollaborationMessageState.Sent(
                        staged.device,
                        staged.transactionId,
                        "\$accepted",
                        staged.origin,
                    ),
            )
        assertIs<AppResult.Failed>(f.match(records = listOf(sent)))
        val proof = CollaborationSpaceMessageCompletion(sent, listOf("\$accepted"))
        assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(
            f.match(records = listOf(sent), completions = listOf(proof))
        )
        assertIs<AppResult.Failed>(
            f.match(
                records = listOf(sent),
                completions = listOf(proof.copy(record = sent.copy(revision = 4))),
            )
        )
    }

    @Test
    fun transportMatchesTheInstalledSenderAndDeviceForEitherRoomOrigin() {
        val f = Fixture()
        assertIs<AppResult.Success<Unit>>(f.transport())
        assertFalse(f.observed.recipientReady)
        // A transport match is not readiness or permission to resume.
        val m = f.model
        assertIs<AppResult.Success<Unit>>(
            matchCollaborationSpaceQueueTransport(
                m.queue.runtime,
                m.conversation,
                m.binding,
                m.transport,
                m.queue.snapshot,
                CollaborationNativePlatform.ANDROID,
            )
        )
    }

    @Test
    fun transportCannotSubstituteLogicalDeviceOrAnotherOriginalSender() {
        val f = Fixture()
        for (observed in
            listOf(
                f.observed.copy(
                    matrixDeviceId = "HRIS_${f.model.queue.runtime.deviceId.replace("-", "")}"
                ),
                f.observed.copy(
                    matrixUserId =
                        "@hris_${f.model.queue.id(999).replace("-", "")}_${f.model.queue.owner.parent.accountId.replace("-", "")}:example.test"
                ),
                f.observed.copy(device = f.observed.device.copy(id = f.model.queue.id(999))),
                f.observed.copy(
                    device = f.observed.device.copy(origin = CollaborationSpaceDeviceOrigin.SPACE)
                ),
                f.observed.copy(room = f.model.room),
                f.observed.copy(
                    conversation = f.model.conversation.copy(id = f.model.queue.id(999))
                ),
            )) assertIs<AppResult.Failed>(f.transport(observed))
        assertIs<AppResult.Failed>(f.transport(platform = CollaborationNativePlatform.IOS))
    }

    @Test
    fun localReadOfANewRoomNeverConsultsTheCompanyCommandNamespace() = runTest {
        val f = Fixture()
        f.source.base.failures["get"] = f.source.base.directory.storageFailure
        f.source.base.failures["completion"] = f.source.base.directory.storageFailure
        val result =
            assertIs<AppResult.Success<CollaborationMixedOriginalQueueMatch>>(f.source.read()).value
        assertEquals(
            f.record,
            assertIs<CollaborationMatchedQueueMessage.Space>(result.messages.single()).record,
        )
        assertEquals(0, f.source.base.count("get"))
        assertEquals(0, f.source.base.count("completion"))
        assertEquals(1, f.source.base.count("space-get"))
        assertEquals(2, f.source.base.count("inspect"))
        f.source.noDelivery()
    }

    @Test
    fun missingSpaceRecordCannotFallBackToACoincidentCompanyId() = runTest {
        val f = Fixture()
        f.source.records.clear()
        f.source.base.records[f.record.intent.id] =
            f.model.queue.record.copy(
                intent = f.model.queue.record.intent.copy(id = f.record.intent.id)
            )
        assertIs<AppResult.Failed>(f.source.read())
        assertEquals(0, f.source.base.count("get"))
        f.source.noDelivery()
    }

    @Test
    fun newRoomReadRetainsFailuresAndConfirmedDenials() = runTest {
        for (denied in listOf(false, true)) {
            val f = Fixture()
            f.source.base.failures["space-get"] = f.source.base.directory.storageFailure
            if (denied) f.source.base.hooks["space-get"] = { f.source.base.directory.deny() }
            assertEquals(
                if (denied) f.source.base.directory.denied
                else f.source.base.directory.storageFailure,
                assertIs<AppResult.Failed>(f.source.read()).failure,
            )
            assertEquals(0, f.source.base.count("get"))
            f.source.noDelivery()
        }
    }

    @Test
    fun newRoomReadCannotPublishAfterBindingOrNativeInventoryChanges() = runTest {
        for (binding in listOf(false, true)) {
            val f = Fixture()
            f.source.base.hooks["space-get"] = {
                if (binding) f.source.base.retained = f.model.binding
                else
                    f.source.base.snapshot =
                        f.snapshot.copy(original = f.snapshot.original.copy(messages = emptyList()))
            }
            assertIs<AppResult.Failed>(f.source.read())
            f.source.noDelivery()
        }
    }

    private class Fixture {
        val source = MixedOriginalQueueReadFixture()
        val model = source.base.model
        val room =
            model.room.copy(
                roomId = "!new-space:example.test",
                origin = CollaborationSpaceRoomOrigin.SPACE,
                original = null,
            )
        val binding = model.binding.copy(room = room, original = null)
        val device = model.queue.device.copy(roomId = room.roomId)
        val record =
            source.record.copy(
                state =
                    (source.record.state as CollaborationMessageState.Staged).copy(device = device)
            )
        val snapshot =
            source.base.snapshot.copy(
                original =
                    source.base.snapshot.original.copy(
                        device = device,
                        messages = listOf(source.original),
                    )
            )
        val observed = model.transport.copy(room = room)

        init {
            source.base.retained = binding
            source.base.snapshot = snapshot
            source.records[record.intent.id] = record
        }

        fun access(
            snapshot: CollaborationSpaceOriginalQueueSnapshot = this.snapshot,
            binding: CollaborationSpaceRoomBinding = this.binding,
        ) =
            collaborationMixedQueueCompanyAccess(
                model.queue.runtime,
                model.conversation,
                binding,
                snapshot,
            )

        fun match(
            snapshot: CollaborationSpaceOriginalQueueSnapshot = this.snapshot,
            records: List<CollaborationSpaceMessageRecord> = listOf(record),
            completions: List<CollaborationSpaceMessageCompletion> = emptyList(),
        ) =
            matchCollaborationMixedOriginalQueue(
                model.queue.runtime,
                model.conversation,
                binding,
                snapshot,
                emptyList(),
                emptyList(),
                records,
                completions,
            )

        fun transport(
            observed: CollaborationSpaceDirectTransport = this.observed,
            platform: CollaborationNativePlatform = CollaborationNativePlatform.ANDROID,
        ) =
            matchCollaborationSpaceQueueTransport(
                model.queue.runtime,
                model.conversation,
                binding,
                observed,
                snapshot,
                platform,
            )
    }
}
