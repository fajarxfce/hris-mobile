package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.notifications.NOTIFICATION_PUBLICATION_PARTITION
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest

/**
 * Actual encrypted SQLite/IndexedDB storage; fixture keys retained in memory, no OS publication.
 */
abstract class CollaborationNotificationPublicationStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun dismissalIntentReopensInCleanupDiscoveryWithoutPreviewOrPreparedPublication() =
        withDatabase { f ->
            val original = f.store(null, f.body())
            val body =
                original.body.copy(
                    state = CollaborationNotificationPublicationState.Dismissing,
                    message = null,
                    actions = null,
                )
            val pending = f.store(original, body)
            f.reopen()
            assertEquals(pending, f.read(body))
            assertEquals(
                listOf(pending),
                expect(f.journal().page(f.access(), preparedOnly = false)).publications,
            )
            assertTrue(
                expect(f.journal().page(f.access(), preparedOnly = true)).publications.isEmpty()
            )
            assertIs<AppResult.Failed>(
                f.journal()
                    .replace(
                        f.access(),
                        pending,
                        original.body.copy(
                            state = CollaborationNotificationPublicationState.Dismissing
                        ),
                    )
            )
            val completed = f.store(pending, body.inactive())
            f.reopen()
            assertEquals(completed, f.read(body))
            assertTrue(
                expect(f.journal().page(f.access(), preparedOnly = false)).publications.isEmpty()
            )
        }

    @Test
    fun aLostDismissalWriteResponseRetainsTheExactIndexedCleanupIntent() = withDatabase { f ->
        val original = f.store(null, f.body())
        val body =
            original.body.copy(
                state = CollaborationNotificationPublicationState.Dismissing,
                message = null,
                actions = null,
            )
        val actual = f.database.replicas
        val lost =
            object : ReplicaStore by actual {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    assertTrue(
                        actual.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                            wake,
                        )
                    )
                    error("Simulated lost response")
                }
            }
        assertIs<AppResult.Failed>(f.journal(lost).replace(f.access(), original, body))
        f.reopen()
        val pending = f.read(body)!!
        assertEquals(body, pending.body)
        assertEquals(
            listOf(pending),
            expect(f.journal().page(f.access(), preparedOnly = false)).publications,
        )
        assertEquals(
            AppResult.Success(CollaborationNotificationPublicationWrite.Contended),
            f.journal().replace(f.access(), original, body),
        )
    }

    @Test
    fun exactPreviewAndAllActionEnvelopesReopenWithoutPlaintextOrHistoryMutation() =
        withDatabase { f ->
            val history = f.raw()
            val body = f.body()
            val stored = f.store(null, body)
            val raw = f.rawPublications()
            val metadata = listOf(raw.checkpoint.toString()) + raw.documents.map { it.toString() }
            for (privateValue in
                listOf(
                    body.message!!.body!!,
                    body.message!!.senderName!!,
                    body.actions!!.reply.encoded,
                    body.target.conversationId,
                    body.parent.nativeSessionId,
                )) {
                assertTrue(metadata.none { privateValue in it })
                assertFalse(stored.toString().contains(privateValue))
                assertFalse(body.toString().contains(privateValue))
            }
            f.reopen()
            assertEquals(stored, f.read(body))
            assertEquals(listOf(stored), expect(f.journal().page(f.access())).publications)
            val decoded =
                expect(
                    EncryptedCollaborationNotificationActionRepository(
                            f.database.replicas,
                            f.cipher,
                            f.keys,
                        )
                        .open(body.parent, body.actions!!.reply)
                )
            assertEquals(
                body.target,
                assertIs<CollaborationNotificationAction.Reply>(decoded).target,
            )
            assertEquals(history, f.raw())
        }

    @Test
    fun staleAcknowledgmentAndDismissalCannotReplaceANewerRender() = withDatabase { f ->
        val first = f.store(null, f.body())
        val published =
            f.store(
                first,
                first.body.copy(state = CollaborationNotificationPublicationState.Published),
            )
        assertTrue(published.version.revision > first.version.revision)
        val next = f.store(published, f.body(2))
        assertEquals(first.id, next.id)
        assertEquals(first.body.target.notificationId, next.body.target.notificationId)
        assertNotEquals(first.body.target.renderId, next.body.target.renderId)
        val inactive = published.body.inactive()
        assertEquals(
            AppResult.Success(CollaborationNotificationPublicationWrite.Contended),
            f.journal().replace(f.access(), published, inactive),
        )
        assertEquals(
            AppResult.Success(CollaborationNotificationPublicationWrite.Contended),
            f.journal().replace(f.access(), null, inactive),
        )
        f.reopen()
        assertEquals(next, f.read(next.body))
    }

    @Test
    fun anotherConversationsCommitDoesNotInvalidateAnUnchangedDocumentSnapshot() =
        withDatabase { f ->
            val first = f.store(null, f.body())
            val second = f.store(null, f.body(2, historyId(4002)))
            val updated =
                f.store(
                    first,
                    first.body.copy(state = CollaborationNotificationPublicationState.Published),
                )
            assertTrue(updated.version.revision > second.version.revision)
            assertEquals(second, f.read(second.body))
            assertEquals(listOf(second), expect(f.journal().page(f.access())).publications)
            assertEquals(
                setOf(updated, second),
                expect(f.journal().page(f.access(), preparedOnly = false)).publications.toSet(),
            )
        }

    @Test
    fun activePagingIsBoundedAndPreparedFilterDoesNotResurrectDismissedWatermarks() =
        withDatabase { f ->
            val all = (1..7).map { f.store(null, f.body(it, historyId(4000 + it))) }
            val inactive = f.store(all[1], all[1].body.inactive())
            val published =
                f.store(
                    all[3],
                    all[3].body.copy(state = CollaborationNotificationPublicationState.Published),
                )
            val expected =
                all.filter { it.id != inactive.id && it.id != published.id }.sortedBy { it.id }
            val first = expect(f.journal().page(f.access()))
            assertEquals(expected.take(4), first.publications)
            assertEquals(first.publications.last().id, first.nextAfter)
            f.reopen()
            val last = expect(f.journal().page(f.access(), first.nextAfter))
            assertEquals(expected.drop(4), last.publications)
            assertNull(last.nextAfter)
            assertEquals(inactive, f.read(inactive.body))
            assertNull(inactive.body.message)
            assertNull(inactive.body.actions)
        }

    @Test
    fun activeCapacityNeverEvictsAndInactiveWatermarksReleaseCapacityAtomically() =
        withDatabase { f ->
            val all = (1..64).map { f.store(null, f.body(it, historyId(4000 + it))) }
            val extra = f.body(65, historyId(4065))
            val before = f.rawPublications()
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationWrite.CapacityExceeded),
                f.journal().replace(f.access(), null, extra),
            )
            assertEquals(before, f.rawPublications())
            val dismissing =
                f.store(
                    all.first(),
                    all.first()
                        .body
                        .copy(
                            state = CollaborationNotificationPublicationState.Dismissing,
                            message = null,
                            actions = null,
                        ),
                )
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationWrite.CapacityExceeded),
                f.journal().replace(f.access(), null, extra),
            )
            val inactive = f.store(dismissing, dismissing.body.inactive())
            val accepted = f.store(null, extra)
            f.reopen()
            assertEquals(inactive, f.read(inactive.body))
            assertEquals(accepted, f.read(extra))
            assertNull(inactive.body.message)
            assertNull(inactive.body.actions)
            for (old in all.drop(1)) assertEquals(old, f.read(old.body))
        }

    @Test
    fun sameAccountReauthenticationKeepsWatermarksWhileOtherAccountsCannotReadOrWriteThem() =
        withDatabase { f ->
            val first = f.store(null, f.body())
            val renewed = f.access.copy(sessionId = "renewed-login")
            f.reopen(renewed)
            val access =
                CollaborationRecoveryAccess(renewed.sessionId, renewed.audience, renewed.accountId)
            assertEquals(
                first,
                expect(
                    f.journal()
                        .read(access, first.body.target.companyId, first.body.target.conversationId)
                ),
            )
            val inactive =
                assertIs<CollaborationNotificationPublicationWrite.Stored>(
                        expect(f.journal().replace(access, first, first.body.inactive()))
                    )
                    .publication
            val other = renewed.copy(accountId = historyId(9999))
            f.activate(other)
            val otherAccess = access.copy(accountId = other.accountId)
            assertNull(
                expect(
                    f.journal()
                        .read(
                            otherAccess,
                            first.body.target.companyId,
                            first.body.target.conversationId,
                        )
                )
            )
            assertIs<AppResult.Failed>(f.journal().replace(otherAccess, inactive, first.body))
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationWrite.Contended),
                f.journal().replace(access, inactive, first.body),
            )
            f.activate(renewed)
            assertEquals(
                inactive,
                expect(
                    f.journal()
                        .read(access, first.body.target.companyId, first.body.target.conversationId)
                ),
            )
        }

    @Test
    fun missingAndLostKeysNeverCreateReplacementMaterialOrOverwriteRetainedFacts() =
        withDatabase(seed = false) { f ->
            val empty = f.watermark()
            assertNull(
                expect(
                    f.journal()
                        .read(f.access(), empty.target.companyId, empty.target.conversationId)
                )
            )
            assertTrue(expect(f.journal().page(f.access())).publications.isEmpty())
            assertIs<AppResult.Failed>(f.journal().replace(f.access(), null, empty))
            assertNull(f.keys.read())
            f.merge(f.event())
            val first = f.store(null, f.body())
            val history = f.raw()
            val raw = f.rawPublications()
            f.keys.write(null)
            assertIs<AppResult.Failed>(
                f.journal()
                    .read(f.access(), first.body.target.companyId, first.body.target.conversationId)
            )
            assertIs<AppResult.Failed>(
                f.journal().replace(f.access(), first, first.body.inactive())
            )
            assertNull(f.keys.read())
            assertEquals(raw, f.rawPublications())
            assertEquals(history, f.raw())
        }

    @Test
    fun missingActiveDocumentAndTamperedCheckpointDoNotProducePartialPages() = withDatabase { f ->
        val first = f.store(null, f.body())
        val actual = f.database.replicas
        val raw = f.rawPublications().checkpoint!!
        val missing =
            object : ReplicaStore by actual {
                override suspend fun document(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): ReplicaDocument? = null
            }
        assertIs<AppResult.Failed>(f.journal(missing).page(f.access()))
        for (bad in
            listOf(
                raw.copy(revision = raw.revision + 1),
                raw.copy(payload = "AAAA"),
                raw.copy(handle = raw.handle.copy(generation = historyId(8888))),
            )) {
            val altered =
                object : ReplicaStore by actual {
                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? = bad
                }
            assertIs<AppResult.Failed>(f.journal(altered).page(f.access()))
        }
        assertEquals(first, f.read(first.body))
    }

    @Test
    fun tamperedDocumentIdentityRevisionCiphertextAndCrossAccountReplayAreRejected() =
        withDatabase { f ->
            val first = f.store(null, f.body())
            val actual = f.database.replicas
            val document = f.rawPublications().documents.single()
            for (bad in
                listOf(
                    document.copy(id = "p-" + "0".repeat(64)),
                    document.copy(version = document.version + 1),
                    document.copy(sortKey = "other"),
                    document.copy(payload = "AAAA"),
                )) {
                val altered =
                    object : ReplicaStore by actual {
                        override suspend fun document(
                            scope: ReplicaScope,
                            partition: String,
                            id: String,
                        ): ReplicaDocument? = bad
                    }
                assertIs<AppResult.Failed>(
                    f.journal(altered)
                        .read(
                            f.access(),
                            first.body.target.companyId,
                            first.body.target.conversationId,
                        )
                )
            }
            val raw = f.rawPublications().checkpoint!!
            val other = f.access().copy(accountId = historyId(9999))
            val replay =
                object : ReplicaStore by actual {
                    override suspend fun checkpoint(scope: ReplicaScope, partition: String) =
                        raw.copy(handle = raw.handle.copy(scope = recoveryScope(other)))
                }
            assertIs<AppResult.Failed>(f.journal(replay).page(other))
            assertEquals(first, f.read(first.body))
        }

    @Test
    fun aLateAccountReplacementRejectsTheLoadedPrivateBody() = withDatabase { f ->
        val first = f.store(null, f.body())
        val actual = f.database.replicas
        val store =
            object : ReplicaStore by actual {
                override suspend fun document(
                    scope: ReplicaScope,
                    partition: String,
                    id: String,
                ): ReplicaDocument? {
                    val value = actual.document(scope, partition, id)
                    f.activate(f.access.copy(accountId = historyId(9999)))
                    return value
                }
            }
        assertIs<AppResult.Failed>(
            f.journal(store)
                .read(f.access(), first.body.target.companyId, first.body.target.conversationId)
        )
        f.activate()
        assertEquals(first, f.read(first.body))
    }

    @Test
    fun cancellationAfterInsertOrUpdateJoinsAndRetainsTheExactCommittedEnvelopes() =
        withDatabase { f ->
            for (update in listOf(false, true)) {
                val body = f.body(if (update) 2 else 1, historyId(if (update) 4002 else 4001))
                val previous = if (update) f.store(null, body) else null
                val desired =
                    if (update)
                        body.copy(state = CollaborationNotificationPublicationState.Published)
                    else body
                val actual = f.database.replicas
                val entered = CompletableDeferred<Unit>()
                var joined = false
                suspend fun pause(): Boolean {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        joined = true
                    }
                }
                val store =
                    object : ReplicaStore by actual {
                        override suspend fun replace(
                            scope: ReplicaScope,
                            partition: String,
                            expected: ReplicaCheckpoint?,
                            snapshot: ReplicaSnapshot?,
                            wake: WorkWakeRequest?,
                        ): Boolean {
                            assertTrue(actual.replace(scope, partition, expected, snapshot, wake))
                            return pause()
                        }

                        override suspend fun commit(
                            handle: ReplicaHandle,
                            expectedRevision: Long,
                            checkpoint: String,
                            documents: List<ReplicaDocument>,
                            publish: Boolean,
                            wake: WorkWakeRequest?,
                        ): Boolean {
                            assertTrue(
                                actual.commit(
                                    handle,
                                    expectedRevision,
                                    checkpoint,
                                    documents,
                                    publish,
                                    wake,
                                )
                            )
                            return pause()
                        }
                    }
                coroutineScope {
                    val job = async { f.journal(store).replace(f.access(), previous, desired) }
                    select {
                        entered.onAwait {}
                        job.onAwait { fail("Replacement returned before the commit barrier") }
                    }
                    job.cancelAndJoin()
                }
                assertTrue(joined)
                f.reopen()
                assertEquals(desired, f.read(body)!!.body)
            }
        }

    @Test
    fun aLostCommitReplyIsRecoveredByReadingWithoutAHiddenRetryOrNewEnvelope() = withDatabase { f ->
        val first = f.store(null, f.body())
        val actual = f.database.replicas
        var commits = 0
        val store =
            object : ReplicaStore by actual {
                override suspend fun commit(
                    handle: ReplicaHandle,
                    expectedRevision: Long,
                    checkpoint: String,
                    documents: List<ReplicaDocument>,
                    publish: Boolean,
                    wake: WorkWakeRequest?,
                ): Boolean {
                    commits++
                    assertTrue(
                        actual.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                            wake,
                        )
                    )
                    return false
                }
            }
        val desired = first.body.copy(state = CollaborationNotificationPublicationState.Published)
        assertEquals(
            AppResult.Success(CollaborationNotificationPublicationWrite.Contended),
            f.journal(store).replace(f.access(), first, desired),
        )
        assertEquals(1, commits)
        f.reopen()
        val retained = f.read(desired)!!
        assertEquals(desired, retained.body)
        assertEquals(first.body.actions, retained.body.actions)
        assertEquals(
            AppResult.Success(CollaborationNotificationPublicationWrite.Contended),
            f.journal().replace(f.access(), first, first.body.inactive()),
        )
    }

    @Test
    fun malformedStatePreviewOwnerAndPageBoundsNeverChangeTheJournal() = withDatabase { f ->
        val body = f.body()
        val first = f.store(null, body)
        val raw = f.rawPublications()
        for (bad in
            listOf(
                body.copy(parent = body.parent.copy(nativeSessionId = "bad")),
                body.copy(target = body.target.copy(renderId = "bad")),
                body.copy(contentHash = "bad"),
                body.copy(timestamp = -1),
                body.copy(message = null),
                body.copy(actions = null),
                body.copy(state = CollaborationNotificationPublicationState.Dismissed),
                body.copy(
                    message =
                        body.message!!.copy(
                            access = body.message!!.access.copy(companyId = historyId(7777))
                        )
                ),
                body.copy(
                    message = body.message!!.copy(preview = CollaborationNotificationPreview.Hidden)
                ),
                body.copy(message = body.message!!.copy(body = "x".repeat(401))),
                body.copy(message = body.message!!.copy(body = "\uDC00")),
                body.copy(
                    actions =
                        body.actions!!.copy(
                            reply = CollaborationNotificationActionToken("v2.invalid")
                        )
                ),
                body.copy(
                    actions =
                        body.actions!!.copy(reply = CollaborationNotificationActionToken("v1.AA=A"))
                ),
                body.copy(
                    actions =
                        body.actions!!.copy(reply = CollaborationNotificationActionToken("v1.AB=="))
                ),
            )) assertIs<AppResult.Failed>(f.journal().replace(f.access(), first, bad))
        assertIs<AppResult.Failed>(f.journal().page(f.access(), limit = 0))
        assertIs<AppResult.Failed>(f.journal().page(f.access(), limit = 5))
        assertIs<AppResult.Failed>(f.journal().page(f.access(), after = "bad"))
        assertEquals(raw, f.rawPublications())
    }

    @Test
    fun boundedMultibytePreviewsAndHiddenContentRoundTrip() = withDatabase { f ->
        val body = f.body()
        val full =
            body.copy(
                message = body.message!!.copy(senderName = "🙂".repeat(80), body = "🙂".repeat(200))
            )
        val stored = f.store(null, full)
        assertTrue(f.rawPublications().documents.single().payload!!.length <= 49_152)
        f.reopen()
        assertEquals(stored, f.read(full))
        val hidden =
            full.copy(
                message =
                    full.message!!.copy(
                        preview = CollaborationNotificationPreview.Hidden,
                        senderName = null,
                        body = null,
                    )
            )
        val next = f.store(stored, hidden)
        f.reopen()
        assertEquals(next, f.read(hidden))
        assertNull(next.body.message!!.body)
    }

    private fun CollaborationHistoryFixture.access() =
        CollaborationRecoveryAccess(access.sessionId, access.audience, access.accountId)

    private fun CollaborationHistoryFixture.journal(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationNotificationPublicationRepository(store, cipher, keys)

    private suspend fun CollaborationHistoryFixture.rawPublications() =
        database.replicas
            .observe(recoveryScope(access()), NOTIFICATION_PUBLICATION_PARTITION, limit = 200)
            .first()

    private suspend fun CollaborationHistoryFixture.read(
        body: CollaborationNotificationPublicationBody
    ) = expect(journal().read(access(), body.target.companyId, body.target.conversationId))

    private suspend fun CollaborationHistoryFixture.store(
        expected: CollaborationNotificationPublication?,
        body: CollaborationNotificationPublicationBody,
    ) =
        assertIs<CollaborationNotificationPublicationWrite.Stored>(
                expect(journal().replace(access(), expected, body))
            )
            .publication

    private fun CollaborationNotificationPublicationBody.inactive() =
        copy(
            state = CollaborationNotificationPublicationState.Dismissed,
            message = null,
            actions = null,
        )

    private fun CollaborationHistoryFixture.watermark(
        index: Int = 1,
        conversationId: String = conversation,
    ) =
        CollaborationNotificationPublicationBody(
            CollaborationNativeParent(
                access.sessionId,
                access.audience,
                access.accountId,
                historyId(9800),
            ),
            CollaborationNotificationActionTarget(
                access.companyId,
                conversationId,
                room,
                event(index).eventId,
                historyId(60_000 + conversationId.takeLast(6).toInt()),
                historyId(10_000 + index),
            ),
            event(index).contentHash,
            event(index).timestamp,
            CollaborationNotificationPublicationState.Cancelled,
        )

    private suspend fun CollaborationHistoryFixture.body(
        index: Int = 1,
        conversationId: String = conversation,
    ): CollaborationNotificationPublicationBody {
        val base = watermark(index, conversationId)
        val message =
            CollaborationMessageNotification(
                access,
                conversationId,
                room,
                base.target.eventId,
                base.contentHash,
                CollaborationNotificationSettingsRevision(historyId(9701), 1),
                null,
                CollaborationNotificationPreview.Message,
                "Fictional sender",
                "Fictional private preview $index",
            )
        val tokens =
            EncryptedCollaborationNotificationActionRepository(database.replicas, cipher, keys)
        val actions =
            CollaborationNotificationPublicationActions(
                expect(
                    tokens.seal(
                        base.parent,
                        CollaborationNotificationAction.Reply(
                            historyId(20_000 + index),
                            base.target,
                        ),
                    )
                ),
                expect(
                    tokens.seal(
                        base.parent,
                        CollaborationNotificationAction.MarkRead(
                            historyId(30_000 + index),
                            base.target,
                        ),
                    )
                ),
                expect(
                    tokens.seal(
                        base.parent,
                        CollaborationNotificationAction.Mute(
                            historyId(40_000 + index),
                            base.target,
                            message.settingsVersion,
                            message.preview,
                        ),
                    )
                ),
                expect(
                    tokens.seal(
                        base.parent,
                        CollaborationNotificationAction.Dismiss(
                            historyId(50_000 + index),
                            base.target,
                        ),
                    )
                ),
            )
        return base.copy(
            state = CollaborationNotificationPublicationState.Prepared,
            message = message,
            actions = actions,
        )
    }

    private fun <T> expect(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

    private fun withDatabase(
        seed: Boolean = true,
        block: suspend (CollaborationHistoryFixture) -> Unit,
    ) = runTest {
        val f = CollaborationHistoryFixture(databaseOwner())
        try {
            f.activate()
            if (seed) f.merge(f.event())
            block(f)
        } finally {
            f.close()
        }
    }
}
