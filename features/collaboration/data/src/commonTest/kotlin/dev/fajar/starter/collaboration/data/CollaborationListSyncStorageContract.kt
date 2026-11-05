package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationListRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

abstract class CollaborationListSyncStorageContract {
    protected abstract fun databaseOwner(): CollaborationListDatabaseOwner

    @Test
    fun versionOneFactsAndKeysSurviveStagingRestartUntilCatchupPublication() = withDatabase { f ->
        f.merge(listOf(listSummary(1), listSummary(2)))
        val original = f.raw()
        val key = f.keys.read()
        var stage = f.startSync()
        val renamed =
            listSummary(1).copy(peer = listSummary(1).peer.copy(displayName = "Updated colleague"))
        stage =
            f.writeSync(
                stage,
                1,
                listOf(CollaborationSummaryFact(listId(1), 4, renamed)),
                state =
                    stage.sync!!.copy(
                        cursor = "opaque-bootstrap",
                        afterConversationId = listId(1),
                        bootstrapPages = 1,
                    ),
            )
        assertEquals(original, f.raw())
        assertEquals(1, f.checkpoint()?.format)
        assertEquals(renamed, f.window(listOf(listId(1))).facts.single().summary)
        val resumed = f.access.copy(sessionId = "reopened")
        f.reopen(resumed)
        val resumedStage =
            assertIs<AppResult.Success<CollaborationListSyncWindow>>(
                    f.repository.syncWindow(resumed, listOf(listId(1)))
                )
                .value
        assertEquals(stage, resumedStage.checkpoint)
        assertEquals(renamed, resumedStage.facts.single().summary)
        f.activate()
        stage =
            f.writeSync(
                stage,
                2,
                listOf(CollaborationSummaryFact(listId(2), 7, null)),
                state =
                    CollaborationListSyncState(
                        listId(9100),
                        CollaborationListSyncPhase.CATCHUP,
                        "opaque-catchup",
                    ),
            )
        assertEquals(original, f.raw())
        f.writeSync(
            stage,
            2,
            emptyList(),
            publish = true,
            state =
                stage.sync!!.copy(
                    phase = CollaborationListSyncPhase.CHANGES,
                    cursor = "opaque-ready",
                ),
        )
        assertEquals(listOf(renamed), f.page().items)
        assertEquals(2, f.checkpoint()?.format)
        assertEquals(key, f.keys.read())
        assertFalse(f.raw().checkpoint!!.payload.contains("opaque-ready"))
    }

    @Test
    fun interactiveInsertPreemptsPartialReplacementAndRejectsItsLateCompletion() =
        withDatabase { f ->
            f.merge(listOf(listSummary(1)))
            val before = f.checkpoint()
            val stage = f.startSync()
            f.merge(listOf(listSummary(2)))
            assertEquals(listOf(listSummary(1), listSummary(2)), f.page().items)
            assertEquals(before?.generation, f.checkpoint()?.generation)
            val late =
                stage.copy(
                    local = stage.local.copy(revision = 1, count = 1),
                    published = true,
                    sync =
                        stage.sync!!.copy(
                            phase = CollaborationListSyncPhase.CHANGES,
                            cursor = "late",
                        ),
                )
            assertEquals(
                AppResult.Success(false),
                f.repository.commitSync(
                    f.access,
                    stage,
                    late,
                    listOf(CollaborationSummaryFact(listId(3), 0, listSummary(3))),
                ),
            )
            assertEquals(listOf(listSummary(1), listSummary(2)), f.page().items)
        }

    @Test
    fun interactiveFallbackCannotReplaceTrackedMetadataOrResurrectATombstone() = withDatabase { f ->
        var current = f.startSync()
        current =
            f.writeSync(
                current,
                2,
                listOf(
                    CollaborationSummaryFact(listId(1), 5, listSummary(1)),
                    CollaborationSummaryFact(listId(2), 7, null),
                ),
                publish = true,
                state =
                    current.sync!!.copy(
                        phase = CollaborationListSyncPhase.CHANGES,
                        cursor = "ready",
                    ),
            )
        val tracked = f.raw().documents.single()
        f.startSync(9200)
        val renamed =
            listSummary(1).copy(peer = listSummary(1).peer.copy(displayName = "Untracked response"))
        f.merge(listOf(renamed, listSummary(2), listSummary(3)))
        assertEquals(listOf(listSummary(1), listSummary(3)), f.page().items)
        assertEquals(tracked, f.raw().documents.first())
        val window = f.window(listOf(listId(1), listId(2), listId(3)))
        assertEquals(listOf(5L, 7L, null), window.facts.map { it.revision })
        assertNull(window.facts[1].summary)
        assertEquals(3, window.checkpoint?.local?.count)
        assertEquals(current.sync, window.checkpoint?.sync)
        val authoritative =
            listSummary(3)
                .copy(peer = listSummary(3).peer.copy(displayName = "Tracked counterpart"))
        f.writeSync(
            window.checkpoint!!,
            3,
            listOf(CollaborationSummaryFact(listId(3), 0, authoritative)),
            publish = true,
        )
        assertEquals(0L, f.window(listOf(listId(3))).facts.single().revision)
        assertEquals(authoritative, f.page().items.last())
    }

