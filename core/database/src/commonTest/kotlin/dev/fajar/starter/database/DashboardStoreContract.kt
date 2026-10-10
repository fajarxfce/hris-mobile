package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.flow.first

val sampleDashboard =
    DashboardRecord(2, 1, 3, listOf(ActivityRecord("a", "Review", "Project", "09:00")), 10)

suspend fun verifyDashboardTransactions(database: AppDatabase) {
    database.accounts.activate("session-a")
    val store = database.dashboard
    assertNull(store.observe("session-a").first())
    store.replaceContent("session-a", sampleDashboard)
    assertFalse(store.setSaved("session-a", ActivityChangeRecord("missing", "unknown", true)))
    assertTrue(store.pendingChanges("session-a", 10).isEmpty())
    assertTrue(store.setSaved("session-a", ActivityChangeRecord("first", "a", true)))
    assertTrue(store.setSaved("session-a", ActivityChangeRecord("no-change", "a", true)))
    assertEquals(listOf("first"), store.pendingChanges("session-a", 10).map { it.operationId })
    assertFails { store.setSaved("session-a", ActivityChangeRecord("first", "a", false)) }
    // Duplicate outbox identity fails the transaction, including its local preference write.
    assertTrue(store.observe("session-a").first()!!.activity.single().saved)
    assertTrue(store.setSaved("session-a", ActivityChangeRecord("second", "a", false)))
    store.acknowledge("session-a", "first")
    store.acknowledge("session-a", "first")
    assertEquals(listOf("second"), store.pendingChanges("session-a", 10).map { it.operationId })
    store.replaceContent("session-a", sampleDashboard.copy(projects = 3))
    val refreshed = store.observe("session-a").first()!!
    assertEquals(3, refreshed.projects)
    assertFalse(refreshed.activity.single().saved)
    assertEquals(1, refreshed.pendingChanges)
    store.acknowledge("session-a", "second")
    assertEquals(0, store.observe("session-a").first()!!.pendingChanges)
    store.setSaved("session-a", ActivityChangeRecord("third", "a", true))
    store.replaceContent("session-a", sampleDashboard)
    assertTrue(store.observe("session-a").first()!!.activity.single().saved)
}

/** Executed against SQLite and IndexedDB. */
suspend fun verifyAccountIsolation(database: AppDatabase) {
    database.accounts.activate("session-a")
    database.dashboard.replaceContent("session-a", sampleDashboard)
    database.dashboard.setSaved("session-a", ActivityChangeRecord("pending", "a", true))
    database.accounts.activate("session-b")
    assertNull(database.dashboard.observe("session-a").first())
    assertNull(database.dashboard.observe("session-b").first())
    assertFalse(database.dashboard.replaceContent("session-a", sampleDashboard))
    assertFalse(database.dashboard.setSaved("session-a", ActivityChangeRecord("late", "a", true)))
    assertTrue(database.dashboard.pendingChanges("session-b", 10).isEmpty())
    assertTrue(database.dashboard.replaceContent("session-b", sampleDashboard))
    database.accounts.activate("session-b")
    assertNotNull(database.dashboard.observe("session-b").first())
    database.accounts.activate(null)
    assertNull(database.dashboard.observe("session-b").first())
}

/** Retry, de-duplication and stale page rejection share the same contract on both engines. */
suspend fun verifyPaginationTransactions(database: AppDatabase) {
    database.accounts.activate("pages")
    val first = sampleDashboard.copy(snapshot = "first", nextCursor = "page-2")
    database.dashboard.replaceContent("pages", first)
    database.dashboard.setSaved("pages", ActivityChangeRecord("saved", "a", true))
    val page = listOf(first.activity.single(), ActivityRecord("b", "Second", "Project", "10:00"))
    assertTrue(database.dashboard.appendPage("pages", "first", "page-2", page, "page-3"))
    var current = database.dashboard.observe("pages").first()!!
    assertEquals(listOf("a", "b"), current.activity.map { it.id })
    assertTrue(current.activity.first().saved)
    assertFalse(database.dashboard.appendPage("pages", "first", "page-2", page, null))
    database.dashboard.replaceContent("pages", first.copy(snapshot = "refreshed"))
    assertFalse(database.dashboard.appendPage("pages", "first", "page-3", page, null))
    current = database.dashboard.observe("pages").first()!!
    assertEquals(listOf("a"), current.activity.map { it.id })
    assertEquals("page-2", current.nextCursor)
    assertTrue(database.dashboard.appendPage("pages", "refreshed", "page-2", page, null))
    assertNull(database.dashboard.observe("pages").first()!!.nextCursor)
    database.accounts.activate("another-account")
    assertFalse(database.dashboard.appendPage("pages", "refreshed", "page-2", page, null))
}
