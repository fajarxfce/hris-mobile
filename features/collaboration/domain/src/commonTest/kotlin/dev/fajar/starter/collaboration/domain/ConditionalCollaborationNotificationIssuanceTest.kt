package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ConditionalCollaborationNotificationIssuanceTest {
    @Test
    fun exactReferenceCanReplayAndSilentlyReplacePrivacyOrNativeFamily() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val first = ready(f.issue()).publication
        assertEquals(first, ready(f.issuer(f.scope(), f.message(), first.reference())).publication)
        assertEquals(4, f.seals)
        f.projection.preference(
            CollaborationNotificationSettings(preview = CollaborationNotificationPreview.Hidden)
        )
        f.projection.settings.state =
            f.projection.settings.state.copy(
                version = f.projection.settings.state.version!!.copy(revision = 1)
            )
        val changed = ready(f.issuer(f.scope(), f.message(), first.reference()))
        assertFalse(changed.newOriginal)
        assertNotEquals(first.body.target.renderId, changed.publication.body.target.renderId)
        assertNull(changed.publication.body.message!!.body)
        val writes = f.writes
        assertEquals(
            AppResult.Success(CollaborationNotificationIssuance.Unchanged),
            f.issuer(f.scope(), f.message(), first.reference()),
        )
        assertEquals(writes, f.writes)
        assertEquals(changed.publication, f.stored)
        f.replaceFamily()
        val renewed = ready(f.issuer(f.scope(), f.message(), changed.publication.reference()))
        assertFalse(renewed.newOriginal)
        assertEquals(f.parent, renewed.publication.body.parent)
        assertNotEquals(
            changed.publication.body.target.renderId,
            renewed.publication.body.target.renderId,
        )
    }

    @Test
    fun acknowledgmentOnlyVersionChangePreventsStaleIssuanceWithoutSealingOrMutation() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val first = ready(f.issue()).publication
        f.stored =
            first.copy(
                version = first.version.copy(revision = first.version.revision + 1),
                body = first.body.copy(state = CollaborationNotificationPublicationState.Published),
            )
        val acknowledged = f.stored
        val opens = f.opens
        assertEquals(
            AppResult.Success(CollaborationNotificationIssuance.Unchanged),
            f.issuer(f.scope(), f.message(), first.reference()),
        )
        assertEquals(acknowledged, f.stored)
        assertEquals(4, f.seals)
        assertEquals(opens, f.opens)
        assertEquals(1, f.writes)
        f.stored = null
        assertEquals(
            AppResult.Success(CollaborationNotificationIssuance.Unchanged),
            f.issuer(f.scope(), f.message(), first.reference()),
        )
        assertNull(f.stored)
        assertEquals(1, f.writes)
    }

    @Test
    fun malformedForeignAccountAndWrongConversationConditionsFailBeforeJournalIo() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val reference = ready(f.issue()).publication.reference()
        val foreign = CollaborationNotificationIssuanceFixture.id(7997)
        val invalid =
            listOf(
                reference.copy(id = "invalid"),
                reference.copy(parent = reference.parent.copy(accountId = foreign)),
                reference.copy(
                    parent = reference.parent.copy(audience = "https://foreign.example.test/")
                ),
                reference.copy(target = reference.target.copy(companyId = foreign)),
                reference.copy(target = reference.target.copy(conversationId = foreign)),
            )
        val reads = f.reads
        val message = f.message()
        for (condition in invalid) assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.issuer(f.scope(), message, condition)).failure.kind,
        )
        assertEquals(reads, f.reads)
        assertEquals(1, f.writes)
        assertEquals(4, f.seals)
    }

    private fun ready(result: AppResult<CollaborationNotificationIssuance>) =
        assertIs<CollaborationNotificationIssuance.Ready>(
            assertIs<AppResult.Success<CollaborationNotificationIssuance>>(result).value
        )

    private fun CollaborationNotificationPublication.reference() =
        CollaborationNotificationPublicationReference(
            id,
            version,
            body.parent,
            body.target,
            body.state,
        )
}
