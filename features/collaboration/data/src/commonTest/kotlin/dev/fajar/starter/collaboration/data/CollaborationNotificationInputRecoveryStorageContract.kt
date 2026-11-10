@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.data.notifications.NOTIFICATION_INPUT_RECOVERY_PARTITION
import dev.fajar.starter.collaboration.data.recovery.recoveryScope
import dev.fajar.starter.collaboration.data.repositories.EncryptedCollaborationNotificationInputRecoveryRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

abstract class CollaborationNotificationInputRecoveryStorageContract {
    protected abstract fun databaseOwner(): CollaborationHistoryDatabaseOwner

    @Test
    fun fullSnapshotAndInterruptedPositionReopenForSameAccountWithoutPlaintext() =
        withDatabase { f ->
            val state = state().copy(nextIndex = 17, inFlight = true, retryRequired = true)
            val saved = present(f.progress().replace(f.access(), null, state))
            val raw = f.rawProgress()
            for (reference in state.references) assertFalse(raw.payload.contains(reference.id))
            assertFalse(raw.payload.contains(state.cycleId))
            val key = f.keys.read()
            val renewed = f.access.copy(sessionId = "renewed-session")
            f.reopen(renewed)
            val access =
                CollaborationRecoveryAccess(renewed.sessionId, renewed.audience, renewed.accountId)
            assertEquals(AppResult.Success(saved), f.progress().current(access))
            assertEquals(key, f.keys.read())
            assertEquals(AppResult.Success(null), f.progress().current(f.access()))
        }

    @Test
    fun exactCasPreservesCompetingProgressAndLostWriteResponseReopensCommittedState() =
        withDatabase { f ->
            val first = present(f.progress().replace(f.access(), null, state(3)))
            val next = first.state.copy(nextIndex = 1, inFlight = true)
            val lost =
                object : ReplicaStore by f.database.replicas {
                    override suspend fun replace(
                        scope: ReplicaScope,
                        partition: String,
                        expected: ReplicaCheckpoint?,
                        snapshot: ReplicaSnapshot?,
                        wake: ReplicaWakeRequest?,
                    ): Boolean {
                        assertTrue(
                            f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                        )
                        throw IllegalStateException("Controlled lost cursor response")
                    }
                }
            assertIs<AppResult.Failed>(f.progress(lost).replace(f.access(), first, next))
            f.reopen()
            val restored = present(f.progress().current(f.access()))
            assertEquals(next, restored.state)
            assertNotEquals(first.generation, restored.generation)
            assertEquals(
                AppResult.Success(null),
                f.progress().replace(f.access(), first, first.state),
            )
            assertEquals(AppResult.Success(restored), f.progress().current(f.access()))
        }

