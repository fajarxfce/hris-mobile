package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationNotificationRetirementTest {
    @Test
    fun discoveryReturnsOnlyOwnedCleanupReferencesForPreparedAndPublishedRecords() = runTest {
        val f = RetirementFixture()
        f.prepare()
        val first = refs(f.read(f.base.parent))
        assertEquals(listOf(f.base.stored!!.cleanupReference()), first.references)
        assertEquals(f.base.parent, first.parent)
        assertNull(first.nextAfter)
        assertEquals(1, f.pages)
        assertEquals(1, f.base.writes)
        assertFalse(first.toString().contains("Private message"))
        f.base.finisher(f.base.stored!!, CollaborationNotificationPublicationState.Published)
        assertEquals(
            CollaborationNotificationPublicationState.Published,
            refs(f.read(f.base.parent)).references.single().state,
        )
        assertEquals(2, f.base.writes)
    }

    @Test
    fun olderNativeFamilyCanBeDiscoveredAndCancelledUnderRetainedCompanyDenial() = runTest {
        val f = RetirementFixture()
        f.prepare()
        val old = f.base.stored!!
        f.base.replaceFamily()
        f.base.local.gate.value = CollaborationListAdmission("denied", f.base.local.denied)
        f.base.local.setup.beforeWorkspace = { error("No company access for cleanup") }
        val ref = refs(f.read(f.base.parent)).references.single()
        assertEquals(old.body.parent, ref.parent)
        val receipt = settled(f.cancel(f.base.parent, ref))
        assertEquals(CollaborationNotificationPublicationState.Cancelled, receipt.state)
        val retired = f.base.stored!!
        assertNull(retired.body.message)
        assertNull(retired.body.actions)
        assertEquals(old.body.target, retired.body.target)
        assertEquals(old.body.timestamp, retired.body.timestamp)
        assertEquals(old.body.contentHash, retired.body.contentHash)
        assertEquals(4, f.base.opens)
        assertTrue(refs(f.read(f.base.parent)).references.isEmpty())
    }

    @Test
    fun malformedPagingInputDoesNotReadStorageAndEmptyPagesDoNotCreateRecords() = runTest {
        val f = RetirementFixture()
        for (limit in listOf(0, 5)) failed(
            f.read(f.base.parent, limit = limit),
            FailureKind.Validation,
        )
        failed(f.read(f.base.parent, after = "not-an-id"), FailureKind.Validation)
        assertEquals(0, f.pages)
        assertTrue(refs(f.read(f.base.parent)).references.isEmpty())
        assertEquals(0, f.base.writes)
        assertEquals(0, f.base.seals)
    }

    @Test
    fun invalidUnorderedDuplicateOrCrossOwnerPagesNeverEscape() = runTest {
        val f = RetirementFixture()
        f.prepare()
        val original = f.base.stored!!
        val other = original.copy(id = "p-" + "b".repeat(64))
        val examples =
            listOf(
                CollaborationNotificationPublicationPage(List(5) { original }, null),
                CollaborationNotificationPublicationPage(listOf(original, original), null),
                CollaborationNotificationPublicationPage(listOf(other, original), null),
                CollaborationNotificationPublicationPage(listOf(original), other.id),
                CollaborationNotificationPublicationPage(emptyList(), original.id),
                CollaborationNotificationPublicationPage(
                    listOf(original.copy(id = "invalid")),
                    null,
                ),
                CollaborationNotificationPublicationPage(
                    listOf(
                        original.copy(
                            body =
                                original.body.copy(
                                    parent = original.body.parent.copy(accountId = id(9000)),
                                    message =
                                        original.body.message!!.let {
                                            it.copy(access = it.access.copy(accountId = id(9000)))
                                        },
                                )
                        )
                    ),
                    null,
                ),
                CollaborationNotificationPublicationPage(
                    listOf(
                        original.copy(
                            body =
                                original.body.copy(
                                    state = CollaborationNotificationPublicationState.Cancelled,
                                    message = null,
                                    actions = null,
                                )
                        )
                    ),
                    null,
                ),
            )
        for (page in examples) {
            f.page = page
            failed(f.read(f.base.parent), FailureKind.Unavailable)
        }
        val foreignAudience =
            original.copy(
                body =
                    original.body.copy(
                        parent = original.body.parent.copy(audience = "https://another.invalid"),
                        message =
                            original.body.message!!.let {
                                it.copy(
                                    access = it.access.copy(audience = "https://another.invalid")
                                )
                            },
                    )
            )
        assertTrue(validCollaborationNotificationPublication(foreignAudience))
        f.page = CollaborationNotificationPublicationPage(listOf(foreignAudience), null)
        failed(f.read(f.base.parent), FailureKind.Unavailable)
        f.page =
            CollaborationNotificationPublicationPage(
                object : AbstractList<CollaborationNotificationPublication>() {
                    override val size = Int.MAX_VALUE

                    override fun get(index: Int): CollaborationNotificationPublication =
                        error("Oversized page must not be copied")
                },
                null,
            )
        failed(f.read(f.base.parent), FailureKind.Unavailable)
        f.page = CollaborationNotificationPublicationPage(listOf(original), null)
        failed(f.read(f.base.parent, after = original.id), FailureKind.Unavailable)
        assertEquals(1, f.base.writes)
    }

    @Test
    fun validPageCursorIsForwardedWithoutAnImplicitScanOrRetry() = runTest {
        val f = RetirementFixture()
        f.prepare()
        f.page =
            CollaborationNotificationPublicationPage(listOf(f.base.stored!!), f.base.stored!!.id)
        val page = refs(f.read(f.base.parent, after = "p-" + "0".repeat(64), limit = 1))
        assertEquals(f.base.stored!!.id, page.nextAfter)
        assertEquals(1, f.pages)
        assertEquals("p-" + "0".repeat(64), f.after)
        assertEquals(1, f.limit)
    }

    @Test
    fun nativeReplacementDuringDiscoveryWithholdsEvenAStorageFailure() = runTest {
        val f = RetirementFixture()
        f.prepare()
        val captured = f.base.parent
        f.afterPage = { f.base.replaceFamily() }
        f.pageFailure = f.base.local.storage
        failed(f.read(captured), FailureKind.Unauthorized)
        assertEquals(1, f.pages)
    }

    @Test
    fun staleVersionTargetParentAndAbsentRecordNeverRetireAReplacement() = runTest {
        val f = RetirementFixture()
        f.prepare()
        val original = f.base.stored!!
        val ref = original.cleanupReference()
        val stale =
            listOf(
                ref.copy(version = ref.version.copy(revision = 10)),
                ref.copy(target = ref.target.copy(renderId = id(9001))),
                ref.copy(parent = ref.parent.copy(nativeSessionId = id(9002))),
                ref.copy(id = "p-" + "b".repeat(64)),
            )
        for (expected in stale) assertEquals(
            AppResult.Success(CollaborationNotificationPublicationSettlement.Contended),
            f.cancel(f.base.parent, expected),
        )
        assertEquals(original, f.base.stored)
        assertEquals(1, f.base.writes)
        f.base.stored = null
        assertEquals(
            AppResult.Success(CollaborationNotificationPublicationSettlement.Contended),
            f.cancel(f.base.parent, ref),
        )
        assertEquals(1, f.base.writes)
    }

    @Test
    fun anotherAccountOrInvalidReferenceCannotReadTheJournal() = runTest {
        val f = RetirementFixture()
        f.prepare()
        val ref = f.base.stored!!.cleanupReference()
        val reads = f.base.reads
        failed(
            f.cancel(f.base.parent, ref.copy(parent = ref.parent.copy(accountId = id(9003)))),
            FailureKind.Unauthorized,
        )
        failed(f.cancel(f.base.parent, ref.copy(id = "invalid")), FailureKind.Validation)
        failed(
            f.cancel(f.base.parent, ref.copy(version = ref.version.copy(revision = -1))),
            FailureKind.Validation,
        )
        assertEquals(reads, f.base.reads)
        assertEquals(1, f.base.writes)
    }

    @Test
    fun inactiveExactWatermarkIsIdempotentAndCannotBeReclassified() = runTest {
        val f = RetirementFixture()
        f.prepare()
        f.base.inactive()
        val before = f.base.stored!!
        val receipt = settled(f.cancel(f.base.parent, before.cleanupReference()))
        assertEquals(CollaborationNotificationPublicationState.Dismissed, receipt.state)
        assertEquals(before, f.base.stored)
        assertEquals(1, f.base.writes)
    }

    @Test
    fun cancellationAfterCommitKeepsRetiredFactsAndWithholdsTheResult() = runTest {
        val f = RetirementFixture()
        f.prepare()
        val ref = f.base.stored!!.cleanupReference()
        val committed = CompletableDeferred<Unit>()
        f.base.afterWrite = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val request = async { f.cancel(f.base.parent, ref) }
        committed.await()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertEquals(
            CollaborationNotificationPublicationState.Cancelled,
            f.base.stored!!.body.state,
        )
        assertNull(f.base.stored!!.body.message)
        assertNull(f.base.stored!!.body.actions)
        f.base.afterWrite = {}
        assertTrue(refs(f.read(f.base.parent)).references.isEmpty())
        assertEquals(2, f.base.writes)
    }

    @Test
    fun busyCancellationDoesNotQueueAndTimeoutDoesNotRetryTheWrite() = runTest {
        val f = RetirementFixture()
        f.prepare()
        val ref = f.base.stored!!.cleanupReference()
        val entered = CompletableDeferred<Unit>()
        f.base.afterRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val request = async { f.cancel(f.base.parent, ref) }
        entered.await()
        val reads = f.base.reads
        failed(f.cancel(f.base.parent, ref), FailureKind.Unavailable)
        assertEquals(reads, f.base.reads)
        failed(request.await(), FailureKind.Unavailable)
        assertEquals(8_000, testScheduler.currentTime)
        assertEquals(1, f.base.writes)
        assertEquals(ref, f.base.stored!!.cleanupReference())
    }

    @Test
    fun lateFamilyChangeAndInvalidCommitReceiptNeverAcknowledgeCancellation() = runTest {
        for (change in listOf(true, false)) {
            val f = RetirementFixture()
            f.prepare()
            val parent = f.base.parent
            val ref = f.base.stored!!.cleanupReference()
            if (change) f.base.afterWrite = { f.base.replaceFamily() }
            else
                f.base.writeOutcome =
                    CollaborationNotificationPublicationWrite.Stored(f.base.stored!!)
            failed(
                f.cancel(parent, ref),
                if (change) FailureKind.Unauthorized else FailureKind.Conflict,
            )
            assertEquals(
                CollaborationNotificationPublicationState.Cancelled,
                f.base.stored!!.body.state,
            )
            assertEquals(2, f.base.writes)
        }
    }

    private fun refs(result: AppResult<CollaborationNotificationPublicationReferences>) =
        assertIs<AppResult.Success<CollaborationNotificationPublicationReferences>>(result).value

    private fun settled(result: AppResult<CollaborationNotificationPublicationSettlement>) =
        assertIs<CollaborationNotificationPublicationSettlement.Settled>(
            assertIs<AppResult.Success<CollaborationNotificationPublicationSettlement>>(result)
                .value
        )

    private fun failed(result: AppResult<*>, kind: FailureKind) {
        assertEquals(kind, assertIs<AppResult.Failed>(result).failure.kind)
    }

    private fun id(value: Int) = CollaborationNotificationIssuanceFixture.id(value)
}

