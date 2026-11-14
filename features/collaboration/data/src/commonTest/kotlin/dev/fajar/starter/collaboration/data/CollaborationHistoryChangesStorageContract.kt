package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationSpaceHistoryRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.AtomicCredentialStore
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

abstract class CollaborationHistoryChangesStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun absenceAndInvalidInputsNeverCreateAKey() = withDatabase { f ->
        assertEquals(
            AppResult.Success(CollaborationHistoryChanges.Absent),
            f.repository.changes(location(f)),
        )
        for (limit in listOf(0, 17)) {
            assertIs<AppResult.Failed>(f.repository.changes(location(f), limit = limit))
        }
        assertIs<AppResult.Failed>(f.repository.changes(location(f).copy(roomId = "invalid room")))
        val owner =
            CollaborationHistoryChangeOwner(
                f.access.audience,
                f.access.accountId,
                CollaborationHistoryChangeOwnerKind.SPACE,
                f.access.spaceId,
                f.conversation,
                f.room,
            )
        for (cursor in
            listOf(
                CollaborationHistoryChangeCursor.After(owner, "invalid-generation", 0),
                CollaborationHistoryChangeCursor.After(owner, historyId(77), -1),
                CollaborationHistoryChangeCursor.Within(
                    CollaborationHistoryChangeWindow(owner, historyId(77), null, 0),
                    0,
                    "m-invalid",
                ),
            )) assertIs<AppResult.Failed>(f.repository.changes(location(f), cursor))
        assertNull(f.keys.read())
        assertNull(f.raw().checkpoint)
    }

    @Test
    fun companyAndGroupRetainTheirExactPhysicalCoordinates() = withDatabase { f ->
        val groupEvent = f.event()
        val companyEvent = f.event(2)
        f.merge(groupEvent)
        val company = f.legacyRepository()
        val original =
            CollaborationSpaceHistoryLocation.Original(
                f.legacyAccess.copy(companyId = historyId(9901)),
                historyId(9902),
                "!original:example.test",
            )
        assertEquals(
            AppResult.Success(true),
            company.merge(
                original.access,
                original.conversationId,
                original.roomId,
                listOf(companyEvent),
            ),
        )
        val groupPage = page(f)
        val companyPage =
            assertIs<CollaborationHistoryChanges.Page>(success(company.changes(original)))
        assertEquals(listOf(groupEvent.eventId), groupPage.eventIds)
        assertEquals(listOf(companyEvent.eventId), companyPage.eventIds)
        assertEquals(CollaborationHistoryChangeOwnerKind.SPACE, groupPage.window.owner.kind)
        assertEquals(CollaborationHistoryChangeOwnerKind.COMPANY, companyPage.window.owner.kind)
        assertEquals(original.access.companyId, companyPage.window.owner.organizationId)
        assertEquals(original.conversationId, companyPage.window.owner.conversationId)
        assertEquals(original.roomId, companyPage.window.owner.roomId)
        assertIs<AppResult.Failed>(company.changes(original, completed(groupPage)))
    }

    @Test
    fun oneIndexedReadOwnsEachBoundedPageWithoutIndividualLookups() = withDatabase { f ->
        val originals = (1..8).map(f::event)
        f.merge(*originals.toTypedArray())
        val alias = originals.first().copy(eventId = "\$alias")
        f.merge(alias)
        var reads = 0
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun changedDocuments(
                    scope: ReplicaScope,
                    partition: String,
                    cursor: ReplicaChangeCursor?,
                    limit: Int,
                ): ReplicaChangePage {
                    reads++
                    assertEquals(3, limit)
                    return f.database.replicas.changedDocuments(scope, partition, cursor, limit)
                }

                override suspend fun checkpoint(
                    scope: ReplicaScope,
                    partition: String,
                ): ReplicaCheckpoint? = error("No second checkpoint read")

                override suspend fun document(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): ReplicaDocument? = error("No individual history reads")

                override fun observe(
                    scope: ReplicaScope,
                    partition: String,
                    after: String?,
                    limit: Int,
                ): Flow<ReplicaPage> = error("No full history observation")
            }
        f.repository = f.repository(store)
        var current = page(f, limit = 3)
        assertEquals(1, reads)
        assertNotNull(current.next)
        val events = current.eventIds.toMutableSet()
        repeat(16) {
            val cursor = current.next ?: return@repeat
            current = page(f, cursor, 3)
            assertTrue(current.eventIds.size <= 3)
            events += current.eventIds
        }
        assertNull(current.next)
        assertEquals(6, reads)
        assertEquals(originals.map { it.eventId }.toSet() + alias.eventId, events)
    }

    @Test
    fun laterWritesWaitForTheNextFiniteInterval() = withDatabase { f ->
        f.merge(f.event(1), f.event(2), f.event(3))
        val first = page(f, limit = 1)
        val alias = f.event().copy(eventId = "\$new-alias")
        f.merge(alias, f.event(4))
        var current = first
        repeat(16) {
            val cursor = current.next ?: return@repeat
            current = page(f, cursor, 1)
            assertEquals(first.window, current.window)
        }
        assertNull(current.next)
        assertNotEquals(first.sourceRevision, current.sourceRevision)
        val next = page(f, completed(first))
        assertNull(next.next)
        assertTrue(alias.eventId in next.eventIds)
        assertTrue(f.event(4).eventId in next.eventIds)
        assertTrue(next.window.throughRevision > first.window.throughRevision)
    }

    @Test
    fun unchangedReplayDoesNotCreateAChangedMessage() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        val initial = page(f)
        val before = f.raw()
        f.merge(original)
        val unchanged = page(f, completed(initial))
        assertTrue(unchanged.eventIds.isEmpty())
        assertNull(unchanged.next)
        assertEquals(initial.window.throughRevision, unchanged.window.throughRevision)
        assertEquals(before, f.raw())
    }

    @Test
    fun hiddenAndRedactedOriginalsStillReachTheChangeReader() = withDatabase { f ->
        f.merge(f.event(), f.event(2))
        val initial = page(f)
        val head = assertNotNull(f.raw().checkpoint)
        CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { content, index ->
            val codec =
                CollaborationHistoryCodec(
                    f.access,
                    f.conversation,
                    f.room,
                    f.cipher,
                    content,
                    index,
                )
            val checkpoint = codec.checkpoint(head)
            val original = assertNotNull(f.lookup())
            val hidden =
                codec.message(
                    head.handle.generation,
                    head.revision + 1,
                    original.copy(locallyHidden = true),
                )
            assertTrue(
                f.database.replicas.commit(
                    head.handle,
                    head.revision,
                    codec.checkpoint(
                        head.handle.generation,
                        head.revision + 1,
                        checkpoint.count,
                        checkpoint.hiddenRules,
                    ),
                    listOf(hidden),
                    true,
                )
            )
        }
        f.merge(CollaborationHistoryChange.Redacted(f.event(2).eventId, f.event(2).sender))
        val changed = page(f, completed(initial))
        assertEquals(setOf(f.event().eventId, f.event(2).eventId), changed.eventIds.toSet())
        assertTrue(assertNotNull(f.lookup()).locallyHidden)
        assertTrue(assertNotNull(f.lookup(f.event(2).eventId)).redacted)
    }

    @Test
    fun editEvidenceAndRevisionRemovalKeepTheirOriginalTarget() = withDatabase { f ->
        val original = f.event()
        f.merge(original)
        val initial = page(f)
        val replacement = edit(original)
        assertEquals(
            AppResult.Success(true),
            f.repository.mergeEdits(f.access, f.conversation, f.room, listOf(replacement)),
        )
        val edited = page(f, completed(initial))
        assertTrue(original.eventId in edited.eventIds)
        assertTrue(replacement.eventId in edited.eventIds)
        f.merge(CollaborationHistoryChange.Redacted(replacement.eventId, original.sender))
        val removed = page(f, completed(edited))
        assertTrue(original.eventId in removed.eventIds)
        assertTrue(replacement.eventId in removed.eventIds)
        assertNull(removed.next)
        assertNull(assertNotNull(f.lookup()).latestEdit)
    }

    @Test
    fun auxiliaryOnlyPagesDoNotLoopUntilAReferenceAppears() = withDatabase { f ->
        val original = f.event()
        val company = f.legacyRepository()
        val source =
            CollaborationSpaceHistoryLocation.Original(f.legacyAccess, f.conversation, f.room)
        assertEquals(
            AppResult.Success(true),
            company.hide(
                source.access,
                source.conversationId,
                source.roomId,
                listOf(
                    CollaborationMessageReference(
                        original.sender,
                        original.eventId,
                        original.logicalId,
                    )
                ),
            ),
        )
        val first =
            assertIs<CollaborationHistoryChanges.Page>(success(company.changes(source, limit = 1)))
        assertTrue(first.eventIds.isEmpty())
        val next = assertNotNull(first.next)
        val last =
            assertIs<CollaborationHistoryChanges.Page>(success(company.changes(source, next, 1)))
        assertTrue(last.eventIds.isEmpty())
        assertNull(last.next)
        assertEquals(
            AppResult.Success(true),
            company.merge(source.access, source.conversationId, source.roomId, listOf(original)),
        )
        val imported =
            assertIs<CollaborationHistoryChanges.Page>(
                success(company.changes(source, completed(last)))
            )
        assertEquals(listOf(original.eventId), imported.eventIds)
        val retained =
            assertNotNull(
                success(
                    company.getByEvent(
                        source.access,
                        source.conversationId,
                        source.roomId,
                        original.eventId,
                    )
                )
            )
        assertTrue(retained.locallyHidden)
    }

    @Test
    fun renewedLoginResumesTheSameOwnedWindowAfterReopen() = withDatabase { f ->
        f.merge(f.event(), f.event(2))
        val initial = page(f, limit = 1)
        val renewed = f.access.copy(sessionId = "renewed-login")
        f.reopen(renewed)
        val next = page(f, assertNotNull(initial.next), source = location(f).copy(access = renewed))
        assertEquals(initial.window, next.window)
        assertTrue(next.eventIds.isNotEmpty())
        assertNull(next.next)
        assertEquals(
            AppResult.Success(CollaborationHistoryChanges.Absent),
            f.repository.changes(location(f)),
        )
    }

    @Test
    fun aCursorCannotCrossAccountAudienceOrganizationConversationOrRoom() = withDatabase { f ->
        f.merge(f.event())
        val cursor = completed(page(f))
        val source = location(f)
        for (other in
            listOf(
                source.copy(access = source.access.copy(accountId = historyId(88))),
                source.copy(access = source.access.copy(audience = "https://other.example.test/")),
                source.copy(access = source.access.copy(spaceId = historyId(89))),
                source.copy(conversationId = historyId(90)),
                source.copy(roomId = "!other:example.test"),
            )) assertIs<AppResult.Failed>(f.repository.changes(other, cursor))
        assertEquals(listOf(f.event().eventId), page(f).eventIds)
    }

    @Test
    fun replacementRequiresRestartInsteadOfReusingOldProgress() = withDatabase { f ->
        f.merge(f.event())
        val original = page(f)
        val head = assertNotNull(f.raw().checkpoint)
        assertTrue(
            f.database.replicas.replace(head.handle.scope, head.handle.partition, head, null)
        )
        f.merge(f.event(2))
        assertEquals(
            AppResult.Success(CollaborationHistoryChanges.Restart),
            f.repository.changes(location(f), completed(original)),
        )
        val replacement = page(f)
        assertNotEquals(original.window.generation, replacement.window.generation)
        assertEquals(listOf(f.event(2).eventId), replacement.eventIds)
    }

    @Test
    fun corruptCiphertextAndMissingKeysFailWithoutCreatingReplacementKeys() = withDatabase { f ->
        f.merge(f.event())
        corruptOriginal(f) { it.copy(payload = "invalid-ciphertext") }
        assertIs<AppResult.Failed>(f.repository.changes(location(f)))
        f.keys.write(null)
        assertIs<AppResult.Failed>(f.repository.changes(location(f)))
        assertNull(f.keys.read())
        assertNotNull(f.raw().checkpoint)
    }

    @Test
    fun forgedTagsCannotHideAnOriginalFromAuthentication() = withDatabase { f ->
        f.merge(f.event())
        corruptOriginal(f) { it.copy(tags = setOf("unrelated")) }
        assertIs<AppResult.Failed>(f.repository.changes(location(f)))
    }

    @Test
    fun nullOriginalIsRejectedInsteadOfMarkingItsWindowComplete() = withDatabase { f ->
        f.merge(f.event())
        val initial = page(f)
        corruptOriginal(f) { it.copy(payload = null, tags = emptySet()) }
        assertIs<AppResult.Failed>(f.repository.changes(location(f), completed(initial)))
    }

    @Test
    fun cancellationDuringKeyAcquisitionJoinsWithoutChangingSource() = withDatabase { f ->
        coroutineScope {
            f.merge(f.event())
            val before = f.raw()
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val keys =
                object : AtomicCredentialStore by f.keys {
                    override suspend fun read(): String? {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
                }
            val repository =
                EncryptedCollaborationSpaceHistoryRepository(f.database.replicas, f.cipher, keys)
            val pending = async { repository.changes(location(f)) }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(stopped.isCompleted)
            assertEquals(before, f.raw())
            assertEquals(listOf(f.event().eventId), page(f).eventIds)
        }
    }

    private fun location(f: CollaborationSpaceHistoryFixture) =
        CollaborationSpaceHistoryLocation.Space(f.access, f.conversation, f.room)

    private suspend fun page(
        f: CollaborationSpaceHistoryFixture,
        cursor: CollaborationHistoryChangeCursor? = null,
        limit: Int = 16,
        source: CollaborationSpaceHistoryLocation.Space = location(f),
    ) =
        assertIs<CollaborationHistoryChanges.Page>(
            success(f.repository.changes(source, cursor, limit))
        )

    private fun completed(page: CollaborationHistoryChanges.Page) =
        CollaborationHistoryChangeCursor.After(
            page.window.owner,
            page.window.generation,
            page.window.throughRevision,
        )

    private fun <T> success(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

    private suspend fun corruptOriginal(
        f: CollaborationSpaceHistoryFixture,
        transform: (ReplicaDocument) -> ReplicaDocument,
    ) {
        val before = f.raw()
        val head = assertNotNull(before.checkpoint)
        val original = before.documents.single { it.id.startsWith("m-") }
        CollaborationHistoryKeys(f.database.replicas, f.cipher, f.keys).use { content, index ->
            val codec =
                CollaborationHistoryCodec(
                    f.access,
                    f.conversation,
                    f.room,
                    f.cipher,
                    content,
                    index,
                )
            val checkpoint = codec.checkpoint(head)
            assertTrue(
                f.database.replicas.commit(
                    head.handle,
                    head.revision,
                    codec.checkpoint(
                        head.handle.generation,
                        head.revision + 1,
                        checkpoint.count,
                        checkpoint.hiddenRules,
                    ),
                    listOf(transform(original)),
                    true,
                )
            )
        }
    }

    private fun edit(original: CollaborationHistoryChange.Text) =
        CollaborationMessageEdit(
            "\$revision",
            original.sender,
            original.eventId,
            original.timestamp + 100,
            "Revised fictional text",
            "f".repeat(64),
            historyId(1001),
            original.logicalId,
            original.replyToEventId,
            original.forwarded,
        )

    private fun withDatabase(block: suspend (CollaborationSpaceHistoryFixture) -> Unit) = runTest {
        val fixture = CollaborationSpaceHistoryFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
