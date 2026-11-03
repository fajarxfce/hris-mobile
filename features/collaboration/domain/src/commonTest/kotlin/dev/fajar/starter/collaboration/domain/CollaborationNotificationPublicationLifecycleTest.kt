package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest

class CollaborationNotificationPublicationLifecycleTest {
    @Test
    fun cancelledSourceValidationChecksBothTheExactInactiveWriteAndOriginalTimestamp() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = publication(f.issue())
        val receipt =
            settled(f.finisher(original, CollaborationNotificationPublicationState.Cancelled))
        assertEquals(AppResult.Success(Unit), f.validator.afterCancellation(original, receipt))
        val retained = f.stored!!
        assertNull(retained.body.actions)
        assertNull(retained.body.message)
        f.projection.original =
            f.projection.original!!.copy(timestamp = original.body.timestamp + 1)
        failed(f.validator.afterCancellation(original, receipt), FailureKind.Conflict)
        f.projection.original = f.projection.original!!.copy(timestamp = original.body.timestamp)
        f.stored =
            retained.copy(version = retained.version.copy(revision = retained.version.revision + 1))
        failed(f.validator.afterCancellation(original, receipt), FailureKind.Conflict)
        assertEquals(2, f.writes)
    }

    @Test
    fun cancelledSourceValidationRejectsForeignReceiptAndLateParentReplacement() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = publication(f.issue())
        val receipt =
            settled(f.finisher(original, CollaborationNotificationPublicationState.Cancelled))
        for (invalid in
            listOf(
                receipt.copy(id = "foreign"),
                receipt.copy(notificationId = "foreign"),
                receipt.copy(renderId = "foreign"),
                receipt.copy(state = CollaborationNotificationPublicationState.Published),
                receipt.copy(version = original.version),
            )) failed(f.validator.afterCancellation(original, invalid), FailureKind.Validation)
        f.replaceFamily()
        failed(f.validator.afterCancellation(original, receipt), FailureKind.Unauthorized)
        assertEquals(CollaborationNotificationPublicationState.Cancelled, f.stored!!.body.state)
    }

    @Test
    fun exactPreparedAndPublishedValidationIsReadOnlyAndWorksWithExpiredLocalFacts() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        f.local.setup.now += 365.days
        f.local.setup.workspace = null
        f.local.setup.beforeWorkspace = { error("No selected workspace") }
        val original = publication(f.issue())
        assertEquals(AppResult.Success(Unit), f.validator(original))
        assertEquals(1, f.writes)
        assertEquals(4, f.seals)
        settled(f.finisher(original, CollaborationNotificationPublicationState.Published))
        val published = f.stored!!
        assertEquals(AppResult.Success(Unit), f.validator(published))
        assertEquals(2, f.writes)
        assertEquals(original.body.actions, published.body.actions)
        assertEquals(0, f.local.setup.calls)
    }

    @Test
    fun staleAbsentInactiveAndMalformedSnapshotsNeverValidate() = runTest {
        for (change in 0..3) {
            val f = CollaborationNotificationIssuanceFixture()
            val original = publication(f.issue())
            val candidate =
                when (change) {
                    0 -> {
                        f.inactive()
                        original
                    }
                    1 -> {
                        f.stored = null
                        original
                    }
                    2 -> {
                        f.inactive()
                        f.stored!!
                    }
                    else -> original.copy(id = "invalid")
                }
            failed(
                f.validator(candidate),
                if (change < 2) FailureKind.Conflict else FailureKind.Validation,
            )
            assertEquals(4, f.seals)
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun changedSourceReadMuteIdentityAndTimestampWithholdValidation() = runTest {
        for (change in 0..4) {
            val f = CollaborationNotificationIssuanceFixture()
            val original = publication(f.issue())
            when (change) {
                0 ->
                    f.projection.original =
                        f.projection.original!!.copy(redacted = true, text = null)
                1 -> f.projection.original = f.projection.original!!.copy(timestamp = 999)
                2 ->
                    f.projection.preference(
                        CollaborationNotificationSettings(
                            mute = CollaborationNotificationMute.Forever
                        )
                    )
                3 -> f.projection.self = CollaborationSelfIdentity(f.projection.original!!.sender)
                4 ->
                    f.projection.read.state =
                        CollaborationReadState(
                            CollaborationReadStateRevision(
                                CollaborationNotificationIssuanceFixture.id(8001),
                                0,
                            ),
                            setOf(original.body.target.eventId),
                            emptyList(),
                            null,
                        )
            }
            failed(f.validator(original), FailureKind.Conflict)
            assertEquals(original, f.stored)
        }
    }

    @Test
    fun denialOrNativeReplacementDuringEnvelopeAuthenticationWinsOverStorageFailure() = runTest {
        for (denied in listOf(true, false)) {
            val f = CollaborationNotificationIssuanceFixture()
            val original = publication(f.issue())
            f.afterOpen = {
                if (denied) {
                    f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                    f.local.setup.sessionFailure = f.local.storage
                } else f.replaceFamily()
            }
            val result = f.validator(original)
            if (denied) assertEquals(AppResult.Failed(f.local.denied), result)
            else failed(result, FailureKind.Unauthorized)
            assertEquals(original, f.stored)
        }
    }

    @Test
    fun forgedActionAndConcurrentReplacementCannotValidateAnOldRender() = runTest {
        for (replace in listOf(false, true)) {
            val f = CollaborationNotificationIssuanceFixture()
            val original = publication(f.issue())
            if (replace) f.afterOpen = { f.inactive() } else f.wrongOpenedAction = true
            failed(f.validator(original), FailureKind.Conflict)
            assertEquals(4, f.seals)
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun validationDeadlineAndCallerCancellationJoinPendingAuthentication() = runTest {
        for (cancel in listOf(false, true)) {
            val f = CollaborationNotificationIssuanceFixture()
            val original = publication(f.issue())
            val entered = CompletableDeferred<Unit>()
            var joined = false
            f.afterOpen = {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            val job = async { f.validator(original) }
            select {
                entered.onAwait {}
                job.onAwait { fail("Validation returned before authentication barrier") }
            }
            if (cancel) job.cancelAndJoin() else failed(job.await(), FailureKind.Unavailable)
            assertTrue(joined)
            assertEquals(original, f.stored)
        }
    }

    @Test
    fun acknowledgmentAndRetirementAreExactAndReturnOnlyOpaqueMetadata() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = publication(f.issue())
        val acknowledgment =
            settled(f.finisher(original, CollaborationNotificationPublicationState.Published))
        val published = f.stored!!
        assertEquals(original.body.message, published.body.message)
        assertEquals(original.body.actions, published.body.actions)
        assertEquals(published.version, acknowledgment.version)
        assertEquals(
            acknowledgment,
            settled(f.finisher(published, CollaborationNotificationPublicationState.Published)),
        )
        assertEquals(2, f.writes)
        val retired =
            settled(f.finisher(published, CollaborationNotificationPublicationState.Dismissed))
        assertEquals(CollaborationNotificationPublicationState.Dismissed, retired.state)
        assertEquals(original.body.target.notificationId, retired.notificationId)
        assertEquals(original.body.target.renderId, retired.renderId)
        assertNull(f.stored!!.body.message)
        assertNull(f.stored!!.body.actions)
        assertFalse(retired.toString().contains("Private message"))
        assertEquals(
            retired,
            settled(f.finisher(f.stored!!, CollaborationNotificationPublicationState.Cancelled)),
        )
        assertEquals(3, f.writes)
        failed(
            f.finisher(f.stored!!, CollaborationNotificationPublicationState.Published),
            FailureKind.Conflict,
        )
        failed(
            f.finisher(f.stored!!, CollaborationNotificationPublicationState.Prepared),
            FailureKind.Validation,
        )
    }

    @Test
    fun staleSettlementAndAbsentRecordNeverAcknowledgeOrCancelNewerState() = runTest {
        for (missing in listOf(false, true)) {
            val f = CollaborationNotificationIssuanceFixture()
            val original = publication(f.issue())
            if (missing) f.stored = null else f.inactive()
            val retained = f.stored
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationSettlement.Contended),
                f.finisher(original, CollaborationNotificationPublicationState.Cancelled),
            )
            assertEquals(retained, f.stored)
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun currentAccountCanCleanOldNativeFamilyUnderDenialWithoutReadingCompanySources() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = publication(f.issue())
        val historyReads = f.projection.historyCalls
        val opens = f.opens
        f.replaceFamily()
        f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
        f.projection.original = null
        f.projection.historyFailure = f.local.storage
        failed(
            f.finisher(original, CollaborationNotificationPublicationState.Published),
            FailureKind.Unauthorized,
        )
        val retired =
            settled(f.finisher(original, CollaborationNotificationPublicationState.Cancelled))
        assertEquals(CollaborationNotificationPublicationState.Cancelled, retired.state)
        assertNull(f.stored!!.body.message)
        assertNull(f.stored!!.body.actions)
        assertEquals(historyReads, f.projection.historyCalls)
        assertEquals(opens, f.opens)
        assertEquals(original.body.parent, f.stored!!.body.parent)
    }

    @Test
    fun anotherAccountCannotSettleTheOldAccountsPublication() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = publication(f.issue())
        val reads = f.reads
        val session = f.local.setup.session!!
        f.local.setup.session =
            session.copy(
                user = session.user.copy(id = CollaborationNotificationIssuanceFixture.id(8999))
            )
        failed(
            f.finisher(original, CollaborationNotificationPublicationState.Cancelled),
            FailureKind.Unauthorized,
        )
        assertEquals(reads, f.reads)
        assertEquals(original, f.stored)
    }

    @Test
    fun lostSettlementResponseKeepsTheWatermarkWithoutAnImplicitRetry() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = publication(f.issue())
        f.writeOutcome = CollaborationNotificationPublicationWrite.Contended
        assertEquals(
            AppResult.Success(CollaborationNotificationPublicationSettlement.Contended),
            f.finisher(original, CollaborationNotificationPublicationState.Cancelled),
        )
        val retained = f.stored!!
        assertNull(retained.body.message)
        assertNull(retained.body.actions)
        assertEquals(2, f.writes)
        f.writeOutcome = null
        assertEquals(
            AppResult.Success(CollaborationNotificationPublicationSettlement.Contended),
            f.finisher(original, CollaborationNotificationPublicationState.Cancelled),
        )
        settled(f.finisher(retained, CollaborationNotificationPublicationState.Cancelled))
        assertEquals(2, f.writes)
    }

    @Test
    fun settlementIsNonwaitingAndCancellationJoinsWithoutAnotherWrite() = runTest {
        val f = CollaborationNotificationIssuanceFixture()
        val original = publication(f.issue())
        val entered = CompletableDeferred<Unit>()
        var joined = false
        f.afterRead = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                joined = true
            }
        }
        val job = async {
            f.finisher(original, CollaborationNotificationPublicationState.Cancelled)
        }
        select {
            entered.onAwait {}
            job.onAwait { fail("Settlement returned before read barrier") }
        }
        failed(
            f.finisher(original, CollaborationNotificationPublicationState.Published),
            FailureKind.Unavailable,
        )
        job.cancelAndJoin()
        assertTrue(joined)
        assertEquals(original, f.stored)
        assertEquals(1, f.writes)
    }

    @Test
    fun postCommitTimeoutAndParentReplacementWithholdResultButKeepRetiredData() = runTest {
        for (replace in listOf(false, true)) {
            val f = CollaborationNotificationIssuanceFixture()
            val original = publication(f.issue())
            var joined = false
            f.afterWrite = {
                if (replace) {
                    f.replaceFamily()
                    joined = true
                } else
                    try {
                        awaitCancellation()
                    } finally {
                        joined = true
                    }
            }
            failed(
                f.finisher(original, CollaborationNotificationPublicationState.Cancelled),
                if (replace) FailureKind.Unauthorized else FailureKind.Unavailable,
            )
            assertTrue(joined)
            assertEquals(CollaborationNotificationPublicationState.Cancelled, f.stored!!.body.state)
            assertNull(f.stored!!.body.message)
            assertNull(f.stored!!.body.actions)
            assertEquals(2, f.writes)
        }
    }

    @Test
    fun malformedStoredResponseAndCapacityFailureCannotProduceSettlementMetadata() = runTest {
        for (capacity in listOf(false, true)) {
            val f = CollaborationNotificationIssuanceFixture()
            val original = publication(f.issue())
            f.commit = false
            f.writeOutcome =
                if (capacity) CollaborationNotificationPublicationWrite.CapacityExceeded
                else CollaborationNotificationPublicationWrite.Stored(original)
            failed(
                f.finisher(original, CollaborationNotificationPublicationState.Published),
                if (capacity) FailureKind.Storage else FailureKind.Conflict,
            )
            assertEquals(original, f.stored)
        }
    }

    private fun publication(result: AppResult<CollaborationNotificationIssuance>) =
        assertIs<CollaborationNotificationIssuance.Ready>(
                assertIs<AppResult.Success<CollaborationNotificationIssuance>>(result).value
            )
            .publication

    private fun settled(result: AppResult<CollaborationNotificationPublicationSettlement>) =
        assertIs<CollaborationNotificationPublicationSettlement.Settled>(
            assertIs<AppResult.Success<CollaborationNotificationPublicationSettlement>>(result)
                .value
        )

    private fun failed(result: AppResult<*>, kind: FailureKind) =
        assertEquals(kind, assertIs<AppResult.Failed>(result).failure.kind)
}
