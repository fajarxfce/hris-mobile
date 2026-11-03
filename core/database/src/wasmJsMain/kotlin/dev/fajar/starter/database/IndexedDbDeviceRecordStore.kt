@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal class IndexedDbDeviceRecordStore(private val database: suspend () -> JsAny) :
    DeviceRecordStore {
    override suspend fun get(namespace: String, id: String): DeviceRecord? {
        validateDeviceRecordKey(namespace)
        validateDeviceRecordKey(id)
        val db = database()
        currentCoroutineContext().ensureActive()
        return Json.decodeFromString(
            readDeviceRecord(db, namespace, id).await<JsString>().toString()
        )
    }

    override suspend fun page(namespace: String, afterId: String?, limit: Int): List<DeviceRecord> {
        validateDeviceRecordKey(namespace)
        afterId?.let(::validateDeviceRecordKey)
        require(limit in 1..32)
        val db = database()
        currentCoroutineContext().ensureActive()
        return Json.decodeFromString(
            pageDeviceRecords(db, namespace, afterId, limit).await<JsString>().toString()
        )
    }

    override suspend fun retain(record: DeviceRecord, maxItems: Int): DeviceRecord? {
        validateDeviceRecord(record)
        require(maxItems in 1..128)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            Json.decodeFromString(
                retainDeviceRecord(db, Json.encodeToString(record), maxItems)
                    .await<JsString>()
                    .toString()
            )
        }
    }

    override suspend fun remove(record: DeviceRecord): Boolean {
        validateDeviceRecord(record)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            removeDeviceRecord(db, Json.encodeToString(record))
                .await<JsString>()
                .toString()
                .toBooleanStrict()
        }
    }
}

@JsFun(
    """(db, namespace, id) => new Promise((resolve, reject) => {
        const tx = db.transaction('device_records', 'readonly');
        let result = null;
        tx.oncomplete = () => resolve(JSON.stringify(result));
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error || new Error('Device record read aborted'));
        const request = tx.objectStore('device_records').get([namespace, id]);
        request.onsuccess = () => { result = request.result || null; };
    })"""
)
private external fun readDeviceRecord(db: JsAny, namespace: String, id: String): Promise<JsString>

@JsFun(
    """(db, namespace, afterId, limit) => new Promise((resolve, reject) => {
        const tx = db.transaction('device_records', 'readonly');
        let result = [];
        tx.oncomplete = () => resolve(JSON.stringify(result));
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error || new Error('Device record page aborted'));
        const range = IDBKeyRange.bound([namespace, afterId || ''], [namespace, []], true, true);
        const request = tx.objectStore('device_records').getAll(range, limit);
        request.onsuccess = () => { result = request.result; };
    })"""
)
private external fun pageDeviceRecords(
    db: JsAny,
    namespace: String,
    afterId: String?,
    limit: Int,
): Promise<JsString>

@JsFun(
    """(db, json, maxItems) => new Promise((resolve, reject) => {
        const record = JSON.parse(json);
        const tx = db.transaction('device_records', 'readwrite');
        const store = tx.objectStore('device_records');
        let result = null;
        tx.oncomplete = () => resolve(JSON.stringify(result));
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error || new Error('Device record retain aborted'));
        const read = store.get([record.namespace, record.id]);
        read.onsuccess = () => {
            if (read.result) { result = read.result; return; }
            const count = store.count();
            count.onsuccess = () => {
                if (count.result < maxItems) { store.add(record); result = record; }
            };
        };
    })"""
)
private external fun retainDeviceRecord(db: JsAny, json: String, maxItems: Int): Promise<JsString>

@JsFun(
    """(db, json) => new Promise((resolve, reject) => {
        const record = JSON.parse(json);
        const tx = db.transaction('device_records', 'readwrite');
        const store = tx.objectStore('device_records');
        let removed = false;
        tx.oncomplete = () => resolve(removed ? 'true' : 'false');
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error || new Error('Device record removal aborted'));
        const key = [record.namespace, record.id];
        const read = store.get(key);
        read.onsuccess = () => {
            const current = read.result;
            if (current && current.generation === record.generation && current.payload === record.payload) {
                store.delete(key); removed = true;
            }
        };
    })"""
)
private external fun removeDeviceRecord(db: JsAny, json: String): Promise<JsString>
