package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/** Browse compatibility and cryptographic/transactional isolation from incoming history. */
abstract class CollaborationHistoryPurposeStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun legacyBrowseCheckpointOpensWithoutMigrationOrResetAndIncomingStartsEmpty() =
        withDatabase { f ->
            val generation = historyId(9120)
            val context =
                listOf(
                    "hris-history-pages-v1",
                    f.access.audience,
                    f.access.accountId,
                    f.access.companyId,
                    f.traversal.conversationId,
                    f.traversal.roomId,
                    f.traversal.deviceId,
                    generation,
                    "checkpoint",
                    "0",
                    "",
                )
            val payload =
                """{"roomId":"${f.traversal.roomId}","next":"legacy-next","references":0,"schema":1}"""
            val encrypted =
                CollaborationHistoryKeys(f.local.database.replicas, f.local.cipher, f.local.keys)
                    .use(create = true) { key, _ ->
                        Base64.encode(
                            f.local.cipher.encrypt(
                                key,
                                Json.encodeToString(context).encodeToByteArray(),
                                payload.encodeToByteArray(),
                            )
                        )
                    }
            // Real legacy partition and AAD, independent of the new codec's branch selection.
            val partition =
                historyPartition(f.access, f.traversal.conversationId) +
                    "/pages/" +
                    f.traversal.deviceId
            assertTrue(
                f.local.database.replicas.replace(
                    historyScope(f.access),
                    partition,
                    null,
                    ReplicaSnapshot(generation, encrypted, emptyList()),
                )
            )
            f.local.reopen()
            assertEquals(
                CollaborationHistoryProgress(generation, 0, "legacy-next", 0),
                f.progress(),
            )
            assertNull(f.success(f.repository().progress(f.access, incoming(f))))
            assertTrue(
                f.local.database.replicas.replace(
                    historyScope(f.access),
                    traversalPartition(f.access, incoming(f)),
                    null,
                    ReplicaSnapshot(generation, encrypted, emptyList()),
                )
            )
            assertIs<AppResult.Failed>(f.repository().progress(f.access, incoming(f)))
        }

    @Test
    fun incomingRestartAndResolutionNeverChangeBrowsePositionOrReferenceIdentity() =
        withDatabase { f ->
            f.stage(listOf(f.event(), f.event(2)), "browse-next")
            val key = incoming(f)
            val repository = f.repository()
            assertTrue(
                f.success(
                    repository.stage(
                        f.access,
                        key,
                        null,
                        f.page(listOf(f.event(), f.event(3)), next = null),
                    )
                )
            )
            val browse = f.raw()
            val page = f.success(repository.observeReferences(f.access, key).first())
            assertTrue(
                f.success(repository.resolve(f.access, key, page.progress!!, page.events.take(1)))
            )
            val previous = f.success(repository.progress(f.access, key))!!
            assertTrue(
                f.success(
                    repository.stage(
                        f.access,
                        key,
                        previous,
                        f.page(listOf(f.event(), f.event(4)), next = "incoming-next")
                            .copy(requestedUntilPosition = previous.incoming!!.head),
                        CollaborationHistoryPageOrigin.Latest,
                    )
                )
            )
            f.local.reopen()
            assertEquals(browse, f.raw())
            val pending = f.success(f.repository().observeReferences(f.access, key).first())
            assertEquals(listOf(f.event(3), f.event(4)), pending.events.map { it.event })
            assertEquals("incoming-next", pending.progress!!.next)
            assertEquals(listOf(1L, 2L), pending.events.map { it.sequence })
            assertEquals(listOf(f.event(), f.event(2)), f.read().events.map { it.event })
            f.local.merge(f.local.event())
            assertEquals(f.local.event().text, f.local.read().messages.single().text)
        }

    @Test
    fun sameEventUsesDifferentOpaqueIndexesAndTransplantedCiphertextIsRejected() =
        withDatabase { f ->
            val key = incoming(f)
            f.stage(listOf(f.event()))
            assertTrue(
                f.success(f.repository().stage(f.access, key, null, f.page(listOf(f.event()))))
            )
            val browse = f.raw()
            val other =
                f.local.database.replicas
                    .observe(historyScope(f.access), traversalPartition(f.access, key))
                    .first()
            assertNotEquals(browse.documents.single().id, other.documents.single().id)
            assertNotEquals(browse.documents.single().payload, other.documents.single().payload)
            val transplanted =
                object : ReplicaStore by f.local.database.replicas {
                    override fun observeQueries(
                        scope: ReplicaScope,
                        partition: String,
                        queries: List<ReplicaQuery>,
                    ) =
                        f.local.database.replicas.observeQueries(scope, partition, queries).map {
                            page ->
                            val own = page.pages.single().single()
                            // Keep the destination index/tag/version; only substitute the other
                            // lane's bytes.
                            page.copy(
                                pages =
                                    listOf(
                                        listOf(
                                            own.copy(payload = browse.documents.single().payload)
                                        )
                                    )
                            )
                        }

                    override suspend fun checkpoint(
                        scope: ReplicaScope,
                        partition: String,
                    ): ReplicaCheckpoint? {
                        val own =
                            f.local.database.replicas.checkpoint(scope, partition) ?: return null
                        return own.copy(payload = browse.checkpoint!!.payload)
                    }
                }
            assertIs<AppResult.Failed>(f.repository(transplanted).progress(f.access, key))
            assertIs<AppResult.Failed>(
                f.repository(transplanted).observeReferences(f.access, key).first()
            )
            assertEquals(browse, f.raw())
            assertEquals(
                other,
                f.local.database.replicas
                    .observe(historyScope(f.access), traversalPartition(f.access, key))
                    .first(),
            )
        }

    @Test
    fun competingLanesCommitIndependentlyWhileStaleSameLaneWritesLose() = withDatabase { f ->
        val key = incoming(f)
        f.stage(emptyList(), "browse-next")
        assertTrue(
            f.success(
                f.repository()
                    .stage(f.access, key, null, f.page(emptyList(), next = "incoming-next"))
            )
        )
        val browse = f.progress()!!
        val incoming = f.success(f.repository().progress(f.access, key))!!
        coroutineScope {
            val arrived = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            var count = 0
            val delayed =
                object : ReplicaStore by f.local.database.replicas {
                    override suspend fun commit(
                        handle: ReplicaHandle,
                        expectedRevision: Long,
                        checkpoint: String,
                        documents: List<ReplicaDocument>,
                        publish: Boolean,
                        wake: ReplicaWakeRequest?,
                    ): Boolean {
                        if (++count == 2) arrived.complete(Unit)
                        released.await()
                        return f.local.database.replicas.commit(
                            handle,
                            expectedRevision,
                            checkpoint,
                            documents,
                            publish,
                            wake,
                        )
                    }
                }
            val writes =
                listOf(f.traversal to browse, key to incoming).map { (traversal, previous) ->
                    async {
                        f.success(
                            f.repository(delayed)
                                .stage(
                                    f.access,
                                    traversal,
                                    previous,
                                    f.page(listOf(f.event()), previous.next, null),
                                )
                        )
                    }
                }
            arrived.await()
            released.complete(Unit)
            assertTrue(writes.awaitAll().all { it })
        }
        assertEquals(
            AppResult.Success(false),
            f.repository()
                .stage(f.access, key, incoming, f.page(listOf(f.event(2)), incoming.next, null)),
        )
        f.local.reopen()
        assertTrue(f.progress()!!.exhausted)
        assertTrue(f.success(f.repository().progress(f.access, key))!!.exhausted)
        assertEquals(1, f.progress()!!.references)
        assertEquals(1, f.success(f.repository().progress(f.access, key))!!.references)
    }

    private fun incoming(f: CollaborationHistoryTraversalFixture) =
        f.traversal.copy(purpose = CollaborationHistoryPurpose.Incoming)

    private fun withDatabase(block: suspend (CollaborationHistoryTraversalFixture) -> Unit) =
        runTest {
            val fixture = CollaborationHistoryTraversalFixture(databaseOwner())
            try {
                fixture.local.activate()
                block(fixture)
            } finally {
                fixture.local.close()
            }
        }
}
