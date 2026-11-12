package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class SpaceThreadParticipantPolicyTest {
    @Test
    fun selectedIdentitySurvivesRenewedLoginAndDifferentRegistrationOutcomes() {
        val f = SpaceThreadHistoryFixture()
        val first = f.participants.record()
        val second =
            first.copy(
                command =
                    first.command.copy(
                        id = "d0000000-0000-4000-8000-000000000002",
                        binding =
                            first.command.binding.copy(
                                nativeSessionId = "e0000000-0000-4000-8000-000000000002"
                            ),
                    ),
                revision = 1,
                attempts = 1,
                outcome = CollaborationSpaceRegistrationOutcome.Unconfirmed(),
            )
        assertEquals(
            AppResult.Success(f.participants.selected),
            collaborationSpaceThreadSelectedIdentity(
                f.access.copy(sessionId = "renewed"),
                listOf(first, second),
            ),
        )
        assertEquals(
            AppResult.Success(null),
            collaborationSpaceThreadSelectedIdentity(f.access, emptyList()),
        )
    }

    @Test
    fun conflictingValidProviderProvenanceCannotChooseTheFirstRecord() {
        val f = SpaceThreadHistoryFixture(true)
        val first = f.participants.record()
        val alternate =
            f.participants.selected.copy(
                origin = CollaborationSpaceIdentityOrigin.LEGACY,
                legacyCompanyId = f.room.position.companyId,
                matrixUserId = f.participants.original.matrixUserId,
            )
        val second =
            first.copy(
                command =
                    first.command.copy(
                        id = "d0000000-0000-4000-8000-000000000002",
                        binding =
                            first.command.binding.copy(
                                nativeSessionId = "e0000000-0000-4000-8000-000000000002"
                            ),
                        provider = alternate,
                    )
            )
        assertTrue(validCollaborationSpaceRegistrationRecord(f.access, second))
        for (records in listOf(listOf(first, second), listOf(second, first))) assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(collaborationSpaceThreadSelectedIdentity(f.access, records))
                .failure
                .kind,
        )
    }

    @Test
    fun duplicateForeignAndMalformedRegistrationFactsAreRejected() {
        val f = SpaceThreadHistoryFixture()
        val valid = f.participants.record()
        val other = "e0000000-0000-4000-8000-000000000002"
        val malformed =
            listOf(
                listOf(valid, valid),
                listOf(valid, valid.copy(command = valid.command.copy(id = other))),
                listOf(
                    valid,
                    valid.copy(
                        command =
                            valid.command.copy(
                                binding = valid.command.binding.copy(nativeSessionId = other)
                            )
                    ),
                ),
                listOf(valid.copy(revision = -1)),
                listOf(
                    valid.copy(
                        command =
                            valid.command.copy(
                                binding = valid.command.binding.copy(accountId = other)
                            )
                    )
                ),
                listOf(
                    valid.copy(
                        command =
                            valid.command.copy(
                                binding = valid.command.binding.copy(spaceId = other)
                            )
                    )
                ),
                listOf(
                    valid.copy(
                        command =
                            valid.command.copy(
                                provider =
                                    valid.command.provider.copy(
                                        matrixUserId = "@unowned:example.test"
                                    )
                            )
                    )
                ),
            )
        for (records in malformed) assertIs<AppResult.Failed>(
            collaborationSpaceThreadSelectedIdentity(f.access, records)
        )
    }

    @Test
    fun selectedIdentityReadsAtMostTheBoundedOriginalParentInventory() {
        val f = SpaceThreadHistoryFixture()
        val valid = f.participants.record()
        val records =
            (1..129).map { index ->
                val suffix = index.toString().padStart(12, '0')
                valid.copy(
                    command =
                        valid.command.copy(
                            id = "d0000000-0000-4000-8000-$suffix",
                            binding =
                                valid.command.binding.copy(
                                    nativeSessionId = "e0000000-0000-4000-8000-$suffix"
                                ),
                        )
                )
            }
        assertEquals(
            AppResult.Success(f.participants.selected),
            collaborationSpaceThreadSelectedIdentity(f.access, records.take(128)),
        )
        assertIs<AppResult.Failed>(collaborationSpaceThreadSelectedIdentity(f.access, records))
    }

    @Test
    fun peerLabelsAreCapturedAndCannotChangeAFormerSnapshot() {
        val f = SpaceThreadHistoryFixture()
        val companies = f.participants.contact.companies.toMutableList()
        val contact = f.participants.contact.copy(companies = companies)
        val value =
            assertIs<AppResult.Success<CollaborationSpaceContact?>>(
                    collaborationSpaceThreadContact(f.access, f.participants.peer, contact)
                )
                .value
        companies.clear()
        assertEquals(f.participants.contact, value)
        assertEquals(
            AppResult.Success(null),
            collaborationSpaceThreadContact(f.access, f.participants.peer, null),
        )
    }

    @Test
    fun foreignSelfAndMalformedPeerLabelsAreRejected() {
        val f = SpaceThreadHistoryFixture()
        val valid = f.participants.contact
        for (contact in
            listOf(
                valid.copy(accountId = f.access.accountId),
                valid.copy(accountId = "e0000000-0000-4000-8000-000000000002"),
                valid.copy(displayName = ""),
                valid.copy(companies = emptyList()),
                valid.copy(companies = valid.companies + valid.companies),
            )) assertIs<AppResult.Failed>(
            collaborationSpaceThreadContact(f.access, f.participants.peer, contact)
        )
    }
}
