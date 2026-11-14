package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceInboxLabelPolicyTest {
    @Test
    fun requestBoundsVisibleIdsAndRejectsAliasesOrDuplicates() {
        val f = SpaceInboxLabelsFixture()
        assertTrue(validCollaborationSpaceInboxLabelRequest(f.access, emptyList()))
        assertTrue(validCollaborationSpaceInboxLabelRequest(f.access, listOf(f.id, f.other)))
        assertFalse(validCollaborationSpaceInboxLabelRequest(f.access, List(51) { f.id }))
        assertFalse(validCollaborationSpaceInboxLabelRequest(f.access, listOf(f.id, f.id)))
        assertFalse(
            validCollaborationSpaceInboxLabelRequest(f.access, listOf("!room:example.test"))
        )
        assertFalse(
            validCollaborationSpaceInboxLabelRequest(f.access.copy(spaceId = "wrong"), listOf(f.id))
        )
    }

    @Test
    fun canonicalPairSelectsTheOtherAccountWithoutInferringItFromTheId() {
        val f = SpaceInboxLabelsFixture()
        val label =
            collaborationSpaceInboxLabelBinding(
                f.access,
                f.id,
                AppResult.Success(f.base.room.conversation.localMetadata()),
            )
        assertEquals(f.contact.accountId, label.peerAccountId)
        assertNull(label.displayName)
        assertNull(label.failure)
    }

    @Test
    fun foreignOrMalformedCanonicalMetadataCannotBecomeAContactBinding() {
        val f = SpaceInboxLabelsFixture()
        val original = f.base.room.conversation.localMetadata()
        for (metadata in
            listOf(
                original.copy(id = f.other),
                original.copy(spaceId = f.other),
                original.copy(version = 1),
            )) {
            val label =
                collaborationSpaceInboxLabelBinding(f.access, f.id, AppResult.Success(metadata))
            assertNull(label.peerAccountId)
            assertEquals(FailureKind.Unexpected, label.failure?.kind)
        }
    }

    @Test
    fun missingCanonicalFactsAndFailedReadsRemainDistinct() {
        val f = SpaceInboxLabelsFixture()
        val missing = collaborationSpaceInboxLabelBinding(f.access, f.id, AppResult.Success(null))
        assertNull(missing.failure)
        val failed =
            collaborationSpaceInboxLabelBinding(f.access, f.id, AppResult.Failed(f.failure))
        assertEquals(f.failure, failed.failure)
        assertNull(failed.peerAccountId)
    }

    @Test
    fun contactMustMatchTheCheckedCanonicalPeer() {
        val f = SpaceInboxLabelsFixture()
        val binding = CollaborationSpaceInboxLabel(f.id, f.contact.accountId)
        val valid =
            collaborationSpaceInboxLabelContact(f.access, binding, AppResult.Success(f.contact))
        assertEquals(f.contact.displayName, valid.displayName)
        for (contact in
            listOf(
                f.contact.copy(accountId = f.otherPeer),
                f.contact.copy(displayName = ""),
                f.contact.copy(companies = emptyList()),
            )) {
            val invalid =
                collaborationSpaceInboxLabelContact(f.access, binding, AppResult.Success(contact))
            assertNull(invalid.displayName)
            assertEquals(FailureKind.Unexpected, invalid.failure?.kind)
        }
    }

    @Test
    fun contactRemovalAndFailureDoNotInventAnotherNameOrDropCanonicalIdentity() {
        val f = SpaceInboxLabelsFixture()
        val binding = CollaborationSpaceInboxLabel(f.id, f.contact.accountId, "Previously visible")
        val removed =
            collaborationSpaceInboxLabelContact(f.access, binding, AppResult.Success(null))
        assertNull(removed.displayName)
        assertNull(removed.failure)
        assertEquals(binding.peerAccountId, removed.peerAccountId)
        val failed =
            collaborationSpaceInboxLabelContact(f.access, binding, AppResult.Failed(f.failure))
        assertNull(failed.displayName)
        assertEquals(f.failure, failed.failure)
        assertEquals(binding.peerAccountId, failed.peerAccountId)
    }
}