    @Test
    fun emptyChangesKeepDocumentBytesAndDenialWhileAdvancingTheCursor() = withDatabase { f ->
        var current = f.startSync()
        current =
            f.writeSync(
                current,
                1,
                listOf(CollaborationSummaryFact(listId(1), 8, listSummary(1))),
                publish = true,
                state =
                    current.sync!!.copy(
                        phase = CollaborationListSyncPhase.CHANGES,
                        cursor = "before",
                    ),
            )
        assertEquals(AppResult.Success(true), f.repository.restrict(f.access, f.denied))
        val denied = f.admission()
        val before = f.raw()
        val after =
            f.writeSync(
                current,
                1,
                emptyList(),
                publish = true,
                state = current.sync!!.copy(cursor = "after"),
            )
        assertEquals(before.documents, f.raw().documents)
        assertNotEquals(before.checkpoint, f.raw().checkpoint)
        assertEquals(denied, f.admission())
        assertEquals("after", f.window(emptyList()).checkpoint?.sync?.cursor)
        val bad = after.copy(local = after.local.copy(revision = after.local.revision + 1))
        assertIs<AppResult.Failed>(
            f.repository.commitSync(
                f.access,
                after,
                bad,
                listOf(CollaborationSummaryFact(listId(1), 9_007_199_254_740_992L, listSummary(1))),
            )
        )
        assertEquals(after, f.window(emptyList()).checkpoint)
    }

    @Test
    fun cancelledEncryptionAndLateAccountReplacementCannotAdvanceTheCursor() = withDatabase { f ->
        f.merge(listOf(listSummary(1)))
        val original = f.raw()
        for (replaceOwner in listOf(false, true)) coroutineScope {
            val stage = f.startSync(if (replaceOwner) 9220 else 9210)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blockedCipher =
                object : ContentCipher by f.cipher {
                    override suspend fun encrypt(
                        key: ByteArray,
                        associatedData: ByteArray,
                        plaintext: ByteArray,
                    ): ByteArray {
                        entered.complete(Unit)
                        release.await()
                        return f.cipher.encrypt(key, associatedData, plaintext)
                    }
                }
            val delayed =
                EncryptedCollaborationListRepository(
                    f.database.replicas,
                    blockedCipher,
                    f.keys,
                    f.admissions,
                )
            val next =
                stage.copy(
                    local = stage.local.copy(revision = 1, count = 1),
                    published = true,
                    sync =
                        stage.sync!!.copy(
                            phase = CollaborationListSyncPhase.CHANGES,
                            cursor = "delayed",
                        ),
                )
            val write = async {
                delayed.commitSync(
                    f.access,
                    stage,
                    next,
                    listOf(CollaborationSummaryFact(listId(2), 5, listSummary(2))),
                )
            }
            entered.await()
            if (replaceOwner) {
                f.activate(f.access.copy(sessionId = "replacement"))
                release.complete(Unit)
                assertEquals(AppResult.Success(false), write.await())
                f.activate()
            } else {
                write.cancelAndJoin()
                assertTrue(write.isCancelled)
            }
            assertEquals(stage, f.window(emptyList()).checkpoint)
            assertEquals(original, f.raw())
        }
    }

    @Test
    fun competingPublicationAndInteractiveInsertCanRetryWithoutLosingEitherFact() =
        withDatabase { f ->
            coroutineScope {
                f.merge(listOf(listSummary(1)))
                val published = f.checkpoint()
                val stage = f.startSync()
                val complete =
                    stage.copy(
                        local = stage.local.copy(revision = 1, count = 1),
                        published = true,
                        sync =
                            stage.sync!!.copy(
                                phase = CollaborationListSyncPhase.CHANGES,
                                cursor = "complete",
                            ),
                    )
                val sync = async {
                    f.repository.commitSync(
                        f.access,
                        stage,
                        complete,
                        listOf(CollaborationSummaryFact(listId(1), 6, listSummary(1))),
                    )
                }
                val insert = async {
                    f.repository.merge(f.access, published, listOf(listSummary(2)), f.now)
                }
                val synced = assertIs<AppResult.Success<Boolean>>(sync.await()).value
                val inserted = assertIs<AppResult.Success<Boolean>>(insert.await()).value
                assertNotEquals(synced, inserted)
                if (!inserted) f.merge(listOf(listSummary(2)))
                assertEquals(listOf(listSummary(1), listSummary(2)), f.page().items)
                if (synced) {
                    assertEquals(complete.sync, f.window(emptyList()).checkpoint?.sync)
                    assertEquals(
                        listOf(6L, null),
                        f.window(listOf(listId(1), listId(2))).facts.map { it.revision },
                    )
                }
            }
        }

    private fun withDatabase(block: suspend (CollaborationListFixture) -> Unit) = runTest {
        val fixture = CollaborationListFixture(databaseOwner())
        try {
            fixture.activate()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}

private suspend fun CollaborationListFixture.window(ids: List<String>) =
    assertIs<AppResult.Success<CollaborationListSyncWindow>>(repository.syncWindow(access, ids))
        .value

private suspend fun CollaborationListFixture.startSync(
    generation: Int = 9199
): CollaborationListSyncCheckpoint {
    val expected = window(emptyList()).checkpoint
    val initial =
        CollaborationListSyncCheckpoint(
            CollaborationListCheckpoint(listId(generation), 0, 0, now, 2),
            false,
            CollaborationListSyncState(listId(9100), CollaborationListSyncPhase.BOOTSTRAP, null),
        )
    assertEquals(AppResult.Success(true), repository.beginSync(access, expected, initial))
    return initial
}

private suspend fun CollaborationListFixture.writeSync(
    expected: CollaborationListSyncCheckpoint,
    count: Int,
    facts: List<CollaborationSummaryFact>,
    publish: Boolean = false,
    state: CollaborationListSyncState = expected.sync!!,
): CollaborationListSyncCheckpoint {
    val next =
        expected.copy(
            local = expected.local.copy(revision = expected.local.revision + 1, count = count),
            published = publish,
            sync = state,
        )
    assertEquals(AppResult.Success(true), repository.commitSync(access, expected, next, facts))
    return next
}
