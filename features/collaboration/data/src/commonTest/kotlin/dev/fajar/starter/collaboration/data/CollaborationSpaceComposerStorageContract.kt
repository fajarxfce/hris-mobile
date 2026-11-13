package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationSpaceComposerStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun absentAndInvalidReadsNeverCreateKeysOrRecoveryWork() = withDatabase { f ->
        val local = f.space()
        assertEquals(AppResult.Success(null), f.drafts().get(local, f.conversation))
        assertEquals(AppResult.Success(null), f.drafts().observe(local, f.conversation).first())
        assertEquals(AppResult.Success(null), f.outbox().get(local, historyId(1001)))
        assertEquals(AppResult.Success(emptyList()), f.outbox().observe(local).first())
        assertEquals(
            AppResult.Success(CollaborationComposerSubmissionPage(emptyList(), null)),
            f.outbox().pending(local),
        )
        assertIs<AppResult.Failed>(
            f.drafts().observe(local.copy(spaceId = "invalid"), f.conversation).first()
        )
        assertIs<AppResult.Failed>(f.outbox().observe(local.copy(spaceId = "invalid")).first())
        assertNull(f.keys.read())
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun exactEditingReopensAfterLoginRenewalAndFencesAStaleWriter() = withDatabase { f ->
        val local = f.space()
        val original = f.submission().original
        assertEquals(AppResult.Success(true), f.drafts().replace(local, null, original))
        val cleared =
            original.copy(revision = historyId(1100), text = "", savedAt = original.savedAt)
        assertEquals(AppResult.Success(true), f.drafts().replace(local, original, cleared))
        assertEquals(
            AppResult.Success(false),
            f.drafts()
                .replace(
                    local,
                    original,
                    original.copy(revision = historyId(1101), text = "Late text"),
                ),
        )
        val key = f.keys.read()
        val renewed = local.copy(sessionId = "renewed")
        f.reopen(f.access.copy(sessionId = renewed.sessionId))
        assertEquals(AppResult.Success(cleared), f.drafts().get(renewed, f.conversation))
        assertEquals(
            AppResult.Success(cleared),
            f.drafts().observe(renewed, f.conversation).first(),
        )
        assertEquals(key, f.keys.read())
    }

    @Test
    fun companyAndTwoGroupsWithEqualMessageIdsKeepIndependentSlotsAndOutboxes() =
        withDatabase { f ->
            val local = f.space()
            val other = local.copy(spaceId = historyId(9500))
            val company =
                f.submission().copy(original = f.submission().original.copy(text = "Company"))
            val group = company.copy(original = company.original.copy(text = "Group"))
            val second = company.copy(original = company.original.copy(text = "Other Group"))
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(f.access, company, null),
            )
            assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(local, group, null),
            )
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.STORED),
                f.outbox().handoff(other, second, null),
            )
            assertEquals(AppResult.Success(company), f.outbox().get(f.access, company.id))
            assertEquals(AppResult.Success(group), f.outbox().get(local, group.id))
            assertEquals(AppResult.Success(second), f.outbox().get(other, second.id))
            assertEquals(AppResult.Success(group.nextDraft), f.drafts().get(local, f.conversation))
            assertEquals(AppResult.Success(second.nextDraft), f.drafts().get(other, f.conversation))
        }

    @Test
    fun lostInitialHandoffReplyRetainsBothDocumentsAndReplayPreservesNewerTypingAndWake() =
        withDatabase { f ->
            val local = f.space()
            val original = f.submission()
            val store =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                        wake: WorkWakeRequest?,
                    ): Boolean {
                        assertEquals(2, assertNotNull(snapshot).documents.size)
                        assertEquals(
                            CollaborationSpaceRecoveryWake.WORK_KEY,
                            assertNotNull(wake).key,
                        )
                        assertTrue(
                            f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                        )
                        error("Simulated lost committed response")
                    }
                }
            assertIs<AppResult.Failed>(f.outbox(store).handoff(local, original, null))
            assertEquals(AppResult.Success(original), f.outbox().get(local, original.id))
            assertEquals(
                AppResult.Success(original.nextDraft),
                f.drafts().get(local, f.conversation),
            )
            val wake =
                assertNotNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
            assertTrue(f.database.workWakes.acknowledge(wake.key, wake.generation))
            val editing = original.nextDraft.copy(revision = historyId(1100), text = "Keep typing")
            assertEquals(
                AppResult.Success(true),
                f.drafts().replace(local, original.nextDraft, editing),
            )
            assertEquals(
                AppResult.Success(CollaborationComposerHandoffResult.RETAINED),
                f.outbox().handoff(local, original, null),
            )
            assertEquals(AppResult.Success(editing), f.drafts().get(local, f.conversation))
            assertEquals(wake.copy(pending = false), f.database.workWakes.get(wake.key))
        }

    @Test
    fun fullRecoveryWakeCapacityCannotPartiallyClearTheDraftOrStoreAnOriginal() =
        withDatabase { f ->
            val local = f.space()
            val submission = f.submission()
            assertEquals(
                AppResult.Success(true),
                f.drafts().replace(local, null, submission.original),
            )
            repeat(32) {
                assertNotNull(f.database.workWakes.request("occupied.$it", "generation", 32))
            }
            assertIs<AppResult.Failed>(f.outbox().handoff(local, submission))
            assertEquals(
                AppResult.Success(submission.original),
                f.drafts().get(local, f.conversation),
            )
            assertEquals(AppResult.Success(null), f.outbox().get(local, submission.id))
            assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        }

    @Test
    fun boundedRecoveryAndFullOutboxKeepTheUnsubmittedEditingSlot() = withDatabase { f ->
        val local = f.space()
        val originals = (0 until 128).map { f.submission(it) }
        for (original in originals) assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(local, original, null),
        )
        val next = f.submission(128)
        assertEquals(AppResult.Success(true), f.drafts().replace(local, null, next.original))
        val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.CAPACITY_EXCEEDED),
            f.outbox().handoff(local, next),
        )
        assertEquals(
            AppResult.Success(next.original),
            f.drafts().get(local, next.original.conversationId),
        )
        assertEquals(AppResult.Success(null), f.outbox().get(local, next.id))
        assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
        val store =
            object : ReplicaStore by f.database.replicas {
                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> = error("No full-partition recovery scan")

                override fun observeQueries(
                    scope: ReplicaScope,
                    partition: String,
                    queries: List<ReplicaQuery>,
                ): Flow<ReplicaQueryPage> {
                    assertEquals(listOf(ReplicaQuery(COMPOSER_OUTBOX_TAG, limit = 9)), queries)
                    return f.database.replicas.observeQueries(scope, partition, queries)
                }
            }
        val page =
            assertIs<AppResult.Success<CollaborationComposerSubmissionPage>>(
                    f.outbox(store).pending(local)
                )
                .value
        assertEquals(originals.take(8), page.submissions)
        assertEquals(originals[7].id, page.next)
    }

    @Test
    fun heldAndRemovedOriginalsNeverOverwriteTheCurrentEditorOrRotateWake() = withDatabase { f ->
        val local = f.space()
        val original = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(local, original, null),
        )
        val editing = original.nextDraft.copy(revision = historyId(1100), text = "New input")
        assertEquals(
            AppResult.Success(true),
            f.drafts().replace(local, original.nextDraft, editing),
        )
        val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
        val different = original.copy(original = original.original.copy(text = "Different"))
        assertEquals(AppResult.Success(false), f.outbox().hold(local, different))
        assertEquals(AppResult.Success(true), f.outbox().hold(local, original))
        assertEquals(
            AppResult.Success(CollaborationComposerSubmissionPage(emptyList(), null)),
            f.outbox().pending(local),
        )
        assertEquals(
            AppResult.Success(listOf(CollaborationComposerOutboxEntry(original, true))),
            f.outbox().observe(local).first(),
        )
        assertEquals(AppResult.Success(false), f.outbox().remove(local, different))
        assertEquals(AppResult.Success(true), f.outbox().remove(local, original))
        assertEquals(AppResult.Success(false), f.outbox().remove(local, original))
        assertEquals(AppResult.Success(editing), f.drafts().get(local, f.conversation))
        assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun lostExistingContentKeyCannotBeReplacedOverGroupCiphertext() = withDatabase { f ->
        val local = f.space()
        val original = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(local, original, null),
        )
        val key = f.keys.read()
        val before = f.raw(local)
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.outbox().get(local, original.id))
        assertIs<AppResult.Failed>(
            f.outbox().handoff(local.copy(spaceId = historyId(9500)), f.submission(1), null)
        )
        assertNull(f.keys.read())
        assertEquals(before, f.raw(local))
        f.keys.write(key)
        assertEquals(AppResult.Success(original), f.outbox().get(local, original.id))
    }

    @Test
    fun competingHandoffsKeepOneExactOriginalAndItsEditorSuccessor() = withDatabase { f ->
        val local = f.space()
        val first = f.submission()
        val second =
            first.copy(
                original = first.original.copy(revision = historyId(1200), text = "Second"),
                nextRevision = historyId(1201),
            )
        assertEquals(AppResult.Success(true), f.drafts().replace(local, null, first.original))
        val results = coroutineScope {
            val one = async { f.outbox().handoff(local, first, first.original) }
            val two = async { f.outbox().handoff(local, second, first.original) }
            listOf(one.await(), two.await())
        }
        assertEquals(
            1,
            results.count { it == AppResult.Success(CollaborationComposerHandoffResult.STORED) },
        )
        assertEquals(
            1,
            results.count { it == AppResult.Success(CollaborationComposerHandoffResult.CHANGED) },
        )
        val original =
            if (results[0] == AppResult.Success(CollaborationComposerHandoffResult.STORED)) first
            else second
        assertEquals(
            AppResult.Success(listOf(CollaborationComposerOutboxEntry(original, false))),
            f.outbox().observe(local).first(),
        )
        assertEquals(AppResult.Success(original.nextDraft), f.drafts().get(local, f.conversation))
    }

    @Test
    fun transplantedGroupCiphertextCannotOpenUnderAnotherGroupOrCompany() = withDatabase { f ->
        val local = f.space()
        val original = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(local, original, null),
        )
        val source = f.raw(local)
        val checkpoint = assertNotNull(source.checkpoint)
        val other = local.copy(spaceId = historyId(9500))
        for (target in
            listOf(
                CollaborationComposerCoordinates.Space(other),
                CollaborationComposerCoordinates.Company(f.access),
            )) {
            assertTrue(
                f.database.replicas.replace(
                    target.scope,
                    target.partition,
                    null,
                    ReplicaSnapshot(
                        checkpoint.handle.generation,
                        checkpoint.payload,
                        source.documents,
                    ),
                )
            )
        }
        assertIs<AppResult.Failed>(f.outbox().get(other, original.id))
        assertIs<AppResult.Failed>(f.outbox().get(f.access, original.id))
        assertEquals(AppResult.Success(original), f.outbox().get(local, original.id))
        assertEquals(source, f.raw(local))
    }

    @Test
    fun cancelledSubmissionCannotCommitEitherDocumentOrWake() = withDatabase { f ->
        val local = f.space()
        val job = Job().also { it.cancel() }
        assertFailsWith<CancellationException> {
            withContext(job) { f.outbox().handoff(local, f.submission(), null) }
        }
        assertEquals(AppResult.Success(null), f.drafts().get(local, f.conversation))
        assertNull(f.keys.read())
        assertNull(f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun timestampOnlyReplayConflictCannotChangeOriginalInputOrWake() = withDatabase { f ->
        val local = f.space()
        val original = f.submission()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(local, original, null),
        )
        val wake = f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY)
        val changed =
            original.copy(
                submittedAt =
                    Instant.fromEpochMilliseconds(original.submittedAt.toEpochMilliseconds() + 1)
            )
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.CHANGED),
            f.outbox().handoff(local, changed, null),
        )
        assertEquals(AppResult.Success(original), f.outbox().get(local, original.id))
        assertEquals(AppResult.Success(original.nextDraft), f.drafts().get(local, f.conversation))
        assertEquals(wake, f.database.workWakes.get(CollaborationSpaceRecoveryWake.WORK_KEY))
    }

    @Test
    fun groupComposerMetadataNeverBecomesIncomingMessageHistory() = withDatabase { f ->
        val local = f.space()
        assertEquals(
            AppResult.Success(CollaborationComposerHandoffResult.STORED),
            f.outbox().handoff(local, f.submission(), null),
        )
        val checkpoint = assertNotNull(f.raw(local).checkpoint)
        val mapped =
            CollaborationAccountIncomingHistoryLocatorPageMapper(checkpoint.handle.scope, null, 1)
                .map(listOf(checkpoint))
        assertEquals(CollaborationAccountIncomingHistoryLocatorPage(emptyList(), null), mapped)
    }

    private fun CollaborationHistoryFixture.space() =
        CollaborationSpaceLocalAccess(
            access.sessionId,
            access.audience,
            access.accountId,
            access.companyId,
        )

    private fun CollaborationHistoryFixture.drafts() =
        EncryptedCollaborationDraftRepository(database.replicas, cipher, keys)

    private fun CollaborationHistoryFixture.outbox(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationComposerOutboxRepository(store, cipher, keys)

    private fun CollaborationHistoryFixture.submission(index: Int = 0) =
        CollaborationComposerSubmission(
            CollaborationDraft(
                if (index == 0) conversation else historyId(3000 + index),
                historyId(1001 + 2 * index),
                "Original $index",
                Instant.parse("2026-11-01T12:00:00Z"),
            ),
            historyId(1002 + 2 * index),
            Instant.parse("2026-11-01T12:00:01Z"),
        )

    private suspend fun CollaborationHistoryFixture.raw(
        local: CollaborationSpaceLocalAccess
    ): ReplicaPage {
        val coordinates = CollaborationComposerCoordinates.Space(local)
        return database.replicas
            .observe(coordinates.scope, coordinates.partition, limit = 200)
            .first()
    }

    private fun withDatabase(block: suspend (CollaborationHistoryFixture) -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            val fixture = CollaborationHistoryFixture(databaseOwner())
            try {
                fixture.activate()
                block(fixture)
            } finally {
                fixture.close()
            }
        }
    }
}
