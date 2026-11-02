@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.database

import kotlin.js.Promise
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal class IndexedDbWorkWakeStore(private val database: suspend () -> JsAny) : WorkWakeStore {
    override suspend fun get(key: String): WorkWakeRecord? {
        validateWorkWakeKey(key)
        val db = database()
        currentCoroutineContext().ensureActive()
        return Json.decodeFromString(readWorkWake(db, key).await<JsString>().toString())
    }

    override suspend fun request(key: String, generation: String, maxKeys: Int): WorkWakeRecord? {
        validateWorkWakeKey(key)
        validateWorkWakeGeneration(generation)
        require(maxKeys in 1..128)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            Json.decodeFromString(
                requestWorkWake(db, key, generation, maxKeys).await<JsString>().toString()
            )
        }
    }

    override suspend fun acknowledge(key: String, generation: String): Boolean {
        validateWorkWakeKey(key)
        validateWorkWakeGeneration(generation)
        val db = database()
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            acknowledgeWorkWake(db, key, generation).await<JsString>().toString().toBooleanStrict()
        }
    }
}

@JsFun(
    """(db, key) => new Promise((resolve, reject) => {
        const tx = db.transaction('work_wakes', 'readonly');
        let result = null;
        tx.oncomplete = () => resolve(JSON.stringify(result));
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error || new Error('Wake read aborted'));
        const read = tx.objectStore('work_wakes').get(key);
        read.onsuccess = () => { result = read.result || null; };
    })"""
)
private external fun readWorkWake(db: JsAny, key: String): Promise<JsString>

@JsFun(
    """(db, key, generation, maxKeys) => new Promise((resolve, reject) => {
        const tx = db.transaction('work_wakes', 'readwrite');
        const store = tx.objectStore('work_wakes');
        let result = null;
        tx.oncomplete = () => resolve(JSON.stringify(result));
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error || new Error('Wake request aborted'));
        const read = store.get(key);
        const put = () => { result = {key, generation, pending:true}; store.put(result); };
        read.onsuccess = () => {
            const current = read.result;
            if (current && current.generation === generation) { result = current; return; }
            if (current) { put(); return; }
            const count = store.count();
            count.onsuccess = () => { if (count.result < maxKeys) put(); };
        };
    })"""
)
private external fun requestWorkWake(
    db: JsAny,
    key: String,
    generation: String,
    maxKeys: Int,
): Promise<JsString>

@JsFun(
    """(db, key, generation) => new Promise((resolve, reject) => {
        const tx = db.transaction('work_wakes', 'readwrite');
        const store = tx.objectStore('work_wakes');
        let changed = false;
        tx.oncomplete = () => resolve(changed ? 'true' : 'false');
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error || new Error('Wake acknowledgment aborted'));
        const read = store.get(key);
        read.onsuccess = () => {
            const current = read.result;
            if (current && current.generation === generation && current.pending) {
                store.put({key, generation, pending:false});
                changed = true;
            }
        };
    })"""
)
private external fun acknowledgeWorkWake(
    db: JsAny,
    key: String,
    generation: String,
): Promise<JsString>