    @Test
    fun cancellationAfterCommitRetainsCursorWithoutPublishingSuccess() = withDatabase { f ->
        var committed = false
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    val result =
                        f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                    assertTrue(result)
                    committed = true
                    currentCoroutineContext().cancel()
                    return result
                }
            }
        val task =
            CoroutineScope(currentCoroutineContext()).async {
                f.progress(store).replace(f.access(), null, state(3))
            }
        assertFailsWith<CancellationException> { task.await() }
        assertTrue(committed)
        f.reopen()
        assertEquals(state(3), present(f.progress().current(f.access())).state)
    }

    @Test
    fun accountReplacementDuringCommitCannotAdvanceEitherOwner() = withDatabase { f ->
        val first = present(f.progress().replace(f.access(), null, state(3)))
        val other = f.access.copy(sessionId = "other", accountId = historyId(3001))
        val store =
            object : ReplicaStore by f.database.replicas {
                override suspend fun replace(
                    scope: ReplicaScope,
                    partition: String,
                    expected: ReplicaCheckpoint?,
                    snapshot: ReplicaSnapshot?,
                    wake: ReplicaWakeRequest?,
                ): Boolean {
                    f.activate(other)
                    return f.database.replicas.replace(scope, partition, expected, snapshot, wake)
                }
            }
        assertEquals(
            AppResult.Success(null),
            f.progress(store)
                .replace(f.access(), first, first.state.copy(nextIndex = 1, inFlight = true)),
        )
        assertEquals(
            AppResult.Success(null),
            f.progress()
                .current(
                    CollaborationRecoveryAccess(other.sessionId, other.audience, other.accountId)
                ),
        )
        f.activate()
        assertEquals(AppResult.Success(first), f.progress().current(f.access()))
    }

    @Test
    fun missingAndWrongHistoryKeysNeverRegenerateOrOverwriteProgress() = withDatabase { f ->
        val first = present(f.progress().replace(f.access(), null, state(3)))
        val originalKey = assertNotNull(f.keys.read())
        val raw = f.rawProgress()
        for (key in listOf<String?>(null, "not-a-history-key", Base64.encode(ByteArray(64)))) {
            f.keys.write(key)
            assertIs<AppResult.Failed>(f.progress().current(f.access()))
            assertIs<AppResult.Failed>(f.progress().replace(f.access(), first, first.state))
            assertEquals(key, f.keys.read())
            assertEquals(raw, f.rawProgress())
        }
        f.keys.write(originalKey)
        assertEquals(AppResult.Success(first), f.progress().current(f.access()))
    }

    @Test
    fun authenticatedUnsupportedSchemaAndInvalidProgressCannotReplaceStoredFacts() =
        withDatabase { f ->
            val first = present(f.progress().replace(f.access(), null, state(3)))
            val original = f.payload()
            val invalid =
                listOf(
                    JsonObject(original + ("schema" to JsonPrimitive(2))),
                    JsonObject(original + ("nextIndex" to JsonPrimitive(4))),
                    JsonObject(
                        original +
                            ("references" to
                                JsonArray(
                                    listOf(
                                        original.getValue("references").jsonArray.first(),
                                        original.getValue("references").jsonArray.first(),
                                    )
                                ))
                    ),
                )
            for (payload in invalid) {
                f.rewrite(payload.toString())
                f.reopen()
                val raw = f.rawProgress()
                assertIs<AppResult.Failed>(f.progress().current(f.access()))
                assertIs<AppResult.Failed>(f.progress().replace(f.access(), first, first.state))
                assertEquals(raw, f.rawProgress())
            }
        }

    @Test
    fun ciphertextCannotBeReboundToAnotherGeneration() = withDatabase { f ->
        val first = present(f.progress().replace(f.access(), null, state(3)))
        val raw = f.rawProgress()
        assertTrue(
            f.database.replicas.replace(
                recoveryScope(f.access()),
                NOTIFICATION_INPUT_RECOVERY_PARTITION,
                raw,
                ReplicaSnapshot(Uuid.random().toString(), raw.payload, emptyList()),
            )
        )
        assertIs<AppResult.Failed>(f.progress().current(f.access()))
        assertIs<AppResult.Failed>(f.progress().replace(f.access(), first, first.state))
    }

    @Test
    fun freshAccountAndInvalidStatesCannotCreateAHistoryKey() =
        withDatabase(createKey = false) { f ->
            assertEquals(AppResult.Success(null), f.progress().current(f.access()))
            assertIs<AppResult.Failed>(f.progress().replace(f.access(), null, state(3)))
            for (invalid in
                listOf(
                    state(0),
                    state(65),
                    state(3).copy(nextIndex = 4),
                    state(3).copy(inFlight = true),
                    state(3).copy(finished = true),
                    state(3).copy(cycleId = "invalid"),
                )) {
                assertIs<AppResult.Failed>(f.progress().replace(f.access(), null, invalid))
            }
            assertNull(f.keys.read())
            assertEquals(AppResult.Success(null), f.progress().current(f.access()))
        }

    private fun state(count: Int = 64) =
        CollaborationNotificationInputRecoveryState(
            historyId(3100),
            (1..count).map {
                CollaborationNotificationInputReference(
                    "q-" + it.toString(16).padStart(64, '0'),
                    historyId(3200 + it),
                )
            },
        )

    private fun CollaborationHistoryFixture.access() =
        CollaborationRecoveryAccess(access.sessionId, access.audience, access.accountId)

    private fun CollaborationHistoryFixture.progress(store: ReplicaStore = database.replicas) =
        EncryptedCollaborationNotificationInputRecoveryRepository(store, cipher, keys)

    private fun present(result: AppResult<CollaborationNotificationInputRecoveryCursor?>) =
        assertNotNull(
            assertIs<AppResult.Success<CollaborationNotificationInputRecoveryCursor?>>(result).value
        )

    private suspend fun CollaborationHistoryFixture.rawProgress() =
        assertNotNull(
            database.replicas.checkpoint(
                recoveryScope(access()),
                NOTIFICATION_INPUT_RECOVERY_PARTITION,
            )
        )

    private fun CollaborationHistoryFixture.aad(generation: String) =
        Json.encodeToString(
                listOf(
                    "hris-notification-input-recovery-v1",
                    access.audience,
                    access.accountId,
                    NOTIFICATION_INPUT_RECOVERY_PARTITION,
                    generation,
                    "0",
                )
            )
            .encodeToByteArray()

    private suspend fun CollaborationHistoryFixture.payload(): JsonObject {
        val raw = rawProgress()
        return CollaborationHistoryKeys(database.replicas, cipher, keys).use(create = false) {
            key,
            _ ->
            val plain = cipher.decrypt(key, aad(raw.handle.generation), Base64.decode(raw.payload))
            try {
                Json.parseToJsonElement(plain.decodeToString()).jsonObject
            } finally {
                plain.fill(0)
            }
        }
    }

    private suspend fun CollaborationHistoryFixture.rewrite(payload: String) {
        val raw = rawProgress()
        val generation = Uuid.random().toString()
        val encoded =
            CollaborationHistoryKeys(database.replicas, cipher, keys).use(create = false) { key, _
                ->
                Base64.encode(cipher.encrypt(key, aad(generation), payload.encodeToByteArray()))
            }
        assertTrue(
            database.replicas.replace(
                recoveryScope(access()),
                NOTIFICATION_INPUT_RECOVERY_PARTITION,
                raw,
                ReplicaSnapshot(generation, encoded, emptyList()),
            )
        )
    }

    private fun withDatabase(
        createKey: Boolean = true,
        block: suspend (CollaborationHistoryFixture) -> Unit,
    ) = runTest {
        val f = CollaborationHistoryFixture(databaseOwner())
        try {
            f.activate()
            if (createKey) f.merge(f.event())
            block(f)
        } finally {
            f.close()
        }
    }
}
