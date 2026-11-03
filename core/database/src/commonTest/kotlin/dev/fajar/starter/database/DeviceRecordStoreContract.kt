package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.*

private fun deviceRecord(id: String = "id") = DeviceRecord("input", id, "generation", "sealed-$id")

suspend fun verifyDeviceRecordRetention(store: DeviceRecordStore) {
    val first = deviceRecord()
    assertNull(store.get(first.namespace, first.id))
    assertFalse(store.remove(first))
    assertEquals(first, store.retain(first, 1))
    val competing = first.copy(generation = "different", payload = "different-ciphertext")
    assertEquals(first, store.retain(competing, 1))
    assertEquals(first, store.get(first.namespace, first.id))
    assertNull(store.retain(deviceRecord("full"), 1))
    assertFalse(store.remove(competing))
    assertFalse(store.remove(first.copy(payload = "changed")))
    assertFalse(store.remove(first.copy(namespace = "other")))
    assertTrue(store.remove(first))
    assertFalse(store.remove(first))
    assertEquals(competing, store.retain(competing, 1))
    assertFalse(store.remove(first))
    assertEquals(competing, store.get(first.namespace, first.id))
}

suspend fun verifyDeviceRecordCapacity(store: DeviceRecordStore) = coroutineScope {
    val ready = CompletableDeferred<Unit>()
    val attempts =
        (0 until 40).map { index ->
            async {
                ready.await()
                store.retain(deviceRecord("id-$index").copy(namespace = "input-${index % 2}"), 8)
            }
        }
    ready.complete(Unit)
    val retained = attempts.awaitAll().filterNotNull()
    assertEquals(8, retained.size)
    assertEquals(8, retained.map { it.id }.toSet().size)
    assertEquals(8, store.page("input-0").size + store.page("input-1").size)
    assertNull(store.retain(deviceRecord("another-namespace"), 8))
    val first = retained.first()
    assertEquals(first, store.retain(first.copy(payload = "replacement"), 1))
    assertTrue(store.remove(first))
    assertNotNull(store.retain(deviceRecord("freed-capacity"), 8))
}

suspend fun verifyDeviceRecordConcurrentIdentity(
    first: DeviceRecordStore,
    second: DeviceRecordStore,
) = coroutineScope {
    val ready = CompletableDeferred<Unit>()
    val attempts =
        (0 until 16).map { index ->
            async {
                ready.await()
                (if (index % 2 == 0) first else second).retain(
                    deviceRecord().copy(generation = "attempt-$index", payload = "sealed-$index"),
                    1,
                )
            }
        }
    ready.complete(Unit)
    val results = attempts.awaitAll()
    val retained = assertNotNull(results.first())
    assertTrue(results.all { it == retained })
    assertEquals(retained, first.get(retained.namespace, retained.id))
    assertEquals(retained, second.get(retained.namespace, retained.id))
    assertTrue(second.remove(retained))
    val next = retained.copy(generation = "next")
    assertEquals(next, first.retain(next, 1))
    assertFalse(second.remove(retained))
    assertEquals(next, second.get(next.namespace, next.id))
}

suspend fun verifyDeviceRecordPages(store: DeviceRecordStore) {
    val records = (0 until 35).map { deviceRecord("id-" + it.toString().padStart(2, '0')) }
    records.reversed().forEach { assertEquals(it, store.retain(it, 64)) }
    val other = deviceRecord("id-00").copy(namespace = "other")
    assertEquals(other, store.retain(other, 64))
    assertEquals(listOf(other), store.page("other"))
    assertEquals(records.take(32), store.page("input", limit = 32))
    assertEquals(records.takeLast(3), store.page("input", records[31].id, 32))
    assertTrue(store.page("input", records.last().id).isEmpty())
    assertTrue(store.page("missing").isEmpty())
    // A cursor does not require the corresponding row to remain present.
    assertTrue(store.remove(records[31]))
    assertEquals(records.takeLast(3), store.page("input", records[31].id, 32))
}

suspend fun verifyDeviceRecordOwnership(database: AppDatabase) {
    val record = deviceRecord()
    assertEquals(record, database.deviceRecords.retain(record, 1))
    database.accounts.activate("first-session", "first-account")
    assertEquals(record, database.deviceRecords.get(record.namespace, record.id))
    database.accounts.activate(null)
    assertEquals(record, database.deviceRecords.get(record.namespace, record.id))
    database.accounts.activate("next-session", "next-account")
    assertEquals(record, database.deviceRecords.get(record.namespace, record.id))
}

suspend fun verifyDeviceRecordCancellation(store: DeviceRecordStore) = coroutineScope {
    val record = deviceRecord()
    launch {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> { store.retain(record, 1) }
        }
        .join()
    assertNull(store.get(record.namespace, record.id))
    assertEquals(record, store.retain(record, 1))
    launch {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> { store.remove(record) }
            assertFailsWith<CancellationException> { store.get(record.namespace, record.id) }
            assertFailsWith<CancellationException> { store.page(record.namespace) }
        }
        .join()
    assertEquals(record, store.get(record.namespace, record.id))
}

suspend fun verifyDeviceRecordBounds(store: DeviceRecordStore) {
    val record = deviceRecord()
    for (invalid in listOf("", "identity/path", "with space", "é", "a".repeat(129))) {
        assertFailsWith<IllegalArgumentException> { store.get(invalid, record.id) }
        assertFailsWith<IllegalArgumentException> { store.get(record.namespace, invalid) }
        assertFailsWith<IllegalArgumentException> { store.page(record.namespace, invalid) }
        for (bad in
            listOf(
                record.copy(namespace = invalid),
                record.copy(id = invalid),
                record.copy(generation = invalid),
            )) {
            assertFailsWith<IllegalArgumentException> { store.retain(bad, 1) }
            assertFailsWith<IllegalArgumentException> { store.remove(bad) }
        }
    }
    for (payload in listOf("", "has space", "line\nbreak", "é", "a".repeat(131_073))) {
        assertFailsWith<IllegalArgumentException> {
            store.retain(record.copy(payload = payload), 1)
        }
        assertFailsWith<IllegalArgumentException> { store.remove(record.copy(payload = payload)) }
    }
    for (limit in listOf(0, 33)) assertFailsWith<IllegalArgumentException> {
        store.page(record.namespace, limit = limit)
    }
    for (capacity in listOf(0, 129)) assertFailsWith<IllegalArgumentException> {
        store.retain(record, capacity)
    }
    assertTrue(store.page(record.namespace).isEmpty())
    val maximum =
        DeviceRecord("n".repeat(128), "i".repeat(128), "g".repeat(128), "c".repeat(131_072))
    assertEquals(maximum, store.retain(maximum, 128))
    assertEquals(maximum, store.get(maximum.namespace, maximum.id))
    assertTrue(store.remove(maximum))
    assertEquals("DeviceRecord(<redacted>)", maximum.toString())
}