private class RetirementFixture {
    val base = CollaborationNotificationIssuanceFixture()
    var pages = 0
    var after: String? = null
    var limit = 0
    var page: CollaborationNotificationPublicationPage? = null
    var pageFailure: Failure? = null
    var afterPage: suspend () -> Unit = {}
    val journal =
        object : CollaborationNotificationPublicationRepository by base.journal {
            override suspend fun page(
                access: CollaborationRecoveryAccess,
                after: String?,
                limit: Int,
                preparedOnly: Boolean,
            ): AppResult<CollaborationNotificationPublicationPage> {
                pages++
                this@RetirementFixture.after = after
                this@RetirementFixture.limit = limit
                assertFalse(preparedOnly)
                assertEquals(base.parent.accountId, access.accountId)
                val result =
                    page
                        ?: CollaborationNotificationPublicationPage(
                            listOfNotNull(
                                base.stored?.takeIf { it.body.state.isActivePublication() }
                            ),
                            null,
                        )
                afterPage()
                return pageFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(result)
            }
        }
    val read =
        ReadCollaborationNotificationPublications(
            journal,
            base.local.setup.sessions,
            base.local.server,
            base.local.build,
        )
    val cancel =
        CancelCollaborationNotificationPublication(
            journal,
            base.local.setup.sessions,
            base.local.server,
            base.local.build,
        )

    suspend fun prepare() {
        assertIs<AppResult.Success<*>>(base.issue())
    }
}
