package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

suspend fun verifyReplicaAccountOwnership(database: AppDatabase) {
    val store = database.replicas
    val scope = ReplicaScope("account-session", "api-a/account-a")
    val partition = "owned-company/inbox"
    val document = ReplicaDocument("message", 1, "message", "sealed-message")
    val snapshot = ReplicaSnapshot("shared-generation", "sealed-checkpoint", listOf(document))
    database.accounts.activate(scope.sessionId, scope.accountKey)
    assertTrue(store.replace(scope, partition, null, snapshot))
    assertTrue(
        store.replace(
            ReplicaScope(scope.sessionId),
            partition,
            null,
            snapshot.copy(checkpoint = "temporary"),
        )
    )
    assertEquals("temporary", store.checkpoint(ReplicaScope(scope.sessionId), partition)!!.payload)
    assertEquals(snapshot.checkpoint, store.checkpoint(scope, partition)!!.payload)
    assertTrue(store.containsRecords("owned-company/"))
    assertFalse(store.containsRecords("owned-company/other"))
    val old = store.checkpoint(scope, partition)!!

    // A retained account key alone does not authorize reads or mutations.
    val other = scope.copy(accountKey = "api-a/account-b")
    database.accounts.activate(other.sessionId, other.accountKey)
    assertNull(store.document(scope, partition, document.id))
    assertFalse(store.clear(scope, partition))
    assertFalse(store.commit(old.handle, old.revision, "late", emptyList(), true))
    assertTrue(store.replace(other, partition, null, snapshot.copy(checkpoint = "other-account")))
    assertNull(store.checkpoint(ReplicaScope(other.sessionId), partition))

    database.accounts.activate(null)
    assertTrue(store.observe(other, partition).first().documents.isEmpty())
    assertTrue(store.containsRecords("owned-company/"))
    val renewed = scope.copy(sessionId = "account-login-two")
    database.accounts.activate(renewed.sessionId, renewed.accountKey)
    assertEquals(document, store.document(renewed, partition, document.id))
    assertEquals(
        old.copy(handle = old.handle.copy(scope = renewed)),
        store.checkpoint(renewed, partition),
    )
    assertNull(store.checkpoint(scope, partition))
    assertFalse(store.clear(scope, partition))
    assertFalse(store.begin(scope, partition, "late", "late", old.handle.generation))
    assertFalse(store.replace(scope, partition, old, null))
    assertFalse(store.commit(old.handle, old.revision, "late", emptyList(), true))

    assertTrue(store.replace(renewed, "second-company/inbox", null, snapshot))
    assertTrue(store.clear(renewed, partition))
    assertNotNull(store.checkpoint(renewed, "second-company/inbox"))
    database.accounts.activate(other.sessionId, other.accountKey)
    assertEquals("other-account", store.checkpoint(other, partition)!!.payload)

    val otherApi = scope.copy(accountKey = "api-b/account-a")
    database.accounts.activate(otherApi.sessionId, otherApi.accountKey)
    assertNull(store.checkpoint(otherApi, partition))
    assertFalse(store.clear(other, partition))
    assertFailsWith<IllegalArgumentException> {
        store.checkpoint(otherApi.copy(accountKey = ""), partition)
    }
    assertFailsWith<IllegalArgumentException> {
        store.checkpoint(otherApi.copy(accountKey = "a".repeat(2049)), partition)
    }
}

suspend fun verifyReplicaAccountReplacement(database: AppDatabase) = coroutineScope {
    val store = database.replicas
    val scope = ReplicaScope("pending-session", "api/pending-account")
    val partition = "pending-company/inbox"
    val message = ReplicaDocument("message", 1, "message", "published")
    database.accounts.activate(scope.sessionId, scope.accountKey)
    assertTrue(
        store.replace(
            scope,
            partition,
            null,
            ReplicaSnapshot("published", "published", listOf(message)),
        )
    )
    assertTrue(store.begin(scope, partition, "staged", "staged", "published"))
    val staged = store.checkpoint(scope, partition)!!
    val release = CompletableDeferred<Unit>()
    val late =
        async(start = CoroutineStart.UNDISPATCHED) {
            release.await()
            store.commit(
                staged.handle,
                staged.revision,
                "late",
                listOf(message.copy(version = 2)),
                true,
            )
        }
    val observed =
        async(start = CoroutineStart.UNDISPATCHED) {
            store.observe(scope, partition).first { it.checkpoint == null }
        }
    val renewed = scope.copy(sessionId = "pending-login-two")
    database.accounts.activate(renewed.sessionId, renewed.accountKey)
    release.complete(Unit)
    assertFalse(late.await())
    assertTrue(observed.await().documents.isEmpty())
    assertEquals(listOf(message), store.observe(renewed, partition).first().documents)
    val resumed = store.checkpoint(renewed, partition)!!
    assertEquals(staged.copy(handle = staged.handle.copy(scope = renewed)), resumed)
    assertTrue(
        store.commit(
            resumed.handle,
            resumed.revision,
            "ready",
            listOf(message.copy(version = 2)),
            true,
        )
    )
    assertEquals(2, store.document(renewed, partition, message.id)!!.version)

    val ready = store.checkpoint(renewed, partition)!!
    assertTrue(
        store.commit(
            ready.handle,
            ready.revision,
            "deleted",
            listOf(message.copy(version = 3, payload = null)),
            true,
        )
    )
    val third = renewed.copy(sessionId = "pending-login-three")
    database.accounts.activate(third.sessionId, third.accountKey)
    val deleted = store.checkpoint(third, partition)!!
    assertTrue(
        store.commit(
            deleted.handle,
            deleted.revision,
            "duplicate",
            listOf(message.copy(version = 3)),
            true,
        )
    )
    assertNull(store.document(third, partition, message.id))
}

/** Both platform migration tests seed the same pre-ownership schema and opaque values. */
suspend fun verifyLegacyReplicaRetention(database: AppDatabase) {
    val scope = ReplicaScope("legacy-session")
    val partition = "legacy/inbox"
    database.accounts.activate(scope.sessionId, "legacy-account")
    val store = database.replicas
    val active = store.observe(scope, partition).first()
    assertEquals("active", active.checkpoint!!.handle.generation)
    assertEquals("active-checkpoint", active.checkpoint.payload)
    assertEquals(listOf(ReplicaDocument("message", 2, "01", "sealed-message")), active.documents)
    val staged = store.checkpoint(scope, partition)!!
    assertEquals("staged", staged.handle.generation)
    assertEquals(3, staged.revision)
    assertEquals("staging-checkpoint", staged.payload)
    assertFalse(staged.published)
    assertTrue(
        store.commit(
            staged.handle,
            3,
            "resumed",
            listOf(ReplicaDocument("deleted", 4, "02", "must-not-return")),
            true,
        )
    )
    assertNull(store.document(scope, partition, "deleted"))
    assertEquals("staged-message", store.document(scope, partition, "message")!!.payload)
    val account = ReplicaScope("legacy-session", "legacy-account")
    assertNull(store.checkpoint(account, partition))
    assertTrue(
        store.replace(
            account,
            partition,
            null,
            ReplicaSnapshot("active", "account-facts", emptyList()),
        )
    )
    database.accounts.activate("replacement-session", account.accountKey)
    assertNull(store.checkpoint(ReplicaScope("replacement-session"), partition))
    assertEquals(
        "account-facts",
        store.checkpoint(account.copy(sessionId = "replacement-session"), partition)!!.payload,
    )
}
