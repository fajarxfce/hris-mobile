package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppPlatform
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationNotificationReplyRecoveryTest {
    @Test
    fun discoveryReturnsOnlyOrderedOpaqueReferencesDespiteDeniedCompanyExpiredCredentialsAndOldFamily() =
        runTest {
            val f = CollaborationNotificationReplyRecoveryFixture()
            f.native.replaceFamily()
            f.local.setup.now += 365.days
            f.local.setup.workspace = null
            f.local.setup.beforeWorkspace = { error("No selection") }
            f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
            val work = expect(f.read())!!
            assertEquals(f.records.take(4).map { it.id }, work.entries.map { it.id })
            assertTrue(work.hasMore)
            assertEquals(listOf<Long?>(null), f.pageAfters)
            assertEquals(0, f.local.messageReads)
            assertEquals(0, f.native.opens)
            assertEquals(0, f.local.setup.calls)
            assertTrue(f.records.none { it.candidate.text in work.toString() })
        }

    @Test
    fun cursorSelectsFollowingRecordsAndWrapsAtMostOnce() = runTest {
        val f = CollaborationNotificationReplyRecoveryFixture()
        f.cursor = CollaborationNotificationReplyRecoveryCursor(f.id(2000), 4)
        assertEquals(listOf(5L, 6L), expect(f.read())!!.entries.map { it.sequence })
        f.cursor = f.cursor!!.copy(afterSequence = 9)
        val wrapped = expect(f.read())!!
        assertNull(wrapped.afterSequence)
        assertEquals(f.cursor, wrapped.cursor)
        assertEquals(listOf<Long?>(4, 9, null), f.pageAfters)
        f.records = emptyList()
        f.pageAfters.clear()
        assertNull(expect(f.read()))
        assertEquals(listOf<Long?>(9, null), f.pageAfters)
    }

    @Test
    fun heldInputsAreExcludedWithoutChangingTheirText() = runTest {
        val f = CollaborationNotificationReplyRecoveryFixture()
        f.records =
            f.records.mapIndexed { i, r ->
                if (i % 2 == 0) r.copy(review = CollaborationNotificationReplyReview.SessionChanged)
                else r
            }
        val before = f.records
        assertEquals(listOf(2L, 4L, 6L), expect(f.read())!!.entries.map { it.sequence })
        assertEquals(before, f.records)
    }

    @Test
    fun malformedCursorAndPageNeverBecomeRecoveryWork() = runTest {
        val f = CollaborationNotificationReplyRecoveryFixture()
        f.cursorResult = AppResult.Success(CollaborationNotificationReplyRecoveryCursor("bad", 1))
        assertFailed(f.read())
        assertTrue(f.pageAfters.isEmpty())
        f.cursorResult = null
        val first = f.records.first()
        val malformed =
            listOf(
                CollaborationNotificationReplyPage(emptyList(), 1),
                CollaborationNotificationReplyPage(f.records, null),
                CollaborationNotificationReplyPage(listOf(first, first), null),
                CollaborationNotificationReplyPage(f.records.take(2).reversed(), null),
                CollaborationNotificationReplyPage(
                    listOf(
                        first.copy(review = CollaborationNotificationReplyReview.MessageChanged)
                    ),
                    null,
                ),
                CollaborationNotificationReplyPage(listOf(first.copy(id = "bad")), null),
                CollaborationNotificationReplyPage(
                    listOf(
                        first.copy(
                            candidate =
                                first.candidate.copy(
                                    parent = first.candidate.parent.copy(accountId = f.id(999))
                                )
                        )
                    ),
                    null,
                ),
                CollaborationNotificationReplyPage(listOf(first), first.sequence),
                CollaborationNotificationReplyPage(f.records.take(4), 999),
            )
        for (page in malformed) {
            f.pageResult = AppResult.Success(page)
            assertFailed(f.read())
        }
        f.pageResult = AppResult.Success(CollaborationNotificationReplyPage(listOf(first), null))
        f.cursor = CollaborationNotificationReplyRecoveryCursor(f.id(2000), 2)
        assertFailed(f.read())
    }

    @Test
    fun invalidIdentityAndUnsupportedPlatformNeverReadStorage() = runTest {
        val f = CollaborationNotificationReplyRecoveryFixture()
        assertIs<AppResult.Failed>(f.reader(AppPlatform.Web)())
        f.local.setup.session = null
        assertIs<AppResult.Failed>(f.read())
        assertEquals(0, f.cursorReads)
    }

    @Test
    fun replacementParentWinsAfterCursorOrPageEvenWhenStorageFails() = runTest {
        for (stage in listOf("cursor", "page", "wrap")) for (failed in listOf(false, true)) {
            val f = CollaborationNotificationReplyRecoveryFixture()
            val changed: suspend () -> Unit = { f.native.replaceFamily() }
            when (stage) {
                "cursor" -> {
                    f.afterCursor = changed
                    if (failed) f.cursorResult = AppResult.Failed(f.local.storage)
                }
                "page" -> {
                    f.afterPage = changed
                    if (failed) f.pageResult = AppResult.Failed(f.local.storage)
                }
                else -> {
                    f.cursor = CollaborationNotificationReplyRecoveryCursor(f.id(2000), 100)
                    f.afterPage = {
                        if (f.pageAfters.size == 1 && failed)
                            f.pageResult = AppResult.Failed(f.local.storage)
                        if (f.pageAfters.size == 2) changed()
                    }
                }
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.read()).failure.kind,
            )
        }
    }

    @Test
    fun storageFailuresPreserveCursorAndInput() = runTest {
        val f = CollaborationNotificationReplyRecoveryFixture()
        val before = f.records
        f.cursorResult = AppResult.Failed(f.local.storage)
        assertEquals(AppResult.Failed(f.local.storage), f.read())
        f.cursorResult = null
        f.pageResult = AppResult.Failed(f.local.storage)
        assertEquals(AppResult.Failed(f.local.storage), f.read())
        assertNull(f.cursor)
        assertEquals(before, f.records)
    }

    @Test
    fun deadlineAndCancellationJoinThePendingPageBeforeRetry() = runTest {
        val f = CollaborationNotificationReplyRecoveryFixture()
        var stopped = false
        f.afterPage = {
            try {
                delay(8_001)
            } finally {
                stopped = true
            }
        }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.read()).failure.kind)
        assertTrue(stopped)
        val entered = CompletableDeferred<Unit>()
        stopped = false
        f.afterPage = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val pending = async { f.read() }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(stopped)
        f.afterPage = {}
        assertNotNull(expect(f.read()))
        assertEquals(0, f.advances)
    }

    private fun <T> expect(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

    private fun assertFailed(result: AppResult<*>) {
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(result).failure.kind)
    }
}
